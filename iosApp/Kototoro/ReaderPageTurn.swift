import KototoroShared
import SwiftUI

/// Android scene reader's ADVANCED (cover) and SIMULATION (curl) page turns. Like the scene host, this pager owns the
/// travel itself instead of a scroll view: a drag or a tap moves a reading-order position, and every frame resolves
/// each slot's shift, fold and shade from the shared reader-core rules (`IosReader.pageTurn`). Coordinates are
/// physical; right-to-left reading only flips the travel direction and the edge pages fold from.
struct ReaderTurnPager<Page: View>: View {
    let count: Int
    let revision: Int
    @Binding var current: Int
    let style: Int
    let vertical: Bool
    let mirrored: Bool
    let engine: IosReader
    let size: CGSize
    /// True while the page itself takes drags (zoomed in).
    let isLocked: () -> Bool
    let onDragStart: () -> Void
    let onSettle: (Int) -> Void
    @ViewBuilder let page: (Int) -> Page

    @State private var position: Double
    @State private var anchor: Int
    @State private var moving = false
    @State private var downFraction = 0.5
    @State private var dragActive = false
    @State private var transitionID = 0

    init(count: Int, revision: Int, current: Binding<Int>, style: Int, vertical: Bool, mirrored: Bool, engine: IosReader,
         size: CGSize, isLocked: @escaping () -> Bool = { false },
         onDragStart: @escaping () -> Void,
         onSettle: @escaping (Int) -> Void,
         @ViewBuilder page: @escaping (Int) -> Page) {
        self.count = count; self.revision = revision; _current = current; self.style = style; self.vertical = vertical
        self.mirrored = mirrored; self.engine = engine; self.size = size; self.isLocked = isLocked
        self.onSettle = onSettle; self.page = page
        self.onDragStart = onDragStart
        _position = State(initialValue: Double(current.wrappedValue))
        _anchor = State(initialValue: current.wrappedValue)
    }

    private var extent: CGFloat { max(1, vertical ? size.height : size.width) }
    /// Reading forward is a drag towards the leading edge: left, right in right-to-left reading, or up.
    private var readingSign: CGFloat { mirrored && !vertical ? 1 : -1 }

    var body: some View {
        let low = max(0, Int(position.rounded(.down)) - 1)
        let high = min(count - 1, Int(position.rounded(.up)) + 1)
        ZStack(alignment: .topLeading) {
            if low <= high {
                ForEach(low...high, id: \.self) { slot in
                    page(slot)
                        .frame(width: size.width, height: size.height)
                        .modifier(PageTurnEffect(
                            position: position, slot: slot, anchor: anchor, moving: moving, style: style,
                            vertical: vertical, mirrored: mirrored, downFraction: downFraction, size: size, engine: engine
                        ))
                        // Earlier pages draw above later ones: the page being left, or the previous page coming
                        // back, is the one on top.
                        .zIndex(-Double(slot))
                }
            }
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .clipped()
        .contentShape(Rectangle())
        .environment(\.layoutDirection, .leftToRight)
        // Simultaneous: as an ordinary gesture it would wait for the pages' own tap and zoom gestures to fail,
        // which only happens when the finger lifts, so the turn could not follow the drag.
        .simultaneousGesture(drag)
        .onChange(of: current) { _, target in
            guard !moving, Double(target) != position else { return }
            settle(at: target, animation: .easeInOut(duration: 0.36))
        }
        .onChange(of: count) { _, _ in
            position = Double(min(current, max(0, count - 1))); anchor = Int(position)
        }
        .onChange(of: revision) { _, _ in
            transitionID += 1
            position = Double(min(current, max(0, count - 1))); anchor = Int(position)
            moving = false; dragActive = false
        }
        // A container element, so the pages inside keep their own identifiers.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("reader.turnPager")
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                guard count > 0, dragActive || !isLocked() else { return }
                if !dragActive { onDragStart() }
                dragActive = true
                if !moving {
                    moving = true
                    let across = vertical ? value.startLocation.x / size.width : value.startLocation.y / size.height
                    downFraction = min(1, max(0, across))
                }
                let travel = (vertical ? value.translation.height : value.translation.width) * readingSign / extent
                position = rubberBand(Double(anchor) + travel)
            }
            .onEnded { value in
                guard dragActive else { return }
                dragActive = false
                if isLocked() { settle(at: anchor, animation: .snappy(duration: 0.3)); return }
                let predicted = (vertical ? value.predictedEndTranslation.height : value.predictedEndTranslation.width)
                    * readingSign / extent
                let step = predicted > 0.3 ? 1 : predicted < -0.3 ? -1 : 0
                settle(at: min(max(0, anchor + step), count - 1), animation: .snappy(duration: 0.3))
            }
    }

    /// Past the first or last page the travel resists instead of revealing nothing.
    private func rubberBand(_ value: Double) -> Double {
        let upper = Double(max(0, count - 1))
        if value < 0 { return -0.15 * (1 - 1 / (1 - value * 2)) }
        if value > upper { return upper + 0.15 * (1 - 1 / (1 + (value - upper) * 2)) }
        return value
    }

