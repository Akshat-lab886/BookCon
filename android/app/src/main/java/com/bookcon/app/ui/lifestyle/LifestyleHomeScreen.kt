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
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.ui.components.BookCover
import com.bookcon.app.ui.components.SearchField
import com.bookcon.app.ui.library.LibraryViewModel

/**
 * Oripio-inspired home screen (PRD v2.2 + user reference screenshot).
 *
 * Layout (top → bottom):
 *  1. Full-bleed teal hero: avatar circle (top right), search field,
 *     "Your Book Library / Make Your Own Space" headline, "N books ready to read" subtitle,
 *     black "Import books" pill button
 *  2. Rounded teal CTA card: "Find the next book you'll love" + "Add a book now" pill
 *     + pink circular book icon on the right
 *  3. "Categories" header + 3 pill chips (Health / Science / Motivation)
 *  4. "Recently added" header + "View all" link + horizontal book carousel
 *     (wider cards: cover image + title + filename source row)
 *  5. "Continue reading" header (visible at top of scroll)
 *  6. Bottom nav: Home / Books / Bookmarks / Profile
 */
@Composable
fun LifestyleHomeScreen(
    onOpenBook: (String) -> Unit,
    onOpenReader: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onImport: () -> Unit,
    onAddBook: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val bookCount = state.books.size
    val booksDisplay = state.books.takeLast(8).reversed()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { LifestyleBottomNav(
            current = LifestyleTab.HOME,
            onHome = { /* already here */ },
            onBooks = onOpenLibrary,
            onBookmarks = onOpenBookmarks,
            onProfile = onOpenProfile,
        ) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // 1. Teal hero
            item { TealHero(
                bookCount = bookCount,
                onImport = onImport,
            ) }

            // 2. Rounded teal "find the next book" CTA
            item { FindNextBookCta(onAddBook = onAddBook) }

            // 3. Categories
            item { CategoriesHeader() }
            item { CategoriesRow() }

            // 4. Recently added
            item { RecentlyAddedHeader() }
            item { RecentlyAddedRow(
                books = booksDisplay,
                serverUrl = state.serverUrl,
                onClick = onOpenBook,
            ) }

            // 5. Continue reading (visible at bottom of fold)
            item { ContinueReadingHeader() }
        }
    }
}

// ---- 1. Teal hero ----
@Composable
private fun TealHero(bookCount: Int, onImport: () -> Unit) {
    val teal = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(teal)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp),
    ) {
        // Top: search field with avatar circle on right
        Row(verticalAlignment = Alignment.CenterVertically) {
            var search by rememberSaveable { mutableStateOf("") }
            SearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "Search your books",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "B",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Your Book Library",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Make Your Own Space",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$bookCount books ready to read",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onImport,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = Color.White,
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import books", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- 2. Rounded teal "Find the next book" CTA ----
@Composable
private fun FindNextBookCta(onAddBook: () -> Unit) {
    val teal = MaterialTheme.colorScheme.primary
    val pink = MaterialTheme.colorScheme.secondary
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = teal,
        contentColor = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Find the next",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "book you'll love",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onAddBook,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = teal,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text("Add a book now", fontWeight = FontWeight.SemiBold)
                }
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(pink),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.LibraryBooks,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

// ---- 3. Categories ----
@Composable
private fun CategoriesHeader() {
    Text(
        "Categories",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp),
    )
}

@Composable
private fun CategoriesRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryChip("Health", Icons.Filled.Favorite, MaterialTheme.colorScheme.tertiary, onClick = {})
        CategoryChip("Science", Icons.Filled.Science, MaterialTheme.colorScheme.primary, onClick = {})
        CategoryChip("Motivation", Icons.Filled.LocalFireDepartment, MaterialTheme.colorScheme.error, onClick = {})
    }
}

@Composable
private fun CategoryChip(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.clickable { onClick() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

// ---- 4. Recently added ----
@Composable
private fun RecentlyAddedHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Recently added",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "View all",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.clickable { },
        )
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
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
    ) {
        items(books, key = { it.id }) { book ->
            RecentlyAddedCard(
                book = book,
                serverUrl = serverUrl,
                onClick = { onClick(book.id) },
            )
        }
    }
}

@Composable
private fun RecentlyAddedCard(
    book: BookEntity,
    serverUrl: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clickable { onClick() },
    ) {
        BookCover(
            coverUrl = book.coverUrl,
            title = book.title,
            serverUrl = serverUrl,
            cornerRadius = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            book.title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = listOfNotNull(
            book.publisher.takeUnless { it.isNullOrBlank() },
            book.authors.firstOrNull(),
        ).firstOrNull() ?: book.format
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 5. Continue reading ----
@Composable
private fun ContinueReadingHeader() {
    Text(
        "Continue reading",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 12.dp),
    )
}

// ---- bottom nav ----
enum class LifestyleTab { HOME, BOOKS, BOOKMARKS, PROFILE }

@Composable
private fun LifestyleBottomNav(
    current: LifestyleTab,
    onHome: () -> Unit,
    onBooks: () -> Unit,
    onBookmarks: () -> Unit,
    onProfile: () -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        NavigationBarItem(
            selected = current == LifestyleTab.HOME,
            onClick = onHome,
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            label = { Text("Home") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = current == LifestyleTab.BOOKS,
            onClick = onBooks,
            icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = "Books") },
            label = { Text("Books") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = current == LifestyleTab.BOOKMARKS,
            onClick = onBookmarks,
            icon = { Icon(Icons.Filled.Bookmark, contentDescription = "Bookmarks") },
            label = { Text("Bookmarks") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        NavigationBarItem(
            selected = current == LifestyleTab.PROFILE,
            onClick = onProfile,
            icon = { Icon(Icons.Filled.Person, contentDescription = "Profile") },
            label = { Text("Profile") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}
