package cn.studybridge.mobile

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

const val CHATGPT_API = "https://api.openai.com/v1"
const val OPENAI_ISSUER = "https://auth.openai.com"
const val CHATGPT_SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
const val CHATGPT_USAGE = "https://chatgpt.com/settings/usage"
private const val DYNAMIC_CLIENT = "dynamic_agent_client"

data class ChatGptAccount(val id: String = newId(), val clientId: String, val subject: String, val email: String, val accessToken: String = "", val refreshToken: String = "", val idToken: String = "", val scopes: List<String> = emptyList(), val expiresAt: Long = 0, val nonce: String = "", val earliestRefreshAt: Long = 0) {
    val planGranted get() = "chatgpt.tokens.use.direct" in scopes && "resource.invoke" in scopes
    val connected get() = accessToken.isNotBlank()
    val label get() = (email.ifBlank { "ChatGPT 账号" }) + " · " + clientId.takeLast(6)
    fun signedOut() = copy(accessToken = "", refreshToken = "", idToken = "", scopes = emptyList(), expiresAt = 0, nonce = "", earliestRefreshAt = 0)
    override fun toString() = "ChatGptAccount(id=$id, credentials=redacted)"
}
fun ChatGptAccount.json() = JSONObject().put("id", id).put("clientId", clientId).put("subject", subject).put("email", email).put("accessToken", accessToken).put("refreshToken", refreshToken).put("idToken", idToken).put("scopes", JSONArray(scopes)).put("expiresAt", expiresAt).put("nonce", nonce).put("earliestRefreshAt", earliestRefreshAt)
fun chatGptAccountFromJson(o: JSONObject): ChatGptAccount {
    val a = o.optJSONArray("scopes") ?: JSONArray()
    return ChatGptAccount(o.getString("id"), o.getString("clientId"), o.getString("subject"), o.optString("email"), o.optString("accessToken"), o.optString("refreshToken"), o.optString("idToken"), (0 until a.length()).map { a.getString(it) }, o.optLong("expiresAt"), o.optString("nonce"), o.optLong("earliestRefreshAt"))
}
fun freshSecret(bytes: Int = 32): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
data class OpenAiEndpoints(val issuer: String, val authorize: String, val token: String, val jwks: String, val revoke: String?)
data class OAuthAttempt(val redirect: String, val clientId: String, val hostId: String, val state: String = freshSecret(), val nonce: String = freshSecret(), val verifier: String = freshSecret(48), val idTokenHint: String = "", val emailHint: String = "", val expectedSubject: String = "", val forceConsent: Boolean = false) {
    override fun toString() = "OAuthAttempt(secrets=redacted)"
}
data class OAuthCallback(val code: String, val clientId: String, val error: String = "") { override fun toString() = "OAuthCallback(redacted)" }
class OAuthFailure(val reason: String, message: String): IllegalStateException(message)

fun authorizationUrl(e: OpenAiEndpoints, a: OAuthAttempt): String = e.authorize.toHttpUrl().newBuilder().apply {
    addQueryParameter("client_id", a.clientId)
    if (a.clientId == DYNAMIC_CLIENT) addQueryParameter("agent_name_hint", "雨课伴学")
    if (a.forceConsent) addQueryParameter("prompt", "consent")
    addQueryParameter("ext_agent_host_id", a.hostId)
    addQueryParameter("response_type", "code"); addQueryParameter("redirect_uri", a.redirect)
    addQueryParameter("scope", CHATGPT_SCOPES); addQueryParameter("resource", CHATGPT_API)
    addQueryParameter("state", a.state); addQueryParameter("nonce", a.nonce)
    addQueryParameter("code_challenge_method", "S256"); addQueryParameter("code_challenge", pkceChallenge(a.verifier))
    if (a.clientId != DYNAMIC_CLIENT) {
        if (a.idTokenHint.isNotBlank()) addQueryParameter("id_token_hint", a.idTokenHint)
        if (a.emailHint.isNotBlank()) addQueryParameter("login_hint", a.emailHint)
    }
}.build().toString()

