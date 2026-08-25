//
//  LibraryScreen.swift
//  BookCon
//
//  The main screen: books grid with tag/series filters, plus management of
//  tags and series. Presented inside RootView's NavigationStack.
//

import SwiftUI
import UniformTypeIdentifiers

struct LibraryScreen: View {

    // MARK: Sections

    private enum LibraryTab: String, CaseIterable, Identifiable {
        case books, tags, series

        var id: String { rawValue }

        var label: String {
            switch self {
            case .books: "Books"
            case .tags: "Tags"
            case .series: "Series"
            }
        }
    }

    // MARK: State

    @EnvironmentObject private var store: LibraryStore
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var tab: LibraryTab = .books
    @State private var isShowingImporter = false
    @State private var isImporting = false

    // Book filters (chips). Empty sets = show everything.
    @State private var selectedTagIDs: Set<String> = []
    @State private var selectedSeriesIDs: Set<String> = []

    @State private var activeBook: Book?
    @State private var bookPendingDeletion: Book?

    @State private var isShowingNewTagAlert = false
    @State private var isShowingNewSeriesAlert = false
    /// Book awaiting assignment to a series that is about to be created.
    @State private var seriesAssignmentTarget: Book?

    @State private var tagsPendingDeletion: [Tag] = []
    @State private var seriesPendingDeletion: [Series] = []

    private static let importTypes: [UTType] = [
        .pdf,
        UTType("org.idpf.epub-container") ?? .data,
        .zip, // CBZ files are zip archives.
    ]

    // MARK: Body

    var body: some View {
        content
            .navigationTitle("BookCon")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            .fileImporter(
                isPresented: $isShowingImporter,
                allowedContentTypes: Self.importTypes,
                allowsMultipleSelection: true
            ) { handleImport(result: $0) }
            .fullScreenCover(item: $activeBook) { book in
                readerView(for: book)
                    .ignoresSafeArea()
            }
            .confirmDelete(
                itemName: bookPendingDeletion?.title ?? "",
                isPresented: Binding(
                    get: { bookPendingDeletion != nil },
                    set: { if !$0 { bookPendingDeletion = nil } }
                ),
                onDelete: deletePendingBook
            )
            .confirmDelete(
                itemName: tagsPendingDeletion.map(\.name).joined(separator: ", "),
                isPresented: Binding(
                    get: { !tagsPendingDeletion.isEmpty },
                    set: { if !$0 { tagsPendingDeletion = [] } }
                ),
                onDelete: deletePendingTags
            )
            .confirmDelete(
                itemName: seriesPendingDeletion.map(\.name).joined(separator: ", "),
                isPresented: Binding(
                    get: { !seriesPendingDeletion.isEmpty },
                    set: { if !$0 { seriesPendingDeletion = [] } }
                ),
                onDelete: deletePendingSeries
            )
            .textFieldAlert(
                title: "New tag",
                placeholder: "Tag name",
                isPresented: $isShowingNewTagAlert
            ) { name in
                store.createTag(name: name)
            }
            .textFieldAlert(
                title: "New series",
                placeholder: "Series name",
                isPresented: $isShowingNewSeriesAlert
            ) { name in
                store.createSeries(name: name)
                if let target = seriesAssignmentTarget,
                   let created = store.series.first(where: { $0.name == name }) {
                    store.assign(bookId: target.id, seriesId: created.id, tagIds: target.tagIds)
                }
                seriesAssignmentTarget = nil
            }
    }

    // MARK: Layout

