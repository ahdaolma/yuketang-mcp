"""Personal Rain Classroom MCP prototype. No third-party project code bundled."""
from __future__ import annotations

import argparse
import asyncio
import io
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import sys
import time
from urllib.parse import urlsplit

import httpx
from mcp.server.fastmcp import FastMCP, Image
from mcp.types import ToolAnnotations
from PIL import Image as PILImage
from websockets.asyncio.client import connect

HOSTS = {"standard": "www.yuketang.cn", "hetang": "pro.yuketang.cn",
         "changjiang": "changjiang.yuketang.cn", "huanghe": "huanghe.yuketang.cn"}
MEDIA_DOMAINS = ("yuketang.cn", "xuetangx.com", "myqcloud.com", "qcloud.com",
                 "qpic.cn", "weixin.qq.com", "aliyuncs.com", "alicdn.com")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
MAX_MEDIA = 12 * 1024 * 1024


class BridgeError(ValueError):
    pass


def numeric(value: str) -> str:
    if not re.fullmatch(r"[0-9]{1,24}", str(value)):
        raise BridgeError("资源编号必须是数字；请从课程活动返回值中选择。")
    return str(value)


def redact(value):
    """Remove session credentials even from unexpected nested API structures."""
    hidden = {"sessionid", "cookie", "authorization", "auth", "token", "accesstoken",
              "refreshtoken", "lessontoken", "password", "secret", "setcookie", "setauth"}
    if isinstance(value, dict):
        return {k: redact(v) for k, v in value.items()
                if re.sub(r"[^a-z]", "", k.lower()) not in hidden}
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


def image_png(data: bytes) -> bytes:
    try:
        with PILImage.open(io.BytesIO(data)) as im:
            im.thumbnail((2000, 2000))
            output = io.BytesIO()
            im.convert("RGB").save(output, "PNG")
            return output.getvalue()
    except Exception:
        raise BridgeError("服务器返回的内容不是可读取的课件图片。") from None


async def media_bytes(url: str) -> bytes:
    # No account cookies or authorization are ever sent to a media host.
    async with httpx.AsyncClient(timeout=20, trust_env=False) as client:
        for _ in range(4):
            p = urlsplit(url)
            host = (p.hostname or "").lower()
            if (p.scheme != "https" or p.username or p.password or p.port not in (None, 443)
                    or not any(host == d or host.endswith("." + d) for d in MEDIA_DOMAINS)):
                raise BridgeError("图片域名尚未适配，请反馈域名即可，不要发送登录凭据。")
            addresses = await asyncio.get_running_loop().getaddrinfo(host, 443, type=socket.SOCK_STREAM)
            if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
                raise BridgeError("图片地址未通过公网地址检查。")
            async with client.stream("GET", url) as response:
                if response.is_redirect:
                    url = str(response.url.join(response.headers["location"]))
                    continue
                response.raise_for_status()
                data = bytearray()
                async for chunk in response.aiter_bytes():
                    data.extend(chunk)
                    if len(data) > MAX_MEDIA:
                        raise BridgeError("图片超过 12 MiB 上限。")
                return bytes(data)
        raise BridgeError("图片跳转次数过多。")