// Returns null for unrelated/invalid requests; these must not consume the transaction.
fun oauthCallback(target: String, host: String, a: OAuthAttempt): OAuthCallback? {
    if (target.length > 12000 || host != a.redirect.toHttpUrl().let { "${it.host}:${it.port}" } || !target.startsWith("/auth/callback?")) return null
    val u = runCatching { ("http://$host$target").toHttpUrl() }.getOrNull() ?: return null
    if (u.encodedPath != "/auth/callback" || u.fragment != null) return null
    if (listOf("state", "code", "client_id", "error").any { u.queryParameterValues(it).size > 1 }) return null
    val state = u.queryParameter("state") ?: return null
    if (!MessageDigest.isEqual(state.toByteArray(), a.state.toByteArray())) return null
    val error = u.queryParameter("error")
    if (error != null) return OAuthCallback("", a.clientId, if (error == "access_denied") "access_denied" else "authorization_failed")
    val supplied = u.queryParameter("client_id")
    val client = if (a.clientId == DYNAMIC_CLIENT) supplied ?: return OAuthCallback("", "", "missing_client") else {
        if (supplied != null && supplied != a.clientId) return OAuthCallback("", "", "client_mismatch")
        a.clientId
    }
    if (client == DYNAMIC_CLIENT || !client.matches(Regex("[A-Za-z0-9._-]{1,200}"))) return OAuthCallback("", "", "invalid_client")
    val code = u.queryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 8192 && it.none(Char::isISOControl) } ?: return OAuthCallback("", client, "missing_code")
    return OAuthCallback(code, client)
}

class LoopbackReceiver : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
    @Volatile private var current: Socket? = null
    override fun close() { runCatching { current?.close() }; runCatching { server.close() } }
    suspend fun receive(a: OAuthAttempt): OAuthCallback = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        try {
            while (System.nanoTime() < deadline) {
                ensureActive()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                current = socket
                socket.use {
                    it.soTimeout = 2000
                    try {
                        val input = it.getInputStream(); val head = StringBuilder()
                        while (head.length <= 16384 && !head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; head.append(c.toChar()) }
                        val lines = head.toString().split("\r\n")
                        val first = lines.first().split(' ')
                        val hosts = lines.drop(1).filter { line -> line.substringBefore(':').equals("Host", true) }
                        val result = if (head.length <= 16384 && first.size == 3 && first[0] == "GET" && first[2] in listOf("HTTP/1.1", "HTTP/1.0") && hosts.size == 1) oauthCallback(first[1], hosts.single().substringAfter(':').trim(), a) else null
                        val body = if (result != null) "<!doctype html><html lang=zh><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>雨课伴学</title><body><h2>已收到授权结果</h2><p>返回 App 完成账号验证。此页面不代表模型请求已经验证成功。</p><a href=\"rainstudy://openai-return\">返回雨课伴学</a></body></html>" else "Invalid callback. Return to the authorization page."
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val status = if (result == null) "400 Bad Request" else "200 OK"
                        val headers = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'\r\nConnection: close\r\n\r\n"
                        try { it.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() } } catch (_: java.io.IOException) { /* The validated callback must not depend on the browser reading its response. */ }
                        if (result != null) return@withContext result
                    } catch (_: java.io.IOException) { /* Ignore incomplete or aborted local requests. */ }
                }
                current = null
            }
            throw OAuthFailure("timeout", "ChatGPT 授权超时，请回到模型页重新开始。")
        } finally { close() }
    }
}

