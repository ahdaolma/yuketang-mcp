package cn.studybridge.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import cn.studybridge.mobile.FeedbackTextButton as TextButton
import cn.studybridge.mobile.FeedbackOutlinedButton as OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
data class PageVisual(val bitmap: Bitmap? = null, val text: String = "")
class MaterialPreviews(context: Context) {
    private val root = File(context.cacheDir, "page-previews").apply { mkdirs() }
    private companion object { val gate = Semaphore(2) }
    suspend fun load(key: String, source: suspend () -> Attachment): PageVisual = gate.withPermit {
        withContext(Dispatchers.IO) {
            val file = File(root, imageDigest(key.toByteArray()) + ".jpg")
            if (file.isFile) {
                val image = BitmapFactory.decodeFile(file.path)
                if (image != null) { file.setLastModified(System.currentTimeMillis()); return@withContext PageVisual(image) }
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
            val original = boundedBitmap(Base64.decode(a.image.substringAfter(','), Base64.DEFAULT))
            run {
                val scale = minOf(1f, 640f / maxOf(original.width, original.height))
                val small = Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
                // A failed cache write must not hide an otherwise readable page.
                val temp = File.createTempFile("preview-", ".tmp", root)
                try { runCatching { temp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }; check(temp.renameTo(file)); trim() } }
                finally { temp.delete() }
                if (small !== original) original.recycle()
                PageVisual(small)
            }
        }
    }
    @Synchronized private fun trim() {
        val files = root.listFiles()?.filter { it.extension in listOf("jpg", "txt") }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) { if (total <= 40L * 1024 * 1024) break; val size = file.length(); if (file.delete()) total -= size }
    }
}

@Composable fun PagePreview(key: String, label: String, repository: MaterialPreviews, source: suspend () -> Attachment) {
    val loader by rememberUpdatedState(source)
    var retry by remember(key) { mutableIntStateOf(0) }
    var enlarged by remember(key) { mutableStateOf(false) }
    val result by produceState<VisualLoad?>(null, key, retry) {
        value = null
        value = try { VisualLoad(key, retry, repository.load(key) { loader() }) } catch (e: CancellationException) { throw e } catch (_: Exception) { VisualLoad(key, retry, null, "预览暂不可用") }
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val current = result?.takeIf { it.key == key && it.attempt == retry }
            if (current == null) Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)); }
            else if (current.visual == null) Row(verticalAlignment = Alignment.CenterVertically) { Text(current.error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { retry++ }) { Text("重试预览") } }
            else {
                current.visual?.bitmap?.let { Image(it.asImageBitmap(), "$label 缩略图", Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp).clickable { enlarged = true }, contentScale = ContentScale.Fit) }
                    ?: Text(current.visual?.text.orEmpty().ifBlank { "此页没有可预览内容" }, maxLines = 5, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { enlarged = true }) { Text("放大预览") }
            }
        }
    }
    if (enlarged) ImageViewer(label, onClose = { enlarged = false }) { loader() }
}

@Composable fun ImageViewer(label: String, onClose: () -> Unit, source: suspend () -> Attachment) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImageViewerContent(label, onClose, source)
    }
}

// Keep the asynchronous load and its observed state in the dialog's composition.
// The host may stop drawing while this full-screen window owns the foreground.
@Composable private fun ImageViewerContent(label: String, onClose: () -> Unit, source: suspend () -> Attachment) {
    val loader by rememberUpdatedState(source)
    var retry by remember(label) { mutableIntStateOf(0) }
    val attempt = retry
    val visual by produceState<VisualLoad?>(null, label, attempt) {
        value = null
        val loadSource = loader
        value = try { withContext(Dispatchers.IO) {
            val a = loadSource(); VisualLoad(label, attempt, PageVisual(a.image?.let { boundedBitmap(Base64.decode(it.substringAfter(','), Base64.DEFAULT)) }, a.text))
        } } catch (e: CancellationException) { throw e } catch (_: Exception) { VisualLoad(label, attempt, null, "原页读取失败，请检查网络或重新导入资料。") }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis); TextButton(onClick = onClose) { Text("关闭预览") } }
            var scale by remember(label) { mutableFloatStateOf(1f) }
            var offset by remember(label) { mutableStateOf(Offset.Zero) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("双指缩放、拖动查看", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("重置缩放") } }
            val current = visual?.takeIf { it.key == label && it.attempt == attempt }
            if (current == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (current.visual == null) { Text(current.error); OutlinedButton(onClick = { retry++ }) { Text("重新读取") } }
            else current.visual?.bitmap?.let { bitmap ->
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLow).pointerInput(bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val boundX = size.width * (scale - 1) / 2; val boundY = size.height * (scale - 1) / 2
                        offset = Offset((offset.x + pan.x).coerceIn(-boundX, boundX), (offset.y + pan.y).coerceIn(-boundY, boundY))
                    }
                }, contentAlignment = Alignment.Center) {
                    Image(bitmap.asImageBitmap(), label, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y), contentScale = ContentScale.Fit)
                }
            } ?: Text(current.visual?.text.orEmpty(), Modifier.weight(1f).verticalScroll(rememberScrollState()))
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
    var start = 0
    matches.forEach { match ->
        if (match.range.first > start) Markdown(text.substring(start, match.range.first), sourceOffset + start)
        val id = match.groupValues[2]; val label = match.groupValues[1].ifBlank { "课件原页" }
        key(id, match.range.first) { PagePreview("note:$id", label, repository) { withContext(Dispatchers.IO) { Attachment(images.data(id), label, "") } } }
        start = match.range.last + 1
    }
    if (start < text.length || matches.isEmpty()) Markdown(text.substring(start), sourceOffset + start)
}
