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
        NotebookEntity::class,
        NoteEntity::class,
    ],
    version = 2,
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
    abstract fun notebookDao(): NotebookDao
    abstract fun noteDao(): NoteDao

    companion object {
        const val NAME = "bookcon.db"

        /** v1→v2: notebooks + notes (additive; no existing tables touched). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notebooks` (" +
                        "`id` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`createdAt` TEXT NOT NULL, " +
                        "`updatedAt` TEXT NOT NULL, " +
                        "`deletedAt` TEXT, " +
                        "`dirty` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notebooks_bookId` ON `notebooks` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notebooks_updatedAt` ON `notebooks` (`updatedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (" +
                        "`id` TEXT NOT NULL, " +
                        "`notebookId` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`bookPage` INTEGER NOT NULL, " +
                        "`anchorKey` TEXT NOT NULL, " +
                        "`contentJson` TEXT NOT NULL, " +
                        "`createdAt` TEXT NOT NULL, " +
                        "`updatedAt` TEXT NOT NULL, " +
                        "`deletedAt` TEXT, " +
                        "`dirty` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_notebookId` ON `notes` (`notebookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_updatedAt` ON `notes` (`updatedAt`)")
            }
        }
    }
}
