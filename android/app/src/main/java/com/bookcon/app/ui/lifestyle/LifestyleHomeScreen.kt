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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.bookcon.app.ui.components.BottomNavBar
import com.bookcon.app.ui.components.NavTab
import com.bookcon.app.ui.library.LibraryViewModel

/**
 * Light tokens for the Home tab only.
 *
 * The reference shows Home on a warm cream page with a teal hero, while Books /
 * Bookmarks / Profile use the dark scheme. Pinning these here keeps Home
 * faithful to the screenshot regardless of the app-wide dark palette.
 */
private object LifestyleColors {
    val Page = Color(0xFF0F1014)
    val Card = Color(0xFF12151B)
    val Divider = Color(0xFF1C2028)
    val TextPrimary = Color(0xFFF2F3F7)
    val TextSecondary = Color(0xFFBABFC9)
    /** Sampled from the reference: a bright mint, not the muted teal I guessed. */
    val Teal = Color(0xFF52D7B5)
    val MintInk = Color(0xFF0F1018)
    val AvatarPlum = Color(0xFF6A3953)
    val Pill = Color(0xFF0F1014)
    val White = Color(0xFFFFFFFF)
}

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
    // state.books arrives sorted updatedAt DESC, so takeLast(8) kept the OLDEST tail
    // and "Recently added" showed the eight least recently touched books.
    val booksDisplay = state.books.take(8)
    // The ViewModel already computes this correctly — ORDER BY lastOpenedAt DESC,
    // limited to 10 — so "Continue reading" was re-derived here from an unrelated
    // ordering and listed the first six books that happened to be open.
    val continueReading = state.continueReading.take(6)

    Scaffold(
        containerColor = LifestyleColors.Page,
        bottomBar = {
            BottomNavBar(
                current = NavTab.Home,
                onSelect = { tab ->
                    when (tab) {
                        NavTab.Home -> Unit
                        NavTab.Books -> onOpenLibrary()
                        NavTab.Bookmarks -> onOpenBookmarks()
                        NavTab.Profile -> onOpenProfile()
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
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

            // 4. Recently added — only when there is something to show. The
            //    reference home screen (empty library) leaves plain dark space
            //    below Categories, so neither this nor the carousel's fixed
            //    height should appear until the library has books.
            if (booksDisplay.isNotEmpty()) {
                item { RecentlyAddedHeader() }
                item {
                    RecentlyAddedRow(
                        books = booksDisplay,
                        serverUrl = state.serverUrl,
                        onClick = onOpenBook,
                    )
                }
            }

            // 5. Continue reading
            if (continueReading.isNotEmpty()) {
                item { ContinueReadingHeader() }
                item { ContinueReadingRow(books = continueReading, serverUrl = state.serverUrl, onClick = onOpenReader) }
            }
        }
    }
}

/** Small white dots + a squiggle, placed to match the reference hero. */
@Composable
private fun HeroDoodles() {
    Box(Modifier.fillMaxSize()) {
        listOf(
            Triple(74.dp, 96.dp, 5.dp),
            Triple(192.dp, 250.dp, 4.dp),
            Triple(310.dp, 190.dp, 5.dp),
            Triple(36.dp, 190.dp, 4.dp),
        ).forEach { (dx, dy, d) ->
            Box(
                modifier = Modifier
                    .offset(x = dx, y = dy)
                    .size(d)
                    .background(Color.White.copy(alpha = 0.55f), CircleShape)
            )
        }
        // thin zigzag, lower right
        Canvas(
            modifier = Modifier
                .offset(x = 252.dp, y = 300.dp)
                .size(24.dp, 11.dp)
        ) {
            val p = Path()
            p.moveTo(0f, size.height * 0.7f)
            p.lineTo(size.width * 0.25f, 0f)
            p.lineTo(size.width * 0.5f, size.height * 0.7f)
            p.lineTo(size.width * 0.75f, 0f)
            p.lineTo(size.width, size.height * 0.7f)
            drawPath(p, Color.White.copy(alpha = 0.42f), style = Stroke(width = 2.2f))
        }
    }
}

// ---- 1. Teal hero ----
@Composable
private fun TealHero(bookCount: Int, onImport: () -> Unit) {
    val teal = LifestyleColors.Teal
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(teal),
    ) {
        // Decorative dots and a small squiggle, positioned in dp so they land
        // in the same places as the reference instead of drifting with the
        // hero's height.
        HeroDoodles()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp, bottom = 44.dp),
        ) {
            // Top: search field with avatar circle on right. The reference
            // leaves a clear band of empty mint to the right of the avatar, so
            // the row stops short of the edge rather than filling the width.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 70.dp),
            ) {
                var search by rememberSaveable { mutableStateOf("") }
                SearchField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = "Search your books",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(LifestyleColors.AvatarPlum),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "B",
                        color = LifestyleColors.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Text(
                "Your Book Library",
                color = LifestyleColors.MintInk,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 33.sp,
                lineHeight = 41.sp,
            )
            Text(
                "Make Your Own Space",
                color = LifestyleColors.MintInk,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 33.sp,
                lineHeight = 41.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (bookCount == 0)
                    "Import your first book and start a beautiful library"
                else
                    "$bookCount books ready to read",
                color = LifestyleColors.MintInk,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onImport,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LifestyleColors.White,
                    contentColor = LifestyleColors.MintInk,
                ),
                contentPadding = PaddingValues(horizontal = 34.dp, vertical = 17.dp),
                modifier = Modifier.heightIn(min = 58.dp),
            ) {
                Icon(
                    Icons.Filled.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = LifestyleColors.MintInk,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Import books",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = LifestyleColors.MintInk,
                )
            }
        }
    }
}

