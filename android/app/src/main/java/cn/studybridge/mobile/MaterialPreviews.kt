package cn.studybridge.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import cn.studybridge.mobile.FeedbackTextButton as TextButton
import cn.studybridge.mobile.FeedbackOutlinedButton as OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

private data class VisualLoad(val key: String, val attempt: Int, val visual: PageVisual?, val error: String = "")
data class PageVisual(val bitmap: Bitmap? = null, val text: String = "", val imageId: String = "")
class MaterialPreviews(context: Context) {
    private val root = File(context.cacheDir, "page-previews").apply { mkdirs() }
    val markup = ImageMarkupStore(context)
    private companion object { val gate = Semaphore(2) }
    suspend fun load(key: String, source: suspend () -> Attachment): PageVisual = gate.withPermit {
        withContext(Dispatchers.IO) {
            val file = File(root, imageDigest("preview1280:$key".toByteArray()) + ".jpg")
            if (file.isFile) {
                val image = BitmapFactory.decodeFile(file.path)
                if (image != null) {
                    file.setLastModified(System.currentTimeMillis())
                    val savedId = runCatching { File(root, file.nameWithoutExtension + ".id").readText() }.getOrDefault("")
                    // Existing offline note thumbnails predate the digest sidecar.
                    val id = savedId.ifBlank { key.substringAfterLast(':').takeIf { Regex("[a-f0-9]{64}").matches(it) }.orEmpty() }
                    return@withContext PageVisual(image, imageId = id)
                }
                file.delete()
            }
            val textFile = File(root, file.nameWithoutExtension + ".txt")
            if (textFile.isFile) { textFile.setLastModified(System.currentTimeMillis()); return@withContext PageVisual(text = textFile.readText()) }
            val a = source()
            if (a.image == null) {
                val text = a.text.take(2000)
                runCatching { textFile.writeText(text); trim() }.onFailure { textFile.delete() }
                return@withContext PageVisual(text = text)
            }
            val originalBytes = Base64.decode(a.image.substringAfter(','), Base64.DEFAULT)
            val imageId = imageDigest(originalBytes)
            val original = boundedBitmap(originalBytes)
            run {
                val scale = minOf(1f, 1280f / maxOf(original.width, original.height))
                val small = Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
                // A failed cache write must not hide an otherwise readable page.
                val temp = File.createTempFile("preview-", ".tmp", root)
                try { runCatching { temp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }; check(temp.renameTo(file)); File(root, file.nameWithoutExtension + ".id").writeText(imageId); trim() } }
                finally { temp.delete() }
                if (small !== original) original.recycle()
                PageVisual(small, imageId = imageId)
            }
        }
    }
    @Synchronized private fun trim() {
        val files = root.listFiles()?.filter { it.extension in listOf("jpg", "txt") }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) { if (total <= 40L * 1024 * 1024) break; val size = file.length(); if (file.delete()) { total -= size; File(root, file.nameWithoutExtension + ".id").delete() } }
    }
}

@Composable fun PagePreview(key: String, label: String, repository: MaterialPreviews, markup: ImageMarkupStore = repository.markup, source: suspend () -> Attachment) {
    val loader by rememberUpdatedState(source)
    var retry by remember(key) { mutableIntStateOf(0) }
    var enlarged by remember(key) { mutableStateOf(false) }
    val result by produceState<VisualLoad?>(null, key, retry) {
        value = null
        value = try { VisualLoad(key, retry, withContext(Dispatchers.IO) {
            val raw = repository.load(key) { loader() }
            if (raw.bitmap != null && raw.imageId.isNotBlank()) raw.copy(bitmap = markup.render(raw.imageId, raw.bitmap)) else raw
        }) } catch (e: CancellationException) { throw e } catch (_: Exception) { VisualLoad(key, retry, null, "预览暂不可用") }
    }
    val renderReady = LocalNoteRenderReady.current
    LaunchedEffect(result, key, retry, renderReady) {
        // A visible error is also a finished layout; a missing image must not
        // prevent the reader from reaching the remaining pages.
        renderReady?.invoke(result?.let { it.key == key && it.attempt == retry } == true)
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val current = result?.takeIf { it.key == key && it.attempt == retry }
            if (current == null) Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)); }
            else if (current.visual == null) Row(verticalAlignment = Alignment.CenterVertically) { Text(current.error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { retry++ }) { Text("重试预览") } }
            else {
                current.visual?.bitmap?.let { Image(it.asImageBitmap(), "$label 缩略图", Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height).clickable(onClickLabel = "查看和批注图片") { enlarged = true }, contentScale = ContentScale.Fit) }
                    ?: Text(current.visual?.text.orEmpty().ifBlank { "此页没有可预览内容" }, Modifier.fillMaxWidth().padding(8.dp).clickable(onClickLabel = "查看完整内容") { enlarged = true }, maxLines = 5, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (enlarged) ImageViewer(label, onClose = { enlarged = false; retry++ }, markup = markup) { loader() }
}

