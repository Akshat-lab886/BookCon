package com.bookcon.app.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.ui.components.BottomNavBar
import com.bookcon.app.ui.components.NavTab
import com.bookcon.app.ui.theme.BrandColors

/**
 * Bookmarks screen matching the reference screenshot: serif header, bold sans
 * subtitle, and a centred pure-black bookmark glyph above the empty state's
 * serif title + sans body.
 */
@Composable
fun BookmarksScreen(
    onBack: () -> Unit,
    /**
     * Opens a book, optionally seeking straight to [locatorJson]. Tapping a bookmark
     * used to ignore the stored locator and resume at the last-read position, so
     * every bookmark in the tab led to the same page.
     */
    onOpenBook: (String, String?) -> Unit,
    onOpenHome: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.Page)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 18.dp, bottom = 14.dp)
            ) {
                Text(
                    "Bookmarks",
                    style = MaterialTheme.typography.displaySmall,
                    color = BrandColors.TextPrimary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Every page you've saved, across your whole library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextSecondary,
                )
            }

            if (state.bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 30.dp),
                    ) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = BrandColors.EmptyGlyph,
                            modifier = Modifier.size(104.dp),
                        )
                        Spacer(Modifier.height(26.dp))
                        Text(
                            "No bookmarks yet",
                            style = MaterialTheme.typography.displaySmall,
                            color = BrandColors.TextPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Tap the bookmark icon while reading\nto save pages you want to find again.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BrandColors.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.bookmarks, key = { it.id }) { bm ->
                        BookmarkCard(
                            row = bm,
                            onOpen = { onOpenBook(bm.bookId, bm.locatorJson) },
                            onDelete = { viewModel.delete(bm.id) },
                        )
                    }
                    item { Spacer(Modifier.height(104.dp)) }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp)
        ) {
            BottomNavBar(
                current = NavTab.Bookmarks,
                onSelect = { tab ->
                    when (tab) {
                        NavTab.Home -> onOpenHome()
                        NavTab.Books -> onOpenLibrary()
                        NavTab.Bookmarks -> Unit
                        NavTab.Profile -> onOpenProfile()
                    }
                },
            )
        }
    }
}

@Composable
private fun BookmarkCard(
    row: BookmarkRow,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        color = BrandColors.Card,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Bookmark,
                contentDescription = null,
                tint = BrandColors.Green,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.label.take(60),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = BrandColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete bookmark",
                tint = BrandColors.TextSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onDelete),
            )
        }
    }
}
