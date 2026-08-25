//
//  InkCanvasLayer.swift
//  BookCon
//
//  PencilKit annotation overlay for the PDF reader.
//
//  The PKCanvasView is sized to the *visible viewport* of the PDFView and
//  never scrolls itself. The coordinator translates strokes between viewport
//  space and PDF page space (see InkGeometry), so persisted ink is stored in
//  page coordinates and stays registered with the paper while the user
//  scrolls or flips pages.
//
//  Correctness detail: when tool == .none the canvas is hidden and has
//  isUserInteractionEnabled == false, so scroll/page-turn gestures reach the
//  PDFView underneath untouched.
//

import SwiftUI
import PencilKit
import PDFKit

/// Drives the annotation overlay: selected tool, undo history, the live
/// PDFView reference, and the per-page in-memory drawing cache.
@MainActor
final class InkController: ObservableObject {

    enum Tool {
        case pen
        case marker
        case eraser
        case none
    }

    /// Currently selected tool. `.none` disables ink capture entirely so
    /// ordinary scrolling gestures reach the PDFView underneath.
    @Published var tool: Tool = .none

    /// True while the undo stack has entries.
    @Published private(set) var canUndo = false

    /// Live PDFView, injected by PDFKitView once its view controller exists.
    weak var pdfView: PDFView?

    /// In-memory cache of decoded page drawings.
    let drawingCache = InkDrawingCache()

    /// Installed by the canvas coordinator; performs the actual undo.
    var undoHandler: (() -> Void)?

    /// Snapshot history of page-space drawing data, newest last.
    private(set) var undoStack: [Data] = []

    static let undoLimit = 20

    func pushUndo(_ data: Data) {
        undoStack.append(data)
        if undoStack.count > Self.undoLimit {
            undoStack.removeFirst(undoStack.count - Self.undoLimit)
        }
        canUndo = true
    }

    func popUndo() -> Data? {
        let entry = undoStack.popLast()
        canUndo = !undoStack.isEmpty
        return entry
    }

    func resetUndo() {
        undoStack.removeAll()
        canUndo = false
    }

    /// Routes an undo request to whichever canvas coordinator is attached.
    func performUndo() {
        undoHandler?()
    }
}

/// Transparent PencilKit overlay aligned on top of the visible PDF viewport.
struct InkCanvasLayer: View {

    @ObservedObject var controller: InkController
    let bookId: String
    let pageProvider: () -> Int

    var body: some View {
        PencilKitCanvas(controller: controller, bookId: bookId, pageProvider: pageProvider)
            // Second line of defense alongside the canvas's own
            // isUserInteractionEnabled so `.none` never steals touches.
            .allowsHitTesting(controller.tool != .none)
            .ignoresSafeArea()
    }
}

/// UIViewRepresentable hosting the PKCanvasView.
struct PencilKitCanvas: UIViewRepresentable {

    @ObservedObject var controller: InkController
    let bookId: String
    let pageProvider: () -> Int

    func makeCoordinator() -> Coordinator {
        Coordinator(controller: controller, bookId: bookId)
    }

    func makeUIViewController(context: Context) -> PKCanvasView {
        let canvas = PKCanvasView()
        canvas.drawingPolicy = .anyInput
        canvas.isOpaque = false
        canvas.backgroundColor = .clear
        canvas.delegate = context.coordinator
        context.coordinator.attach(canvas)
        return canvas
    }

    func updateUIViewController(_ uiViewController: PKCanvasView, context: Context) {
        let coordinator = context.coordinator
        coordinator.setPageProvider(pageProvider)
        coordinator.ensureObservingPdfView()
        coordinator.applyTool(controller.tool)
        coordinator.syncWithVisiblePage()
    }

    // MARK: - Coordinator

    @MainActor
    final class Coordinator: NSObject, PKCanvasViewDelegate {

        private let controller: InkController
        private let bookId: String

        private weak var canvas: PKCanvasView?
        private var pageProvider: () -> Int = { 0 }

        /// Index of the page whose ink is currently loaded into the canvas.
        private var loadedPage: Int?

        /// Authoritative drawing for `loadedPage`, stored in **page** space.
        private var baseDrawing = PKDrawing()

        /// Viewport origin the base drawing was last projected with.
        private var lastAppliedOrigin: CGPoint = .zero

        /// Guards programmatic `canvas.drawing` writes from re-entering the
        /// change/persistence path.
        private var suppressDrawingCallbacks = false

        private var pageObserver: NSObjectProtocol?
        private weak var observedPdfView: PDFView?

        init(controller: InkController, bookId: String) {
            self.controller = controller
            self.bookId = bookId
            super.init()
        }

        deinit {
            if let pageObserver {
                NotificationCenter.default.removeObserver(pageObserver)
            }
        }

        // MARK: Setup

        func attach(_ canvas: PKCanvasView) {
            self.canvas = canvas
            applyTool(controller.tool)
            ensureObservingPdfView()
            controller.undoHandler = { [weak self] in self?.performUndo() }
        }

        func setPageProvider(_ provider: @escaping () -> Int) {
            pageProvider = provider
        }

