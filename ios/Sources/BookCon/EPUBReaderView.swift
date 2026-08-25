//
//  EPUBReaderView.swift
//  BookCon
//
//  Top-level SwiftUI screen for reading an EPUB.
//
//  Routing is controlled by the single boolean `EPUBRoute.useReadium`
//  (EPUBFallbackReader.swift):
//    * true  -> ReadiumEPUBEngine (all Readium code stays inside EPUBEngine.swift)
//    * false -> EPUBFallbackReader
//
//  The engine class is constructed asynchronously in `.task` (the onAppear hook,
//  since opening a publication with Readium 3.x is async) and kept in @State;
//  engines are classes, so identity is stable across view updates.
//

import SwiftUI
import UIKit

struct EPUBReaderView: View {

    let book: Book

    // MARK: State

    @Environment(\.dismiss) private var dismiss
#if BOOKCON_ENABLE_READIUM
    @StateObject private var bridge: ReaderBridge
    @State private var engine: ReadiumEPUBEngine?
    @State private var failureText: String?
#endif
    @State private var displayedPct: Double

    init(book: Book) {
        self.book = book
#if BOOKCON_ENABLE_READIUM
        _bridge = StateObject(wrappedValue: ReaderBridge(bookId: book.id))
#endif
        _displayedPct = State(initialValue: book.progressPct)
    }

    // MARK: Body

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Divider()
            content
        }
        .background(Color(uiColor: .systemBackground))
        .navigationBarHidden(true)
        .task { await bootstrap() }
#if BOOKCON_ENABLE_READIUM
        .onChange(of: bridge.pct) { _, newValue in
            displayedPct = newValue
        }
#endif
    }

    // MARK: Slim top bar

    private var topBar: some View {
        HStack(spacing: 12) {
            Button(action: { dismiss() }) {
                Image(systemName: "chevron.left")
                    .font(.body.weight(.semibold))
            }
            .accessibilityLabel("Back")

            Text(book.title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .frame(minWidth: 0)

            Spacer(minLength: 8)

#if BOOKCON_ENABLE_READIUM
            if EPUBRoute.useReadium {
                Button {
                    _ = engine?.addHighlightFromSelection()
                } label: {
                    Image(systemName: "highlighter")
                }
                .disabled(engine == nil)
                .accessibilityLabel("Highlight selection")
            }
#endif

            Text("\(Int(displayedPct.rounded()))%")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(minWidth: 38, alignment: .trailing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    // MARK: Content routing

    @ViewBuilder
    private var content: some View {
        #if BOOKCON_ENABLE_READIUM
        if EPUBRoute.useReadium {
            if let engine {
                EngineSurface(engineViewController: engine.viewController())
            } else if let failureText {
                VStack(spacing: 10) {
                    Image(systemName: "book.closed")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text(failureText)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 24)
                    Button("Close", action: { dismiss() })
                        .buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView("Opening “\(book.title)”…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            EPUBFallbackReader(book: book)
        }
        #else
        EPUBFallbackReader(book: book)
        #endif
    }

    // MARK: Bootstrap

    /// onAppear construction hook — `ReadiumEPUBEngine.openAsync` is async, so the
    /// engine is built here instead of in `init`.
    private func bootstrap() async {
        #if BOOKCON_ENABLE_READIUM
        bridge.onDismiss = { dismiss() }
        guard EPUBRoute.useReadium else { return } // fallback builds itself
        guard engine == nil else { return }

        do {
            let fileURL = LibraryStore.shared.url(for: book)
            let box = try await ReadiumEPUBEngine.openAsync(fileURL: fileURL)
            let storedHighlights =
                UserDefaults.standard.stringArray(forKey: Self.highlightsKey(book.id)) ?? []

            engine = try ReadiumEPUBEngine(
                box: box,
                initialPct: book.progressPct,
                highlightsJSON: storedHighlights,
                host: bridge,
                onHighlightsChanged: { list in
                    // Minimal-but-functional persistence of locator JSON strings.
                    UserDefaults.standard.set(list, forKey: Self.highlightsKey(book.id))
                }
            )
        } catch {
            failureText = error.localizedDescription
        }
        #endif
    }

    private static func highlightsKey(_ bookId: String) -> String {
        "bookcon.epub.highlights." + bookId
    }
}

// MARK: - UIViewController embedding

/// Hosts the plain UIViewController handed out by the engine.
private struct EngineSurface: UIViewControllerRepresentable {
    let engineViewController: () -> UIViewController

    func makeUIViewController(context: Context) -> UIViewController {
        engineViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// MARK: - Host bridge

/// Implements `EPUBEngineHost` for the view:
///   * live progress feeds the header percentage immediately,
///   * LibraryStore writes are throttled to at most one per second (>= 1s),
///   * `hostDismiss()` pops the screen.
#if BOOKCON_ENABLE_READIUM
@MainActor
final class ReaderBridge: ObservableObject, EPUBEngineHost {

    let bookId: String
    @Published var pct: Double = 0
    var onDismiss: (() -> Void)?

    private let storeThrottle = ProgressThrottle(minInterval: 1.0)

    init(bookId: String) {
        self.bookId = bookId
    }

    func engineDidUpdateProgress(pct: Double) {
        self.pct = min(max(pct, 0), 100)
        storeThrottle.fire {
            LibraryStore.shared.updateProgress(bookId: self.bookId, pct: self.pct)
        }
    }

    func hostDismiss() {
        onDismiss?()
    }
}
#endif
