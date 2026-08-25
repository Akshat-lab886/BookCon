//
//  Components.swift
//  BookCon
//
//  Small shared UI pieces used exclusively by BookCon's shell screens
//  (BookConApp.swift, LibraryScreen.swift). Nothing here is exported to the
//  reader screens.
//

import SwiftUI
import UIKit

// MARK: - CoverImage

/// In-memory cache for decoded cover images. NSCache is thread-safe and
/// evicts automatically under memory pressure.
enum CoverImageCache {
    static let shared = NSCache<NSURL, UIImage>()

    static func image(at url: URL) -> UIImage? {
        shared.object(forKey: url as NSURL)
    }

    static func store(_ image: UIImage, at url: URL, cost: Int) {
        shared.setObject(image, forKey: url as NSURL, cost: cost)
    }
}

/// Loads a cover image asynchronously from `url`, caching decoded images in
/// memory. While loading — or when no cover exists — shows a tinted gradient
/// placeholder carrying the title's initial.
struct CoverImage: View {
    let url: URL?
    var title: String
    /// Gradient color used by the placeholder; callers pass a format-based tint.
    var tint: Color = .accentColor

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                placeholder
            }
        }
        .task(id: url) { await loadIfNeeded() }
    }

    private var placeholder: some View {
        ZStack {
            LinearGradient(
                colors: [tint.opacity(0.65), tint.opacity(0.28)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Text(initial)
                .font(.system(size: 42, weight: .bold, design: .rounded))
                .foregroundStyle(.white.opacity(0.92))
                .minimumScaleFactor(0.5)
                .padding(6)
        }
    }

    private var initial: String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.first else { return "?" }
        return String(first).uppercased()
    }

    private func loadIfNeeded() async {
        guard let url else { return }
        if let cached = CoverImageCache.image(at: url) {
            image = cached
            return
        }
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let decoded = UIImage(data: data)
        else { return }
        CoverImageCache.store(decoded, at: url, cost: data.count)
        image = decoded
    }
}

// MARK: - ProgressOverlay

/// Lightweight modal overlay with a spinner, shown during long-running work
/// such as file imports. Fades in/out and blocks interaction while visible.
struct ProgressOverlay: View {
    var isVisible: Bool
    var message: String = "Working…"

    var body: some View {
        ZStack {
            Rectangle()
                .fill(.ultraThinMaterial)
                .ignoresSafeArea()

            VStack(spacing: 12) {
                ProgressView()
                    .controlSize(.large)
                Text(message)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            .padding(28)
            .background(
                .regularMaterial,
                in: RoundedRectangle(cornerRadius: 18, style: .continuous)
            )
            .shadow(color: .black.opacity(0.18), radius: 24, y: 6)
        }
        .opacity(isVisible ? 1 : 0)
        .allowsHitTesting(isVisible)
        .accessibilityHidden(!isVisible)
        .animation(.easeInOut(duration: 0.15), value: isVisible)
    }
}

// MARK: - FilterChip

/// A small capsule toggle used in the library's filter chips row.
struct FilterChip: View {
    let label: String
    var systemImage: String?
    var isActive: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.caption2.weight(.semibold))
                }
                Text(label)
                    .font(.footnote.weight(.medium))
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .foregroundStyle(isActive ? Color.white : Color.primary)
            .background(
                isActive ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(Color.secondary.opacity(0.14)),
                in: Capsule()
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }
}

// MARK: - ConfirmDelete

/// A `confirmationDialog` wrapper that asks before permanently deleting
/// something. Attach via `View.confirmDelete(itemName:isPresented:onDelete:)`.
struct ConfirmDelete: ViewModifier {
    let itemName: String
    let onDelete: () -> Void
    @Binding var isPresented: Bool

    func body(content: Content) -> some View {
        content.confirmationDialog(
            "Delete “\(itemName)”?",
            isPresented: $isPresented,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive, action: onDelete)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This permanently removes “\(itemName)” from your library.")
        }
    }
}

extension View {
    func confirmDelete(
        itemName: String,
        isPresented: Binding<Bool>,
        onDelete: @escaping () -> Void
    ) -> some View {
        modifier(
            ConfirmDelete(
                itemName: itemName,
                onDelete: onDelete,
                isPresented: isPresented
            )
        )
    }
}

// MARK: - TextFieldAlert

/// Presents a system alert containing a single text field (iOS 16+).
struct TextFieldAlert: ViewModifier {
    let title: String
    var placeholder: String
    var confirmLabel: String
    let onConfirm: (String) -> Void
    @Binding var isPresented: Bool

    @State private var text = ""

    func body(content: Content) -> some View {
        content
            .alert(title, isPresented: $isPresented) {
                TextField(placeholder, text: $text)
                Button("Cancel", role: .cancel) {}
                Button(confirmLabel) {
                    let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    text = ""
                    guard !value.isEmpty else { return }
                    onConfirm(value)
                }
            }
            .onChange(of: isPresented) { _, presented in
                if presented { text = "" }
            }
    }
}

extension View {
    func textFieldAlert(
        title: String,
        placeholder: String,
        isPresented: Binding<Bool>,
        confirmLabel: String = "Add",
        onConfirm: @escaping (String) -> Void
    ) -> some View {
        modifier(
            TextFieldAlert(
                title: title,
                placeholder: placeholder,
                confirmLabel: confirmLabel,
                onConfirm: onConfirm,
                isPresented: isPresented
            )
        )
    }
}
