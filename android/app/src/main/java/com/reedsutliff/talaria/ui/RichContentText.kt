package com.reedsutliff.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Content segment types ────────────────────────────────────

private sealed class Segment {
    data class Plain(val text: String) : Segment()
    data class CodeBlock(val language: String, val code: String) : Segment()
    data class InlineCode(val code: String) : Segment()
    data class FilePath(val path: String) : Segment()
    data class Bold(val text: String) : Segment()
}

// ── File path regex patterns ─────────────────────────────────

private val FILE_PATH_PATTERNS = listOf(
    // Absolute paths
    Regex("""(/[a-zA-Z0-9_./-]+\.[a-zA-Z0-9]{1,10})\b"""),
    // Relative paths with directory separators
    Regex("""((?:src|lib|app|server|android|config|infra|docs)/[a-zA-Z0-9_./-]+\.[a-zA-Z0-9]{1,10})\b"""),
    // Line number references like :42 or :100-120
    Regex("""(/[a-zA-Z0-9_./-]+\.[a-zA-Z0-9]{1,10}:\d+(?:-\d+)?)\b"""),
)

// ── Parser ───────────────────────────────────────────────────

private fun parseContent(text: String): List<Segment> {
    val segments = mutableListOf<Segment>()
    var i = 0

    while (i < text.length) {
        // Code block: ```lang\n...\n```
        if (text.startsWith("```", i)) {
            val endFence = text.indexOf("\n```", i + 3)
            if (endFence != -1) {
                val inner = text.substring(i + 3, endFence).trimStart()
                val langEnd = inner.indexOf('\n')
                val lang = if (langEnd != -1) inner.substring(0, langEnd).trim() else ""
                val code = if (langEnd != -1) inner.substring(langEnd + 1) else inner
                segments.add(Segment.CodeBlock(lang, code))
                i = endFence + 4
                continue
            }
        }

        // Inline code: `...`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end != -1 && end - i > 1) {
                segments.add(Segment.InlineCode(text.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }

        // Bold: **...**
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end != -1) {
                segments.add(Segment.Bold(text.substring(i + 2, end)))
                i = end + 2
                continue
            }
        }

        // File path
        var matched = false
        for (pattern in FILE_PATH_PATTERNS) {
            val match = pattern.find(text, i)
            if (match != null && match.range.first == i) {
                segments.add(Segment.FilePath(match.value))
                i = match.range.last + 1
                matched = true
                break
            }
        }
        if (matched) continue

        // Plain text — consume until next special character
        var end = i + 1
        while (end < text.length && text[end] != '`' && !text.startsWith("**", end)
            && !text.startsWith("```", end)
        ) {
            // Check if a file path starts here
            var pathMatched = false
            for (pattern in FILE_PATH_PATTERNS) {
                if (pattern.find(text, end) != null && pattern.find(text, end)!!.range.first == end) {
                    pathMatched = true
                    break
                }
            }
            if (pathMatched) break
            end++
        }
        segments.add(Segment.Plain(text.substring(i, end)))
        i = end
    }

    return segments
}

// ── Composable ───────────────────────────────────────────────

/**
 * Renders text with support for:
 * - Code blocks (```lang ... ```)
 * - Inline code (`...`)
 * - Bold text (**...**)
 * - Clickable file paths
 */
@Composable
fun RichContentText(
    text: String,
    modifier: Modifier = Modifier,
    onFilePathClick: ((String) -> Unit)? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: Int = 15,
) {
    val segments = remember(text) { parseContent(text) }

    Column(modifier = modifier) {
        segments.forEach { segment ->
            when (segment) {
                is Segment.Plain -> {
                    SelectionContainer {
                        Text(
                            text = segment.text,
                            color = textColor,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.4).sp,
                        )
                    }
                }

                is Segment.CodeBlock -> {
                    SelectionContainer {
                        Text(
                            text = segment.code.trimEnd(),
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
                                .padding(12.dp)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }

                is Segment.InlineCode -> {
                    Text(
                        text = segment.code,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = (fontSize - 1).sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF334155).copy(alpha = 0.5f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }

                is Segment.FilePath -> {
                    if (onFilePathClick != null) {
                        Text(
                            text = segment.path,
                            color = Color(0xFF60A5FA),
                            fontSize = (fontSize - 1).sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E3A5F).copy(alpha = 0.5f))
                                .clickable { onFilePathClick(segment.path) }
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    } else {
                        Text(
                            text = segment.path,
                            color = Color(0xFF60A5FA),
                            fontSize = (fontSize - 1).sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                is Segment.Bold -> {
                    SelectionContainer {
                        Text(
                            text = segment.text,
                            color = textColor,
                            fontSize = fontSize.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
