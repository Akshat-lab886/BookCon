//
//  LibraryStore.swift
//  BookCon
//
//  Single source of truth for the offline library: books, tags, and series.
//
//  Persistence model:
//    * Library metadata  → Application Support/bookcon-library.json (pretty-printed,
//      rewritten atomically after every mutation).
//    * Book files        → Documents/imports/<bookId>.<ext>
//    * Generated covers  → Documents/covers/<bookId>.png
//
//  Fully offline and deterministic: no networking anywhere.
//

import Foundation
import UIKit

@MainActor
final class LibraryStore: ObservableObject {

    static let shared = LibraryStore()

    // MARK: - Published state

    @Published var books: [Book] = []
    @Published var tags: [Tag] = []
    @Published var series: [Series] = []

    // MARK: - Locations

    private let importsDirectory: URL
    private let coversDirectory: URL
    private let libraryFileURL: URL

    /// Shape of the persisted library document.
    private struct LibraryFile: Codable {
        var books: [Book]
        var tags: [Tag]
        var series: [Series]
    }

    // MARK: - Init & persistence

    private init() {
        let fileManager = FileManager.default
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let appSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]

        importsDirectory = documents.appendingPathComponent("imports", isDirectory: true)
        coversDirectory = documents.appendingPathComponent("covers", isDirectory: true)
        libraryFileURL = appSupport.appendingPathComponent("bookcon-library.json")

        ensureDirectories()

