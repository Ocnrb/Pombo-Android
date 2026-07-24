package com.pombo.android.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color

/**
 * Message text with tappable links, mirroring the web's `linkify`
 * (src/js/ui/utils.js:78).
 *
 * The web renders messages as HTML and turns bare URLs into anchors; Compose
 * renders literal strings, so without this every shared link was dead text
 * that could not be opened at all.
 *
 * Deliberately matched to the web:
 *  - the same URL pattern, which stops at whitespace and bracket characters;
 *  - **http/https only** — the web parses the URL and bails on any other
 *    protocol. That check is a security boundary, not a formatting nicety:
 *    message text is attacker-controlled, and handing an arbitrary scheme to
 *    the system UriHandler would let a sender fire off intent:, file: or
 *    javascript:-style targets on tap;
 *  - display truncated at 50 chars (47 + "…"), while the link still opens the
 *    full URL, so a long tracking URL cannot visually impersonate a short one.
 */
private val URL_PATTERN = Regex("""https?://[^\s<>"'`()\[\]{}]+""", RegexOption.IGNORE_CASE)

/** Accent colour the web gives links (`text-[#F6851B]`). */
private val LinkColor = Color(0xFFF6851B)

fun linkifiedText(text: String): AnnotatedString {
    val matches = URL_PATTERN.findAll(text).toList()
    if (matches.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0
        for (m in matches) {
            if (m.range.first > cursor) append(text.substring(cursor, m.range.first))
            val url = m.value
            // Anything that is not http/https never becomes a link. The regex
            // already constrains the scheme, but re-checking here keeps the
            // guarantee local to the place that builds the annotation.
            val safe = url.startsWith("http://", true) || url.startsWith("https://", true)
            val display = if (url.length > 50) url.take(47) + "…" else url
            if (safe) {
                withLink(
                    LinkAnnotation.Url(
                        url,
                        styles = TextLinkStyles(
                            style = SpanStyle(color = LinkColor),
                            pressedStyle = SpanStyle(
                                color = LinkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                    )
                ) { append(display) }
            } else {
                append(display)
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
