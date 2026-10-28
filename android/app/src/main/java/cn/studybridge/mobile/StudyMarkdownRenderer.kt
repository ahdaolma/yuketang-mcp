package cn.studybridge.mobile

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.node.Node

internal data class StudyMarkdownPalette(val color: Int, val link: Int, val code: Int, val line: Int, val row: Int, val background: Int, val density: Float, val dark: Boolean)

@Composable internal fun studyMarkdownPalette(): StudyMarkdownPalette {
    val colors = MaterialTheme.colorScheme
    return StudyMarkdownPalette(colors.onSurface.toArgb(), colors.primary.toArgb(), colors.surfaceContainerHigh.toArgb(),
        colors.outlineVariant.toArgb(), colors.surfaceContainer.toArgb(), colors.background.toArgb(),
        LocalDensity.current.density, colors.background.luminance() < .5f)
}

internal fun createStudyMarkdownRenderer(context: Context, p: StudyMarkdownPalette): Markwon = Markwon.builder(context)
    .usePlugin(TablePlugin.create { builder -> builder.tableBorderColor(p.line).tableBorderWidth(maxOf(1, (p.density * .5f).toInt()))
        .tableCellPadding((8 * p.density).toInt()).tableHeaderRowBackgroundColor(p.code).tableOddRowBackgroundColor(p.row).tableEvenRowBackgroundColor(p.background) })
    .usePlugin(object : AbstractMarkwonPlugin() {
        private val externalLinks = io.noties.markwon.LinkResolverDef()
        override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) {
            builder.linkResolver { view, link ->
                if (link.startsWith("study-note:")) (view as? NoteSelectableTextView)?.knowledgeLink?.invoke(link)
                else externalLinks.resolve(view, link)
            }
        }
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder.linkColor(p.link).codeTextColor(p.color).codeBlockTextColor(p.color).codeBackgroundColor(p.code).codeBlockBackgroundColor(p.code)
                .blockQuoteColor(p.link).blockQuoteWidth((2 * p.density).toInt()).headingBreakHeight(0).thematicBreakColor(p.line).listItemColor(p.link)
                .headingTextSizeMultipliers(floatArrayOf(1.28f, 1.16f, 1.06f, 1f, 1f, 1f))
        }
    }).build()

internal fun configureReaderMarkdown(parser: ReaderMarkdownParser, context: Context, p: StudyMarkdownPalette) {
    parser.configureRendering(p) {
        val worker = createStudyMarkdownRenderer(context, p)
        val render: (String, Node) -> Spanned = { source, node ->
            val body = worker.render(node).let { if (it.isEmpty() && source.isNotEmpty()) SpannableStringBuilder(source) else it }
            renderStudyMarks(body, p.density, p.dark)
        }
        render
    }
}

internal fun renderStudyMarkdown(renderer: Markwon, syntax: ReaderMarkdownParser?, raw: String): Spanned {
    val source = markdownForStudyMarks(raw)
    return if (syntax == null) renderer.toMarkdown(source) else renderer.render(syntax.parse(source)).let {
        if (it.isEmpty() && source.isNotEmpty()) SpannableStringBuilder(source) else it
    }
}
