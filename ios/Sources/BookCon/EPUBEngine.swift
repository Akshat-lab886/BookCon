//
//  EPUBEngine.swift
//  BookCon
//
//  READIUM ISOLATION BOUNDARY (referred to in design docs as "ReadiumEngine").
//  This is the ONE AND ONLY file in the app allowed to `import ReadiumShared`,
//  `import ReadiumNavigator` and `import ReadiumAdapterGCDWebServer`.
//  Everything the rest of the app touches is plain
//  Foundation/UIKit types: `EPUBEngineHost`, `[String]` highlight JSON, and an
//  opaque `Any` publication box. If Readium 3.2.0 API shapes drift, only this
//  file should need fixing — risky spots carry `// VERIFY-CI:` comments.
//
//  Toolkit version targeted: readium/swift-toolkit 3.2.0 (modules:
//  ReadiumShared, ReadiumNavigator).
//

import Foundation
import UIKit

import ReadiumShared
import ReadiumNavigator
import ReadiumAdapterGCDWebServer

// MARK: - Host contract (the entire surface the app sees)

/// Implemented by the SwiftUI hosting layer (see EPUBReaderView.swift).
/// Deliberately tiny and Readium-free.
@MainActor
protocol EPUBEngineHost: AnyObject {
    /// Estimated overall reading progress in the 0...100 range.
    func engineDidUpdateProgress(pct: Double)
    /// Ask the host to close the reader (unrecoverable state, user request...).
    func hostDismiss()
}

// MARK: - Engine

/// Readium-backed EPUB engine.
///
/// Lifecycle:
/// 1. `ReadiumEPUBEngine.openAsync(fileURL:)` (or the sync shim `open(fileURL:)`)
///    parses the file and returns an opaque `Any` holding a `PublicationBox`.
/// 2. `init(box:initialPct:highlightsJSON:host:onHighlightsChanged:)` builds the
///    `EPUBNavigatorViewController`.
/// 3. `viewController()` hands a plain `UIViewController` to SwiftUI for embedding.
@MainActor
final class ReadiumEPUBEngine: NSObject {

    // MARK: Opaque publication box

    /// Hides the Readium `Publication` from every caller outside this file.
    final class PublicationBox {
        fileprivate let publication: Publication
        fileprivate init(publication: Publication) {
            self.publication = publication
        }
    }

    enum EngineError: LocalizedError {
        case invalidBox
        case navigatorUnavailable
        case cancelled

        var errorDescription: String? {
            switch self {
            case .invalidBox:
                return "Internal error: the file could not be opened as a publication."
            case .navigatorUnavailable:
                return "Could not create the EPUB navigator."
            case .cancelled:
                return "Opening the book was cancelled."
            }
        }
    }

    // MARK: Constants

    /// Decoration group used for user highlights.
    static let highlightGroup = "bookcon-highlights"

    /// The 4 highlight tints offered by the app.
    static let highlightTints: [(r: UInt8, g: UInt8, b: UInt8)] = [
        (255, 213, 79),  // amber
        (129, 199, 132), // green
        (100, 181, 246), // blue
        (240, 98, 146),  // pink
    ]

    /// Minimum interval between progress reports forwarded to the host.
    /// (EPUBReaderView additionally throttles its own LibraryStore writes to >= 1s.)
    private static let hostCallbackMinInterval: TimeInterval = 0.4

    // MARK: State

    private let publication: Publication
    private var navigator: EPUBNavigatorViewController?
    private weak var host: EPUBEngineHost?
    private var onHighlightsChanged: ([String]) -> Void

    private struct StoredHighlight {
        var id: String       // decoration id (also used for tap-to-remove)
        var json: String     // locator JSON — the persisted representation
        var tintIndex: Int   // index into highlightTints (not persisted)
        var locator: Locator // decoded form, cached for decoration rebuilds
    }

    private var highlights: [StoredHighlight] = []
    private var nextTintIndex = 0
    private var lastHostReport = Date.distantPast
    private(set) var currentPct: Double = 0

    // MARK: Opening (factory)

