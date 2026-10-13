import XCTest
import UIKit

/// Local works through the document picker: `Scripts/stage-picker-fixtures.py` places a two-page CBZ and a GB18030
/// TXT novel in "On My iPhone"; both are imported from the 本地作品 sources and open in the readers.
final class LocalImportTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    @MainActor
    func testPagedWindowChangesChapterAndStopsAtTheBookEnds() throws {
        openChapterWindowFixture()
        selectReaderMode("默认（从左到右）")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 2)
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("01"))
        swipeAcrossChapter(canvas, forward: true)
        waitForReaderChapter("02")
        swipeAcrossChapter(canvas, forward: true)
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("02"))
        swipeAcrossChapter(canvas, forward: false)
        waitForReaderChapter("01")
        swipeAcrossChapter(canvas, forward: false)
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("01"))
        attach("普通分页跨章与首末章停止")
    }

    private func openChapterWindowFixture() {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        let first = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"
        )).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        first.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
    }

    @MainActor
    func testPagedCrossChapterBookmarkKeepsChapterLocalPage() throws {
        openChapterWindowFixture()
        selectReaderMode("默认（从左到右）")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 2)
        swipeAcrossChapter(canvas, forward: true)
        waitForReaderChapter("02")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
        XCTAssertEqual(app.staticTexts["reader.totalPages"].value as? String, "1")
        let bookmark = app.buttons["reader.bookmark"]
        if bookmark.value as? String == "已添加" { bookmark.tap() }
        bookmark.tap()
        let saved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            bookmark.value as? String == "已添加"
        }, object: nil)
        wait(for: [saved], timeout: 10)
        swipeAcrossChapter(canvas, forward: false)
        waitForReaderChapter("01")
        app.buttons["reader.chapters"].tap()
        app.segmentedControls.buttons["书签"].tap()
        let entry = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "02", "第 1 页"))
            .firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 10), app.debugDescription)
        entry.tap()
        waitForReaderChapter("02")
        app.buttons["reader.back"].tap()
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 15))
        app.terminate()
    }

    /// Run after the bookmark case: its completed navigation saves chapter 02 to the real library database.
    @MainActor
    func testPagedCrossChapterHistoryReopensWithTheMatchingImage() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 15))
        app.buttons["details.resume"].tap()
        waitForReaderChapter("02")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
        attach("跨章重启续读的实际图片和章内页码")
    }

    @MainActor
    func testRtlPagedAndAnimatedWindowsChangeChapter() throws {
        openChapterWindowFixture()
        for animation in ["禁用", "高级", "仿真"] {
            selectReaderMode("从右到左")
            selectReaderAnimation(animation)
            let canvas = animation == "禁用" ? app.scrollViews["reader.canvas"]
                : app.descendants(matching: .any)["reader.turnPager"].firstMatch
            XCTAssertTrue(canvas.waitForExistence(timeout: 10))
            waitForChapterWindow(canvas, count: 2)
            swipeAcrossChapter(canvas, forward: true, reversed: true)
            waitForReaderChapter("02")
            swipeAcrossChapter(canvas, forward: false, reversed: true)
            waitForReaderChapter("01")
        }
        attach("RTL 原生高级仿真分页跨章")
    }

    @MainActor
    func testVerticalPagedWindowChangesChapterWithTheMatchingImage() throws {
        openChapterWindowFixture()
        selectReaderMode("从上到下")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 2)
        swipeAcrossChapter(canvas, forward: true, vertical: true)
        waitForReaderChapter("02")
        swipeAcrossChapter(canvas, forward: false, vertical: true)
        waitForReaderChapter("01")
        attach("纵向分页普通跨章与实际图片")
    }

    @MainActor
    func testPagedWindowTrimsNeighborsAndKeepsTheDisplayedChapter() throws {
        importFile(source: "LOCAL", file: "fixture_reader_window", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("默认（从左到右）")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 5)
        for chapter in ["02", "03"] {
            app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
            let lastPage = app.images.matching(NSPredicate(
                format: "identifier == %@ AND label CONTAINS %@", "reader.image", "第 3 页"
            )).firstMatch
            let atLastPage = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                self.app.staticTexts["reader.currentPage"].value as? String == "3" && lastPage.isHittable
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [atLastPage], timeout: 10), .completed,
                           "\(canvas.value ?? "no position")\n\(canvas.debugDescription)")
            swipeAcrossChapter(canvas, forward: true)
            waitForReaderChapter(chapter)
            XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
            XCTAssertEqual(app.staticTexts["reader.totalPages"].value as? String, "3")
        }
        swipeAcrossChapter(canvas, forward: false)
        waitForReaderChapter("02")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "3")
        attach("三章窗口裁剪保留实际画面和章内页码")
    }

    /// Continuous strips join complete neighbor chapters: plain scrolling enters 02 and 03 and returns to 02 without
    /// an explicit chapter switch, and the window stays bounded to three chapters.
    @MainActor
    func testContinuousStripScrollsIntoNeighborChapters() throws {
        importFile(source: "LOCAL", file: "fixture_reader_window", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 6)
        for chapter in ["02", "03"] {
            scrollStrip(canvas, until: chapter, forward: true)
            waitForReaderChapter(chapter)
            XCTAssertEqual(app.staticTexts["reader.totalPages"].value as? String, "3")
        }
        waitForChapterWindow(canvas, count: 6)
        scrollStrip(canvas, until: "02", forward: false)
        // The first visible page reads: 02's last page at the top while 03 still fills the middle of the screen.
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("02"))
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "3")
        waitForChapterWindow(canvas, count: 9)
        dragStrip(canvas, forward: false)
        waitForReaderChapter("02")
        attach("连续条漫普通滚动跨章")
    }

    /// 02 is shorter than the screen, so it never becomes the first visible page: scrolling to the strip's end reads it.
    @MainActor
    func testContinuousStripEndEntersAShortNextChapter() throws {
        importFile(source: "LOCAL", file: "fixture_reader_pull_long", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 3)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        let lastPage = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.staticTexts["reader.currentPage"].value as? String == "2"
        }, object: nil)
        wait(for: [lastPage], timeout: 10)
        for _ in 0..<12 where !app.buttons["reader.chapters"].label.contains("02") {
            canvas.swipeUp(velocity: .fast)
            if !app.buttons["reader.more"].exists { canvas.tap() }
        }
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("02"), "\(canvas.value ?? "no position")")
        waitForReaderChapter("02")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
        XCTAssertFalse(app.buttons["reader.nextChapter"].isEnabled)
        attach("条漫末端进入短邻章")
    }

    private func scrollStrip(_ canvas: XCUIElement, until chapter: String, forward: Bool) {
        for _ in 0..<12 where !app.buttons["reader.chapters"].label.contains(chapter) { dragStrip(canvas, forward: forward) }
    }

    private func dragStrip(_ canvas: XCUIElement, forward: Bool) {
        let start = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: forward ? 0.7 : 0.3))
        let end = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: forward ? 0.3 : 0.7))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.1)
        if !app.buttons["reader.more"].exists { canvas.tap() }
    }

    @MainActor
    func testPreviousChapterButtonOpensTheChapterStart() throws {
        importFile(source: "LOCAL", file: "fixture_reader_pull_long", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "02"
        )).firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        app.buttons["reader.previousChapter"].tap()
        waitForReaderChapter("01")
        let tail = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas)) < 5 && self.app.staticTexts["reader.currentPage"].value as? String == "1"
                && self.app.images.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@",
                                                       "reader.image", "第 1 页")).firstMatch.isHittable
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [tail], timeout: 15), .completed,
                       "\(canvas.value ?? "no position")\n\(canvas.debugDescription)")
        attach("上一章按钮与 Android 一致打开章首")
        app.buttons["reader.nextChapter"].tap()
        waitForReaderChapter("02")
    }

    private func swipeAcrossChapter(_ canvas: XCUIElement, forward: Bool, vertical: Bool = false, reversed: Bool = false) {
        if vertical {
            if forward { canvas.swipeUp() } else { canvas.swipeDown() }
        } else if forward != reversed { canvas.swipeLeft() }
        else { canvas.swipeRight() }
        XCTAssertTrue(canvas.waitForExistence(timeout: 30))
        if !app.buttons["reader.more"].exists { canvas.tap() }
    }

    private func waitForChapterWindow(_ canvas: XCUIElement, count: Int) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (canvas.value as? String)?.contains("窗口 \(count) 页") == true
        }, object: nil)
        wait(for: [ready], timeout: 30)
        XCTAssertFalse(app.staticTexts["reader.chapterPull"].exists)
    }

    private func waitForReaderChapter(_ chapter: String) {
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.buttons["reader.chapters"].exists && self.app.buttons["reader.chapters"].label.contains(chapter)
        }, object: nil)
        wait(for: [changed], timeout: 15)
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        let correctImage = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readerImageMatchesChapter(chapter)
        }, object: nil)
        wait(for: [correctImage], timeout: 10)
    }

    /// These immutable CBZ fixtures use red for chapter 01 and blue for chapter 02.
    /// Verifies displayed pixels as well as metadata, including when a lazy pager restores an offscreen slot.
    private func readerImageMatchesChapter(_ chapter: String) -> Bool {
        return autoreleasepool {
            guard let image = app.screenshot().image.cgImage,
                  let pixel = image.cropping(to: CGRect(x: image.width / 2, y: image.height / 2, width: 1, height: 1)) else {
                return false
            }
            var bytes = [UInt8](repeating: 0, count: 4)
            let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
                guard let context = CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8,
                                              bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
                context.draw(pixel, in: CGRect(x: 0, y: 0, width: 1, height: 1))
                return true
            }
            guard drawn else { return false }
            if chapter == "03" { return Int(bytes[1]) > Int(bytes[0]) + 50 && Int(bytes[1]) > Int(bytes[2]) + 50 }
            return chapter == "02" ? Int(bytes[2]) > Int(bytes[0]) + 50 : Int(bytes[0]) > Int(bytes[2]) + 50
        }
    }

    private func selectReaderAnimation(_ animation: String) {
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "翻页效果")).firstMatch.tap()
        app.buttons[animation].tap()
        app.buttons["reader.panel.close"].tap()
    }

    @MainActor
    func testImportedComicOpensInTheReader() throws {
        importFile(source: "LOCAL", file: "fixture_comic")
        openFirstChapter(title: "fixture_comic")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        attach("本地漫画阅读")
    }

    @MainActor
    func testPrepareDownloadedReaderFixture() throws {
        importFile(source: "LOCAL", file: "fixture_reader_download", reuse: true)
        openFirstChapter(title: "fixture_reader_download")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["下载本章"].tap()
        XCTAssertTrue(app.staticTexts["本章已下载，可离线阅读"].waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["reader.panel.close"].tap()
        app.buttons["reader.back"].tap()
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 10))
        app.terminate()
    }

    /// The host may corrupt this fixture's offline copy between tests; the original imported CBZ remains intact.
    @MainActor
    func testDownloadedReaderFixtureReopensAfterImageRepair() throws {
        importFile(source: "LOCAL", file: "fixture_reader_download", reuse: true)
        openFirstChapter(title: "fixture_reader_download")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.staticTexts["reader.pageError.0"].exists)
        app.buttons["reader.back"].tap()
        app.terminate()
    }

    @MainActor
    func testContinuousAutoScrollAdvancesToTheNextChapter() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        let first = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"
        )).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 10), app.debugDescription)
        first.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["自动翻页"].tap()
        app.buttons["reader.panel.close"].tap()
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.buttons["reader.chapters"].label.contains("02")
        }, object: nil)
        wait(for: [advanced], timeout: 15)
        XCTAssertTrue(app.images["reader.image"].firstMatch.exists)
        XCTAssertFalse(app.buttons["reader.nextChapter"].isEnabled)
        attach("条漫自动跨章")
    }

    @MainActor
    func testDisplayPreferenceAndBackgroundPreserveTheLongPagePosition() throws {
        importFile(source: "LOCAL", file: "fixture_reader_long", reuse: true)
        openFirstChapter(title: "fixture_reader_long")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        canvas.swipeUp()
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.readingOffset(canvas) > 300 },
                                              object: nil)
        wait(for: [moved], timeout: 10)
        let saved = readingOffset(canvas)
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["控件"].tap()
        let keepScreenOn = app.switches["reader.option.keepScreenOn"]
        XCTAssertTrue(keepScreenOn.waitForExistence(timeout: 10), app.debugDescription)
        keepScreenOn.tap()
        app.buttons["reader.panel.close"].tap()
        XCTAssertEqual(readingOffset(canvas), saved, accuracy: 5)
        XCUIDevice.shared.press(.home)
        // Allow the background save to complete; terminate checks persistence rather than retained view state.
        Thread.sleep(forTimeInterval: 1)
        app.terminate(); app.launch()
        importFile(source: "LOCAL", file: "fixture_reader_long", reuse: true)
        let resume = app.buttons["details.resume"]
        XCTAssertTrue(resume.waitForExistence(timeout: 30), app.debugDescription)
        resume.tap()
        XCTAssertTrue(canvas.waitForExistence(timeout: 30), app.debugDescription)
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - saved) < 5
        }, object: nil)
        wait(for: [restored], timeout: 15)
        attach("显示设置与后台续读保留长页位置")
    }

    @MainActor
    func testHorizontalContinuousDirectionZoomAndBookmarkRestore() throws {
        importFile(source: "LOCAL", file: "fixture_reader_horizontal", reuse: true)
        openFirstChapter(title: "fixture_reader_horizontal")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("横向条漫（连续）")
        setHorizontalReversed(false)
        // Reused fixtures may already be halfway through page 1; selecting its unchanged slider value is a no-op.
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "2")
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        let canvas = app.scrollViews["reader.canvas"]
        let start = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas)) < 5
        }, object: nil)
        wait(for: [start], timeout: 10)
        canvas.swipeLeft()
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > 300
        }, object: nil)
        wait(for: [moved], timeout: 10)
        let beforeDirection = readingOffset(canvas)
        setHorizontalReversed(true)
        let preserved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - beforeDirection) < 5
        }, object: nil)
        wait(for: [preserved], timeout: 10)
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5)).tap()
        let advancedByTap = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > beforeDirection + 100
        }, object: nil)
        wait(for: [advancedByTap], timeout: 10)
        // The page turn hides chrome; keep both pinch contacts inside the reading surface.
        canvas.pinch(withScale: 2, velocity: 1)
        let zoomed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingZoom(canvas) > 120
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [zoomed], timeout: 10), .completed, canvas.debugDescription)
        let top = app.images["reader.image"].firstMatch.frame.minY
        canvas.swipeUp()
        let panned = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.images["reader.image"].firstMatch.frame.minY < top - 100
        }, object: nil)
        wait(for: [panned], timeout: 10)
        let saved = readingOffset(canvas)
        canvas.tap()
        let bookmark = app.buttons["reader.bookmark"]
        if bookmark.value as? String == "已添加" { bookmark.tap() }
        bookmark.tap()
        canvas.swipeRight()
        let farther = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > saved + 100
        }, object: nil)
        wait(for: [farther], timeout: 10)
        app.buttons["reader.chapters"].tap()
        app.segmentedControls.buttons["书签"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "第 1 页")).firstMatch.tap()
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - saved) < 5
        }, object: nil)
        wait(for: [restored], timeout: 10)
        attach("横向反向缩放与书签恢复")
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["切换方向"].tap()
        app.segmentedControls.buttons["布局"].tap()
        let direction = app.switches["reader.option.horizontalReversed"]
        XCTAssertTrue(direction.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(direction.value as? String, "0")
        app.buttons["reader.panel.close"].tap()
        let keptPosition = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - saved) < 5 && self.readingZoom(canvas) > 120
        }, object: nil)
        wait(for: [keptPosition], timeout: 10)
        attach("横向快捷方向保留连续模式与缩放")
    }

    @MainActor
    func testHorizontalReverseAutoScrollAdvancesToTheNextChapter() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        let first = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"
        )).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        first.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("横向条漫（连续）")
        setHorizontalReversed(true)
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.sliders.firstMatch.adjust(toNormalizedSliderPosition: 1)
        app.buttons["自动翻页"].tap()
        app.buttons["reader.panel.close"].tap()
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.buttons["reader.chapters"].label.contains("02")
        }, object: nil)
        wait(for: [advanced], timeout: 20)
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        attach("横向反向自动跨章")
    }

    private func setHorizontalReversed(_ enabled: Bool) {
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        let toggle = app.switches["reader.option.horizontalReversed"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5), app.debugDescription)
        if (toggle.value as? String == "1") != enabled { toggle.tap() }
        app.buttons["reader.panel.close"].tap()
    }

    private func readingZoom(_ canvas: XCUIElement) -> Int {
        let value = canvas.value as? String ?? ""
        return Int(value.components(separatedBy: "缩放 ").last?.components(separatedBy: "%").first ?? "") ?? -1
    }

    @MainActor
    func testNeighbourFailureDoesNotHideReadablePageAndRetriesTheFailedPage() throws {
        importFile(source: "LOCAL", file: "fixture_reader_error", reuse: true)
        openFirstChapter(title: "fixture_reader_error")
        let image = app.images["reader.image"].firstMatch
        XCTAssertTrue(image.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(image.isHittable)
        XCTAssertFalse(app.staticTexts["reader.error"].exists)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        let retry = app.buttons["reader.pageRetry.1"]
        XCTAssertTrue(retry.waitForExistence(timeout: 30), app.debugDescription)
        retry.tap()
        XCTAssertTrue(retry.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.buttons["reader.pageRetry.0"].exists)
        XCTAssertFalse(app.staticTexts["reader.error"].exists)
        attach("页级失败与定向重试")
    }

    @MainActor
    func testLongPageBookmarkRestoresItsOffset() throws {
        importFile(source: "LOCAL", file: "fixture_reader_long", reuse: true)
        openFirstChapter(title: "fixture_reader_long")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        XCTAssertTrue(canvas.waitForExistence(timeout: 10), app.debugDescription)
        canvas.swipeUp()
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > 300
        }, object: nil)
        wait(for: [moved], timeout: 10)
        let saved = readingOffset(canvas)
        let bookmark = app.buttons["reader.bookmark"]
        // Repeated test runs may already have a bookmark on this page.
        if bookmark.value as? String == "已添加" { bookmark.tap() }
        bookmark.tap()
        canvas.swipeUp()
        let farther = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > saved + 200
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [farther], timeout: 10), .completed, "saved \(saved): \(canvas.value ?? "")")
        app.buttons["reader.chapters"].tap()
        app.segmentedControls.buttons["书签"].tap()
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "第 1 页")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
        row.tap()
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - saved) < 5
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 10), .completed, "saved \(saved): \(canvas.value ?? "")")
        attach("长页书签恢复中段")
    }

    private func readingOffset(_ canvas: XCUIElement) -> Int {
        let value = canvas.value as? String ?? ""
        // The description continues after the offset (window size), so only its number is read.
        let tail = value.components(separatedBy: "页内偏移 ").dropFirst().first ?? ""
        return Int(tail.prefix { $0 == "-" || $0.isNumber }) ?? -1
    }

    private func selectReaderMode(_ mode: String) {
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "阅读模式")).firstMatch.tap()
        app.buttons[mode].tap()
        app.buttons["reader.panel.close"].tap()
    }

    @MainActor
    func testImportedTextNovelSplitsChapters() throws {
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel")
        openFirstChapter(title: "fixture_novel")
        let paragraph = app.staticTexts["novel.firstParagraph"]
        XCTAssertTrue(paragraph.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(paragraph.label.contains("简介"), paragraph.label)
        attach("本地小说阅读")
    }

    private func importFile(source name: String, file: String, reuse: Bool = false) {
        app.tabBars.buttons["浏览"].tap()
        let kind = name == "LOCAL_NOVEL" ? "NOVEL" : "MANGA"
        if app.navigationBars.buttons["关闭"].exists { app.navigationBars.buttons["关闭"].tap() }
        let space = app.buttons["space.menu"]
        if space.waitForExistence(timeout: 3) {
            space.tap()
            let choice = app.buttons["space.select.\(kind)"]
            XCTAssertTrue(choice.waitForExistence(timeout: 5))
            choice.tap()
        } else if app.segmentedControls["contentKind"].exists {
            app.segmentedControls["contentKind"].buttons[kind == "NOVEL" ? "小说" : "漫画"].tap()
        }
        let field = app.pageSearchField()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        if let text = field.value as? String, text != field.placeholderValue, !text.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count + 2))
        }
        field.typeText("本地")
        let source = app.buttons["source.\(name)"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        source.tap()
        let item = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "source.content.", file)).firstMatch
        // Reader regressions may reuse their immutable fixtures; import tests still exercise the picker every run.
        if reuse && item.waitForExistence(timeout: 3) { item.tap(); return }
        let add = app.buttons["browse.importLocal"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), app.debugDescription)
        add.tap()
        // Wait for the remote document UI; the underlying app has a Browse tab too.
        let pickerReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.collectionViews.firstMatch.exists || self.app.tables.firstMatch.exists
                || self.app.staticTexts["最近项目"].exists || self.app.navigationBars["我的iPhone"].exists
        }, object: nil)
        wait(for: [pickerReady], timeout: 15)
        attach("文件选择器打开后")
        let browseTabs = app.tabBars.buttons.matching(NSPredicate(format: "label == %@", "浏览")).allElementsBoundByIndex
        if let browse = browseTabs.last(where: { $0.isHittable }) { browse.tap() }
        for title in ["我的iPhone", "我的 iPhone", "On My iPhone"] {
            let location = app.cells.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
            if location.exists && location.isHittable { location.tap(); break }
        }
        let picked = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", file)).firstMatch
        let pickerList: XCUIElement
        if app.collectionViews.firstMatch.exists { pickerList = app.collectionViews.firstMatch }
        else if app.tables.firstMatch.exists { pickerList = app.tables.firstMatch }
        else {
            let visibleList = app.scrollViews.allElementsBoundByIndex.first {
                $0.isHittable && $0.frame.height > 200 && $0.frame.minY > 100
                    && $0.frame.maxY < app.frame.maxY - 60
            }
            XCTAssertNotNil(visibleList, app.debugDescription)
            pickerList = visibleList ?? app.scrollViews.firstMatch
        }
        if !picked.waitForExistence(timeout: 3) {
            attach("新夹具所在文件目录")
            for _ in 0..<6 {
                pickerList.swipeUp()
                if picked.exists { break }
            }
            if !picked.exists {
                for _ in 0..<12 {
                    pickerList.swipeDown()
                    if picked.exists { break }
                }
            }
        }
        XCTAssertTrue(picked.waitForExistence(timeout: 3), "Stage the fixtures first: \(app.debugDescription)")
        for _ in 0..<6 where picked.frame.midY > app.frame.maxY - 90 || picked.frame.midY < 110 {
            if picked.frame.midY > app.frame.maxY - 90 { pickerList.swipeUp() } else { pickerList.swipeDown() }
        }
        picked.tap()
        // Multiple selection keeps the picker open until 打开 is pressed.
        for label in ["打开", "Open"] where app.buttons[label].waitForExistence(timeout: 3) {
            app.buttons[label].tap(); break
        }
        XCTAssertTrue(item.waitForExistence(timeout: 30), app.debugDescription)
        attach("\(name) 导入后")
        item.tap()
    }

    private func openFirstChapter(title: String) {
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30), app.debugDescription)
        let chapter = revealChapter(in: app, timeout: 30)
        XCTAssertTrue(chapter.exists, app.debugDescription)
        chapter.tap()
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
