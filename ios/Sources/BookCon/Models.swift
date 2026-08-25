import Foundation

enum BookFormat: String, Codable, CaseIterable {
    case pdf, epub, cbz
}

struct Book: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var title: String
    var format: BookFormat
    var addedAt: Date = Date()
    /// 0...100
    var progressPct: Double = 0
    var coverFileName: String?
    var fileName: String
    var seriesId: String? = nil
    var tagIds: [String] = []
}

struct Tag: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var name: String
}

struct Series: Identifiable, Codable, Equatable {
    var id: String = UUID().uuidString
    var name: String
}
