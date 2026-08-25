//
//  EPUBFallbackReader.swift
//  BookCon
//
//  Dependency-free EPUB renderer used when the Readium navigator is disabled
//  (see `EPUBRoute.useReadium`). This file MUST compile WITHOUT any Readium
//  import: it unzips the container with an inline minimal central-directory ZIP
//  reader, resolves the OPF spine order, and renders one spine document at a
//  time in a WKWebView with Previous/Next controls, swipe gestures and a
//  progress bar.
//

import Foundation
import SwiftUI
import WebKit

// MARK: - Routing gate

enum EPUBRoute {
    static var useReadium: Bool { false }
}

// MARK: - Progress throttle (shared utility)

/// Fires work at most once per `minInterval`. Not thread-safe; use from MainActor.
final class ProgressThrottle {
    private let minInterval: TimeInterval
    private var lastFire = Date.distantPast

    init(minInterval: TimeInterval) {
        self.minInterval = minInterval
    }

    func fire(_ work: () -> Void) {
        let now = Date()
        guard now.timeIntervalSince(lastFire) >= minInterval else { return }
        lastFire = now
        work()
    }

    func reset() {
        lastFire = .distantPast
    }
}

// MARK: - Minimal ZIP reader (central directory based)

/// One entry as described by the archive's central directory.
struct ZipEntry {
    var path: String
    var method: Int          // 0 = stored, 8 = deflate
    var compressedSize: Int
    var uncompressedSize: Int
    var localHeaderOffset: Int
}

enum MiniZipError: LocalizedError {
    case notAZipArchive
    case corruptArchive
    case zip64Unsupported
    case encryptedEntry(String)
    case unsupportedMethod(Int, String)
    case decompressionFailed(String)

    var errorDescription: String? {
        switch self {
        case .notAZipArchive:
            return "This file is not a ZIP-based EPUB."
        case .corruptArchive:
            return "The EPUB archive appears to be corrupted."
        case .zip64Unsupported:
            return "Zip64 archives are not supported by the basic reader."
        case .encryptedEntry(let path):
            return "Encrypted entry is not supported: \(path)"
        case .unsupportedMethod(let method, let path):
            return "Unsupported compression (\(method)) for entry: \(path)"
        case .decompressionFailed(let why):
            return "Could not inflate EPUB content: \(why)"
        }
    }
}

enum MiniZip {

    // MARK: Central directory parsing

