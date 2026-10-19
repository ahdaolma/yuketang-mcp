package cn.studybridge.mobile

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date

class ChatGptAuthTest {
    companion object {
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val key = RSAKey.Builder(pair.public as RSAPublicKey).privateKey(pair.private as RSAPrivateKey).keyID("unit-key").build()
        private val jwks = JWKSet(key.toPublicJWK()).toString()
    }
    private val now = System.currentTimeMillis()
    private fun jwt(aud: String = "oaiapp_test", issuer: String = OPENAI_ISSUER, nonce: String = "test-nonce", subject: String = "test-subject", expiry: Long = now + 3600000, issued: Long = now): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(aud).subject(subject).claim("email", "unit@example.com").claim("nonce", nonce).issueTime(Date(issued)).expirationTime(Date(expiry)).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unit-key").build(), claims).apply { sign(RSASSASigner(key)) }.serialize()
    }
    private fun endpoints() = OpenAiEndpoints(OPENAI_ISSUER, "$OPENAI_ISSUER/api/accounts/authorize", "$OPENAI_ISSUER/api/accounts/oauth/token", "$OPENAI_ISSUER/.well-known/jwks.json", "$OPENAI_ISSUER/oauth/revoke")
    private fun attempt(client: String = "dynamic_agent_client") = OAuthAttempt("http://127.0.0.1:1455/auth/callback", client, "urn:uuid:00000000-0000-4000-8000-000000000001", state = "test-state", nonce = "test-nonce")
    private fun response(request: Request, body: String, status: Int = 200) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody()).build()
    private fun form(request: Request): okhttp3.HttpUrl { val b = okio.Buffer(); request.body!!.writeTo(b); return ("https://unit.invalid/?" + b.readUtf8()).toHttpUrl() }

    @Test fun defaultConfigurationIsProviderNeutralAndExistingConfigurationSurvives() {
        val p = Provider()
        assertEquals("模型配置", p.name); assertTrue(p.baseUrl.isBlank()); assertTrue(p.model.isBlank())
        val configured = Provider(name = "DeepSeek", baseUrl = "https://api.deepseek.com", key = "test-configured", model = "deepseek-flash", images = true)
        assertEquals(configured, savedFromJson(Saved(providers = listOf(configured)).toJson()).providers.single())
        val pristine = configured.copy(key = "")
        assertEquals("模型配置", savedFromJson(Saved(providers = listOf(pristine)).toJson()).providers.single().name)
    }
    @Test fun pkceMatchesTheRfc7636Challenge() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val one = freshSecret(); assertTrue(one.length >= 43); assertFalse(one.contains('=')); assertNotEquals(one, freshSecret())
    }
    @Test fun authorizationRequestsAllScopesAndKeepsRegistrationsSeparate() {
        val a = attempt()
        val first = authorizationUrl(endpoints(), a).toHttpUrl()
        assertEquals("雨课伴学", first.queryParameter("agent_name_hint")); assertEquals(CHATGPT_API, first.queryParameter("resource"))
        assertEquals(CHATGPT_SCOPES, first.queryParameter("scope")); assertEquals(a.redirect, first.queryParameter("redirect_uri")); assertEquals("S256", first.queryParameter("code_challenge_method"))
        assertFalse(first.toString().contains(a.verifier)); assertNull(first.queryParameter("prompt"))
        val returning = authorizationUrl(endpoints(), a.copy(clientId = "oaiapp_saved", idTokenHint = "test-id-hint", forceConsent = true)).toHttpUrl()
        assertNull(returning.queryParameter("agent_name_hint")); assertEquals("oaiapp_saved", returning.queryParameter("client_id")); assertEquals("test-id-hint", returning.queryParameter("id_token_hint")); assertEquals("consent", returning.queryParameter("prompt"))
        assertFalse(a.toString().contains(a.verifier))
    }
    @Test fun callbackRequiresMatchingStateHostPathAndIssuedClient() {
        val a = attempt(); val host = "127.0.0.1:1455"
        val valid = "/auth/callback?state=test-state&code=unit-code&client_id=oaiapp_new"
        assertEquals("oaiapp_new", oauthCallback(valid, host, a)!!.clientId)
        assertNull(oauthCallback(valid.replace("test-state", "wrong"), host, a))
        assertNull(oauthCallback(valid, "attacker.invalid:1455", a))
        assertNull(oauthCallback(valid.replace("/auth/callback", "/callback"), host, a))
        assertNull(oauthCallback(valid + "&code=other", host, a))
        assertEquals("missing_client", oauthCallback("/auth/callback?state=test-state&code=unit-code", host, a)!!.error)
        assertEquals("client_mismatch", oauthCallback(valid, host, attempt("oaiapp_saved"))!!.error)
        assertEquals("access_denied", oauthCallback("/auth/callback?state=test-state&error=access_denied&code=unused", host, a)!!.error)
        assertFalse(oauthCallback(valid, host, a).toString().contains("unit-code"))
    }
    @Test fun realLoopbackListenerIgnoresWrongStateThenConsumesTheValidCode() = runBlocking {
        val receiver = LoopbackReceiver(); val a = attempt().copy(redirect = receiver.redirect)
        val result = async(Dispatchers.IO) { receiver.receive(a) }
        val u = receiver.redirect.toHttpUrl()
        fun call(state: String): String = Socket("127.0.0.1", u.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().apply { write("GET /auth/callback?state=$state&code=secret-test-code&client_id=oaiapp_test HTTP/1.1\r\nHost: 127.0.0.1:${u.port}\r\n\r\n".toByteArray()); flush() }
            socket.getInputStream().bufferedReader().readText()
        }
        assertTrue(call("wrong").startsWith("HTTP/1.1 400"))
        val html = call("test-state"); assertTrue(html.startsWith("HTTP/1.1 200")); assertTrue(html.contains("rainstudy://openai-return")); assertFalse(html.contains("secret-test-code"))
        assertEquals("secret-test-code", withTimeout(5000) { result.await() }.code)
        assertTrue(runCatching { Socket("127.0.0.1", u.port).close() }.isFailure)
    }
    @Test fun signedIdentityIsCheckedBeforeTrustingEmailOrSubject() {
        val verified = verifyOpenAiIdToken(jwt(), jwks, "oaiapp_test", "test-nonce", now = now)
        assertEquals("test-subject", verified.subject); assertEquals("unit@example.com", verified.email)
        listOf(jwt(aud = "another-client"), jwt(issuer = "https://attacker.invalid"), jwt(nonce = "wrong"), jwt(expiry = now - 10000), jwt(issued = now + 60000)).forEach { invalid ->
            val error = runCatching { verifyOpenAiIdToken(invalid, jwks, "oaiapp_test", "test-nonce", now = now) }.exceptionOrNull()
            assertTrue(error is OAuthFailure); assertFalse(error!!.message!!.contains(invalid))
        }
        assertTrue(runCatching { verifyOpenAiIdToken(jwt(), jwks, "oaiapp_test", "test-nonce", expectedSubject = "different-user", now = now) }.isFailure)
        val tampered = jwt().split('.').let { "${it[0]}.${it[1]}.AAAA" }
        assertTrue(runCatching { verifyOpenAiIdToken(tampered, jwks, "oaiapp_test", "test-nonce", now = now) }.isFailure)
    }
    @Test fun codeExchangeUsesPkceIssuedClientAndValidatedOpenAiKeys() {
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); requested.add(r.url.encodedPath); assertNull(r.header("Cookie")); assertNull(r.header("Authorization"))
            when (r.url.encodedPath) {
                "/.well-known/openid-configuration" -> response(r, JSONObject().put("issuer", OPENAI_ISSUER).put("authorization_endpoint", endpoints().authorize).put("token_endpoint", endpoints().token).put("jwks_uri", endpoints().jwks).put("revocation_endpoint", endpoints().revoke).toString())
                "/.well-known/jwks.json" -> response(r, jwks)
                else -> {
                    val f = form(r); assertEquals("oaiapp_test", f.queryParameter("client_id")); assertEquals("unit-code", f.queryParameter("code")); assertEquals("test-verifier", f.queryParameter("code_verifier")); assertEquals("http://127.0.0.1:1455/auth/callback", f.queryParameter("redirect_uri")); assertNull(f.queryParameter("client_secret"))
                    response(r, JSONObject().put("id_token", jwt()).put("access_token", "test-access").put("refresh_token", "test-refresh").put("token_type", "Bearer").put("expires_in", 3600).put("scope", CHATGPT_SCOPES).toString())
                }
            }
        }.build()
        val auth = ChatGptAuth(client); val e = auth.endpoints()
        val account = auth.exchange(e, attempt().copy(verifier = "test-verifier"), OAuthCallback("unit-code", "oaiapp_test"), "local-profile")
        assertTrue(account.connected); assertTrue(account.planGranted); assertEquals("local-profile", account.id)
        assertEquals(listOf("/.well-known/openid-configuration", "/api/accounts/oauth/token", "/.well-known/jwks.json"), requested)
        assertFalse(account.toString().contains(account.accessToken))
    }
    @Test fun identityOnlyGrantDoesNotAuthorizeInferenceAndSerializationRetainsBindings() {
        val account = accountFromGrant(JSONObject().put("id_token", "test-only"), OpenAiIdentity("test-sub", "unit@example.com"), "oaiapp_unit", "profile", "nonce", now = now)
        assertFalse(account.planGranted)
        val run = Saved(chatgptAccounts = listOf(account), chatgptHostId = "urn:uuid:test", chatgptDraftClients = listOf("oaiapp_pending"))
        assertEquals(run, savedFromJson(run.toJson()))
        assertFalse(run.providerReady(Provider(chatgptAccountId = account.id)))
    }
    @Test fun refreshRotatesTokensAtomicallyWithoutMixingClientsOrAddingScopes() {
        val old = ChatGptAccount(clientId = "oaiapp_existing", subject = "test-sub", email = "test@example.com", accessToken = "test-old-access", refreshToken = "test-old-refresh", idToken = "test-retained-id", scopes = CHATGPT_SCOPES.split(' '))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val f = form(chain.request()); assertEquals("refresh_token", f.queryParameter("grant_type")); assertEquals(old.clientId, f.queryParameter("client_id")); assertEquals(old.refreshToken, f.queryParameter("refresh_token")); assertNull(f.queryParameter("scope"))
            response(chain.request(), """{"access_token":"test-new-access","refresh_token":"test-new-refresh","token_type":"Bearer","expires_in":3600}""")
        }.build()
        val fresh = ChatGptAuth(client).refresh(endpoints(), old)
        assertEquals("test-new-refresh", fresh.refreshToken); assertEquals(old.clientId, fresh.clientId); assertEquals(old.subject, fresh.subject); assertEquals(old.scopes, fresh.scopes); assertEquals(old.idToken, fresh.idToken)
        val signedOut = fresh.signedOut(); assertTrue(signedOut.accessToken.isEmpty()); assertTrue(signedOut.refreshToken.isEmpty()); assertTrue(signedOut.idToken.isEmpty()); assertEquals(old.clientId, signedOut.clientId)
    }
    @Test fun failedRefreshRetainsAMachineReadableReasonWithoutEchoingCredentials() {
        val client = OkHttpClient.Builder().addInterceptor { c -> response(c.request(), """{"error":"invalid_grant","error_description":"test-secret-never-echo"}""", 400) }.build()
        val old = ChatGptAccount(clientId = "oaiapp_test", subject = "test-sub", email = "", refreshToken = "test-refresh")
        val error = runCatching { ChatGptAuth(client).refresh(endpoints(), old) }.exceptionOrNull() as OAuthFailure
        assertEquals("invalid_grant", error.reason); assertFalse(error.message!!.contains("test-secret-never-echo"))
    }
    @Test fun discoveryRejectsCredentialOrKeyEndpointsOnAnotherHost() {
        val client = OkHttpClient.Builder().addInterceptor { c -> response(c.request(), JSONObject().put("issuer", OPENAI_ISSUER).put("authorization_endpoint", endpoints().authorize).put("token_endpoint", "https://attacker.invalid/steal").put("jwks_uri", endpoints().jwks).toString()) }.build()
        assertTrue(runCatching { ChatGptAuth(client).endpoints() }.isFailure)
    }
    @Test fun modelPickerKeepsAccountSpecificOrderAndPassesSlugs() {
        val data = JSONObject("""{"models":[{"slug":"model-b","display_name":"Model B","visibility":"list"},{"slug":"hidden","visibility":"hidden"},{"slug":"model-a","display_name":"Model A","visibility":"list"}],"data":[{"id":"not-the-plan-catalog"}]}""")
        assertEquals(listOf(ModelChoice("model-b", "Model B"), ModelChoice("model-a", "Model A")), parseModelChoices(data, true))
    }
    @Test fun oauthAccessTokenCanNeverBeSentToACustomProviderHost() = runBlocking {
        var called = false
        val client = OkHttpClient.Builder().addInterceptor { called = true; error("Must not send") }.build()
        val p = Provider(baseUrl = "https://attacker.invalid/v1", key = "test-access", model = "unit", protocol = "responses", chatgptAccountId = "profile")
        assertTrue(runCatching { AiApi(client).stream(p, listOf(Message("user", "test"))) {} }.isFailure)
        assertFalse(called)
    }
    @Test fun policyAndUsageErrorsAreDistinctAndNeverSwitchToApiBilling() {
        val req = Request.Builder().url("$CHATGPT_API/responses").build()
        val policy = response(req, """{"error":{"code":"subscription_sharing_user_not_eligible"}}""", 403).use(::chatGptResponseFailure)
        assertTrue(policy.message!!.contains("不支持")); assertTrue(policy.message!!.contains("重新登录不能"))
        val limited = response(req, """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", 429).use(::chatGptResponseFailure)
        assertTrue(limited.message!!.contains("管理用量")); assertEquals(429, limited.status)
        val nonstandard = response(req, """{"detail":"private diagnostic"}""", 503).use(::chatGptResponseFailure)
        assertTrue(nonstandard.message!!.contains("登录已保留")); assertFalse(nonstandard.message!!.contains("private diagnostic"))
    }
}
