//
//  PDFKitView.swift
//  BookCon
//
//  UIViewControllerRepresentable wrapper around PDFKit's PDFView.
//  Publishes the current zero-based page index through a Binding by
//  observing .PDFViewPageChanged, and hands the live PDFView instance to the
//  ink controller so the annotation overlay can map viewport coordinates to
//  page coordinates.
//

import SwiftUI
import PDFKit

struct PDFKitView: UIViewRepresentable {

    let book: Book

    /// Zero-based index of the page currently visible in the viewer.
    @Binding var currentPageIndex: Int

    /// Total number of pages in the loaded document (written once ready).
    @Binding var pageCount: Int

    /// Optional receiver of the live PDFView; used by the ink overlay to
    /// translate between viewport and page coordinate spaces.
    var inkController: InkController?

    // MARK: - UIViewControllerRepresentable

    func makeCoordinator() -> Coordinator {
        Coordinator(pageIndex: $currentPageIndex)
    }

    func makeUIView(context: Context) -> PDFView {
        let pdfView = PDFView()
        pdfView.document = PDFDocument(url: LibraryStore.shared.url(for: book))
        pdfView.displayMode = .singlePageContinuous
        pdfView.autoScales = true
        pdfView.displayDirection = .vertical
        pdfView.backgroundColor = .systemBackground

        context.coordinator.startObserving(pdfView)
        inkController?.pdfView = pdfView

        let count = pdfView.document?.pageCount ?? 0
        if count > 0 {
            // Defer the state write out of the current view-update pass.
            DispatchQueue.main.async {
                self.pageCount = count
            }
        }
        return pdfView
    }

    func updateUIView(_ pdfView: PDFView, context: Context) {
        // Nothing to refresh here; the coordinator's observer keeps the page
        // binding current as the user scrolls.
    }

    // MARK: - Coordinator

    @MainActor
    final class Coordinator {

        private let pageIndex: Binding<Int>
        private var observer: NSObjectProtocol?

        init(pageIndex: Binding<Int>) {
            self.pageIndex = pageIndex
        }

        func startObserving(_ pdfView: PDFView) {
            guard observer == nil else { return }
            observer = NotificationCenter.default.addObserver(
                forName: .PDFViewPageChanged,
                object: pdfView,
                queue: .main
            ) { [weak self, weak pdfView] _ in
                guard let self, let pdfView else { return }
                guard let document = pdfView.document, let current = pdfView.currentPage else { return }
                self.pageIndex.wrappedValue = document.index(for: current)
            }
        }

        deinit {
            if let observer {
                NotificationCenter.default.removeObserver(observer)
            }
        }
    }
}
