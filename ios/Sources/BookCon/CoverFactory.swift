//
//  CoverFactory.swift
//  BookCon
//
//  Offline cover generation for imported books.
//    * PDF  — renders page 0 via PDFKit into a bitmap whose longest side is <= 512pt.
//    * EPUB — pulls a raster cover image out of the EPUB container (a plain ZIP archive)
//             using a minimal hand-rolled ZIP reader. No third-party zip libraries.
//
//  Every path is defensive: malformed input returns nil, never traps.
//

import Foundation
import UIKit
import PDFKit

enum CoverFactory {

    /// Longest allowed cover side, in points.
    private static let maxCoverSide: CGFloat = 512

    // MARK: - PDF

    /// Renders the first page of the PDF at `fileURL`, preserving aspect ratio.
    /// Returns nil for missing, unreadable, or empty documents.
    static func makeCoverPDF(fileURL: URL) -> UIImage? {
        guard let document = PDFDocument(url: fileURL), document.pageCount > 0 else { return nil }
        guard let page = document.page(at: 0) else { return nil }

        let pageRect = page.bounds(for: .mediaBox)
        guard pageRect.width.isFinite, pageRect.height.isFinite,
              pageRect.width > 0, pageRect.height > 0 else { return nil }

        // Fit within maxCoverSide x maxCoverSide; never upscale small pages.
        let longestSide = max(pageRect.width, pageRect.height)
        let fitScale = min(maxCoverSide / longestSide, 1)
        let targetSize = CGSize(
            width: (pageRect.width * fitScale).rounded(.down),
            height: (pageRect.height * fitScale).rounded(.down)
        )
        guard targetSize.width >= 1, targetSize.height >= 1 else { return nil }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 2        // fixed 2x bitmap: crisp and identical on every device
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)

