package cn.studybridge.mobile

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun readsProviderCatalogBeforeAnyModelIsSelectedWithoutGeneratingChat() {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); paths.add(request.url.encodedPath)
            assertEquals("GET", request.method); assertEquals("Bearer fixture-key", request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"id":"vision-model","display_name":"视觉讲解"},{"id":"text-model","name":"文字讲解"},{"id":"vision-model"}]}""".toResponseBody()).build()
        }.build()
        val choices = AiApi(client).modelChoices(Provider(baseUrl = "https://provider.example/v1", key = "fixture-key", model = ""))
        assertEquals(listOf("/v1/models"), paths)
        assertEquals(listOf(ModelChoice("text-model", "文字讲解"), ModelChoice("vision-model", "视觉讲解")), choices)
    }
    @Test fun editingAConnectionInvalidatesItsCatalogWhileChoosingAModelDoesNot() {
        val p = Provider(id = "fixture", baseUrl = "https://provider.example/v1", key = "fixture-key")
        val original = modelCatalogKey(p)
        for (changed in listOf(p.copy(baseUrl = "https://other.example/v1"), p.copy(key = "different-key"), p.copy(protocol = "responses"), p.copy(chatgptAccountId = "different-account"), p.copy(id = "different-row"))) assertNotEquals(original, modelCatalogKey(changed))
        assertEquals(original, modelCatalogKey(p.copy(model = "vision-model", name = "重命名", images = true)))
        assertEquals(original, modelCatalogKey(p.copy(baseUrl = p.baseUrl + "/")))
        assertFalse(original.contains(p.key))
    }
}