        /// (Re-)observes the live PDFView once PDFKitView publishes it.
        func ensureObservingPdfView() {
            guard let pdfView = controller.pdfView else { return }
            if pageObserver != nil, pdfView === observedPdfView { return }
            if let pageObserver {
                NotificationCenter.default.removeObserver(pageObserver)
                self.pageObserver = nil
            }
            observedPdfView = pdfView
            pageObserver = NotificationCenter.default.addObserver(
                forName: .PDFViewPageChanged,
                object: pdfView,
                queue: .main
            ) { [weak self] _ in
                self?.handleViewportChange()
            }
        }

        // MARK: Tool state

        func applyTool(_ tool: InkController.Tool) {
            guard let canvas else { return }
            switch tool {
            case .pen:
                canvas.tool = InkStyles.penTool
                setActive(true, on: canvas)
            case .marker:
                canvas.tool = InkStyles.markerTool
                setActive(true, on: canvas)
            case .eraser:
                canvas.tool = InkStyles.eraserTool
                setActive(true, on: canvas)
            case .none:
                // Must not intercept touches so scrolling keeps working.
                setActive(false, on: canvas)
            }
        }

        private func setActive(_ active: Bool, on canvas: PKCanvasView) {
            canvas.isHidden = !active
            canvas.isUserInteractionEnabled = active
        }

        // MARK: Page synchronization

        /// Loads a different page's ink when the visible page changed, and
        /// re-projects the loaded drawing when the page's viewport origin
        /// moved (continuous scrolling keeps the ink glued to the paper).
        func syncWithVisiblePage() {
            handleViewportChange()
        }

        private func handleViewportChange() {
            guard let canvas, let pdfView = controller.pdfView else { return }
            let index = pageProvider()
            if loadedPage != index {
                loadInk(page: index, pdfView: pdfView, canvas: canvas)
                return
            }
            guard let page = pdfView.document?.page(at: index) else { return }
            let origin = InkGeometry.pageFrame(of: page, in: pdfView).origin
            if abs(origin.x - lastAppliedOrigin.x) > 0.5 || abs(origin.y - lastAppliedOrigin.y) > 0.5 {
                project(baseDrawing, onto: canvas, viewportOrigin: origin)
            }
        }

        private func loadInk(page index: Int, pdfView: PDFView, canvas: PKCanvasView) {
            let drawing: PKDrawing
            if let cached = controller.drawingCache.drawing(for: index) {
                drawing = cached
            } else if let restored = InkSerialization.drawing(
                from: AnnotationsStore.shared.inkData(bookId: bookId, page: index)) {
                drawing = restored
            } else {
                drawing = PKDrawing()
            }
            controller.drawingCache.store(drawing, for: index)

            // Undo history does not survive page turns in v1.
            controller.resetUndo()
            baseDrawing = drawing

            let origin = pdfView.document?.page(at: index)
                .map({ InkGeometry.pageFrame(of: $0, in: pdfView).origin }) ?? .zero
            project(drawing, onto: canvas, viewportOrigin: origin)
            loadedPage = index
        }

        /// Renders `drawing` (page space) into the canvas at the viewport
        /// origin the page currently occupies.
        private func project(_ drawing: PKDrawing, onto canvas: PKCanvasView, viewportOrigin: CGPoint) {
            suppressDrawingCallbacks = true
            canvas.drawing = drawing.transformed(by: InkGeometry.pageToCanvasTranslation(origin: viewportOrigin))
            suppressDrawingCallbacks = false
            lastAppliedOrigin = viewportOrigin
        }

        // MARK: Persistence

        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            guard !suppressDrawingCallbacks else { return }

            let previousBase = baseDrawing
            var newBase = canvasView.drawing

            // Canonicalize the fresh strokes into page space.
            if let pdfView = controller.pdfView,
               let page = currentPage(in: pdfView) {
                let origin = InkGeometry.pageFrame(of: page, in: pdfView).origin
                newBase = canvasView.drawing.transformed(by: InkGeometry.canvasToPageTranslation(origin: origin))
                lastAppliedOrigin = origin
            }

            baseDrawing = newBase
            controller.pushUndo(InkSerialization.data(from: previousBase))

            let index = pageProvider()
            controller.drawingCache.store(newBase, for: index)
            AnnotationsStore.shared.saveInk(
                bookId: bookId,
                page: index,
                data: InkSerialization.data(from: newBase)
            )
        }

        private func currentPage(in pdfView: PDFView) -> PDFPage? {
            let index = pageProvider()
            return pdfView.document?.page(at: index) ?? pdfView.currentPage
        }

        // MARK: Undo

        private func performUndo() {
            guard let canvas else { return }
            guard let data = controller.popUndo(),
                  let restored = InkSerialization.drawing(from: data) else { return }

            baseDrawing = restored
            let index = pageProvider()
            controller.drawingCache.store(restored, for: index)
            AnnotationsStore.shared.saveInk(bookId: bookId, page: index, data: data)

            var origin = lastAppliedOrigin
            if let pdfView = controller.pdfView, let page = currentPage(in: pdfView) {
                origin = InkGeometry.pageFrame(of: page, in: pdfView).origin
            }
            project(restored, onto: canvas, viewportOrigin: origin)
        }
    }
}