    /// Parses every central-directory entry. The whole archive is held in memory,
    /// which is acceptable for book-sized files.
    static func readCentralDirectory(_ data: Data) throws -> [ZipEntry] {
        let eocdOffset = try findEndOfCentralDirectory(data)

        guard let totalEntries = u16(data, eocdOffset + 10),
              let cdSize = u32(data, eocdOffset + 12),
              let cdOffset = u32(data, eocdOffset + 16)
        else { throw MiniZipError.corruptArchive }

        // Zip64 markers -> bail out cleanly (books never need it).
        guard totalEntries != 0xFFFF, cdSize != 0xFFFFFFFF, cdOffset != 0xFFFFFFFF else {
            throw MiniZipError.zip64Unsupported
        }

        var entries: [ZipEntry] = []
        entries.reserveCapacity(totalEntries)
        var cursor = cdOffset
        let end = min(cdOffset &+ cdSize, data.count)

        for _ in 0..<totalEntries {
            guard cursor + 46 <= end,
                  let signature = u32(data, cursor), signature == 0x0201_4B50,
                  let flags = u16(data, cursor + 8),
                  let method = u16(data, cursor + 10),
                  let compressedSize = u32(data, cursor + 20),
                  let uncompressedSize = u32(data, cursor + 24),
                  let nameLength = u16(data, cursor + 28),
                  let extraLength = u16(data, cursor + 30),
                  let commentLength = u16(data, cursor + 32),
                  let localHeaderOffset = u32(data, cursor + 44)
            else { break } // tolerate trailing garbage; keep what we parsed

            let nameRange = (cursor + 46)..<(cursor + 46 + nameLength)
            guard nameRange.upperBound <= data.count,
                  let name = String(data: data.subdata(in: nameRange), encoding: .utf8)
                  ?? String(data: data.subdata(in: nameRange), encoding: .isoLatin1)
            else { break }

            if flags & 0x0001 == 0 { // encrypted entries are skipped here;
                entries.append(      // extraction never sees them.
                    ZipEntry(
                        path: name,
                        method: method,
                        compressedSize: compressedSize,
                        uncompressedSize: uncompressedSize,
                        localHeaderOffset: localHeaderOffset
                    )
                )
            }
            cursor += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /// Scans backwards for the EOCD signature 0x06054b50 (comment may be <= 64 KiB).
    private static func findEndOfCentralDirectory(_ data: Data) throws -> Int {
        let floor_ = max(0, data.count - (22 + 65_536))
        var i = data.count - 22
        while i >= floor_ {
            if u32(data, i) == 0x0605_4B50 { return i }
            i -= 1
        }
        throw MiniZipError.notAZipArchive
    }

    // MARK: Extraction

    /// Extracts every readable entry into `destination`, returning written
    /// relative paths. Path traversal ("zip slip") is rejected.
    static func extractArchive(at archiveURL: URL, to destination: URL) throws -> [String] {
        let data = try Data(contentsOf: archiveURL)
        let entries = try readCentralDirectory(data)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)

        var written: [String] = []
        written.reserveCapacity(entries.count)
        for entry in entries where !entry.path.isEmpty && !entry.path.hasSuffix("/") {
            guard let relativePath = sanitizedPath(entry.path) else { continue }
            let payload = try extractEntry(from: data, entry: entry)
            let target = destination.appendingPathComponent(relativePath)
            try FileManager.default.createDirectory(
                at: target.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try payload.write(to: target, options: .atomic)
            written.append(relativePath)
        }
        return written
    }

    /// Decompresses a single entry using its local header for the data offset.
    static func extractEntry(from data: Data, entry: ZipEntry) throws -> Data {
        let lho = entry.localHeaderOffset
        // Local header: sig(4) ... nameLen@26 extraLen@28, data at 30+nameLen+extraLen.
        guard lho >= 0, lho + 30 <= data.count,
              u32(data, lho) == 0x0403_4B50,
              let nameLength = u16(data, lho + 26),
              let extraLength = u16(data, lho + 28)
        else { throw MiniZipError.corruptArchive }

        let start = lho + 30 + nameLength + extraLength
        let end = start + entry.compressedSize
        guard start >= 0, end <= data.count else { throw MiniZipError.corruptArchive }

        let raw = data.subdata(in: start..<end)

        switch entry.method {
        case 0: // stored
            return raw
        case 8: // deflate
            return try inflate(raw, entryName: entry.path)
        default:
            throw MiniZipError.unsupportedMethod(entry.method, entry.path)
        }
    }

    /// Inflates a raw DEFLATE stream. Apple's libcompression `.zlib` decodes
    /// RFC-1951 raw DEFLATE (no zlib wrapper), which is exactly what ZIP
    /// entries contain — so no manual header stripping is needed.
    private static func inflate(_ raw: Data, entryName: String) throws -> Data {
        do {
            let inflated = try (raw as NSData).decompressed(using: .zlib) as Data
            guard !inflated.isEmpty else {
                throw MiniZipError.decompressionFailed("empty output for \(entryName)")
            }
            return inflated
        } catch let error as MiniZipError {
            throw error
        } catch {
            throw MiniZipError.decompressionFailed("\(entryName): \(error.localizedDescription)")
        }
    }

    // MARK: Helpers

    private static func sanitizedPath(_ path: String) -> String? {
        let components = path.split(separator: "/", omittingEmptySubsequences: true)
        guard !components.isEmpty, !components.contains("..") else { return nil }
        return components.joined(separator: "/")
    }

    private static func u16(_ data: Data, _ offset: Int) -> Int? {
        guard offset >= 0, offset + 2 <= data.count else { return nil }
        let base = data.startIndex
        return Int(data[base + offset]) | (Int(data[base + offset + 1]) << 8)
    }

    private static func u32(_ data: Data, _ offset: Int) -> Int? {
        guard let low = u16(data, offset), let high = u16(data, offset + 2) else { return nil }
        return low | (high << 16)
    }
}

// MARK: - OPF spine resolution

/// One renderable spine document.
struct FallbackSpineItem {
    var url: URL   // extracted file URL
}

enum MiniOPFError: LocalizedError {
    case missingContainer
    case missingOPF
    case emptySpine

    var errorDescription: String? {
        switch self {
        case .missingContainer:
            return "Invalid EPUB: META-INF/container.xml not found."
        case .missingOPF:
            return "Invalid EPUB: package document (OPF) not found."
        case .emptySpine:
            return "This EPUB has no readable content."
        }
    }
}

enum EPUBStructure {

    /// Resolves the reading order: container.xml -> rootfile (OPF) -> spine order
    /// mapped through the manifest, keeping only HTML-ish documents.
    static func loadSpine(root: URL) throws -> [FallbackSpineItem] {
        let containerURL = root.appendingPathComponent("META-INF/container.xml")
        guard FileManager.default.fileExists(atPath: containerURL.path),
              let containerXML = try? String(contentsOf: containerURL, encoding: .utf8),
              let rootfileTag = tags(named: "rootfile", in: containerXML).first,
              let opfPath = attribute("full-path", in: rootfileTag)
        else { throw MiniOPFError.missingContainer }

        let opfURL = root.appendingPathComponent(opfPath)
        guard let opfXML = try? String(contentsOf: opfURL, encoding: .utf8) else {
            throw MiniOPFError.missingOPF
        }
        let opfDirectory = opfURL.deletingLastPathComponent()

        var hrefByID: [String: String] = [:]
        for tag in tags(named: "item", in: opfXML) {
            if let id = attribute("id", in: tag), let href = attribute("href", in: tag) {
                hrefByID[id] = href
            }
        }

        var items: [FallbackSpineItem] = []
        for tag in tags(named: "itemref", in: opfXML) {
            guard let idref = attribute("idref", in: tag),
                  let href = hrefByID[idref]
            else { continue }

            let fragmentFree = href.components(separatedBy: "#").first ?? href
            let pathExtensionLower = (fragmentFree as NSString).pathExtension.lowercased()
            guard ["xhtml", "html", "htm"].contains(pathExtensionLower) else { continue }

            let cleaned = fragmentFree.removingPercentEncoding ?? fragmentFree
            items.append(FallbackSpineItem(url: opfDirectory.appendingPathComponent(cleaned)))
        }

        guard !items.isEmpty else { throw MiniOPFError.emptySpine }
        return items
    }

    // MARK: Tiny tag / attribute scanners (no XMLParser dependency)

    /// All `<name ...>` opening/self-closing tags present in `xml`.
    private static func tags(named name: String, in xml: String) -> [String] {
        let source = xml as NSString
        var results: [String] = []
        let needle = "<\(name)"
        var searchStart = 0
        while searchStart < source.length {
            let searchRange = NSRange(location: searchStart, length: source.length - searchStart)
            let found = source.range(of: needle, options: [.caseInsensitive], range: searchRange)
            guard found.location != NSNotFound else { break }
            let closeRange = source.range(
                of: ">",
                range: NSRange(location: found.location, length: source.length - found.location)
            )
            guard closeRange.location != NSNotFound else { break }
            results.append(source.substring(with: NSRange(location: found.location, length: NSMaxRange(closeRange) - found.location)))
            searchStart = NSMaxRange(closeRange) + 1
        }
        return results
    }

    /// Value of `attr="..."` / `attr='...'` inside a single tag string.
    private static func attribute(_ attr: String, in tag: String) -> String? {
        var tokens: [String] = []
        var current = ""
        var quote: Character?
        for character in tag {
            if let openQuote = quote {
                if character == openQuote {
                    quote = nil
                } else {
                    current.append(character)
                }
            } else if character == "\"" || character == "'" {
                quote = character
            } else if character.isWhitespace {
                if !current.isEmpty {
                    tokens.append(current)
                    current = ""
                }
            } else {
                current.append(character)
            }
        }
        if !current.isEmpty { tokens.append(current) }

        let wanted = attr.lowercased()
        for token in tokens {
            let parts = token.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            if parts.count == 2, parts[0].lowercased() == wanted {
                return String(parts[1])
            }
        }
        return nil
    }
}

// MARK: - Fallback reader view

/// Basic one-document-at-a-time EPUB reader.
struct EPUBFallbackReader: View {

    let book: Book

    private enum LoadState: Equatable {
        case loading
        case ready
        case failed(String)
    }

    @Environment(\.dismiss) private var dismiss
    @State private var state: LoadState = .loading
    @State private var pages: [URL] = []
    @State private var index = 0
    @State private var scratchDir: URL?
    private let throttle = ProgressThrottle(minInterval: 1.0)

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            content
            Divider()
            footer
        }
        .background(Color(uiColor: .systemBackground))
        .task(id: book.id) { await prepare() }
        .onDisappear { cleanupScratch() }
        .simultaneousGesture(swipeGesture)
    }