class ChatGptAuth(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()) {
    private fun get(url: String): JSONObject = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        check(r.isSuccessful) { "无法读取 OpenAI 授权配置，请检查网络。" }
        JSONObject(String(readBounded(r, 262144), Charsets.UTF_8))
    }
    fun endpoints(): OpenAiEndpoints {
        val o = get("$OPENAI_ISSUER/.well-known/openid-configuration")
        check(o.optString("issuer") == OPENAI_ISSUER) { "OpenAI 授权配置身份不匹配。" }
        fun endpoint(k: String): String {
            val value = o.getString(k); val u = value.toHttpUrl()
            check(u.isHttps && u.host == "auth.openai.com" && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null) { "OpenAI 授权配置地址不受信任。" }
            return value
        }
        return OpenAiEndpoints(OPENAI_ISSUER, endpoint("authorization_endpoint"), endpoint("token_endpoint"), endpoint("jwks_uri"), if (o.optional("revocation_endpoint") == null) null else endpoint("revocation_endpoint"))
    }
    private fun token(e: OpenAiEndpoints, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return client.newCall(Request.Builder().url(e.token).post(form).build()).execute().use { r ->
            val data = runCatching { JSONObject(String(readBounded(r, 262144), Charsets.UTF_8)) }.getOrElse { throw OAuthFailure("response", "OpenAI 授权响应无法识别，请重新登录。") }
            if (!r.isSuccessful || data.has("error")) {
                val code = data.optString("error")
                val terminal = code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused", "invalid_client")
                throw OAuthFailure(if (terminal) code else "token_error", if (terminal) "本次授权码或续期记录已失效，请重新使用 ChatGPT 登录。" else "OpenAI 未完成授权或续期，请检查账号权限后重试。")
            }
            data
        }
    }
    fun exchange(e: OpenAiEndpoints, a: OAuthAttempt, callback: OAuthCallback, accountId: String = newId()): ChatGptAccount {
        check(callback.error.isBlank()) { "ChatGPT 授权未完成，请重新开始。" }
        val data = token(e, mapOf("grant_type" to "authorization_code", "client_id" to callback.clientId, "code" to callback.code, "code_verifier" to a.verifier, "redirect_uri" to a.redirect, "resource" to CHATGPT_API))
        val identity = verifyOpenAiIdToken(data.getString("id_token"), get(e.jwks).toString(), callback.clientId, a.nonce, a.expectedSubject)
        return accountFromGrant(data, identity, callback.clientId, accountId, a.nonce)
    }
    fun refresh(e: OpenAiEndpoints, old: ChatGptAccount): ChatGptAccount {
        check(old.refreshToken.isNotBlank()) { "ChatGPT 登录需要重新授权。" }
        val data = token(e, mapOf("grant_type" to "refresh_token", "client_id" to old.clientId, "refresh_token" to old.refreshToken, "resource" to CHATGPT_API))
        val id = data.optional("id_token")
        val identity = id?.let { verifyOpenAiIdToken(it, get(e.jwks).toString(), old.clientId, old.nonce, old.subject, refreshing = true) } ?: OpenAiIdentity(old.subject, old.email)
        return accountFromGrant(data, identity, old.clientId, old.id, old.nonce, old)
    }
    fun revoke(e: OpenAiEndpoints, account: ChatGptAccount): Boolean {
        if (account.refreshToken.isBlank()) return true
        val url = e.revoke ?: return false
        val form = FormBody.Builder().add("token", account.refreshToken).add("token_type_hint", "refresh_token").add("client_id", account.clientId).build()
        return client.newCall(Request.Builder().url(url).post(form).build()).execute().use { it.code == 200 }
    }
}
data class OpenAiIdentity(val subject: String, val email: String)
fun verifyOpenAiIdToken(token: String, jwks: String, clientId: String, nonce: String, expectedSubject: String = "", now: Long = System.currentTimeMillis(), refreshing: Boolean = false): OpenAiIdentity {
    try {
        require(token.length <= 65536)
        val jwt = SignedJWT.parse(token)
        val header = jwt.header
        val key = JWKSet.parse(jwks).getKeyByKeyId(header.keyID ?: error("Missing kid")) ?: error("Unknown signing key")
        val valid = when {
            header.algorithm == JWSAlgorithm.RS256 && key is RSAKey -> jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))
            header.algorithm == JWSAlgorithm.ES256 && key is ECKey -> jwt.verify(ECDSAVerifier(key.toECPublicKey()))
            else -> false
        }
        require(valid)
        val claims = jwt.jwtClaimsSet
        require(claims.issuer == OPENAI_ISSUER && clientId in claims.audience)
        if (claims.audience.size > 1) require(claims.getStringClaim("azp") == clientId)
        require(claims.expirationTime != null && claims.expirationTime.time > now - 5000)
        require(claims.issueTime != null && claims.issueTime.time <= now + 5000)
        require(claims.notBeforeTime == null || claims.notBeforeTime.time <= now + 5000)
        val returnedNonce = claims.getStringClaim("nonce")
        require(if (refreshing && returnedNonce == null) true else returnedNonce == nonce)
        val subject = claims.subject?.takeIf { it.isNotBlank() } ?: error("Missing subject")
        require(expectedSubject.isBlank() || subject == expectedSubject)
        return OpenAiIdentity(subject, claims.getStringClaim("email") ?: "")
    } catch (_: Exception) { throw OAuthFailure("identity", "ChatGPT 账号身份验证未通过；本次授权未替换原登录，请重新开始。") }
}
fun accountFromGrant(o: JSONObject, identity: OpenAiIdentity, clientId: String, id: String, nonce: String, previous: ChatGptAccount? = null, now: Long = System.currentTimeMillis()): ChatGptAccount {
    val scopes = o.optional("scope")?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: previous?.scopes ?: emptyList()
    val token = o.optional("access_token") ?: ""
    val refresh = o.optional("refresh_token") ?: previous?.refreshToken ?: ""
    val seconds = o.optLong("expires_in", 0)
    if ("chatgpt.tokens.use.direct" in scopes) {
        require(o.optString("token_type").equals("Bearer", true) && seconds in 1..86400 && token.isNotBlank()) { "OpenAI 未返回有效的套餐访问凭据。" }
        require(token.none { it.isISOControl() || it.isWhitespace() } && refresh.none { it.isISOControl() || it.isWhitespace() }) { "OpenAI 凭据格式无法识别。" }
    }
    val earliest = o.optLong("earliest_refresh_at", 0).let { if (it in 1..9999999999L) it * 1000 else it }
    return ChatGptAccount(id, clientId, identity.subject, identity.email, token, refresh, o.optional("id_token") ?: previous?.idToken ?: "", scopes, now + seconds * 1000, nonce, earliest)
}