@Composable fun ImageViewer(label: String, onClose: () -> Unit, markup: ImageMarkupStore? = null, source: suspend () -> Attachment) {
    val context = LocalContext.current
    val store = markup ?: remember(context) { ImageMarkupStore(context) }
    val closeRequest = remember { arrayOfNulls<() -> Unit>(1) }
    Dialog(onDismissRequest = { closeRequest[0]?.invoke() ?: onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImageViewerContent(label, onClose, store, { closeRequest[0] = it }, source)
    }
}

@Composable private fun ImageViewerContent(label: String, onClose: () -> Unit, markup: ImageMarkupStore, setCloseRequest: (() -> Unit) -> Unit, source: suspend () -> Attachment) {
    val loader by rememberUpdatedState(source)
    var retry by remember(label) { mutableIntStateOf(0) }
    val attempt = retry
    val visual by produceState<VisualLoad?>(null, label, attempt) {
        value = null
        val loadSource = loader
        value = try { withContext(Dispatchers.IO) {
            val a = loadSource()
            val bytes = a.image?.let { Base64.decode(it.substringAfter(','), Base64.DEFAULT) }
            VisualLoad(label, attempt, PageVisual(bytes?.let { boundedBitmap(it) }, a.text, bytes?.let { imageDigest(it) }.orEmpty()))
        } } catch (e: CancellationException) { throw e } catch (_: Exception) { VisualLoad(label, attempt, null, "原页读取失败，请检查网络或重新导入资料。") }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = visual?.takeIf { it.key == label && it.attempt == attempt }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            val page = current?.visual
            val bitmap = page?.bitmap
            if (page != null && bitmap != null) ImageMarkupEditor(bitmap, page.imageId, label, markup, onClose, setCloseRequest)
            else {
                SideEffect { setCloseRequest(onClose) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), maxLines = 3); TextButton(onClick = onClose) { Text("关闭预览") } }
                if (current == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else if (page == null) { Text(current.error); OutlinedButton(onClick = { retry++ }) { Text("重新读取") } }
                else Text(page.text, Modifier.weight(1f).verticalScroll(rememberScrollState()))
            }
        }
    }
}
@Composable fun IllustratedMarkdown(text: String, sourceImages: SourceImages? = null, sourceOffset: Int = 0) {
    val context = LocalContext.current
    val images = sourceImages ?: remember(context) { SourceImages(context) }
    val repository = remember(context) { MaterialPreviews(context) }
    // Only our private, content-addressed images are loaded. Arbitrary model links
    // remain ordinary Markdown and never trigger file or network image access.
    val matches = remember(text) { sourceImagePattern.findAll(text).toList() }
    val report = LocalNoteRenderReady.current
    val ready = remember(text) { mutableStateMapOf<Int, Boolean>() }
    val count = remember(text) {
        var position = 0; var total = 0
        matches.forEach { match -> if (match.range.first > position) total++; total++; position = match.range.last + 1 }
        total + if (position < text.length || matches.isEmpty()) 1 else 0
    }
    val complete = ready.size == count && ready.values.all { it }
    LaunchedEffect(complete, report) { report?.invoke(complete) }
    var start = 0
    var block = 0
    matches.forEach { match ->
        if (match.range.first > start) {
            val slot = block++
            CompositionLocalProvider(LocalNoteRenderReady provides remember(text, slot) { { done: Boolean -> ready[slot] = done } }) {
                Markdown(text.substring(start, match.range.first), sourceOffset + start)
            }
        }
        val id = match.groupValues[2]; val label = match.groupValues[1].ifBlank { "课件原页" }
        val slot = block++
        key(id, match.range.first) {
            CompositionLocalProvider(LocalNoteRenderReady provides remember(text, slot) { { done: Boolean -> ready[slot] = done } }) {
                PagePreview("note:$id", label, repository, images.markupStore) { withContext(Dispatchers.IO) { Attachment(images.data(id), label, "") } }
            }
        }
        start = match.range.last + 1
    }
    if (start < text.length || matches.isEmpty()) {
        val slot = block
        CompositionLocalProvider(LocalNoteRenderReady provides remember(text, slot) { { done: Boolean -> ready[slot] = done } }) {
            Markdown(text.substring(start), sourceOffset + start)
        }
    }
}
