import asyncio
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import httpx
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server


def png():
    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), "white").save(buffer, "PNG")
    return buffer.getvalue()


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.requests = []
        self.data = {"title": "生理学", "slides": [{"id": "s1", "index": 0, "cover": "https://example.qpic.cn/a.png"}]}

        def handler(request):
            self.requests.append(request)
            if request.url.path == "/pc/web_login":
                return httpx.Response(200, json={"code": 0}, headers={"set-cookie": "sessionid=fake-session; Path=/"})
            if request.url.path == "/api/v3/lesson/presentation/fetch":
                return httpx.Response(200, json={"code": 0, "data": self.data})
            if request.url.path == "/api/v3/user/basic-info":
                return httpx.Response(200, json={"code": 0, "data": {"id": 1, "name": "测试账号"}})
            return httpx.Response(403)

        self.client = server.RainClient(state_dir=self.temp.name, transport=httpx.MockTransport(handler))

    async def asyncTearDown(self):
        self.temp.cleanup()

    async def test_no_login_does_not_send_network_request(self):
        with self.assertRaisesRegex(server.BridgeError, "尚未登录"):
            await self.client.api("/anything")
        self.assertEqual(self.requests, [])

    async def test_permission_denied(self):
        self.client.save_session("fake-session")
        with self.assertRaisesRegex(server.BridgeError, "登录已失效"):
            await self.client.api("/denied")

    async def test_api_schema_change_is_not_empty_list(self):
        self.client.save_session("fake-session")
        self.client.transport = httpx.MockTransport(lambda r: httpx.Response(200, json={"html": "login"}))
        with self.assertRaisesRegex(server.BridgeError, "缺少 data"):
            await self.client.api("/changed")

    async def test_mock_wechat_login_and_persistence(self):
        class FakeSocket:
            def __init__(self):
                self.events = iter([{"op": "requestlogin", "ticket": "https://mp.weixin.qq.com/qr"},
                                    {"op": "loginsuccess", "UserID": "1", "Auth": "fake-auth"}])
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                pass
            async def send(self, value):
                self.sent = json.loads(value)
            async def recv(self):
                return json.dumps(next(self.events))

        with patch.object(server, "connect", return_value=FakeSocket()), patch.object(server, "media_bytes", return_value=png()):
            await self.client.login_worker()
        self.assertEqual(self.client.login_state, "authenticated")
        self.assertEqual(self.client.qr, None)
        self.assertEqual(json.loads(self.client.state_file.read_text())["sessionid"], "fake-session")
        self.assertNotIn("fake-auth", self.client.state_file.read_text())

    async def test_slide_image_and_page_validation(self):
        self.client.save_session("fake-session")
        mcp = server.build_server(self.client)
        with patch.object(server, "media_bytes", return_value=png()):
            result = await mcp.call_tool("read_slide", {"presentation_id": "12", "page": 1})
        self.assertTrue(any(getattr(c, "type", None) == "image" for c in result))
        self.assertNotIn("fake-session", str(result))
        with self.assertRaises(Exception):
            await mcp.call_tool("read_slide", {"presentation_id": "12", "page": 0})

    async def test_identifier_and_nested_redaction(self):
        for bad in ["../1", "1?x=1", "https://evil/1", ""]:
            with self.assertRaises(server.BridgeError):
                server.numeric(bad)
        self.assertEqual(server.redact({"x": [{"lessonToken": "secret", "title": "ok"}], "Auth": "secret"}),
                         {"x": [{"title": "ok"}]})

    async def test_media_rejects_nonpublic_or_unknown_domains(self):
        for url in ["http://www.yuketang.cn/a", "https://127.0.0.1/a", "https://evil.test/a"]:
            with self.assertRaises(server.BridgeError):
                await server.media_bytes(url)

    async def test_real_stdio_handshake_and_tool_call(self):
        params = StdioServerParameters(command=sys.executable,
            args=[str(Path(server.__file__).resolve()), "serve"], env={"YKT_STATE_DIR": self.temp.name})
        async with stdio_client(params) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                tools = await session.list_tools()
                self.assertEqual(len(tools.tools), 7)
                result = await session.call_tool("login_status", {})
                self.assertFalse(result.isError)
                self.assertIn("not_verified", result.content[0].text)


if __name__ == "__main__":
    unittest.main()