        if let data = try? Data(contentsOf: libraryFileURL) {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            if let stored = try? decoder.decode(LibraryFile.self, from: data) {
                books = stored.books
                tags = stored.tags
                series = stored.series
            }
        }
    }

    private func ensureDirectories() {
        let fileManager = FileManager.default
        try? fileManager.createDirectory(at: importsDirectory, withIntermediateDirectories: true)
        try? fileManager.createDirectory(at: coversDirectory, withIntermediateDirectories: true)
        try? fileManager.createDirectory(
            at: libraryFileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
    }

    /// Pretty-printed snapshot of the whole library, rewritten after every mutation.
    private func persist() {
        ensureDirectories()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        let snapshot = LibraryFile(books: books, tags: tags, series: series)
        guard let data = try? encoder.encode(snapshot) else { return }
        try? data.write(to: libraryFileURL, options: [.atomic])
    }

    // MARK: - Import

    /// Imports files handed over as security-scoped URLs (document picker / Files app).
    /// Each file is copied to Documents/imports/<uuid>.<ext>, a cover is generated into
    /// Documents/covers/<bookId>.png when possible, and a new Book row is appended.
    func importFiles(urls: [URL]) async {
        for url in urls {
            importSingleFile(url)
        }
    }

    private func importSingleFile(_ url: URL) {
        let wasScoped = url.startAccessingSecurityScopedResource()
        defer { if wasScoped { url.stopAccessingSecurityScopedResource() } }

        guard let format = Self.bookFormat(forExtension: url.pathExtension.lowercased()) else { return }

        let bookId = LibraryStore.newId()
        // Normalize containers onto canonical extensions (cbr/zip → cbz).
        let fileName = "\(bookId).\(format.rawValue)"
        let destination = importsDirectory.appendingPathComponent(fileName)

        do {
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.copyItem(at: url, to: destination)
        } catch {
            return // unreadable source — skip the file entirely
        }

        var coverFileName: String?
        switch format {
        case .pdf:
            if let image = CoverFactory.makeCoverPDF(fileURL: destination) {
                coverFileName = storeCover(image, bookId: bookId)
            }
        case .epub:
            if let image = CoverFactory.makeCoverEPUB(fileURL: destination) {
                coverFileName = storeCover(image, bookId: bookId)
            }
        case .cbz:
            break // no raster-cover pipeline for comics yet
        }

        let book = Book(
            id: bookId,
            title: sanitizedTitle(of: url),
            format: format,
            addedAt: Date(),
            progressPct: 0,
            coverFileName: coverFileName,
            fileName: fileName,
            seriesId: nil,
            tagIds: []
        )
        books.append(book)
        persist()
    }

    /// Saves a generated cover PNG and returns its file name on success.
    private func storeCover(_ image: UIImage, bookId: String) -> String? {
        let coverName = "\(bookId).png"
        let coverURL = coversDirectory.appendingPathComponent(coverName)
        CoverFactory.savePNG(image, to: coverURL)
        return FileManager.default.fileExists(atPath: coverURL.path) ? coverName : nil
    }

    /// Title = source filename minus extension; never empty.
    private func sanitizedTitle(of url: URL) -> String {
        let trimmed = url.deletingPathExtension().lastPathComponent
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Untitled" : trimmed
    }

    /// Extension → format. cbr and plain zip archives are treated as cbz.
    private static func bookFormat(forExtension ext: String) -> BookFormat? {
        switch ext {
        case "pdf": return .pdf
        case "epub": return .epub
        case "cbz", "cbr", "zip": return .cbz
        default: return nil
        }
    }

    // MARK: - Deletion

    /// Removes the given books from the library and deletes their imported files and covers.
    func deleteBooks(ids: Set<String>) {
        guard !ids.isEmpty else { return }
        let removed = books.filter { ids.contains($0.id) }
        books.removeAll { ids.contains($0.id) }

        let fileManager = FileManager.default
        for book in removed {
            try? fileManager.removeItem(at: importsDirectory.appendingPathComponent(book.fileName))
            if let coverName = book.coverFileName {
                try? fileManager.removeItem(at: coversDirectory.appendingPathComponent(coverName))
            }
        }
        persist()
    }

    // MARK: - Progress

    func updateProgress(bookId: String, pct: Double) {
        guard let index = books.firstIndex(where: { $0.id == bookId }) else { return }
        let clamped = min(max(pct, 0), 100)
        guard books[index].progressPct != clamped else { return }
        books[index].progressPct = clamped
        persist()
    }

    // MARK: - Tags

    /// Creates a tag unless the name is empty or already taken (case-insensitive).
    func createTag(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard !tags.contains(where: { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }) else { return }
        tags.append(Tag(id: LibraryStore.newId(), name: trimmed))
        persist()
    }

    /// Deletes a tag and strips every reference to it from the books.
    func deleteTag(id: String) {
        tags.removeAll { $0.id == id }
        for index in books.indices where books[index].tagIds.contains(id) {
            books[index].tagIds.removeAll { $0 == id }
        }
        persist()
    }

    // MARK: - Series

    /// Creates a series unless the name is empty or already taken (case-insensitive).
    func createSeries(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard !series.contains(where: { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }) else { return }
        series.append(Series(id: LibraryStore.newId(), name: trimmed))
        persist()
    }

    /// Deletes a series and clears the reference from every book in it.
    func deleteSeries(id: String) {
        series.removeAll { $0.id == id }
        for index in books.indices where books[index].seriesId == id {
            books[index].seriesId = nil
        }
        persist()
    }

    // MARK: - Assignment

    /// Sets one book's series membership and tag list at once.
    /// Dangling references (deleted series/tags) are dropped before storing.
    func assign(bookId: String, seriesId: String?, tagIds: [String]) {
        guard let index = books.firstIndex(where: { $0.id == bookId }) else { return }
        let resolvedSeries = seriesId.flatMap { wanted in
            self.series.contains(where: { $0.id == wanted }) ? wanted : nil
        }
        let resolvedTags = tagIds.filter { id in
            self.tags.contains(where: { $0.id == id })
        }
        books[index].seriesId = resolvedSeries
        books[index].tagIds = resolvedTags
        persist()
    }

    // MARK: - File access

    /// On-disk location of an imported book file.
    func url(for book: Book) -> URL {
        importsDirectory.appendingPathComponent(book.fileName)
    }

    /// On-disk location of a book's generated cover, if it has one.
    func coverURL(_ book: Book) -> URL? {
        guard let coverName = book.coverFileName else { return nil }
        return coversDirectory.appendingPathComponent(coverName)
    }

    // MARK: - Helpers

    nonisolated static func newId() -> String {
        UUID().uuidString
    }
}
