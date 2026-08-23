package com.bookcon.app.di

import android.content.Context
import androidx.room.Room
import com.bookcon.app.data.local.AnnotationDao
import com.bookcon.app.data.local.BookConDatabase
import com.bookcon.app.data.local.BookDao
import com.bookcon.app.data.local.BookmarkDao
import com.bookcon.app.data.local.OrganizeDao
import com.bookcon.app.data.local.PositionDao
import com.bookcon.app.data.local.SyncCursorDao
import com.bookcon.app.data.local.UploadQueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BookConDatabase =
        Room.databaseBuilder(context, BookConDatabase::class.java, BookConDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun bookDao(db: BookConDatabase): BookDao = db.bookDao()
    @Provides fun annotationDao(db: BookConDatabase): AnnotationDao = db.annotationDao()
    @Provides fun bookmarkDao(db: BookConDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun positionDao(db: BookConDatabase): PositionDao = db.positionDao()
    @Provides fun organizeDao(db: BookConDatabase): OrganizeDao = db.organizeDao()
    @Provides fun syncCursorDao(db: BookConDatabase): SyncCursorDao = db.syncCursorDao()
    @Provides fun uploadQueueDao(db: BookConDatabase): UploadQueueDao = db.uploadQueueDao()
}