    @ViewBuilder
    private var content: some View {
        VStack(spacing: 0) {
            Picker("Library section", selection: $tab) {
                ForEach(LibraryTab.allCases) { section in
                    Text(section.label).tag(section)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)

            switch tab {
            case .books: booksSection
            case .tags: tagsSection
            case .series: seriesSection
            }
        }
        .overlay {
            ProgressOverlay(isVisible: isImporting, message: "Importing…")
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            switch tab {
            case .tags:
                Button("New") { isShowingNewTagAlert = true }
            case .series:
                Button("New") { isShowingNewSeriesAlert = true }
            case .books:
                EmptyView()
            }

            Menu {
                Button {
                    isShowingImporter = true
                } label: {
                    Label("Import…", systemImage: "square.and.arrow.down")
                }

                Divider()

                Button {
                    isShowingNewTagAlert = true
                } label: {
                    Label("New tag…", systemImage: "tag")
                }

                Button {
                    isShowingNewSeriesAlert = true
                } label: {
                    Label("New series…", systemImage: "folder.badge.plus")
                }
            } label: {
                Image(systemName: "plus")
            }
            .accessibilityLabel("Add")
        }
    }

    // MARK: Books

    private var booksSection: some View {
        Group {
            if store.books.isEmpty {
                emptyState(
                    systemImage: "books.vertical",
                    title: "Your library is empty",
                    message: "Import PDF, EPUB, or CBZ files to start reading.",
                    buttonTitle: "Import Books",
                    action: { isShowingImporter = true }
                )
            } else {
                VStack(spacing: 0) {
                    filterChips
                    if filteredBooks.isEmpty {
                        noMatchesState
                    } else {
                        ScrollView {
                            bookGrid(filteredBooks)
                                .padding(.top, 4)
                        }
                    }
                }
            }
        }
    }

    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                FilterChip(
                    label: "All",
                    systemImage: "books.vertical",
                    isActive: selectedTagIDs.isEmpty && selectedSeriesIDs.isEmpty,
                    action: {
                        selectedTagIDs.removeAll()
                        selectedSeriesIDs.removeAll()
                    }
                )

                ForEach(store.tags) { tag in
                    FilterChip(
                        label: tag.name,
                        systemImage: "tag",
                        isActive: selectedTagIDs.contains(tag.id),
                        action: { toggleFilter(tagID: tag.id) }
                    )
                }

                ForEach(store.series) { series in
                    FilterChip(
                        label: series.name,
                        systemImage: "square.stack.3d.up",
                        isActive: selectedSeriesIDs.contains(series.id),
                        action: { toggleFilter(seriesID: series.id) }
                    )
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
    }

    private func bookGrid(_ books: [Book]) -> some View {
        LazyVGrid(
            columns: [GridItem(
                .adaptive(minimum: horizontalSizeClass == .regular ? 160 : 110),
                spacing: 12
            )],
            spacing: 18
        ) {
            ForEach(books) { book in
                BookTile(
                    book: book,
                    coverURL: store.coverURL(book),
                    onTap: { activeBook = book }
                )
                .contextMenu { contextMenu(for: book) }
            }
        }
        .padding(.horizontal)
        .padding(.bottom, 24)
    }

    @ViewBuilder
    private func contextMenu(for book: Book) -> some View {
        Menu {
            ForEach(store.series) { series in
                Button {
                    store.assign(bookId: book.id, seriesId: series.id, tagIds: book.tagIds)
                } label: {
                    if book.seriesId == series.id {
                        Label(series.name, systemImage: "checkmark")
                    } else {
                        Text(series.name)
                    }
                }
            }
            Divider()
            Button {
                seriesAssignmentTarget = book
                isShowingNewSeriesAlert = true
            } label: {
                Label("New series…", systemImage: "plus.circle")
            }
        } label: {
            Label("Add to series…", systemImage: "folder.badge.plus")
        }

        Menu {
            ForEach(store.tags) { tag in
                Button {
                    toggle(tag: tag, on: book)
                } label: {
                    if book.tagIds.contains(tag.id) {
                        Label(tag.name, systemImage: "checkmark")
                    } else {
                        Text(tag.name)
                    }
                }
            }
            if store.tags.isEmpty {
                Text("No tags yet")
            }
        } label: {
            Label("Toggle tags…", systemImage: "tag")
        }

        Divider()

        Button(role: .destructive) {
            bookPendingDeletion = book
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    @ViewBuilder
    private func readerView(for book: Book) -> some View {
        switch book.format {
        case .pdf:
            PDFReaderView(book: book)
        case .epub, .cbz:
            EPUBReaderView(book: book)
        }
    }

    // MARK: Tags

    @ViewBuilder
    private var tagsSection: some View {
        if store.tags.isEmpty {
            emptyState(
                systemImage: "tag",
                title: "No tags yet",
                message: "Tags help you filter your library. Create your first one.",
                buttonTitle: "New Tag",
                action: { isShowingNewTagAlert = true }
            )
        } else {
            List {
                ForEach(sortedTags) { tag in
                    tagRow(tag)
                }
                .onDelete { offsets in
                    tagsPendingDeletion = offsets.compactMap {
                        sortedTags.indices.contains($0) ? sortedTags[$0] : nil
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    private func tagRow(_ tag: Tag) -> some View {
        HStack {
            Label(tag.name, systemImage: "tag")
            Spacer()
            Text("\(bookCount(tagged: tag))")
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .contextMenu {
            Button(role: .destructive) {
                tagsPendingDeletion = [tag]
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    // MARK: Series

    @ViewBuilder
    private var seriesSection: some View {
        if store.series.isEmpty {
            emptyState(
                systemImage: "square.stack.3d.up",
                title: "No series yet",
                message: "Group related books into a series to keep them together.",
                buttonTitle: "New Series",
                action: { isShowingNewSeriesAlert = true }
            )
        } else {
            List {
                ForEach(sortedSeries) { series in
                    seriesRow(series)
                }
                .onDelete { offsets in
                    seriesPendingDeletion = offsets.compactMap {
                        sortedSeries.indices.contains($0) ? sortedSeries[$0] : nil
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    private func seriesRow(_ series: Series) -> some View {
        HStack {
            Label(series.name, systemImage: "square.stack.3d.up")
            Spacer()
            Text("\(bookCount(in: series))")
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .contextMenu {
            Button(role: .destructive) {
                seriesPendingDeletion = [series]
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    // MARK: Empty / filtered-out states

    private func emptyState(
        systemImage: String,
        title: String,
        message: String,
        buttonTitle: String? = nil,
        action: (() -> Void)? = nil
    ) -> some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 44))
                .foregroundStyle(.tertiary)
            Text(title)
                .font(.title3.weight(.semibold))
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let buttonTitle, let action {
                Button(buttonTitle, action: action)
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }

    private var noMatchesState: some View {
        VStack(spacing: 10) {
            Image(systemName: "line.3.horizontal.decrease.circle")
                .font(.system(size: 40))
                .foregroundStyle(.tertiary)
            Text("No books match the selected filters")
                .font(.headline)
            Button("Clear Filters") {
                selectedTagIDs.removeAll()
                selectedSeriesIDs.removeAll()
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }

    // MARK: Actions & helpers

    private var filteredBooks: [Book] {
        guard !selectedTagIDs.isEmpty || !selectedSeriesIDs.isEmpty else {
            return store.books
        }
        return store.books.filter { book in
            let tagMatch = !selectedTagIDs.isDisjoint(with: book.tagIds)
            let seriesMatch = book.seriesId.map { selectedSeriesIDs.contains($0) } ?? false
            return tagMatch || seriesMatch
        }
    }

    private var sortedTags: [Tag] {
        store.tags.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private var sortedSeries: [Series] {
        store.series.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private func bookCount(tagged tag: Tag) -> Int {
        store.books.filter { $0.tagIds.contains(tag.id) }.count
    }

    private func bookCount(in series: Series) -> Int {
        store.books.filter { $0.seriesId == series.id }.count
    }

    private func toggleFilter(tagID id: String) {
        if selectedTagIDs.contains(id) {
            selectedTagIDs.remove(id)
        } else {
            selectedTagIDs.insert(id)
        }
    }

    private func toggleFilter(seriesID id: String) {
        if selectedSeriesIDs.contains(id) {
            selectedSeriesIDs.remove(id)
        } else {
            selectedSeriesIDs.insert(id)
        }
    }

    private func toggle(tag: Tag, on book: Book) {
        let updated: [String]
        if book.tagIds.contains(tag.id) {
            updated = book.tagIds.filter { $0 != tag.id }
        } else {
            updated = book.tagIds + [tag.id]
        }
        store.assign(bookId: book.id, seriesId: book.seriesId, tagIds: updated)
    }

    private func handleImport(result: Result<[URL], Error>) {
        guard case .success(let urls) = result, !urls.isEmpty else { return }
        isImporting = true
        Task {
            var accessed: [URL] = []
            for url in urls where url.startAccessingSecurityScopedResource() {
                accessed.append(url)
            }
            await store.importFiles(urls: urls)
            for url in accessed {
                url.stopAccessingSecurityScopedResource()
            }
            isImporting = false
        }
    }

    private func deletePendingBook() {
        guard let book = bookPendingDeletion else { return }
        store.deleteBooks(ids: [book.id])
        bookPendingDeletion = nil
    }

    private func deletePendingTags() {
        for tag in tagsPendingDeletion {
            store.deleteTag(id: tag.id)
        }
        selectedTagIDs.subtract(tagsPendingDeletion.map(\.id))
        tagsPendingDeletion = []
    }

    private func deletePendingSeries() {
        for series in seriesPendingDeletion {
            store.deleteSeries(id: series.id)
        }
        selectedSeriesIDs.subtract(seriesPendingDeletion.map(\.id))
        seriesPendingDeletion = []
    }
}

// MARK: - BookTile

/// A single cover tile in the books grid.
private struct BookTile: View {
    let book: Book
    let coverURL: URL?
    let onTap: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CoverImage(url: coverURL, title: book.title, tint: Self.tint(for: book.format))
                .aspectRatio(2.0 / 3.0, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .shadow(color: .black.opacity(0.16), radius: 4, x: 0, y: 2)

            Text(book.title)
                .font(.footnote.weight(.medium))
                .foregroundStyle(Color.primary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)

            Text(progressText)
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .padding(6)
        .background(
            Color.secondary.opacity(0.07),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .onTapGesture(perform: onTap)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(book.title), \(progressText) read")
        .accessibilityHint("Opens the book")
        .accessibilityAddTraits(.isButton)
    }

    private var progressText: String {
        "\(Int(book.progressPct.rounded()))%"
    }

    private static func tint(for format: BookFormat) -> Color {
        switch format {
        case .pdf: .orange
        case .epub: .indigo
        case .cbz: .teal
        }
    }
}
