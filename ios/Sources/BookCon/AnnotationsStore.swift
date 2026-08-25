import Foundation
import PencilKit

/// Per-book ink drawings and highlight notes.
/// Ink blobs live at Application Support/annotations/<bookId>/page-<n>.dat
/// Highlights live as one JSON file per book.
@MainActor
final class AnnotationsStore: ObservableObject {
    static let shared = AnnotationsStore()

    struct HighlightNote: Identifiable, Codable, Equatable {
        var id: String = UUID().uuidString
        var pageIndex: Int
        var text: String
        var colorName: String
        var createdAt: Date = Date()
    }

    private struct HighlightFile: Codable {
        var notes: [HighlightNote] = []
    }

    @Published private(set) var loadedHighlights: [HighlightNote] = []

    private let baseDir: URL

    private init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        baseDir = appSupport.appendingPathComponent("annotations", isDirectory: true)
        try? FileManager.default.createDirectory(at: baseDir, withIntermediateDirectories: true)
    }

    // MARK: - Paths

    private func bookDir(_ bookId: String) -> URL {
        let dir = baseDir.appendingPathComponent(bookId, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func inkURL(_ bookId: String, page: Int) -> URL {
        bookDir(bookId).appendingPathComponent("page-\(page).dat")
    }

    private func highlightsURL(_ bookId: String) -> URL {
        bookDir(bookId).appendingPathComponent("highlights.json")
    }

    // MARK: - Ink

    func inkData(bookId: String, page: Int) -> Data? {
        try? Data(contentsOf: inkURL(bookId, page: page))
    }

    func saveInk(bookId: String, page: Int, data: Data) {
        do {
            try data.write(to: inkURL(bookId, page: page), options: .atomic)
        } catch {
            // Disk full / permissions — annotations are best-effort in v1.
        }
    }

    func deleteInk(bookId: String, page: Int) {
        try? FileManager.default.removeItem(at: inkURL(bookId, page: page))
    }

    // MARK: - Highlights

    func loadHighlights(bookId: String) {
        guard let data = try? Data(contentsOf: highlightsURL(bookId)),
              let file = try? JSONDecoder().decode(HighlightFile.self, from: data) else {
            loadedHighlights = []
            return
        }
        loadedHighlights = file.notes.sorted { $0.pageIndex < $1.pageIndex || ($0.pageIndex == $1.pageIndex && $0.createdAt < $1.createdAt) }
    }

    func highlights(bookId: String) -> [HighlightNote] {
        guard let data = try? Data(contentsOf: highlightsURL(bookId)),
              let file = try? JSONDecoder().decode(HighlightFile.self, from: data) else { return [] }
        return file.notes
    }

    func addHighlight(bookId: String, pageIndex: Int, text: String, colorName: String) {
        var file = currentHighlightFile(bookId)
        file.notes.append(HighlightNote(pageIndex: pageIndex, text: text, colorName: colorName))
        persistHighlights(bookId: bookId, file: file)
        loadHighlights(bookId: bookId)
    }

    func removeHighlight(id: String, bookId: String) {
        var file = currentHighlightFile(bookId)
        file.notes.removeAll { $0.id == id }
        persistHighlights(bookId: bookId, file: file)
        loadHighlights(bookId: bookId)
    }

    private func currentHighlightFile(_ bookId: String) -> HighlightFile {
        if let data = try? Data(contentsOf: highlightsURL(bookId)),
           let file = try? JSONDecoder().decode(HighlightFile.self, from: data) {
            return file
        }
        return HighlightFile()
    }

    private func persistHighlights(bookId: String, file: HighlightFile) {
        if let data = try? JSONEncoder().encode(file) {
            try? data.write(to: highlightsURL(bookId), options: .atomic)
        }
    }

    /// Removes all annotation storage for deleted books.
    func purge(bookId: String) {
        try? FileManager.default.removeItem(at: bookDir(bookId))
    }
}
