package com.bookcon.app.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bookcon.app.data.local.AnnotationEntity
import com.bookcon.app.data.local.BookConDatabase
import com.bookcon.app.data.local.BookmarkEntity
import com.bookcon.app.data.local.PositionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * The Settings row says "N changes not uploaded yet".
 *
 * That number has to count the rows that actually sync — annotations, bookmarks,
 * positions and the organisation tables — and must not count books, whose content
 * is uploaded through a different pipeline that the sync push rejects by design.
 *
 * A wrong number here is worse than no number: the user would be told their work is
 * safe when it is not, or nagged to retry a sync that has nothing to send.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PendingSyncCountTest {

    private lateinit var db: BookConDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BookConDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun annotation(id: String, dirty: Boolean) = AnnotationEntity(
        id = id, bookId = "b1", type = "highlight", locatorJson = "{}",
        color = "yellow", note = "", annotationTags = emptyList(), excerpt = "",
        createdAt = "2024-01-01T00:00:00Z", updatedAt = "2024-01-01T00:00:00Z", dirty = dirty,
    )

    private fun bookmark(id: String, dirty: Boolean) = BookmarkEntity(
        id = id, bookId = "b1", locatorJson = "{}", label = "x",
        createdAt = "2024-01-01T00:00:00Z", updatedAt = "2024-01-01T00:00:00Z", dirty = dirty,
    )

    @Test
    fun a_fresh_install_has_nothing_pending() = runTest {
        assertEquals(0, db.pendingSyncCount())
    }

    @Test
    fun dirty_rows_are_counted_and_clean_ones_are_not() = runTest {
        db.annotationDao().upsert(annotation("a1", dirty = true))
        db.annotationDao().upsert(annotation("a2", dirty = false))
        db.bookmarkDao().upsert(bookmark("b1", dirty = true))
        db.bookmarkDao().upsert(bookmark("b2", dirty = false))
        db.positionDao().upsert(
            PositionEntity(bookId = "b1", locatorJson = "{}", progressPercent = 0.5,
                updatedAt = "2024-01-01T00:00:00Z", dirty = true),
        )
        assertEquals(3, db.pendingSyncCount())
    }

    @Test
    fun a_row_cleared_after_a_successful_push_stops_counting() = runTest {
        val a = annotation("a1", dirty = true)
        db.annotationDao().upsert(a)
        assertEquals(1, db.pendingSyncCount())
        db.annotationDao().clearDirty(listOf("a1"))
        assertEquals(0, db.pendingSyncCount())
    }

    @Test
    fun a_rejected_row_keeps_counting() = runTest {
        // Rejected rows deliberately stay dirty so the edit is not lost. The count
        // is what makes that visible instead of silent.
        db.annotationDao().upsert(annotation("a1", dirty = true))
        db.annotationDao().upsert(annotation("a2", dirty = true))
        assertEquals(2, db.pendingSyncCount())
    }
}
