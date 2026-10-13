import XCTest

final class ReaderChapterPullTests: XCTestCase {
    func testForwardPullReleasesOnlyOnce() {
        var pull = ReaderChapterPull()
        pull.begin(chapterID: "one", atStart: false, atEnd: true, enabled: true)
        pull.update(offset: 460, limit: 400, reversed: false, enabled: true)
        XCTAssertEqual(pull.release(chapterID: "one", enabled: true, extent: 400), .next)
        XCTAssertNil(pull.release(chapterID: "one", enabled: true, extent: 400))
    }

    func testRtlReadingUsesPhysicalOffsetsForBothBoundaries() {
        var pull = ReaderChapterPull()
        pull.begin(chapterID: "one", atStart: false, atEnd: true, enabled: true)
        pull.update(offset: -60, limit: 400, reversed: true, enabled: true)
        XCTAssertEqual(pull.release(chapterID: "one", enabled: true, extent: 400), .next)
        pull.begin(chapterID: "one", atStart: true, atEnd: false, enabled: true)
        pull.update(offset: 460, limit: 400, reversed: true, enabled: true)
        XCTAssertEqual(pull.release(chapterID: "one", enabled: true, extent: 400), .previous)
    }

    func testShortChapterCanPullInEitherDirection() {
        var pull = ReaderChapterPull()
        for offset in [CGFloat(-100), CGFloat(100)] {
            pull.begin(chapterID: "short", atStart: true, atEnd: true, enabled: true)
            pull.update(offset: offset, limit: 0, reversed: false, enabled: true)
            XCTAssertEqual(pull.release(chapterID: "short", enabled: true, extent: 800),
                           offset < 0 ? .previous : .next)
        }
    }

    func testRetractionBeforeReleaseCancelsNavigation() {
        var pull = ReaderChapterPull()
        pull.begin(chapterID: "one", atStart: false, atEnd: true, enabled: true)
        pull.update(offset: 500, limit: 400, reversed: false, enabled: true)
        pull.update(offset: 410, limit: 400, reversed: false, enabled: true)
        XCTAssertNil(pull.release(chapterID: "one", enabled: true, extent: 400))
    }

    func testNormalPagingToTheBoundaryDoesNotCountAsAnotherChapterPull() {
        var pull = ReaderChapterPull()
        pull.begin(chapterID: "one", atStart: false, atEnd: false, enabled: true)
        pull.update(offset: 500, limit: 400, reversed: false, enabled: true)
        XCTAssertNil(pull.release(chapterID: "one", enabled: true, extent: 400))
    }

    func testProgrammaticAndInertialOffsetsCannotStartAPull() {
        var pull = ReaderChapterPull()
        pull.update(offset: 500, limit: 400, reversed: false, enabled: true)
        XCTAssertNil(pull.release(chapterID: "one", enabled: true, extent: 400))
    }

    func testZoomOrLoadingCancelsAnExistingPull() {
        var pull = ReaderChapterPull()
        pull.begin(chapterID: "one", atStart: true, atEnd: true, enabled: true)
        pull.update(offset: -100, limit: 0, reversed: false, enabled: false)
        XCTAssertNil(pull.release(chapterID: "one", enabled: true, extent: 400))
        pull.begin(chapterID: "one", atStart: true, atEnd: true, enabled: false)
        pull.update(offset: 100, limit: 0, reversed: false, enabled: true)
        XCTAssertFalse(pull.isTracking)
    }

    func testChapterReplacementInvalidatesThePull() {
        var pull = ReaderChapterPull()
        pull.begin(chapterID: "old", atStart: false, atEnd: true, enabled: true)
        pull.update(offset: 500, limit: 400, reversed: false, enabled: true)
        XCTAssertNil(pull.release(chapterID: "new", enabled: true, extent: 400))
    }

    func testCustomPagerRequiresActualTravelAtItsFirstOrLastSlot() {
        let threshold = ReaderChapterPull.threshold(extent: 400)
        XCTAssertNil(ReaderChapterPull.resolve(distance: 10, atStart: false, atEnd: true, threshold: threshold))
        XCTAssertEqual(ReaderChapterPull.resolve(distance: 100, atStart: false, atEnd: true, threshold: threshold), .next)
        XCTAssertEqual(ReaderChapterPull.resolve(distance: -100, atStart: true, atEnd: false, threshold: threshold),
                       .previous)
    }
}
