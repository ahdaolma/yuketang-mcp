"""Loopback-only WeChat login and course access setup, separate from MCP stdio."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from pathlib import Path
import secrets
import sys
import time
import webbrowser

import httpx
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse, Response
from starlette.routing import Route
import uvicorn

from server import BridgeError, HOSTS, RainClient, UA


def create_setup_app(region="standard", port=8766, token=None, client_factory=RainClient):
    token = token or secrets.token_urlsafe(32)
    origin = f"http://127.0.0.1:{port}"
    current = client_factory(region)
    lock = asyncio.Lock()
    verified = None
    checked_at = 0.0

    def json_result(value, status=200):
        return JSONResponse(value, status_code=status, headers={"Cache-Control": "no-store"})

    def access_error(request):
        # Prevent foreign sites and DNS rebinding from operating this local account bridge.
        if request.headers.get("host") != f"127.0.0.1:{port}":
            return json_result({"error": "只允许本机 127.0.0.1 访问。"}, 403)
        if request.headers.get("origin") not in (None, origin):
            return json_result({"error": "拒绝其他网页发起的操作。"}, 403)
        if not secrets.compare_digest(request.headers.get("x-setup-key", ""), token):
            return json_result({"error": "请从启动程序打开引导页。"}, 401)
        return None

    async def index(request):
        if request.headers.get("host") != f"127.0.0.1:{port}":
            return Response(status_code=403)
        return HTMLResponse(Path(__file__).with_name("setup.html").read_text("utf-8"), headers={
            "Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
            "Content-Security-Policy": "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                "style-src 'self' 'unsafe-inline'; img-src 'self' blob:; frame-ancestors 'none'; "
                "connect-src 'self'; base-uri 'none'; form-action 'none'"})

    async def api(request: Request):
        nonlocal current, verified, checked_at
        denied = access_error(request)
        if denied is not None:
            return denied
        async with lock:
            action = request.path_params["action"]
            try:
                if action == "status" and request.method == "GET":
                    state = current.login_state
                    running = current.login_task and not current.login_task.done()
                    if running or state in ("error", "expired", "cancelled"):
                        return json_result({"state": state, "region": current.region,
                                            "has_qr": bool(current.qr), "message": current.login_error})
                    current.load_session()
                    if not current.session:
                        return json_result({"state": "not_logged_in", "region": current.region})
                    if verified is None or time.monotonic() - checked_at > 30:
                        verified = None
                        user = await current.api("/api/v3/user/basic-info")
                        if not isinstance(user, dict) or not user.get("id"):
                            raise BridgeError("未返回可验证的账号信息。")
                        verified = {"name": user.get("name")}
                        checked_at = time.monotonic()
                    return json_result({"state": "authenticated", "region": current.region, "user": verified})
                if action == "login" and request.method == "POST":
                    payload = await request.json()
                    selected = payload.get("region") if isinstance(payload, dict) else None
                    if selected not in HOSTS:
                        raise BridgeError("请选择微信里实际使用的雨课堂版本。")
                    if current.login_task and not current.login_task.done():
                        current.login_task.cancel()
                        await asyncio.gather(current.login_task, return_exceptions=True)
                    current = client_factory(selected)
                    verified = None
                    await current.begin_login()
                    return json_result({"state": current.login_state, "region": current.region})
                if action == "qr" and request.method == "GET":
                    if not current.qr or not current.login_task or current.login_task.done():
                        return json_result({"error": "二维码尚未就绪或已失效。"}, 404)
                    return Response(current.qr, media_type="image/png", headers={"Cache-Control": "no-store"})
                if action == "courses" and request.method == "GET":
                    data = await current.api("/v2/api/web/courses/list", {"identity": 2})
                    if not isinstance(data, dict) or not isinstance(data.get("list"), list):
                        raise BridgeError("课程列表结构需要适配，不能把解析失败当成没有课程。")
                    courses = []
                    for row in data["list"]:
                        if not isinstance(row, dict):
                            raise BridgeError("部分课程记录无法识别。")
                        course = row.get("course") if isinstance(row.get("course"), dict) else {}
                        teacher = row.get("teacher") if isinstance(row.get("teacher"), dict) else {}
                        courses.append({"classroom_id": row.get("classroom_id"),
                                        "name": course.get("name") or row.get("name") or "未命名课程",
                                        "teacher": teacher.get("name"), "term": row.get("term")})
                    return json_result({"courses": courses, "coverage": "server_returned_batch"})
                if action == "config" and request.method == "GET":
                    script = str(Path(__file__).with_name("server.py").resolve())
                    return json_result({"mcpServers": {"yuketang": {"command": sys.executable,
                        "args": [script, "serve", "--region", current.region],
                        "env": {"YKT_STATE_DIR": str(current.root.resolve())}}}})
                if action == "diagnose" and request.method == "POST":
                    payload = await request.json()
                    selected = payload.get("region") if isinstance(payload, dict) else None
                    if selected not in HOSTS:
                        raise BridgeError("请选择雨课堂版本。")
                    host = HOSTS[selected]
                    try:
                        async with httpx.AsyncClient(timeout=8, trust_env=False, transport=current.transport) as client:
                            response = await client.get("https://" + host + "/", headers={"User-Agent": UA})
                        return json_result({"host": host, "http_status": response.status_code,
                            "message": "已收到网站响应。此检查不代表微信登录或课件权限已通过。"})
                    except httpx.TimeoutException:
                        return json_result({"host": host, "error": "连接超时，请检查网络后重试。"}, 502)
                    except httpx.HTTPError:
                        return json_result({"host": host, "error": "无法连接此雨课堂站点，请检查网络或入口版本。"}, 502)
                return json_result({"error": "不支持的操作。"}, 404)
            except BridgeError as exc:
                return json_result({"error": str(exc)}, 400)
            except (ValueError, TypeError):
                return json_result({"error": "无法识别请求内容。"}, 400)

    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            if current.login_task and not current.login_task.done():
                current.login_task.cancel()
                await asyncio.gather(current.login_task, return_exceptions=True)

    app = Starlette(routes=[Route("/", index), Route("/api/{action}", api, methods=["GET", "POST"])], lifespan=lifespan)
    return app, token


def run_setup(region, port):
    if not 1024 <= port <= 65535:
        raise BridgeError("端口必须在 1024–65535 之间。")
    app, token = create_setup_app(region=region, port=port)
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, access_log=False))

    async def run():
        task = asyncio.create_task(server.serve())
        try:
            while not task.done() and not server.started:
                await asyncio.sleep(0.1)
            if server.started:
                print(f"本地扫码界面：http://127.0.0.1:{port}/ （启动器会自动打开）")
                webbrowser.open(f"http://127.0.0.1:{port}/#key={token}")
            await task
        finally:
            server.should_exit = True
            if not task.done():
                await task

    asyncio.run(run())
