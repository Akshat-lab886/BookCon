//
//  PDFReaderView.swift
//  BookCon
//
//  Full-screen PDF reading screen: PDFKitView with a transparent PencilKit
//  annotation overlay and an auto-hiding toolbar. Reading progress is
//  reported to the library store as pages turn, throttled to at most one
//  write per second.
//

import SwiftUI

struct PDFReaderView: View {

    let book: Book

    @Environment(\.dismiss) private var dismiss

    @StateObject private var ink = InkController()

    @State private var currentPageIndex = 0
    @State private var pageCount = 0
    @State private var isChromeVisible = true
    @State private var lastProgressWrite = Date.distantPast

    private static let progressThrottleInterval: TimeInterval = 1.0

    var body: some View {
        ZStack(alignment: .top) {
            PDFKitView(
                book: book,
                currentPageIndex: $currentPageIndex,
                pageCount: $pageCount,
                inkController: ink
            )
            .ignoresSafeArea()
            .contentShape(Rectangle())
            .onTapGesture { toggleChrome() }

            InkCanvasLayer(
                controller: ink,
                bookId: book.id,
                pageProvider: { currentPageIndex }
            )

            if isChromeVisible {
                chromeBar
            }
        }
        .navigationBarHidden(true)
        .onAppear {
            recordProgress()
        }
        .onChange(of: currentPageIndex) { _, newIndex in
            recordProgress(pageIndex: newIndex)
        }
        .onDisappear {
            flushProgress()
        }
    }

    // MARK: - Toolbar

    private var chromeBar: some View {
        HStack(spacing: 10) {
            Button {
                flushProgress()
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel("Back")

            Text(book.title)
                .font(.headline)
                .lineLimit(1)
                .truncationMode(.tail)

            Spacer(minLength: 8)

            toolButton(.pen, systemImage: "pencil.tip", accessibilityLabel: "Pen")
            toolButton(.marker, systemImage: "highlighter", accessibilityLabel: "Marker")
            toolButton(.eraser, systemImage: "eraser", accessibilityLabel: "Eraser")

            Button {
                ink.performUndo()
            } label: {
                Image(systemName: "arrow.uturn.backward")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 34, height: 34)
            }
            .disabled(!ink.canUndo)
            .accessibilityLabel("Undo last stroke")

            Button {
                ink.tool = .none
                flushProgress()
                dismiss()
            } label: {
                Image(systemName: "checkmark")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 34, height: 34)
            }
            .accessibilityLabel("Done")
        }
        .foregroundStyle(.primary)
        .padding(.horizontal, 10)
        .padding(.top, 4)
        .padding(.bottom, 8)
        .background(.thinMaterial, ignoresSafeAreaEdges: .top)
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    private func toolButton(_ tool: InkController.Tool,
                            systemImage: String,
                            accessibilityLabel: String) -> some View {
        let isSelected = ink.tool == tool
        return Button {
            withAnimation(.easeInOut(duration: 0.15)) {
                // Tapping the active tool puts it away again.
                ink.tool = (ink.tool == tool) ? .none : tool
            }
        } label: {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(isSelected ? .white : .primary)
                .padding(8)
                .background(
                    Capsule().fill(isSelected ? Color.accentColor : Color.primary.opacity(0.08))
                )
        }
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    // MARK: - Chrome visibility

    private func toggleChrome() {
        withAnimation(.easeInOut(duration: 0.2)) {
            isChromeVisible.toggle()
        }
    }

    // MARK: - Progress

    /// Throttled progress write: at most one store update per second.
    private func recordProgress(pageIndex: Int? = nil) {
        let now = Date()
        guard now.timeIntervalSince(lastProgressWrite) >= Self.progressThrottleInterval else { return }
        writeProgress(pageIndex: pageIndex ?? currentPageIndex)
    }

    /// Immediate write used when leaving the reader (bypasses the throttle).
    private func flushProgress() {
        writeProgress(pageIndex: currentPageIndex)
    }

    private func writeProgress(pageIndex: Int) {
        lastProgressWrite = Date()
        guard pageCount > 0 else { return }
        let pct = min(100.0, max(0.0, Double(pageIndex + 1) / Double(pageCount) * 100.0))
        LibraryStore.shared.updateProgress(bookId: book.id, pct: pct)
    }
}