    private func settle(at target: Int, animation: Animation) {
        transitionID += 1
        let token = transitionID
        moving = true
        withAnimation(animation) {
            position = Double(target)
        } completion: {
            guard token == transitionID else { return }
            anchor = target
            moving = false
            downFraction = 0.5
            if current != target { current = target }
            onSettle(target)
        }
    }
}

/// One slot of [ReaderTurnPager] at an animated reading position, resolved per frame from the shared rules.
private struct PageTurnEffect: ViewModifier, Animatable {
    var position: Double
    let slot: Int
    let anchor: Int
    let moving: Bool
    let style: Int
    let vertical: Bool
    let mirrored: Bool
    let downFraction: Double
    let size: CGSize
    let engine: IosReader

    var animatableData: Double {
        get { position }
        set { position = newValue }
    }

    func body(content: Content) -> some View {
        let turn = engine.pageTurn(
            style: Int32(style), slot: Int32(slot), position: Float(position), anchorSlot: Int32(anchor),
            scrolling: moving, width: Float(size.width), height: Float(size.height),
            vertical: vertical, mirrored: mirrored, downY: Float(downFraction)
        )
        // Reading-order travel of this slot plus the turn's own shift, made physical.
        let pages = (Double(slot) - position) + Double(turn.shift)
        let physical = CGFloat(pages) * (mirrored && !vertical ? -1 : 1)
        let fold = turn.folding ? PageFold(turn: turn, size: size) : nil
        ZStack(alignment: .topLeading) {
            // The page keeps one structural position whether or not it folds, so its state survives a turn.
            content.mask(alignment: .topLeading) {
                FoldPolygon(points: fold?.front ?? PageFold.rectangle(size))
            }
            if let fold {
                content
                    .mask(alignment: .topLeading) { FoldPolygon(points: fold.removed) }
                    .overlay { FoldPolygon(points: fold.removed).fill(.white.opacity(0.1)) }
                    .transformEffect(fold.reflection)
                    .shadow(color: .black.opacity(0.3), radius: 8)
                    .allowsHitTesting(false)
            }
            if turn.shade > 0 { Color.black.opacity(Double(turn.shade)).allowsHitTesting(false) }
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .clipped()
        .opacity(Double(turn.alpha))
        .offset(x: vertical ? 0 : physical * size.width, y: vertical ? physical * size.height : 0)
    }
}

/// The page split by the fold line: what stays in front, what folds over, and the mirror across the line.
private struct PageFold {
    let front: [CGPoint]
    let removed: [CGPoint]
    let reflection: CGAffineTransform

    static func rectangle(_ size: CGSize) -> [CGPoint] {
        [.zero, CGPoint(x: size.width, y: 0), CGPoint(x: size.width, y: size.height), CGPoint(x: 0, y: size.height)]
    }

    init(turn: IosPageTurn, size: CGSize) {
        let top = CGPoint(x: CGFloat(turn.topX), y: CGFloat(turn.topY))
        let bottom = CGPoint(x: CGFloat(turn.bottomX), y: CGFloat(turn.bottomY))
        let keep = CGPoint(x: CGFloat(turn.keepX), y: CGFloat(turn.keepY))
        let direction = CGPoint(x: bottom.x - top.x, y: bottom.y - top.y)
        func side(_ point: CGPoint) -> CGFloat { direction.x * (point.y - top.y) - direction.y * (point.x - top.x) }
        let keepSign: CGFloat = side(keep) >= 0 ? 1 : -1
        let page = Self.rectangle(size)
        front = Self.clip(page) { side($0) * keepSign }
        removed = Self.clip(page) { -side($0) * keepSign }
        let angle = atan2(direction.y, direction.x)
        let cosine = cos(2 * angle), sine = sin(2 * angle)
        reflection = CGAffineTransform(a: cosine, b: sine, c: sine, d: -cosine,
                                       tx: top.x - (cosine * top.x + sine * top.y),
                                       ty: top.y - (sine * top.x - cosine * top.y))
    }

    /// The part of a convex polygon where [inside] is not negative (one Sutherland–Hodgman pass).
    private static func clip(_ polygon: [CGPoint], inside: (CGPoint) -> CGFloat) -> [CGPoint] {
        var result: [CGPoint] = []
        for index in polygon.indices {
            let point = polygon[index], next = polygon[(index + 1) % polygon.count]
            let a = inside(point), b = inside(next)
            if a >= 0 { result.append(point) }
            if (a >= 0) != (b >= 0) {
                let t = a / (a - b)
                result.append(CGPoint(x: point.x + (next.x - point.x) * t, y: point.y + (next.y - point.y) * t))
            }
        }
        return result
    }
}

private struct FoldPolygon: Shape {
    let points: [CGPoint]
    func path(in rect: CGRect) -> Path {
        var path = Path()
        guard let first = points.first else { return path }
        path.move(to: first)
        for point in points.dropFirst() { path.addLine(to: point) }
        path.closeSubpath()
        return path
    }
}
