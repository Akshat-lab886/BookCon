package com.bookcon.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        BookEntity::class,
        AnnotationEntity::class,
        BookmarkEntity::class,
        PositionEntity::class,
        ShelfEntity::class,
        TagEntity::class,
        SeriesEntity::class,
        SyncCursorEntity::class,
        UploadQueueItem::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BookConDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun positionDao(): PositionDao
    abstract fun organizeDao(): OrganizeDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun uploadQueueDao(): UploadQueueDao

    companion object {
        const val NAME = "bookcon.db"
    }
}
