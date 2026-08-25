//
//  PDFOverlaySupport.swift
//  BookCon
//
//  Helpers backing the PDF reader's ink overlay: page <-> viewport geometry
//  mapping, an in-memory PKDrawing cache keyed by page index, serialization
//  shims, and the stock pen/marker/eraser tool configurations.
//

import Foundation
import PDFKit
import PencilKit
import UIKit

/// Maps rectangles between the PDFView viewport coordinate space and the
/// PDFPage coordinate space. Ink drawings are always persisted in **page**
/// space so they stay glued to the paper while the user scrolls or turns
/// pages; they are only projected into viewport space for on-screen display.
enum InkGeometry {

    /// Frame of `page` (in its own bounds space) converted into the
    /// coordinate space of `pdfView`'s visible viewport.
    static func pageFrame(of page: PDFPage, in pdfView: PDFView) -> CGRect {
        pdfView.convert(page.bounds(for: pdfView.displayBox), from: page)
    }

    /// Translation converting a point in **page** space into the current
    /// canvas/viewport space, given the page's viewport origin.
    static func pageToCanvasTranslation(origin: CGPoint) -> CGAffineTransform {
        CGAffineTransform(translationX: origin.x, y: origin.y)
    }

    /// Translation converting a point in canvas/viewport space back into
    /// **page** space (the inverse of `pageToCanvasTranslation`).
    static func canvasToPageTranslation(origin: CGPoint) -> CGAffineTransform {
        CGAffineTransform(translationX: -origin.x, y: -origin.y)
    }
}

/// Data <-> PKDrawing shims that swallow decode failures gracefully.
enum InkSerialization {

    static func drawing(from data: Data?) -> PKDrawing? {
        guard let data else { return nil }
        return try? PKDrawing(data: data)
    }

    static func data(from drawing: PKDrawing) -> Data {
        drawing.dataRepresentation()
    }
}

/// Small LRU-ish in-memory cache of decoded page drawings so flipping back a
/// page does not hit disk every time. Main-thread only (reader screens are).
@MainActor
final class InkDrawingCache {

    private var storage: [Int: PKDrawing] = [:]
    private var recency: [Int] = []
    let capacity: Int

    init(capacity: Int = 8) {
        self.capacity = max(1, capacity)
    }

    func drawing(for page: Int) -> PKDrawing? {
        guard let drawing = storage[page] else { return nil }
        touch(page)
        return drawing
    }

    func store(_ drawing: PKDrawing, for page: Int) {
        storage[page] = drawing
        touch(page)
        trim()
    }

    func removeAll() {
        storage.removeAll()
        recency.removeAll()
    }

    private func touch(_ page: Int) {
        recency.removeAll { $0 == page }
        recency.append(page)
    }

    private func trim() {
        while recency.count > capacity, let oldest = recency.first {
            recency.removeFirst()
            storage.removeValue(forKey: oldest)
        }
    }
}

/// Stock tool configurations for the reader's annotation toolbar.
enum InkStyles {

    static let penWidth: CGFloat = 3
    static let markerWidth: CGFloat = 12
    static let markerAlpha: CGFloat = 0.4

    static var penTool: PKTool {
        PKInkingTool(.pen, color: .black, width: penWidth)
    }

    static var markerTool: PKTool {
        if #available(iOS 17.0, *) {
            // Alpha lives in the ink color, so strokes render translucent.
            let color = UIColor.yellow.withAlphaComponent(markerAlpha)
            return PKInkingTool(.marker, color: color, width: markerWidth)
        }
        return PKInkingTool(.pen, color: .yellow, width: markerWidth)
    }

    static var eraserTool: PKTool {
        PKEraserTool(.vector)
    }
}
}
