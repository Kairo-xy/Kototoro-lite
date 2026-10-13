import CryptoKit
import Foundation
import XCTest

final class SourceImageFileTests: XCTestCase {
    func testLongPageTilePlacementScalesSourcePixelsAndClipsSamplingGutter() {
        let layout = SourceImageFile.tileLayout(
            logical: CGRect(x: 0, y: 2048, width: 2000, height: 2048),
            decoded: CGRect(x: 0, y: 2016, width: 2000, height: 2112),
            sourceSize: CGSize(width: 2000, height: 12000),
            displaySize: CGSize(width: 500, height: 3000)
        )
        XCTAssertEqual(layout.frame, CGRect(x: 0, y: 512, width: 500, height: 512))
        XCTAssertEqual(layout.decodedSize, CGSize(width: 500, height: 528))
        XCTAssertEqual(layout.decodedOffset, CGSize(width: 0, height: -8))
    }

    func testDecodeAndRejectTamperedArtifacts() throws {
        let files = FileManager.default
        let directory = files.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? files.removeItem(at: directory) }
        let png = Data(base64Encoded:
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
        )!
        let hash = SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined()
        let name = "\(hash).img"
        let file = directory.appendingPathComponent(name)
        try png.write(to: file)
        let decoded = try SourceImageFile.decode(directory: directory, relativePath: name,
                                                sha256: hash, byteSize: Int64(png.count))
        XCTAssertEqual(decoded.size.width, 1)
        XCTAssertEqual(decoded.size.height, 1)
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: "../\(name)",
                                                       sha256: hash, byteSize: Int64(png.count)))
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: name,
                                                       sha256: hash, byteSize: Int64(png.count + 1)))
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: name,
                                                       sha256: hash, byteSize: 64 * 1024 * 1024 + 1))
        let html = Data("<html>error</html>".utf8)
        let htmlHash = SHA256.hash(data: html).map { String(format: "%02x", $0) }.joined()
        try html.write(to: directory.appendingPathComponent("\(htmlHash).img"))
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: "\(htmlHash).img",
                                                       sha256: htmlHash, byteSize: Int64(html.count)))
        var tampered = png
        tampered[tampered.count - 1] ^= 1
        try tampered.write(to: file)
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: name,
                                                       sha256: hash, byteSize: Int64(png.count)))
        try files.removeItem(at: file)
        let outside = directory.appendingPathComponent("outside.png")
        try png.write(to: outside)
        try files.createSymbolicLink(at: file, withDestinationURL: outside)
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: name,
                                                       sha256: hash, byteSize: Int64(png.count)))
    }

    func testDimensionsRegionSplitAndPixelSampling() throws {
        let files = FileManager.default
        let directory = files.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? files.removeItem(at: directory) }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 240, height: 120), format: format)
        let wideImage = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 240, height: 120))
            UIColor.black.setFill()
            context.fill(CGRect(x: 24, y: 12, width: 192, height: 96))
        }
        let png = try XCTUnwrap(wideImage.pngData())
        let hash = SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined()
        let name = "\(hash).img"
        try png.write(to: directory.appendingPathComponent(name))

        let dims = try SourceImageFile.dimensions(directory: directory, relativePath: name,
                                                  sha256: hash, byteSize: Int64(png.count))
        XCTAssertEqual(dims.width, 240)
        XCTAssertEqual(dims.height, 120)

        let region = try SourceImageFile.decodeRegion(
            directory: directory,
            relativePath: name,
            sha256: hash,
            byteSize: Int64(png.count),
            rect: CGRect(x: 20, y: 10, width: 100, height: 60),
            sampleSize: 2
        )
        XCTAssertEqual(Int(region.size.width), 50)
        XCTAssertEqual(Int(region.size.height), 30)

        let leftHalf = SourceImageFile.splitPage(wideImage, segment: 1)
        let rightHalf = SourceImageFile.splitPage(wideImage, segment: 2)
        XCTAssertEqual(Int(leftHalf.size.width), 120)
        XCTAssertEqual(Int(leftHalf.size.height), 120)
        XCTAssertEqual(Int(rightHalf.size.width), 120)
        XCTAssertEqual(Int(rightHalf.size.height), 120)

        let cropped = SourceImageFile.crop(wideImage, rect: CGRect(x: 24, y: 12, width: 192, height: 96))
        XCTAssertEqual(Int(cropped.size.width), 192)
        XCTAssertEqual(Int(cropped.size.height), 96)

        let sampled = try XCTUnwrap(SourceImageFile.samplePixels(wideImage, sampleSize: 2))
        XCTAssertEqual(sampled.width, 120)
        XCTAssertEqual(sampled.height, 60)
        XCTAssertEqual(sampled.pixels.first, -1)
        let centerPixel = sampled.pixels[30 * sampled.width + 60]
        XCTAssertEqual(UInt32(bitPattern: centerPixel) & 0x00FFFFFF, 0)
    }
}
