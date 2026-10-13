import Foundation

/// A chapter pull belongs to one user interaction, starting at a chapter boundary.
/// Inertial/programmatic scrolling never starts an interaction and cannot dispatch navigation.
struct ReaderChapterPull: Equatable {
    enum Direction { case previous, next }

    private var chapterID: String?
    private var atStart = false
    private var atEnd = false
    private(set) var distance: CGFloat = 0

    var isTracking: Bool { chapterID != nil }
    var direction: Direction? { Self.resolve(distance: distance, atStart: atStart, atEnd: atEnd, threshold: 0.1) }

    mutating func begin(chapterID: String, atStart: Bool, atEnd: Bool, enabled: Bool) {
        self = ReaderChapterPull()
        guard enabled else { return }
        self.chapterID = chapterID
        self.atStart = atStart
        self.atEnd = atEnd
    }

    mutating func update(offset: CGFloat, limit: CGFloat, reversed: Bool, enabled: Bool) {
        guard isTracking else { return }
        guard enabled, offset.isFinite, limit.isFinite else { distance = 0; return }
        let end = max(0, limit)
        let outside = offset < 0 ? offset : offset > end ? offset - end : 0
        let value = reversed ? -outside : outside
        distance = Self.resolve(distance: value, atStart: atStart, atEnd: atEnd, threshold: 0.1) == nil ? 0 : value
    }

    mutating func release(chapterID: String, enabled: Bool, extent: CGFloat) -> Direction? {
        defer { self = ReaderChapterPull() }
        guard self.chapterID == chapterID, enabled else { return nil }
        return Self.resolve(distance: distance, atStart: atStart, atEnd: atEnd, threshold: Self.threshold(extent: extent))
    }

    static func threshold(extent: CGFloat) -> CGFloat { max(40, min(80, extent * 0.08)) }

    static func resolve(distance: CGFloat, atStart: Bool, atEnd: Bool, threshold: CGFloat) -> Direction? {
        guard distance.isFinite, abs(distance) >= threshold else { return nil }
        if distance < 0 && atStart { return .previous }
        if distance > 0 && atEnd { return .next }
        return nil
    }
}
