package com.bookcon.app.ui.lifestyle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
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
 * Oripio-inspired home screen — exact match to the reference screenshot.
 *
 * Layout (top → bottom):
 *  1. Soft teal gradient hero with decorative dots and a chevron, avatar "B"
 *     on the top right, search field, "Your Book Library / Make Your Own
 *     Space" headline (serif), "N books ready to read" subtitle, and a
 *     black "Import books" pill button
 *  2. Rounded teal CTA card: "Find the next book you'll love" + "Add a book
 *     now" pill + pink circular book icon
 *  3. "Categories" (serif) + 3 pill chips (Health / Science / Motivation)
 *  4. "Recently added" (serif) + "View all" link + horizontal book carousel
 *  5. "Continue reading" header
 *  6. Floating bottom nav card: Home / Books / Bookmarks / Profile
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
        bottomBar = {
            LifestyleBottomNavCard(
                current = LifestyleTab.HOME,
                onHome = { },
                onBooks = onOpenLibrary,
                onBookmarks = onOpenBookmarks,
                onProfile = onOpenProfile,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // 1. Soft teal gradient hero
            item { TealHero(bookCount = bookCount, onImport = onImport) }

            // 2. Rounded teal "find the next book" CTA
            item { FindNextBookCta(onAddBook = onAddBook) }

            // 3. Categories
            item { CategoriesHeader() }
            item { CategoriesRow() }

            // 4. Recently added
            item { RecentlyAddedHeader() }
            item {
                RecentlyAddedRow(
                    books = booksDisplay,
                    serverUrl = state.serverUrl,
                    onClick = onOpenBook,
                )
            }

            // 5. Continue reading
            item { ContinueReadingHeader() }
        }
    }
}

// ---- 1. Teal hero ----
@Composable
private fun TealHero(bookCount: Int, onImport: () -> Unit) {
    val teal = MaterialTheme.colorScheme.primary
    val tealLight = Color(0xFF4FCDBE)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(teal, tealLight),
                )
            ),
    ) {
        // Decorative dots and chevron
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dot = Color.White.copy(alpha = 0.5f)
            drawCircle(dot, radius = 4f, center = Offset(w * 0.18f, h * 0.06f))
            drawCircle(dot, radius = 5f, center = Offset(w * 0.10f, h * 0.30f))
            drawCircle(dot, radius = 6f, center = Offset(w * 0.78f, h * 0.32f))
            val chevronColor = Color.White.copy(alpha = 0.45f)
            val cx = w * 0.78f
            val cy = h * 0.36f
            val path = Path().apply {
                moveTo(cx - 30, cy)
                lineTo(cx, cy - 25)
                lineTo(cx + 30, cy)
                lineTo(cx, cy + 25)
                close()
            }
            drawPath(path, chevronColor)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 36.dp),
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
                        .background(Color(0xFFF3B4C9)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "B",
                        color = Color(0xFF6E1F44),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Text(
                "Your Book Library",
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 38.sp,
            )
            Text(
                "Make Your Own Space",
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 38.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$bookCount books ready to read",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onImport,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Filled.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Import books",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

// ---- 2. Rounded teal CTA ----
@Composable
private fun FindNextBookCta(onAddBook: () -> Unit) {
    val teal = MaterialTheme.colorScheme.primary
    val pink = MaterialTheme.colorScheme.secondary
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = teal,
        contentColor = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                )
                Text(
                    "book you'll love",
                    color = Color.White,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAddBook,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = teal,
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(
                        "Add a book now",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(pink),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.LibraryBooks,
                    contentDescription = null,
                    tint = Color(0xFF6E1F44),
                    modifier = Modifier.size(38.dp),
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
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 20.sp,
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
        CategoryChip("Health", Icons.Filled.Favorite, Color(0xFFFFC857))
        CategoryChip("Science", Icons.Filled.Science, Color(0xFF1FB8A8))
        CategoryChip("Motivation", Icons.Filled.LocalFireDepartment, Color(0xFFDC2626))
    }
}

@Composable
private fun CategoryChip(
    label: String,
    icon: ImageVector,
    iconTint: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable { },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
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
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
        )
        Text(
            "View all",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
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
            .height(280.dp),
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
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
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
            fontSize = 12.sp,
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
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 20.sp,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 12.dp),
    )
}

// ---- bottom nav: floating card ----
enum class LifestyleTab { HOME, BOOKS, BOOKMARKS, PROFILE }

@Composable
private fun LifestyleBottomNavCard(
    current: LifestyleTab,
    onHome: () -> Unit,
    onBooks: () -> Unit,
    onBookmarks: () -> Unit,
    onProfile: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(40.dp),
            color = Color.White,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavItem(
                    selected = current == LifestyleTab.HOME,
                    icon = Icons.Filled.Home,
                    label = "Home",
                    onClick = onHome,
                    modifier = Modifier.weight(1f),
                )
                NavItem(
                    selected = current == LifestyleTab.BOOKS,
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                    label = "Books",
                    onClick = onBooks,
                    modifier = Modifier.weight(1f),
                )
                NavItem(
                    selected = current == LifestyleTab.BOOKMARKS,
                    icon = Icons.Filled.Bookmark,
                    label = "Bookmarks",
                    onClick = onBookmarks,
                    modifier = Modifier.weight(1f),
                )
                NavItem(
                    selected = current == LifestyleTab.PROFILE,
                    icon = Icons.Filled.Person,
                    label = "Profile",
                    onClick = onProfile,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) Color(0xFF1FB8A8) else Color(0xFF5A6478)
    val bg = if (selected) Color(0xFFB6EDE4) else Color.Transparent
    Box(
        modifier = modifier
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
