/* Inline markdown parser for theme descriptions and changelogs adapted from
 * shirkneko/SukiSU 97d2442d (GPL-3.0-or-later); Ziyu adaptation GPL-3.0-only.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private val SITE_MENTIONS = mapOf(
    "github" to "https://github.com",
    "gitee" to "https://gitee.com",
    "bilibili" to "https://www.bilibili.com",
    "coolapk" to "https://www.coolapk.com",
    "telegram" to "https://t.me",
    "weibo" to "https://weibo.com",
    "xiaohongshu" to "https://www.xiaohongshu.com",
    "xda" to "https://xdaforums.com",
    "youtube" to "https://www.youtube.com",
    "afdian" to "https://afdian.com",
    "twitter" to "https://x.com",
    "x" to "https://x.com",
    "zhihu" to "https://www.zhihu.com",
)

private val PLATFORM_GROUP = SITE_MENTIONS.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
private const val USER_PAT = """[\p{L}\p{M}\p{N}._·~+-]+"""
private const val END_PAT = """(?![A-Za-z0-9._-])"""

private val INLINE_MD = Regex(
    """\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^]\n]+]\(https?://[^\s)]+\)|$USER_PAT@(?:$PLATFORM_GROUP)$END_PAT|(?<![A-Za-z0-9_@.-])@(?:$PLATFORM_GROUP)$END_PAT|https?://\S+|\*[^*\n]+\*""",
    RegexOption.IGNORE_CASE,
)
private val FENCED_MD = Regex("```[\\s\\S]*?```")

private fun encodeSeg(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun mentionUrl(token: String): String? {
    if (token.startsWith("@")) return SITE_MENTIONS[token.substring(1).lowercase(Locale.ROOT)]
    val split = token.lastIndexOf('@')
    if (split <= 0) return null
    val user = token.substring(0, split)
    val platform = token.substring(split + 1).lowercase(Locale.ROOT)
    return when (platform) {
        "github" -> "https://github.com/${encodeSeg(user)}"
        "gitee" -> "https://gitee.com/${encodeSeg(user)}"
        "bilibili" -> if (user.all(Char::isDigit)) "https://space.bilibili.com/$user" else "https://search.bilibili.com/all?keyword=${encodeSeg(user)}"
        "coolapk" -> "https://www.coolapk.com/u/${encodeSeg(user)}"
        "telegram" -> "https://t.me/${encodeSeg(user)}"
        "weibo" -> "https://weibo.com/${encodeSeg(user)}"
        "xiaohongshu" -> "https://www.xiaohongshu.com/user/profile/${encodeSeg(user)}"
        "xda" -> "https://xdaforums.com/m/${encodeSeg(user)}/"
        "youtube" -> "https://www.youtube.com/@${encodeSeg(user)}"
        "afdian" -> "https://afdian.com/a/${encodeSeg(user)}"
        "twitter", "x" -> "https://x.com/${encodeSeg(user)}"
        "zhihu" -> "https://www.zhihu.com/people/${encodeSeg(user)}"
        else -> null
    }
}

@Composable
internal fun rememberThemeStoreMarkdown(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.onSurface
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val linkStyles = remember(linkColor) {
        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    }
    return remember(text, linkColor, codeColor, codeBg) {
        buildAnnotatedString {
            fun appendInline(chunk: String) {
                var pos = 0
                INLINE_MD.findAll(chunk).forEach { m ->
                    if (m.range.first > pos) append(chunk.substring(pos, m.range.first))
                    val t = m.value
                    when {
                        t.startsWith("**") && t.length > 4 -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(t.substring(2, t.length - 2)) }
                        t.startsWith("`") && t.length > 2 -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, background = codeBg)) { append(t.substring(1, t.length - 1)) }
                        t.startsWith("[") -> {
                            val sep = t.indexOf("](")
                            if (sep > 1) withLink(LinkAnnotation.Url(t.substring(sep + 2, t.length - 1), linkStyles)) { append(t.substring(1, sep)) }
                            else append(t)
                        }
                        t.startsWith("http://") || t.startsWith("https://") -> withLink(LinkAnnotation.Url(t, linkStyles)) { append(t) }
                        t.contains('@') -> {
                            val url = mentionUrl(t)
                            if (url != null) withLink(LinkAnnotation.Url(url, linkStyles)) { append(t) }
                            else withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) { append(t) }
                        }
                        t.startsWith("*") && t.length > 2 -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
                        else -> append(t)
                    }
                    pos = m.range.last + 1
                }
                if (pos < chunk.length) append(chunk.substring(pos))
            }
            var cursor = 0
            FENCED_MD.findAll(text).forEach { match ->
                if (match.range.first > cursor) appendInline(text.substring(cursor, match.range.first))
                val body = match.value.removePrefix("```").removeSuffix("```").trim('\n')
                if (length > 0) append('\n')
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, background = codeBg)) { append(body) }
                append('\n')
                cursor = match.range.last + 1
            }
            if (cursor < text.length) appendInline(text.substring(cursor))
        }
    }
}