    /// Synchronous factory, kept for parity with the requested signature
    /// `static func open(fileURL: URL) throws -> Any`.
    ///
    /// Readium 3.x opening is inherently `async`; this shim blocks the calling
    /// thread on a semaphore while the parse runs on a detached background task.
    /// Never call it from the main thread — use `openAsync(fileURL:)` from UI code.
    static func open(fileURL: URL) throws -> Any {
        let semaphore = DispatchSemaphore(value: 0)
        var outcome: Result<Any, Error>?
        Task.detached(priority: .userInitiated) {
            do {
                let box = try await ReadiumEPUBEngine.openAsync(fileURL: fileURL)
                outcome = .success(box)
            } catch {
                outcome = .failure(error)
            }
            semaphore.signal()
        }
        semaphore.wait()
        guard let resolved = outcome else {
            throw EngineError.cancelled
        }
        return try resolved.get()
    }

    /// Async factory preferred by the UI layer. Returns an opaque `Any`
    /// (internally a `PublicationBox`).
    nonisolated static func openAsync(fileURL: URL) async throws -> Any {
        // toolkit 3.x chains format-specific parsers behind DefaultPublicationParser
        // (EPUB, PDF, audiobook, CBZ, ...). Only Shared + Navigator products are
        // linked, which is enough: the parsers live in ReadiumShared.
        //
        // VERIFY-CI: DefaultPublicationParser init parameter labels for 3.2.0
        // (`httpClient:` / `pdfFactory:`). Earlier 3.x also accepted an optional
        // `audioFactory:`; omitting it is forward/backward compatible.
        let httpClient = DefaultHTTPClient()
        let assetRetriever = AssetRetriever(
            formatSniffer: DefaultFormatSniffer(),
            resourceFactory: ResourceFactory(httpClient: httpClient),
            archiveOpener: DefaultArchiveOpener()
        )
        let parser = DefaultPublicationParser(
            httpClient: httpClient,
            assetRetriever: assetRetriever,
            pdfFactory: DefaultPDFDocumentFactory()
        )

        let opener = PublicationOpener(parser: parser)

        // VERIFY-CI: `PublicationOpener.open` drifted across 3.x releases:
        //   * early 3.0: `open(at:credentials:allowUserInteraction:warnings:)`
        //   * later 3.x: positional first argument taking a plain file `URL`,
        //     a `Link?`, or a `Source` (e.g. `.file(url)`).
        // The variant below (positional URL) is the most commonly documented
        // form; adjust only the call expression if it does not compile.
        guard let absURL = AbsoluteURL(fileURL) else {
            throw EngineError.cancelled
        }
        let asset = try await assetRetriever.retrieve(url: absURL, hints: FormatHints()).get()
        let publication = try await opener.open(asset: asset, allowUserInteraction: false).get()

        return PublicationBox(publication: publication)
    }

    // MARK: Init

    /// - Parameters:
    ///   - box: opaque value previously returned by `open`/`openAsync`.
    ///   - initialPct: restored progress, 0...100.
    ///   - highlightsJSON: persisted highlight list (locator JSON strings).
    ///   - host: weakly retained progress/dismiss sink.
    ///   - onHighlightsChanged: invoked with the full list whenever it changes.
    init(
        box: Any,
        initialPct: Double,
        highlightsJSON: [String],
        host: EPUBEngineHost?,
        onHighlightsChanged: @escaping ([String]) -> Void
    ) throws {
        guard let publicationBox = box as? PublicationBox else {
            throw EngineError.invalidBox
        }
        self.publication = publicationBox.publication
        self.host = host
        self.onHighlightsChanged = onHighlightsChanged
        super.init()

        currentPct = min(max(initialPct, 0), 100)

        navigator = makeNavigator(initialPct: currentPct)
        guard navigator != nil else {
            throw EngineError.navigatorUnavailable
        }

        applyStoredHighlightsJSON(highlightsJSON)
    }

    // MARK: Navigator construction

    private func makeNavigator(initialPct: Double) -> EPUBNavigatorViewController? {
        let locator = initialLocator(forPct: initialPct)
        // Verified against swift-toolkit 3.2.0 sources:
        // convenience init(publication:initialLocation:readingOrder:config:httpServer:)
        do {
            return try EPUBNavigatorViewController(
                publication: publication,
                initialLocation: locator,
                readingOrder: nil,
                config: .init(preferences: EPUBPreferences()),
                httpServer: GCDHTTPServer()
            )
        } catch {
            return nil
        }
    }