class RainClient:
    def __init__(self, region="standard", state_dir=None, transport=None):
        if region not in HOSTS:
            raise BridgeError("未知雨课堂版本。")
        self.region = region
        self.host = HOSTS[region]
        self.root = Path(state_dir or os.getenv("YKT_STATE_DIR", str(Path.home() / ".yuketang-mcp")))
        self.state_file = self.root / (region + ".json")
        self.session = ""
        self.transport = transport
        self.login_task = None
        self.login_state = "idle"
        self.login_error = None
        self.qr = None
        self.login_started = None

    def load_session(self):
        try:
            state = json.loads(self.state_file.read_text("utf-8"))
            self.session = state.get("sessionid", "")
        except FileNotFoundError:
            self.session = ""
        except (OSError, ValueError):
            raise BridgeError("本地登录记录损坏，请重新扫码登录。") from None

    def save_session(self, session):
        if not session or "\r" in session or "\n" in session:
            raise BridgeError("登录未返回有效会话。")
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        temp = self.state_file.with_suffix(".tmp")
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            os.chmod(temp, 0o600)
            json.dump({"sessionid": session, "saved_at": int(time.time())}, f)
        temp.replace(self.state_file)
        self.session = session

    async def api(self, path, params=None):
        self.load_session()
        if not self.session:
            raise BridgeError("尚未登录。请先调用 login_start，用微信扫码确认，再查看 login_status。")
        try:
            async with httpx.AsyncClient(timeout=20, trust_env=False, transport=self.transport) as client:
                r = await client.get("https://" + self.host + path, params=params,
                                     headers={"User-Agent": UA, "Cookie": "sessionid=" + self.session,
                                              "Referer": "https://" + self.host + "/v2/web/index"})
                if r.status_code in (401, 403) or r.is_redirect:
                    raise BridgeError("登录已失效或该资源尚未开放，请在雨课堂核对，必要时重新扫码。")
                r.raise_for_status()
                payload = r.json()
        except (httpx.HTTPError, ValueError) as e:
            if isinstance(e, BridgeError):
                raise
            raise BridgeError("雨课堂请求失败，请检查网络和所选雨课堂版本。") from None
        if not isinstance(payload, dict):
            raise BridgeError("雨课堂响应格式变化，读取已停止。")
        if (payload.get("code") not in (None, 0, 200)
                or payload.get("success") is False
                or payload.get("status") not in (None, 0, 200)):
            raise BridgeError("雨课堂拒绝读取，请检查登录状态和课程权限。")
        if "data" not in payload:
            raise BridgeError("响应缺少 data 字段，可能需要重新登录或适配新版接口。")
        return payload["data"]

    async def presentation(self, presentation_id):
        data = await self.api("/api/v3/lesson/presentation/fetch", {"presentation_id": numeric(presentation_id)})
        if not isinstance(data, dict) or not isinstance(data.get("slides"), list):
            raise BridgeError("未返回课件页面；此资源可能需要课堂授权，尚不能读取。")
        return data

    async def login_worker(self):
        try:
            self.login_started = time.monotonic()
            async with asyncio.timeout(180):
                async with connect("wss://" + self.host + "/wsapp/", origin="https://" + self.host,
                                   additional_headers={"User-Agent": UA}, open_timeout=15,
                                   ping_interval=20, proxy=None) as ws:
                    await ws.send(json.dumps({"op": "requestlogin", "role": "web", "version": 1.4,
                                              "type": "qrcode", "from": "web"}))
                    while True:
                        try:
                            raw = await asyncio.wait_for(ws.recv(), timeout=50)
                        except TimeoutError:
                            await ws.send(json.dumps({"op": "requestlogin", "role": "web", "version": 1.4,
                                                      "type": "qrcode", "from": "web"}))
                            continue
                        data = json.loads(raw)
                        if data.get("op") == "requestlogin":
                            self.qr = image_png(await media_bytes(data["ticket"]))
                            self.login_state = "waiting_for_scan"
                        elif data.get("op") == "loginsuccess":
                            async with httpx.AsyncClient(timeout=20, trust_env=False, transport=self.transport) as client:
                                r = await client.post("https://" + self.host + "/pc/web_login",
                                                      content=json.dumps({"UserID": data["UserID"], "Auth": data["Auth"]}),
                                                      headers={"User-Agent": UA})
                                r.raise_for_status()
                                session = r.cookies.get("sessionid")
                            self.save_session(session)
                            self.qr = None
                            self.login_state = "authenticated"
                            return
        except asyncio.CancelledError:
            self.login_state = "cancelled"
            raise
        except TimeoutError:
            self.login_state = "expired"
            self.qr = None
        except Exception:
            self.login_state = "error"
            self.login_error = "微信登录连接失败。检查雨课堂版本和网络，然后重新开始扫码。"
            self.qr = None

    async def begin_login(self):
        if self.login_task and not self.login_task.done():
            self.login_task.cancel()
            await asyncio.gather(self.login_task, return_exceptions=True)
        self.qr = None
        self.login_error = None
        self.login_state = "connecting"
        self.login_task = asyncio.create_task(self.login_worker())
        # Bounded wait keeps MCP calls responsive; polling can retrieve a later QR.
        for _ in range(60):
            if self.qr or self.login_task.done():
                break
            await asyncio.sleep(0.1)


