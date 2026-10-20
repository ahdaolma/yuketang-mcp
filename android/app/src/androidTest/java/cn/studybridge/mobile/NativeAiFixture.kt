package cn.studybridge.mobile

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject

// An in-memory HTTP interceptor: the production AiApi still builds and parses
// its real request/stream, but no model credential or network call leaves tests.
fun fixtureAiApi(answer: String, inspect: (Request) -> Unit = {}, beforeResponse: () -> Unit = {}): AiApi = AiApi(OkHttpClient.Builder().addInterceptor { chain ->
    val request = chain.request(); inspect(request); beforeResponse()
    val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", answer))))
    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(("data: $delta\n\ndata: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType())).build()
}.build())
