package com.bookcon.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcon.app.core.rememberBookPainter
import com.bookcon.app.core.resolveCoverUrl

/**
 * 2:3 book cover with smart fallback.
 *
 * If the cover URL loads, shows the image. Otherwise renders a tinted
 * placeholder with the first two letters of the title (or the format
 * abbreviation). 12 dp corner radius by default.
 */
@Composable
fun BookCover(
    coverUrl: String?,
    title: String,
    serverUrl: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    placeholderTint: Color = MaterialTheme.colorScheme.primaryContainer,
    placeholderText: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(placeholderTint),
        contentAlignment = Alignment.Center,
    ) {
        val url = resolveCoverUrl(serverUrl, coverUrl)
        val painter = rememberBookPainter(url)
        if (coverUrl.isNullOrBlank()) {
            // Initial-letter fallback
            val initials = title
                .split(Regex("\\s+"))
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
                .joinToString("")
                .ifEmpty { "📖" }
            Text(
                initials,
                color = placeholderText,
                style = MaterialTheme.typography.displaySmall,
                fontSize = 32.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            Image(
                painter = painter,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
