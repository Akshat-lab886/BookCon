package com.bookcon.app.ui.lifestyle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.ui.components.BookCard
import com.bookcon.app.ui.components.SearchField
import com.bookcon.app.ui.components.SectionHeader
import com.bookcon.app.ui.library.LibraryViewModel
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Oripio-inspired home screen (PRD VOICE-2 design v2.2).
 *
 * Layout (top → bottom):
 *  1. Soft teal/cream hero with "Hello, [name]" + big greeting + search bar
 *  2. Featured card (latest book, full-bleed) — mirrors the Oripio banner
 *  3. Categories (Health / Science / Motivation) — chips with icons
 *  4. Recently added — horizontal book carousel
 *  5. Top authors — horizontal avatars
 *  6. Bottom nav: Home / Library / Search / Stats
 *
 * The screen piggy-backs on the existing [LibraryViewModel] so it can read
 * the same data source as the Library tab without re-querying.
 */
@Composable
fun LifestyleHomeScreen(
    onOpenBook: (String) -> Unit,
    onOpenReader: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val greeting = rememberGreeting()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { LifestyleBottomNav(
            current = LifestyleTab.HOME,
            onHome = { /* already here */ },
            onLibrary = onOpenLibrary,
            onSearch = onOpenSearch,
            onStats = onOpenStats,
        ) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // Hero header
            item { LifestyleHero(greeting = greeting) }

            // Featured carousel (Oripio's "Find the best book of you interest" hero)
            item { FeaturedBookCard(
                book = state.continueReading.firstOrNull() ?: state.books.firstOrNull(),
                serverUrl = state.serverUrl,
                onClick = { book -> book?.let { onOpenReader(it.id) } },
            ) }

            // Categories
            item {
                CategoriesRow(
                    onHealth = { onOpenSearch() },
                    onScience = { onOpenSearch() },
                    onMotivation = { onOpenSearch() },
                )
            }

            // Recently added
            item {
                SectionHeader(
                    title = "Recently added",
                    actionLabel = "View all",
                    onAction = onOpenLibrary,
                )
                RecentlyAddedRow(
                    books = state.books.takeLast(10).reversed(),
                    serverUrl = state.serverUrl,
                    onClick = onOpenBook,
                )
            }

            // Top authors
            item {
                SectionHeader(
                    title = "Top authors",
                    actionLabel = "View all",
                    onAction = onOpenLibrary,
                )
                TopAuthorsRow(authors = state.authors.take(10))
            }

            // Tiny footer with voice-assistant hint
            item {
                VoiceHintCard(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberGreeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 5 -> "Late night"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        hour < 21 -> "Good evening"
        else -> "Good night"
    }
}

@Composable
private fun LifestyleHero(greeting: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BookCon",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            greeting,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Your book library — make your own space.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        var search by rememberSaveable { mutableStateOf("") }
        SearchField(
            value = search,
            onValueChange = { search = it },
            placeholder = "Search your item",
        )
    }
}

@Composable
private fun FeaturedBookCard(
    book: BookEntity?,
    serverUrl: String,
    onClick: (BookEntity?) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clickable { onClick(book) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Find the best\nbook for you",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    lineHeight = 30.sp,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onClick(book) },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        if (book != null) "Open" else "Browse",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // The right-side circle previews the cover
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                if (book != null) {
                    com.bookcon.app.ui.components.BookCover(
                        coverUrl = book.coverUrl,
                        title = book.title,
                        serverUrl = serverUrl,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape),
                        cornerRadius = 60.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.LibraryBooks,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoriesRow(
    onHealth: () -> Unit,
    onScience: () -> Unit,
    onMotivation: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryChip("Health", Icons.Filled.Favorite, MaterialTheme.colorScheme.secondaryContainer, onClick = onHealth)
        CategoryChip("Science", Icons.Filled.Whatshot, MaterialTheme.colorScheme.tertiaryContainer, onClick = onScience)
        CategoryChip("Motivation", Icons.Filled.Home, MaterialTheme.colorScheme.primaryContainer, onClick = onMotivation)
    }
}

@Composable
private fun CategoryChip(
    label: String,
    icon: ImageVector,
    container: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        modifier = Modifier.clickable { onClick() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun RecentlyAddedRow(
    books: List<BookEntity>,
    serverUrl: String,
    onClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height(220.dp),
    ) {
        items(books, key = { it.id }) { book ->
            BookCard(
                title = book.title,
                coverUrl = book.coverUrl,
                serverUrl = serverUrl,
                format = book.format,
                subtitle = book.authors.firstOrNull(),
                modifier = Modifier
                    .width(130.dp)
                    .clickable { onClick(book.id) },
            )
        }
    }
}

@Composable
private fun TopAuthorsRow(authors: List<String>) {
    if (authors.isEmpty()) {
        Text(
            "No authors yet — import a book to get started.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        items(authors) { name ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    name.substringBefore(' ').take(12),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VoiceHintCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Talk to BookCon",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Set your AI key in Settings to use voice mode.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

enum class LifestyleTab { HOME, LIBRARY, SEARCH, STATS }

@Composable
private fun LifestyleBottomNav(
    current: LifestyleTab,
    onHome: () -> Unit,
    onLibrary: () -> Unit,
    onSearch: () -> Unit,
    onStats: () -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        NavigationBarItem(
            selected = current == LifestyleTab.HOME,
            onClick = onHome,
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = current == LifestyleTab.LIBRARY,
            onClick = onLibrary,
            icon = { Icon(Icons.Filled.LibraryBooks, contentDescription = "Library") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = current == LifestyleTab.SEARCH,
            onClick = onSearch,
            icon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = current == LifestyleTab.STATS,
            onClick = onStats,
            icon = { Icon(Icons.Filled.Person, contentDescription = "Stats") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}
