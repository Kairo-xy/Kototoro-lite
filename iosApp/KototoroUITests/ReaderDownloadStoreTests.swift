import CryptoKit
import Foundation
import XCTest

final class ReaderDownloadStoreTests: XCTestCase {
    private let png = Data(base64Encoded:
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
    )!

    func testDamagedOfflinePageIsRepairedAndItsReplacementSurvivesReopening() async throws {
        let root = try directory()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source")
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        let original = ReaderDownloadStore.ImageRecord(path: String(repeating: "a", count: 64) + ".img",
                                                       hash: String(repeating: "a", count: 64), bytes: 3)
        let expected = ReaderDownloadStore.Manifest(pages: "opaque page context", images: [original])
        let store = ReaderDownloadStore()
        try await store.write(expected, in: root)
        try Data("bad".utf8).write(to: root.appendingPathComponent(original.path))
        let hash = SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined()
        let replacement = ReaderDownloadStore.ImageRecord(path: "\(hash).img", hash: hash, bytes: Int64(png.count))
        try png.write(to: source.appendingPathComponent(replacement.path))
        let repaired = try await store.repairImage(in: root, index: 0, expected: expected,
                                                  replacement: replacement, sourceDirectory: source)
        XCTAssertTrue(repaired)
        let reopened = try await ReaderDownloadStore().read(in: root)
        XCTAssertEqual(reopened?.images, [replacement])
        XCTAssertEqual(reopened?.pages, expected.pages)
        XCTAssertEqual(try SourceImageFile.validatedData(directory: root, relativePath: replacement.path,
                                                        sha256: hash, byteSize: replacement.bytes), png)
    }

    func testRepairRejectsAChangedPageListWithoutOverwritingIt() async throws {
        let root = try directory()
        defer { try? FileManager.default.removeItem(at: root) }
        let record = ReaderDownloadStore.ImageRecord(path: String(repeating: "b", count: 64) + ".img",
                                                     hash: String(repeating: "b", count: 64), bytes: 1)
        let store = ReaderDownloadStore()
        let old = ReaderDownloadStore.Manifest(pages: "old page context", images: [record])
        let current = ReaderDownloadStore.Manifest(pages: "new page context", images: [record])
        try await store.write(current, in: root)
        let repaired = try await store.repairImage(in: root, index: 0, expected: old,
                                                  replacement: record, sourceDirectory: root)
        XCTAssertFalse(repaired)
        let preserved = try await store.read(in: root)
        XCTAssertEqual(preserved?.pages, current.pages)
    }

    func testInvalidManifestIsMarkedAndAValidDownloadClearsTheMarker() async throws {
        let root = try directory()
        defer { try? FileManager.default.removeItem(at: root) }
        try Data("broken JSON".utf8).write(to: root.appendingPathComponent("chapter.json"))
        let store = ReaderDownloadStore()
        do {
            _ = try await store.read(in: root)
            XCTFail("Invalid metadata must fail before the reader falls back to the source")
        } catch { XCTAssertTrue(ReaderDownloadStore.isKnownInvalid(in: root)) }
        try await store.write(.init(pages: "valid archive", images: []), in: root)
        XCTAssertFalse(ReaderDownloadStore.isKnownInvalid(in: root))
    }

    func testCachedManifestDetectsAnEqualLengthAtomicReplacement() async throws {
        let root = try directory()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ReaderDownloadStore()
        try await store.write(.init(pages: "AAAA", images: []), in: root)
        let first = try await store.read(in: root)
        XCTAssertEqual(first?.pages, "AAAA")
        try JSONEncoder().encode(ReaderDownloadStore.Manifest(pages: "BBBB", images: []))
            .write(to: root.appendingPathComponent("chapter.json"), options: .atomic)
        let replacement = try await store.read(in: root)
        XCTAssertEqual(replacement?.pages, "BBBB")
    }

    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