    /// Nearest position locator for a 0...100 percentage, used to restore the
    /// last reading location. Falls back to `nil` (navigator opens at `.last`).
    private func initialLocator(forPct pct: Double) -> Locator? {
        // VERIFY-CI: `publication.positions` is lazily computed via the
        // positions service; it can legitimately be empty for exotic pubs.
        let positions = publication.positions
        guard !positions.isEmpty else { return nil }
        let fraction = min(max(pct / 100, 0), 1)
        let index = min(positions.count - 1, Int(fraction * Double(positions.count)))
        return positions[index]
    }

    // MARK: Embedding

    /// Plain `UIViewController` hosting the Readium navigator as a child.
    /// The navigator fills the container; the SwiftUI layer supplies chrome.
    func viewController() -> UIViewController {
        let container = UIViewController()
        container.view.backgroundColor = .systemBackground

        guard let navigator else { return container }
        navigator.willMove(toParent: container)
        container.addChild(navigator)
        navigator.view.frame = container.view.bounds
        navigator.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.view.addSubview(navigator.view)
        navigator.didMove(toParent: container)
        return container
    }

    // MARK: Public controls

    /// Jump to an approximate percentage (0...100).
    func goTo(pct: Double) {
        guard let locator = initialLocator(forPct: pct) else { return }
        // VERIFY-CI: `Navigator.go(to: Locator)` exists across 2.x/3.x.
        navigator?.go(to: locator)
    }

    /// Wraps the currently selected text as a persistent highlight.
    /// Returns `true` when a highlight was created.
    @discardableResult
    func addHighlightFromSelection() -> Bool {
        guard let navigator else { return false }
        // VERIFY-CI: `SelectableNavigator.currentSelection: Selection?`
        // (`Selection.locator` is a stored, non-optional `Locator`) — stable across 3.x.
        guard let selection = navigator.currentSelection else { return false }
        let locator = selection.locator
        guard let json = encodeLocator(locator) else { return false }

        let tintIndex = nextTintIndex % Self.highlightTints.count
        nextTintIndex += 1
        let id = UUID().uuidString
        highlights.append(
            StoredHighlight(id: id, json: json, tintIndex: tintIndex, locator: locator)
        )
        refreshDecorations()
        emitHighlightsChanged()
        navigator.clearSelection()
        return true
    }

    /// Removes the highlight with the given decoration id (tap-to-remove).
    func removeHighlight(id: String) {
        guard highlights.contains(where: { $0.id == id }) else { return }
        highlights.removeAll { $0.id == id }
        refreshDecorations()
        emitHighlightsChanged()
    }

    /// Removes every highlight managed by this session.
    func clearAllHighlights() {
        guard !highlights.isEmpty else { return }
        highlights.removeAll()
        refreshDecorations()
        emitHighlightsChanged()
    }

    /// Persisted representation: locator JSON strings, one per highlight.
    func highlightsJSONList() -> [String] {
        highlights.map { $0.json }
    }

    // MARK: Highlights plumbing

    private func applyStoredHighlightsJSON(_ list: [String]) {
        var seen = Set<String>()
        for json in list where !seen.contains(json) {
            guard let locator = decodeLocator(json) else { continue }
            seen.insert(json)
            highlights.append(
                StoredHighlight(
                    id: UUID().uuidString,
                    json: json,
                    tintIndex: highlights.count % Self.highlightTints.count,
                    locator: locator
                )
            )
        }
        refreshDecorations()
    }

    private func refreshDecorations() {
        guard let navigator else { return }
        // VERIFY-CI: `DecorableNavigator.applyDecorations(_:in:)` was renamed to
        // `setDecorations(_:in:)` in 3.x; revert to `applyDecorations` only if
        // targeting 2.x again.
        navigator.setDecorations(currentDecorations(), in: Self.highlightGroup)
    }

