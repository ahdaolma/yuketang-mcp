package cn.studybridge.mobile

// Only the application's default title is eligible. An existing title always wins.
fun Conversation.withAutomaticTitle(): Conversation {
    if (title != "新对话") return this
    val first = messages.firstOrNull { it.role == "user" && (!it.source.isNullOrBlank() || it.text.isNotBlank()) } ?: return this
    val candidate = first.source?.takeIf { it.isNotBlank() } ?: first.text
    val named = candidate.replace(Regex("\\s+"), " ").trim().take(24)
    return if (named.isBlank()) this else copy(title = named)
}

fun Saved.withAutomaticChatTitles(): Saved = copy(conversations = conversations.map { it.withAutomaticTitle() })

fun allNotePages(slides: List<Slide>): List<Slide> = slides.sortedBy { it.page }.distinctBy { it.page }

fun batchNoteValidation(saved: Saved, pages: List<Slide>): String? {
    if (pages.isEmpty()) return "当前没有已开放的页面。"
    val provider = saved.provider()
    if (!saved.providerReady(provider) || provider.model.isBlank()) return "请先配置模型与账号。"
    if (!provider.images && pages.any { it.cover.isNotBlank() }) return "所选页面包含图片，请选择并开启支持图片输入的模型。"
    return null
}

val UiState.rainBackLabel: String get() = when {
    presentationId.isNotBlank() -> if (lesson != null) "返回本节课件" else "返回课程活动"
    lesson != null -> "返回课程活动"
    course != null -> "返回全部课程"
    else -> "返回资料"
}

val UiState.materialBackLabel: String get() = when (materialPane) {
    "rain" -> rainBackLabel
    "pages" -> if (materialReturnPane == "notes") "返回已有笔记" else "返回本机资料"
    "home" -> "关闭资料"
    else -> "返回资料"
}