    // MARK: Chrome

    private var header: some View {
        HStack(spacing: 12) {
            Button(action: { dismiss() }) {
                Image(systemName: "chevron.left").font(.body.weight(.semibold))
            }
            .accessibilityLabel("Back")

            VStack(alignment: .leading, spacing: 1) {
                Text(book.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text("Basic reader")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Text("\(pages.isEmpty ? 0 : index + 1)/\(pages.count)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            ProgressView(value: progressFraction)
            HStack(spacing: 0) {
                Button {
                    step(by: -1)
                } label: {
                    Label("Previous", systemImage: "chevron.left.circle")
                }
                .labelStyle(.titleAndIcon)
                .font(.footnote)
                .disabled(index <= 0)

                Spacer()
                Text("\(Int((progressFraction * 100).rounded()))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                Spacer()

                Button {
                    step(by: 1)
                } label: {
                    Label("Next", systemImage: "chevron.right.circle")
                }
                .labelStyle(.titleAndIcon)
                .font(.footnote)
                .disabled(index >= pages.count - 1)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var content: some View {
        ZStack {
            switch state {
            case .loading:
                ProgressView("Unpacking book…")
            case .failed(let message):
                VStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text(message)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 24)
                }
            case .ready:
                if let dir = scratchDir, pages.indices.contains(index) {
                    SpinePageView(
                        pageURL: pages[index],
                        accessRoot: dir,
                        onSwipeLeft: { step(by: 1) },
                        onSwipeRight: { step(by: -1) }
                    )
                    .id(index) // fresh web view per spine item
                } else {
                    Text("No page to display.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }

    // MARK: Gestures

    private var swipeGesture: some Gesture {
        DragGesture(minimumDistance: 45)
            .onEnded { value in
                let dx = value.translation.width
                let dy = value.translation.height
                guard abs(dx) > abs(dy), abs(dx) > 60 else { return }
                step(by: dx < 0 ? 1 : -1)
            }
    }

    // MARK: Logic

    private var progressFraction: Double {
        guard pages.count > 1 else { return pages.isEmpty ? 0 : 1 }
        return Double(index) / Double(pages.count - 1)
    }

    private func prepare() async {
        guard state == .loading, pages.isEmpty else { return }
        let fileURL = LibraryStore.shared.url(for: book)
        do {
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("bookcon-fallback-\(UUID().uuidString)", isDirectory: true)
            try MiniZip.extractArchive(at: fileURL, to: directory)
            let items = try EPUBStructure.loadSpine(root: directory)

            scratchDir = directory
            pages = items.map { $0.url }
            let restored = Int((book.progressPct / 100) * Double(items.count))
            index = min(max(restored, 0), items.count - 1)
            state = .ready
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private func step(by delta: Int) {
        let next = index + delta
        guard pages.indices.contains(next) else { return }
        index = next
        reportProgress()
    }

    /// LibraryStore writes are throttled to at most one per second.
    private func reportProgress() {
        guard !pages.isEmpty else { return }
        let pct = min(max(Double(index) / Double(max(pages.count - 1, 1)) * 100, 0), 100)
        throttle.fire {
            LibraryStore.shared.updateProgress(bookId: book.id, pct: pct)
        }
    }

    private func cleanupScratch() {
        if let dir = scratchDir {
            try? FileManager.default.removeItem(at: dir)
        }
    }
}

// MARK: - Spine web view

/// Renders a single extracted XHTML spine document. Horizontal UIKit swipe
/// recognizers drive pagination even when touches land inside the web content.
struct SpinePageView: UIViewRepresentable {

    let pageURL: URL
    let accessRoot: URL
    let onSwipeLeft: () -> Void   // next
    let onSwipeRight: () -> Void  // previous

    final class Coordinator: NSObject {
        var onSwipeLeft: () -> Void
        var onSwipeRight: () -> Void
        var loadedURL: URL?

        init(onSwipeLeft: @escaping () -> Void, onSwipeRight: @escaping () -> Void) {
            self.onSwipeLeft = onSwipeLeft
            self.onSwipeRight = onSwipeRight
        }

        @objc func handleSwipe(_ recognizer: UISwipeGestureRecognizer) {
            switch recognizer.direction {
            case .left:
                onSwipeLeft()
            case .right:
                onSwipeRight()
            default:
                break
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onSwipeLeft: onSwipeLeft, onSwipeRight: onSwipeRight)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.backgroundColor = .systemBackground
        webView.isOpaque = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never

        for direction in [UISwipeGestureRecognizer.Direction.left, .right] {
            let recognizer = UISwipeGestureRecognizer(
                target: context.coordinator,
                action: #selector(Coordinator.handleSwipe(_:))
            )
            recognizer.direction = direction
            recognizer.cancelsTouchesInView = false
            webView.addGestureRecognizer(recognizer)
        }

        context.coordinator.loadedURL = pageURL
        webView.loadFileURL(pageURL, allowingReadAccessTo: accessRoot)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.onSwipeLeft = onSwipeLeft
        context.coordinator.onSwipeRight = onSwipeRight
        if context.coordinator.loadedURL != pageURL {
            context.coordinator.loadedURL = pageURL
            webView.loadFileURL(pageURL, allowingReadAccessTo: accessRoot)
        }
    }
}