        return renderer.image { ctx in
            let cg = ctx.cgContext
            UIColor.white.setFill()
            cg.fill(CGRect(origin: .zero, size: targetSize))

            cg.saveGState()
            // Map PDF user space (origin bottom-left) onto the bitmap (origin top-left)
            // so the full mediaBox exactly fills the canvas.
            cg.translateBy(x: 0, y: targetSize.height)
            cg.scaleBy(x: targetSize.width / pageRect.width,
                       y: targetSize.height / pageRect.height)
            page.draw(with: .mediaBox, to: cg)
            cg.restoreGState()
        }
    }

    // MARK: - EPUB

    /// Extracts a raster cover image from an EPUB file (a ZIP archive).
    /// Preference order:
    ///   1. An image entry whose path contains "cover" (.png/.jpg/.jpeg, case-insensitive).
    ///   2. Otherwise the first usable image entry in the archive.
    /// Parsing META-INF/container.xml → OPF → cover metadata is intentionally skipped;
    /// the heuristic above covers the vast majority of real-world EPUBs.
    static func makeCoverEPUB(fileURL: URL) -> UIImage? {
        guard let archive = try? Data(contentsOf: fileURL), archive.count > 4 else { return nil }
        guard let eocdOffset = endOfCentralDirectoryOffset(in: archive) else { return nil }
        guard let entries = centralDirectoryEntries(in: archive, eocdOffset: eocdOffset),
              !entries.isEmpty else { return nil }
        guard let entry = pickCoverEntry(from: entries) else { return nil }
        guard let imageData = extractImageData(entry, from: archive) else { return nil }
        return UIImage(data: imageData)
    }

    // MARK: - PNG persistence

    /// Writes `image` as PNG to `url` atomically. Silent no-op on failure;
    /// callers detect success via the resulting file (or `FileManager.fileExists`).
    static func savePNG(_ image: UIImage, to url: URL) {
        guard let pngData = image.pngData() else { return }
        try? pngData.write(to: url, options: [.atomic])
    }

    /// Loads a UIImage previously written by `savePNG(from:)`. Returns nil if missing or corrupt.
    static func loadPNG(from url: URL) -> UIImage? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data)
    }

    // MARK: - Minimal ZIP reader
    //
    // Hand-rolled reader for the subset of ZIP that EPUB requires:
    //   1. Locate the End-of-Central-Directory (EOCD) record by scanning backwards over the
    //      trailing 64 KB (that window also covers archive comments).
    //   2. Walk the Central Directory records (one per archive member) to learn each member's
    //      name, compression method, and sizes.
    //   3. Resolve the member's Local File Header to find where its payload actually starts
    //      (local filename/extra lengths can differ from the Central Directory copies).
    //   4. Support compression method 0 (store) and 8 (raw DEFLATE); anything else fails cleanly.
    // All reads are bounds-checked; malformed archives yield nil.

    /// One Central Directory record's worth of metadata for a single archive member.
    private struct ZipEntry {
        var name: String
        var compressionMethod: UInt16
        var compressedSize: Int
        var declaredUncompressedSize: Int
        var localHeaderOffset: Int
    }

    private static let eocdSignature: UInt32             = 0x06054b50
    private static let centralDirectorySignature: UInt32 = 0x02014b50
    private static let localFileHeaderSignature: UInt32  = 0x04034b50

    /// Fixed-size fields of the record types we parse.
    private static let eocdMinSize = 22
    private static let centralDirectoryRecordSize = 46
    private static let localFileHeaderSize = 30

    /// Extensions accepted as raster cover candidates.
    private static let coverImageExtensions: Set<String> = ["png", "jpg", "jpeg"]

    /// Byte offset of the EOCD record, searching at most the trailing 64 KB.
    private static func endOfCentralDirectoryOffset(in data: Data) -> Int? {
        guard data.count >= eocdMinSize else { return nil }
        let windowStart = max(0, data.count - 64 * 1024)
        var offset = data.count - eocdMinSize
        while offset >= windowStart {
            if u32(data, offset) == eocdSignature { return offset }
            offset -= 1
        }
        return nil
    }

    /// Parses every Central Directory record reachable from the EOCD pointers.
    /// Returns nil for ZIP64 archives (0xFFFF / 0xFFFFFFFF marker values) — out of scope.
    private static func centralDirectoryEntries(in data: Data, eocdOffset: Int) -> [ZipEntry]? {
        guard let totalEntries = u16(data, eocdOffset + 10),
              let directorySize = u32(data, eocdOffset + 12),
              let directoryOffset = u32(data, eocdOffset + 16) else { return nil }

        guard totalEntries > 0, totalEntries != 0xFFFF,
              directoryOffset != 0xFFFFFFFF, directorySize != 0xFFFFFFFF else { return nil }

        let directoryEnd = min(data.count, Int(directoryOffset) + Int(directorySize))
        var cursor = Int(directoryOffset)

        var entries: [ZipEntry] = []
        entries.reserveCapacity(Int(totalEntries))

        for _ in 0..<Int(totalEntries) {
            guard cursor + centralDirectoryRecordSize <= directoryEnd,
                  u32(data, cursor) == centralDirectorySignature else { break }
            guard let method = u16(data, cursor + 10),
                  let compressedSize = u32(data, cursor + 20),
                  let uncompressedSize = u32(data, cursor + 24),
                  let nameLength = u16(data, cursor + 28),
                  let extraLength = u16(data, cursor + 30),
                  let commentLength = u16(data, cursor + 32),
                  let localHeaderOffset = u32(data, cursor + 42) else { break }

            let nameStart = cursor + centralDirectoryRecordSize
            let nameEnd = nameStart + Int(nameLength)
            guard nameEnd <= data.count, localHeaderOffset != 0xFFFFFFFF else { break }

            let nameData = data.subdata(in: nameStart..<nameEnd)
            let name = String(data: nameData, encoding: .utf8)
                ?? String(data: nameData, encoding: .isoLatin1)
                ?? ""

            entries.append(ZipEntry(
                name: name,
                compressionMethod: method,
                compressedSize: Int(compressedSize),
                declaredUncompressedSize: Int(uncompressedSize),
                localHeaderOffset: Int(localHeaderOffset)
            ))

            cursor = nameEnd + Int(extraLength) + Int(commentLength)
        }
        return entries
    }

    /// Picks the most plausible cover candidate among the archive's image entries.
    private static func pickCoverEntry(from entries: [ZipEntry]) -> ZipEntry? {
        func isCandidate(_ entry: ZipEntry) -> Bool {
            guard entry.compressedSize > 0 else { return false }
            let name = entry.name
            // Skip directory stubs and macOS resource-fork noise.
            guard !name.hasSuffix("/"), !name.contains("__MACOSX") else { return false }
            let ext = (name as NSString).pathExtension.lowercased()
            return coverImageExtensions.contains(ext)
        }

        let candidates = entries.filter(isCandidate)
        // 1) Anything that calls itself a cover.
        if let coverNamed = candidates.first(where: { $0.name.lowercased().contains("cover") }) {
            return coverNamed
        }
        // 2) Fallback: the first usable image entry in archive order.
        return candidates.first
    }

    /// Locates and decodes one member's payload according to its compression method.
    private static func extractImageData(_ entry: ZipEntry, from data: Data) -> Data? {
        guard entry.localHeaderOffset >= 0,
              entry.localHeaderOffset + localFileHeaderSize <= data.count,
              u32(data, entry.localHeaderOffset) == localFileHeaderSignature else { return nil }

        // The Local File Header repeats the filename/extra lengths; they can differ from the
        // Central Directory copies, so always use the local ones to reach the payload.
        guard let localNameLength = u16(data, entry.localHeaderOffset + 26),
              let localExtraLength = u16(data, entry.localHeaderOffset + 28) else { return nil }

        let payloadStart = entry.localHeaderOffset + localFileHeaderSize
            + Int(localNameLength) + Int(localExtraLength)
        guard entry.compressedSize > 0,
              payloadStart + entry.compressedSize <= data.count else { return nil }
        let payload = data.subdata(in: payloadStart..<(payloadStart + entry.compressedSize))

        switch entry.compressionMethod {
        case 0:
            return payload                                   // stored, uncompressed
        case 8:
            return inflate(payload, expecting: entry.declaredUncompressedSize)
        default:
            return nil                                       // bzip2/LZMA/encrypted: unsupported
        }
    }

    /// Inflates a raw DEFLATE stream — the flavor ZIP uses (no zlib 2-byte header, no trailer).
    /// `COMPRESSION_ZLIB` in the Compression framework operates on exactly this raw form, and
    /// `NSData.decompressed(using:)` (iOS 13+) wraps it without manual buffer management.
    private static func inflate(_ payload: Data, expecting declaredSize: Int) -> Data? {
        guard let inflated = try? (payload as NSData).decompressed(using: .zlib) else { return nil }
        let result = inflated as Data
        guard !result.isEmpty else { return nil }
        // Soft plausibility valve only; UIImage(data:) performs the authoritative validation.
        if declaredSize > 0, result.count > declaredSize * 2 { return nil }
        return result
    }

    // MARK: - Little-endian readers

    /// Bounds-checked little-endian UInt16 read; byte-wise assembly avoids alignment concerns.
    private static func u16(_ data: Data, _ offset: Int) -> UInt16? {
        guard offset >= 0, offset + 2 <= data.count else { return nil }
        let b = [UInt8](data.subdata(in: offset..<(offset + 2)))
        guard b.count == 2 else { return nil }
        return UInt16(b[1]) << 8 | UInt16(b[0])
    }

    /// Bounds-checked little-endian UInt32 read.
    private static func u32(_ data: Data, _ offset: Int) -> UInt32? {
        guard offset >= 0, offset + 4 <= data.count else { return nil }
        let b = [UInt8](data.subdata(in: offset..<(offset + 4)))
        guard b.count == 4 else { return nil }
        return UInt32(b[3]) << 24 | UInt32(b[2]) << 16 | UInt32(b[1]) << 8 | UInt32(b[0])
    }
}
