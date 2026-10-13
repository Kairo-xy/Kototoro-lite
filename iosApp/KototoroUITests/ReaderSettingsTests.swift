import Foundation
import XCTest

final class ReaderSettingsTests: XCTestCase {
    func testNewHorizontalReaderDefaultsToLeftToRight() {
        var preferences = ReaderPreferences()
        preferences.mode = 4
        XCTAssertFalse(preferences.horizontalReversed)
        XCTAssertFalse(preferences.readsRightToLeft)
    }

    func testLegacyHorizontalPreferencesKeepTheirPreviousDirection() throws {
        let horizontal = try JSONDecoder().decode(ReaderPreferences.self, from: Data("{\"mode\":4}".utf8))
        XCTAssertTrue(horizontal.horizontalReversed)
        XCTAssertTrue(horizontal.readsRightToLeft)
        let vertical = try JSONDecoder().decode(ReaderPreferences.self, from: Data("{\"mode\":3}".utf8))
        XCTAssertFalse(vertical.horizontalReversed)
    }

    func testExplicitHorizontalDirectionSurvivesRestart() throws {
        var preferences = ReaderPreferences()
        preferences.mode = 4
        for reversed in [false, true] {
            preferences.horizontalReversed = reversed
            let saved = try JSONEncoder().encode(preferences)
            XCTAssertEqual(try JSONDecoder().decode(ReaderPreferences.self, from: saved), preferences)
        }
    }

    func testOnlyAnActiveDirectionChangeRequiresLayout() {
        var before = ReaderPreferences()
        before.mode = 3
        var after = before
        after.horizontalReversed = true
        XCTAssertTrue(before.hasSameLayout(as: after))
        before.mode = 4
        after.mode = 4
        XCTAssertFalse(before.hasSameLayout(as: after))
        XCTAssertEqual(before.continuousForwardSign, 1)
        XCTAssertEqual(after.continuousForwardSign, -1)
    }
}
