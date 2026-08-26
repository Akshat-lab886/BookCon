package com.bookcon.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF thumbnail scrubber (PRD THUMB): horizontal filmstrip of page previews.
 * Thumbnails render lazily around the visible window through [PdfBook.renderPage]
 * (which is internally synchronized against the pager's renderer). Tap = jump.
 */
@Composable
fun PdfThumbStrip(
    pdf: com.bookcon.app.reader.PdfBook,
    currentPage: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbW = with(LocalDensity.current) { 84.dp.toPx() }.toInt()
    val cache = remember { mutableStateMapOf<Int, Bitmap>() }

    // Render a window of thumbnails around the current page; re-renders as user scrolls
    // because keys stay stable and only missing entries are fetched.
    LaunchedEffect(currentPage) {
        val from = (currentPage - 4).coerceAtLeast(0)
        val to = (currentPage + 8).coerceAtMost(pdf.pageCount - 1)
        for (i in from..to) {
            if (!cache.containsKey(i)) {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { pdf.renderPage(i, thumbW) }.getOrNull()
                }
                if (bmp != null) cache[i] = bmp
            }
        }
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(pdf.pageCount, key = { it }) { index ->
            val bmp = cache[index]
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .height(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onPick(index) }
                    .padding(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        contentScale = ContentScale.FillHeight,
                        modifier = Modifier.height(94.dp),
                    )
                } else {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                    )
                }
            }
        }
    }
}