def build_server(client):
    mcp = FastMCP("雨课堂学习桥", host="127.0.0.1", port=8765,
        instructions="先用 login_status 检查登录。列出课程，再列课程活动寻找 presentation_id。"
                     "不要把 courseware_id、lesson_id、classroom_id 当成 presentation_id。"
                     "读图用 read_slide。接口失败如实报告，不猜测课件内容。"
                     "当前版本不支持实时当前页同步；不要宣称已连接或正在后台持续监控。")
    read = ToolAnnotations(readOnlyHint=True, destructiveHint=False, openWorldHint=True)

    @mcp.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, openWorldHint=True))
    async def login_start() -> list:
        """开始微信扫码授权；显示返回的二维码，提醒用户核对雨课堂账号。"""
        await client.begin_login()
        if client.qr:
            return ["请用微信扫码，并在微信中确认登录。二维码约一分钟刷新，过期可重新调用。", Image(data=client.qr, format="png")]
        return [{"state": client.login_state, "message": client.login_error or "正在连接，请稍后调用 login_status。"}]

    @mcp.tool(annotations=read)
    async def login_status() -> list:
        """查看登录状态；正在登录时可能返回二维码。不会返回会话密钥。"""
        if client.login_task and not client.login_task.done():
            result = [{"state": client.login_state}]
            if client.qr:
                result.append(Image(data=client.qr, format="png"))
            return result
        if client.login_state in ("error", "expired", "cancelled"):
            return [{"state": client.login_state, "message": client.login_error or "请重新扫码。"}]
        try:
            user = await client.api("/api/v3/user/basic-info")
            if not isinstance(user, dict) or not user.get("id"):
                raise BridgeError("未返回可验证的账号信息。")
            return [{"state": "authenticated", "name": user.get("name"), "region": client.region}]
        except BridgeError as exc:
            return [{"state": "not_verified", "message": str(exc), "region": client.region}]

    @mcp.tool(annotations=read)
    async def list_courses() -> dict:
        """列出账号可访问的课程。返回服务端这一批记录，不承诺包含全部历史学期。"""
        data = await client.api("/v2/api/web/courses/list", {"identity": 2})
        if not isinstance(data, dict) or not isinstance(data.get("list"), list):
            raise BridgeError("课程列表结构已变化，不能把解析失败当成没有课程。")
        return {"courses": redact(data["list"]), "coverage": "server_returned_batch"}

    @mcp.tool(annotations=read)
    async def list_course_activities(classroom_id: str, page: int = 0) -> dict:
        """列课程活动，每页 50 条。保留原始标识，不猜测课件 ID。page 从 0 开始。"""
        if not 0 <= page <= 1000:
            raise BridgeError("page 必须为 0–1000。")
        data = await client.api("/v2/api/web/logs/learn/" + numeric(classroom_id),
                                {"actype": -1, "page": page, "offset": 50, "sort": -1})
        if not isinstance(data, dict) or not isinstance(data.get("activities"), list):
            raise BridgeError("课程活动结构已变化，需按你的实际版本适配。")
        return {"activities": redact(data["activities"]), "page": page,
                "note": "保留服务端字段；只用明确命名的 presentation_id 或 presentationId 读取 PPT。"}

    @mcp.tool(annotations=read)
    async def list_live_classes() -> dict:
        """查看正在上课的课程；不会自动加入课堂。"""
        data = await client.api("/api/v3/classroom/on-lesson")
        if not isinstance(data, dict) or not isinstance(data.get("onLessonClassrooms"), list):
            raise BridgeError("实时课程列表结构已变化。")
        return {"classes": redact(data["onLessonClassrooms"]), "current_slide_supported": False}

    @mcp.tool(annotations=read)
    async def list_slides(presentation_id: str) -> dict:
        """读取已知 presentation_id 对应的课件目录。页码 page 是返回顺序，从 1 开始。"""
        data = await client.presentation(presentation_id)
        return {"title": data.get("title"), "slides": [
            {"page": i + 1, "source_index": s.get("index"), "slide_id": s.get("id"),
             "has_image": bool(s.get("cover")), "has_question": "problem" in s}
            for i, s in enumerate(data["slides"])]}

    @mcp.tool(annotations=read)
    async def read_slide(presentation_id: str, page: int) -> list:
        """读取一页已开放课件，返回图片供模型直接理解，不需要额外 AI API key。"""
        data = await client.presentation(presentation_id)
        if not 1 <= page <= len(data["slides"]):
            raise BridgeError("页码超出范围，请先调用 list_slides。")
        slide = data["slides"][page - 1]
        result = [{"title": data.get("title"), "page": page, "source_index": slide.get("index"),
                   "slide_id": slide.get("id"), "content": redact(slide.get("problem") or slide.get("text") or {})}]
        if slide.get("cover"):
            try:
                result.append(Image(data=image_png(await media_bytes(slide["cover"])), format="png"))
            except BridgeError:
                raise
            except Exception:
                raise BridgeError("课件图片暂时无法读取；可能是网络、图片过期或域名尚未适配。") from None
        else:
            result.append("此页未返回图片；以上文字可能不完整，请勿猜测缺失的图表内容。")
        return result

    return mcp


