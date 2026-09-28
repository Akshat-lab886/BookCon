package com.bookcon.app.ui.profile

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookcon.app.ui.components.BottomNavBar
import com.bookcon.app.ui.components.NavTab
import com.bookcon.app.ui.theme.BrandColors

/**
 * Profile screen matching the reference screenshot: a serif "Profile" header,
 * one rounded card holding six icon rows (each with a title and a subtitle,
 * separated by hairline dividers), a separate "Sign out" box in coral, and the
 * floating bottom nav with Profile selected.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onNavigateToSettings: (String) -> Unit = {},
    onOpenHome: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    val rows = listOf(
        ProfileRow(Icons.Default.Settings, "Settings", "Appearance, sync, downloads, sign out", "settings"),
        ProfileRow(Icons.Default.MenuBook, "Notebooks", "Per-book notes — type or draw", "notebooks"),
        ProfileRow(Icons.Default.Translate, "Vocabulary", "Saved words and phrases", "vocab"),
        ProfileRow(Icons.Default.InsertChart, "Reading stats", "Time, pages, streaks", "stats"),
        ProfileRow(Icons.Default.AutoStories, "AI summary", "BYOK key for page summaries", "ai"),
        ProfileRow(Icons.Default.CloudUpload, "Wi-Fi import", "Add books from your computer", "wifi"),
        // These two screens were registered in the nav graph and fully built, but
        // nothing ever linked to them, so a storage manager and a device list existed
        // that the user could not open.
        ProfileRow(Icons.Default.SdStorage, "Storage", "Server space and largest files", "storage"),
        ProfileRow(Icons.Default.Devices, "Devices", "Signed-in devices on your server", "devices"),
        ProfileRow(Icons.Default.Bookmarks, "Annotations", "Every highlight in every book", "annotations"),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.Page)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Profile",
                style = MaterialTheme.typography.displaySmall,
                color = BrandColors.TextPrimary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 18.dp),
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(22.dp),
                color = BrandColors.Card,
            ) {
                Column {
                    rows.forEachIndexed { index, row ->
                        ProfileRowItem(
                            row = row,
                            onClick = { onNavigateToSettings(row.route) },
                        )
                        if (index != rows.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 84.dp, end = 20.dp)
                                    .height(1.dp)
                                    .background(BrandColors.Divider)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Sign out lives in its own box, outside the settings card.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clickable(onClick = onSignOut),
                shape = RoundedCornerShape(18.dp),
                color = BrandColors.Card,
            ) {
                Text(
                    "Sign out",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.SignOut,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }

            // Clear the floating nav.
            Spacer(Modifier.height(104.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp)
        ) {
            BottomNavBar(
                current = NavTab.Profile,
                onSelect = { tab ->
                    when (tab) {
                        NavTab.Home -> onOpenHome()
                        NavTab.Books -> onOpenLibrary()
                        NavTab.Bookmarks -> onOpenBookmarks()
                        NavTab.Profile -> Unit
                    }
                },
            )
        }
    }
}

private data class ProfileRow(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val route: String,
)

@Composable
private fun ProfileRowItem(row: ProfileRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(BrandColors.Green, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                row.icon,
                contentDescription = null,
                tint = BrandColors.GreenText,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                row.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = BrandColors.TextPrimary,
            )
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = BrandColors.TextSecondary,
            )
        }
    }
}
