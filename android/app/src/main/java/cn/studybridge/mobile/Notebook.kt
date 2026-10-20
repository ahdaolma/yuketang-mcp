package cn.studybridge.mobile

import kotlinx.coroutines.CancellationException

fun selectNotePages(from: String, to: String, slides: List<Slide>): List<Slide> {
    val first = from.toIntOrNull() ?: error("请填写开始页码。")
    val last = to.toIntOrNull() ?: error("请填写结束页码。")
    require(first >= 1 && last >= first && last <= (slides.maxOfOrNull { it.page } ?: 0)) { "页码须在 1 至 ${slides.maxOfOrNull { it.page } ?: 0} 之间，结束页不能小于开始页。" }
    require(last - first < 50) { "每次最多连续整理 50 页，请分段选择。" }
    return slides.filter { it.page in first..last }.also { require(it.isNotEmpty()) { "所选范围没有已开放的页面。" } }
}

data class NotebookProgress(val completed: Int, val total: Int, val markdown: String)

// Read, generate and checkpoint one page at a time; never mark an unfinished page complete.
suspend fun buildNotebook(
    title: String,
    pages: List<Slide>,
    read: suspend (Slide) -> Attachment,
    generate: suspend (Attachment) -> String,
    checkpoint: suspend (NotebookProgress) -> Unit,
    onPage: (Int, Int, Int) -> Unit = { _, _, _ -> },
    includeProgress: Boolean = true
) {
    require(pages.isNotEmpty() && pages.map { it.page }.distinct().size == pages.size)
    val sections = mutableListOf<Pair<Int, String>>()
    pages.forEachIndexed { index, page ->
        onPage(page.page, index + 1, pages.size)
        val text = try {
            val attachment = read(page)
            val generated = generate(attachment).also { require(it.isNotBlank()) { "此页未生成有效笔记。" } }
            withSourceImage(attachment, generated)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw IllegalStateException("第 ${page.page} 页未完成；此前已完成 ${sections.size} 页并保存。${e.message?.take(160) ?: "请重试此页。"}", e) }
        sections.add(page.page to text)
        val content = buildString {
            append("# $title\n\n")
            if (includeProgress) {
                append("> 计划范围：第 ${pages.first().page}–${pages.last().page} 页。已完成 ${sections.size}/${pages.size} 页。")
                if (sections.size < pages.size) append("当前为部分笔记；待处理第 ${pages[sections.size].page}–${pages.last().page} 页。")
                else append("所选页面已全部整理。")
                append("\n\n")
            }
            sections.forEach { (number, note) -> append("## 第 $number 页\n\n$note\n\n---\n\n") }
        }
        checkpoint(NotebookProgress(sections.size, pages.size, content))
    }
}
