package com.bookcon.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Notebook feature (v1.5): one notebook per book, auto-created on first note.
 * Notes are page-anchored: each [NoteEntity] stores the book page it was taken
 * on so the notebook groups entries by reading page (tap a note to jump back).
 */
@Entity(tableName = "notebooks", indices = [Index("bookId"), Index("updatedAt")])
data class NotebookEntity(
    @PrimaryKey val id: String,          // "nb:$bookId"
    val bookId: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val dirty: Boolean = false,
)

/**
 * One note = one mixed-canvas page in the book's notebook.
 * [contentJson] holds serialized NoteContent: typed text blocks + ink strokes.
 */
@Entity(tableName = "notes", indices = [Index("notebookId"), Index("updatedAt")])
data class NoteEntity(
    @PrimaryKey val id: String,          // "note:$notebookId:$bookPage"
    val notebookId: String,
    val bookId: String,
    /** 0-based book page the note was taken on (PDF: current page; EPUB: -1). */
    val bookPage: Int,
    /** EPUB anchor "<href>#<position>" the note belongs to; empty for PDF notes. */
    val anchorKey: String,
    /** Serialized NoteContent JSON (text blocks + ink strokes). */
    val contentJson: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val dirty: Boolean = false,
)