    private func currentDecorations() -> [Decoration] {
        highlights.compactMap { stored in
            let tint = Self.highlightTints[stored.tintIndex % Self.highlightTints.count]
            // VERIFY-CI: `Decoration.Style.Highlight(tint:activity:)` memberwise
            // parameters and ReadiumShared's own `Color` struct (red/green/blue
            // UInt8). `opacity`/`cornerRadius` keep their defaults.
            let style = Decoration.Style.Highlight(
                tint: Color(red: tint.r, green: tint.g, blue: tint.b),
                activity: .enabled
            )
            return Decoration(
                id: stored.id,
                locator: stored.locator,
                style: style,
                activatable: .action({ [weak self] tappedID, _ in
                    // VERIFY-CI: `Decoration.Activatable.action` handler signature
                    // `(_ id: String, _ event: Event) -> Void` (3.x). Tap-to-remove;
                    // if the handler type drifted, adapt only this closure.
                    Task { @MainActor [weak self] in
                        self?.removeHighlight(id: tappedID)
                    }
                })
            )
        }
    }

    private func emitHighlightsChanged() {
        onHighlightsChanged(highlightsJSONList())
    }

    private func encodeLocator(_ locator: Locator) -> String? {
        guard let data = try? JSONEncoder().encode(locator) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func decodeLocator(_ json: String) -> Locator? {
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Locator.self, from: data)
    }

    // MARK: Progress estimation

    private func reportProgress(_ locator: Locator) {
        let pct = estimatePct(for: locator)
        currentPct = pct
        let now = Date()
        guard now.timeIntervalSince(lastHostReport) >= Self.hostCallbackMinInterval else { return }
        lastHostReport = now
        host?.engineDidUpdateProgress(pct: pct)
    }

    /// Maps a locator to 0...100.
    ///
    /// Strategy: prefer Readium's global progression (`locations.totalProgression`),
    /// otherwise fall back to a cheap reading-order estimate
    /// `(spineIndex + progression) / spineCount`. Computing `publication.positions`
    /// up front is avoided because it can be expensive for large publications.
    private func estimatePct(for locator: Locator) -> Double {
        // VERIFY-CI: `Locations.totalProgression: Double?` (0...1 through the
        // whole publication) — present in 2.x and 3.x.
        if let totalProgression = locator.locations.totalProgression {
            return min(max(totalProgression, 0), 1) * 100
        }
        if !publication.readingOrder.isEmpty,
           let index = readingOrderIndex(ofHref: locator.href) {
            let progression = locator.locations.progression ?? 0
            let fraction =
                (Double(index) + min(max(progression, 0), 1))
                / Double(publication.readingOrder.count)
            return min(max(fraction, 0), 1) * 100
        }
        return currentPct
    }

    private func readingOrderIndex(ofHref href: String) -> Int? {
        let needle = normalizedHref(href)
        return publication.readingOrder.firstIndex { normalizedHref($0.href) == needle }
    }

    /// Tolerant href comparison: strips fragments, leading "/" and "./".
    private func normalizedHref(_ href: String) -> String {
        var value = href.components(separatedBy: "#").first ?? href
        if value.hasPrefix("./") {
            value.removeSubrange(value.startIndex..<value.index(value.startIndex, offsetBy: 2))
        }
        while value.hasPrefix("/") {
            value.removeFirst()
        }
        return value
    }
}

// MARK: - EPUBNavigatorDelegate

extension ReadiumEPUBEngine: EPUBNavigatorDelegate {

    // VERIFY-CI: 2.x exposed closure callbacks (e.g. `onPageChanged`); 3.x routes
    // all navigation events through the delegate protocol below. Both methods had
    // default implementations historically, so implementing only these two is
    // sufficient; if a required method is added, the compiler will point here.

    /// Continuous scrolling / page turns land here (2.x `onPageChanged` equivalent).
    public func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        reportProgress(locator)
    }

    /// Explicit jumps (TOC, internal links, restore) land here.
    public func navigator(_ navigator: Navigator, didJumpTo locator: Locator) {
        reportProgress(locator)
    }

    /// Required by `NavigatorDelegate` — surface errors as a silent no-op in v1.
    public func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
    }

    public func navigator(_ navigator: Navigator, didFailToLoadResourceAt href: any RelativeURL, withError error: ReadError) {
    }
}