// ---- 2. Rounded teal CTA ----
@Composable
private fun FindNextBookCta(onAddBook: () -> Unit) {
    val teal = LifestyleColors.Teal
    val plum = LifestyleColors.AvatarPlum
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = teal,
        contentColor = LifestyleColors.MintInk,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Find the next",
                    color = LifestyleColors.MintInk,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 23.sp,
                    lineHeight = 31.sp,
                )
                Text(
                    "book you'll love",
                    color = LifestyleColors.MintInk,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 23.sp,
                    lineHeight = 31.sp,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAddBook,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LifestyleColors.Pill,
                        contentColor = teal,
                    ),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 16.dp),
                ) {
                    Text(
                        "Add a book now",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = teal,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(plum),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.LibraryBooks,
                    contentDescription = null,
                    tint = LifestyleColors.White,
                    modifier = Modifier.size(30.dp),
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
        fontWeight = FontWeight.Medium,
        color = LifestyleColors.TextPrimary,
        fontSize = 22.sp,
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
        CategoryChip("Health", "\uD83E\uDDB7")
        CategoryChip("Science", "\uD83D\uDD2C")
        CategoryChip("Motivation", "\uD83D\uDD25")
    }
}

@Composable
private fun CategoryChip(
    label: String,
    emoji: String,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = LifestyleColors.Card,
        border = BorderStroke(1.dp, LifestyleColors.Divider),
        modifier = Modifier.clickable { },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
        ) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = LifestyleColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
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
            color = LifestyleColors.TextPrimary,
            fontSize = 20.sp,
        )
        Text(
            "View all",
            style = MaterialTheme.typography.bodyMedium,
            color = LifestyleColors.Teal,
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
            color = LifestyleColors.TextPrimary,
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
            color = LifestyleColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 5. Continue reading ----
@Composable
private fun ContinueReadingRow(
    books: List<BookEntity>,
    serverUrl: String,
    onClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
    ) {
        items(books, key = { it.id }) { book ->
            Column(
                modifier = Modifier
                    .width(96.dp)
                    .clickable { onClick(book.id) },
            ) {
                BookCover(
                    coverUrl = book.coverUrl,
                    title = book.title,
                    serverUrl = serverUrl,
                    cornerRadius = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingHeader() {
    Text(
        "Continue reading",
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        color = LifestyleColors.TextPrimary,
        fontSize = 20.sp,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 12.dp),
    )
}

// ---- bottom nav: floating card ----
enum class LifestyleTab { HOME, BOOKS, BOOKMARKS, PROFILE }

