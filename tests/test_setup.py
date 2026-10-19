import asyncio
import json
import tempfile
import unittest
from unittest.mock import patch

import httpx

from local_setup import create_setup_app
from server import RainClient


class SetupTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.calls = []

        def handler(request):
            self.calls.append(request)
            if request.url.path == "/api/v3/user/basic-info":
                return httpx.Response(200, json={"code": 0, "data": {"id": "1", "name": "测试账号"}})
            if request.url.path == "/v2/api/web/courses/list":
                return httpx.Response(200, json={"code": 0, "data": {"list": [
                    {"classroom_id": "20", "course": {"name": "生理学"}, "teacher": {"name": "老师"},
                     "sessionid": "never-include-this"}]}})
            return httpx.Response(200, text="ok")

        def factory(region):
            return RainClient(region, state_dir=self.temp.name, transport=httpx.MockTransport(handler))

        self.factory = factory
        self.app, self.key = create_setup_app(token="local-test-key", client_factory=factory)
        self.web = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app),
                                    base_url="http://127.0.0.1:8766", headers={"X-Setup-Key": self.key})

    async def asyncTearDown(self):
        await self.web.aclose()
        self.temp.cleanup()

    async def test_protect_account_endpoints_from_foreign_pages(self):
        self.assertEqual((await self.web.get("/api/status", headers={"X-Setup-Key": ""})).status_code, 401)
        self.assertEqual((await self.web.get("/api/status", headers={"Origin": "https://untrusted.test"})).status_code, 403)
        self.assertEqual((await self.web.get("/api/status", headers={"Host": "untrusted.test:8766"})).status_code, 403)
        self.assertEqual((await self.web.post("/api/login", json={"region": "standard"},
                                            headers={"X-Setup-Key": "wrong"})).status_code, 401)
        self.assertEqual(self.calls, [])

    async def test_initial_state_and_course_failure_are_explicit(self):
        state = await self.web.get("/api/status")
        self.assertEqual(state.json()["state"], "not_logged_in")
        courses = await self.web.get("/api/courses")
        self.assertEqual(courses.status_code, 400)
        self.assertIn("尚未登录", courses.json()["error"])
        self.assertEqual((await self.web.get("/api/qr")).status_code, 404)

    async def test_verified_status_cached_and_courses_return_without_credentials(self):
        self.factory("standard").save_session("fake-session")
        for _ in range(2):
            state = await self.web.get("/api/status")
            self.assertEqual(state.json()["state"], "authenticated")
        self.assertEqual(len(self.calls), 1)
        courses = await self.web.get("/api/courses")
        self.assertEqual(courses.json()["courses"][0]["name"], "生理学")
        self.assertNotIn("never-include-this", courses.text)
        self.assertNotIn("fake-session", state.text)

    async def test_region_change_uses_correct_local_state_and_client_config(self):
        async def fake_login(client):
            client.save_session("fake-new-session")
            client.login_state = "authenticated"

        with patch.object(RainClient, "begin_login", fake_login):
            response = await self.web.post("/api/login", json={"region": "hetang"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["region"], "hetang")
        state = await self.web.get("/api/status")
        self.assertEqual(state.json()["state"], "authenticated")
        config = (await self.web.get("/api/config")).json()
        self.assertEqual(config["mcpServers"]["yuketang"]["args"][-1], "hetang")
        self.assertNotIn("fake-new-session", json.dumps(config))
        self.assertEqual(self.calls[0].url.host, "pro.yuketang.cn")
        invalid = await self.web.post("/api/login", json={"region": "https://untrusted.test"})
        self.assertEqual(invalid.status_code, 400)

    async def test_network_diagnosis_uses_selected_region_and_no_session_cookie(self):
        self.factory("standard").save_session("fake-session")
        result = await self.web.post("/api/diagnose", json={"region": "changjiang"})
        self.assertEqual(result.json()["host"], "changjiang.yuketang.cn")
        self.assertNotIn("cookie", self.calls[0].headers)
        self.assertEqual(self.calls[0].url.path, "/")

    async def test_html_has_no_embedded_account_secret(self):
        page = await self.web.get("/")
        self.assertEqual(page.status_code, 200)
        self.assertNotIn(self.key, page.text)
        self.assertIn("frame-ancestors 'none'", page.headers["content-security-policy"])
        self.assertEqual(page.headers["referrer-policy"], "no-referrer")


if __name__ == "__main__":
    unittest.main()