def main():
    parser = argparse.ArgumentParser(description="雨课堂 MCP 测试版")
    parser.add_argument("command", choices=["serve", "login", "setup"], nargs="?", default="serve")
    parser.add_argument("--region", choices=HOSTS, default="standard")
    parser.add_argument("--transport", choices=["stdio", "streamable-http"], default="stdio")
    parser.add_argument("--port", type=int, default=8766, help="本地扫码引导页端口，默认 8766")
    args = parser.parse_args()
    if args.command == "setup":
        from local_setup import run_setup
        run_setup(args.region, args.port)
        return
    client = RainClient(args.region)
    if args.command == "login":
        async def login():
            import webbrowser
            import tempfile
            await client.begin_login()
            for _ in range(140):
                if client.qr or client.login_task.done():
                    break
                await asyncio.sleep(0.1)
            if not client.qr:
                if client.login_task and not client.login_task.done():
                    client.login_task.cancel()
                    await asyncio.gather(client.login_task, return_exceptions=True)
                raise BridgeError(client.login_error or "二维码获取失败，请重试。")
            # Temporary QR only: never bundle credentials or QR into deliverables.
            with tempfile.TemporaryDirectory(prefix="ykt-login-") as folder:
                target = Path(folder) / "wechat-login.png"
                shown = None
                while client.login_task and not client.login_task.done():
                    if client.qr and shown != client.qr:
                        target.write_bytes(client.qr)
                        webbrowser.open(target.as_uri())
                        shown = client.qr
                        print("请用微信扫描打开的二维码并确认登录。", file=sys.stderr)
                    await asyncio.sleep(0.5)
                await client.login_task
            if client.login_state != "authenticated":
                raise BridgeError(client.login_error or "二维码已过期，请重试。")
            user = await client.api("/api/v3/user/basic-info")
            print("登录成功：" + str(user.get("name", "已验证账号")))
        asyncio.run(login())
    else:
        build_server(client).run(transport=args.transport)


if __name__ == "__main__":
    try:
        main()
    except (BridgeError, KeyboardInterrupt) as exc:
        print(str(exc) or "已退出。", file=sys.stderr)
        sys.exit(1)
