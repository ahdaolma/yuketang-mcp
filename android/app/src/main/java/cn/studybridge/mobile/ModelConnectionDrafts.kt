package cn.studybridge.mobile

import androidx.compose.runtime.mutableStateOf

/** In-memory only: credentials in unfinished forms must never enter SavedState. */
class ModelConnectionDrafts {
    val adding = mutableStateOf<Provider?>(null)
    private var newProvider: Provider? = null
    private var selected: String? = null
    private val drafts = mutableMapOf<String, ModelConnectionDraft>()

    fun observeSelection(id: String) {
        if (selected != null && selected != id) adding.value = null
        selected = id
    }

    fun draft(provider: Provider): ModelConnectionDraft {
        val previous = drafts[provider.id]
        if (previous == null || previous.original != provider && previous.value() == previous.original) {
            return ModelConnectionDraft(provider, provider.id == newProvider?.id).also { drafts[provider.id] = it }
        }
        return previous
    }

    fun beginNew() {
        val provider = newProvider ?: Provider(name = "自定义模型").also { newProvider = it }
        adding.value = provider
    }

    fun saved(id: String) {
        drafts.remove(id)
        if (newProvider?.id == id) cancelNew()
    }

    fun cancelNew() {
        newProvider?.id?.let(drafts::remove)
        newProvider = null
        adding.value = null
    }

    fun discard(id: String) { drafts.remove(id) }
}

class ModelConnectionDraft(val original: Provider, isNew: Boolean) {
    val name = mutableStateOf(original.name)
    val base = mutableStateOf(original.baseUrl)
    val key = mutableStateOf(original.key)
    val model = mutableStateOf(original.model)
    val protocol = mutableStateOf(original.protocol)
    val images = mutableStateOf(original.images)
    val showConfig = mutableStateOf(isNew)
    val manualModel = mutableStateOf(false)

    fun value() = original.copy(name = name.value, baseUrl = base.value, key = key.value,
        model = model.value, protocol = protocol.value, images = images.value)
}
