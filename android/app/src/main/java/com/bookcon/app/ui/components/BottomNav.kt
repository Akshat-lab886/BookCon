package com.bookcon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookcon.app.ui.theme.BrandColors

/** The four tabs, in reference order. */
enum class NavTab(val label: String) {
    Home("Home"),
    Books("Books"),
    Bookmarks("Bookmarks"),
    Profile("Profile"),
}

/**
 * The floating bottom nav from the reference screenshots: a rounded
 * `#12151B` card, with the selected tab rendered as a solid `#114838`
 * forest-green pill carrying white icon + label.
 */
@Composable
fun BottomNavBar(
    current: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = BrandColors.Card,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Icons.Default.Home, NavTab.Home, current == NavTab.Home) { onSelect(NavTab.Home) }
            NavItem(Icons.Default.AutoStories, NavTab.Books, current == NavTab.Books) { onSelect(NavTab.Books) }
            NavItem(Icons.Default.Bookmark, NavTab.Bookmarks, current == NavTab.Bookmarks) { onSelect(NavTab.Bookmarks) }
            NavItem(Icons.Default.Person, NavTab.Profile, current == NavTab.Profile) { onSelect(NavTab.Profile) }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, tab: NavTab, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) BrandColors.GreenText else BrandColors.TextSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) BrandColors.Green else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
            Text(
                tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

/** Standard bottom-nav container: pinned to the bottom, clear of the gesture bar. */
@Composable
fun BottomNavHost(
    current: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 14.dp),
    ) {
        BottomNavBar(current = current, onSelect = onSelect)
    }
}

@Composable
fun NavSpacer() {
    Spacer(Modifier.height(96.dp))
}
