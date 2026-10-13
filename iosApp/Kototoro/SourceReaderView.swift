import Combine
import KototoroShared
import SwiftUI
import UniformTypeIdentifiers

struct ReaderWindowSlot: Identifiable {
    let id: String
    let slot: IosReaderSlot
}

@MainActor
final class SourceReaderModel: ObservableObject {
    @Published private(set) var chapter: IosSourceChapter
    @Published private(set) var pages: [IosSourcePage] = []
    @Published private(set) var index = 0
    @Published private(set) var windowIndex = 0
    @Published private(set) var windowRevision = 0
    @Published private(set) var sceneRevision = 0
    private var window: [IosReaderPage] = []
    private var windowPages: [IosSourcePage] = []
    private var chapterPages: [String: [IosSourcePage]] = [:]
    private var chapterPreloads: [String: ChapterPreload] = [:]
    private var pendingWindow = false
    @Published private(set) var images: [Int: UIImage] = [:]
    @Published private(set) var artifacts: [Int: ReaderImageArtifact] = [:]
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    struct PageFailure {
        let message: String
        let challenge: IosChallenge?
    }
    @Published private(set) var pageFailures: [Int: PageFailure] = [:]
    @Published private(set) var isLoadingPages = false
    @Published private(set) var bookmarks: [IosBookmark] = []
    @Published private(set) var slots: [IosReaderSlot] = [] {
        didSet { identifiedSlots = slots.map { ReaderWindowSlot(id: slotIdentity($0), slot: $0) } }
    }
    private(set) var identifiedSlots: [ReaderWindowSlot] = []
    @Published private(set) var loaded = false
    @Published private(set) var pageRevision = 0
    @Published private(set) var scrollRevision = 0
    @Published var slotIndex: Int = 0
    @Published var autoScroll = false
    /// A page magnified past its fit: drags pan it rather than turning the page.
    var isPageZoomed = false {
        didSet { if !isPageZoomed && pendingWindow && !isUserPaging { publishWindow() } }
    }
    /// The vertical strip's zoom (Android's webtoon scaling frame): the strip is laid out wider rather than magnified,
    /// so pages decode and tile at the size shown. Session-only, back to fit when the reading mode changes.
    @Published private(set) var stripZoom: CGFloat = 1
    let runtime: IosSources
    let content: IosSourceContent
    private let chapterByID: [String: IosSourceChapter]
    let engine = IosReader()
    var library: IosLibrary?
    private var request: UUID?
    private var loading: Set<Int> = []
    /// One download per page of this chapter, shared by the page on screen and the prefetcher (Android's
    /// `PageLoader` tasks). Finished downloads stay: the artifact on disk is decoded again when the page returns.
    private var fetches: [Int: PageFetch] = [:]
    /// Android's reader threads: pages downloading at the same time, the page on screen before prefetches.
    private let pageGate = RequestGate(limit: ReaderLoading.threads)
    /// Pages next to the screen being loaded and decoded before the lazy stack shows them (see [neighborPages]).
    private var neighborLoads: [Int: Task<Void, Never>] = [:]
    /// The next chapter's page list and first downloads, started near this chapter's end (see [preloadNextChapter]).
    /// The page list of a preloaded chapter being opened, used by [load] instead of asking the source again.
    private var preloadedPages: Task<[IosSourcePage], Error>?

    private final class ChapterPreload {
        let chapterId: String
        let pages: Task<[IosSourcePage], Error>
        var fetches: [Int: PageFetch] = [:]
        init(chapterId: String, pages: Task<[IosSourcePage], Error>) { self.chapterId = chapterId; self.pages = pages }
        func cancel() {
            pages.cancel()
            for fetch in fetches.values where !fetch.finished { fetch.task.cancel() }
        }
    }

    private final class PageFetch {
        let task: Task<IosSourceImage, Error>
        let ticket: Int
        let isPrefetch: Bool
        var consumers = 0
        var finished = false
        var failed = false
        init(task: Task<IosSourceImage, Error>, ticket: Int, isPrefetch: Bool) {
            self.task = task; self.ticket = ticket; self.isPrefetch = isPrefetch
        }
    }
    private var viewport = CGSize(width: 1, height: 1)
    private var preferences = ReaderPreferences()
    private var offset: Float = 0
    private var viewportOffset = CGPoint.zero
    private var savedPosition: IosReadingPosition?
    private(set) var restoreOrigin: CGFloat = 0
    /// The continuous scroll origin of the position being read now.
    var currentOrigin: CGFloat { CGFloat(engine.scrollOrigin(index: Int32(windowIndex), offset: offset)) }
    private var awaitingRestore = false
    /// The paged scroll view is moving under the reader's finger (dragged or settling from a drag). Like Android's
    /// pager reports (target page while scrolling, settled page after) and Aidoku's finished page-turn gestures, only
    /// these moves report a page: its own first layout and moves the model made (taps, the slider, restores) do not.
    var isUserPaging = false {
        didSet { if !isUserPaging && pendingWindow { publishWindow() } }
    }
    var pagedWindowNeedsRestore = false

    var selectedPage: IosSourcePage? { pages.indices.contains(index) ? pages[index] : nil }
    var image: UIImage? { images[windowIndex] }
    var previousChapter: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: false) }
    var nextChapter: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: true) }
    var isBookmarked: Bool { bookmarks.contains { $0.chapterId == chapter.id && Int($0.index) == index } }

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        self.runtime = runtime; self.content = content; self.chapter = chapter
        chapterByID = Dictionary(content.chapters.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    func configure(size: CGSize, preferences: ReaderPreferences) {
        guard size.width > 1 && size.height > 1 else { return }
        let cropChanged = loaded && self.preferences.cropMargins != preferences.cropMargins
        let modeChanged = self.preferences.mode != preferences.mode
        let layoutChanged = viewport != size || !self.preferences.hasSameLayout(as: preferences) || slots.isEmpty
        if preferences.mode != self.preferences.mode || preferences.mode < 3 { stripZoom = 1; engine.setZoom(zoom: 1) }
        viewport = size; self.preferences = preferences
        if layoutChanged {
            engine.configure(count: Int32(windowPages.count), width: Int32(size.width), height: Int32(size.height),
                             mode: Int32(preferences.mode), doublePage: preferences.doublePage,
                             cover: preferences.cover, fit: Int32(preferences.fit),
                             splitWide: preferences.splitWidePages, horizontalReversed: preferences.horizontalReversed)
            slots = engine.slots()
            if let current = slots.first(where: { Int($0.index) == slotIndex }),
               current.placements.contains(where: { Int($0.index) == windowIndex }) {
                slotIndex = Int(current.index)
            } else {
                slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?.index ?? 0)
            }
            if loaded && preferences.mode >= 3 { requestScroll(to: currentOrigin) }
            if preferences.mode < 3 { pagedWindowNeedsRestore = true }
        }
        if cropChanged {
            cancelNeighborLoads()
            request = UUID()
            images = [:]; artifacts = [:]; loading = []; pageFailures = [:]; pageRevision += 1
        }
        if loaded && modeChanged { Task { @MainActor [weak self] in self?.publishWindow() } }
    }

    private var startAtEnd = false
    private var failedChapter: (chapter: IosSourceChapter, atEnd: Bool)?

    func load() async {
        guard !loaded else { return }
        let token = UUID(); request = token
        let selected = chapter
        isLoadingPages = true; error = nil; challenge = nil
        pageFailures = [:]
        defer {
            if request == token {
                isLoadingPages = false
                if pendingWindow { publishWindow() }
            }
        }
        do {
            let preloaded = preloadedPages
            preloadedPages = nil
            // A preload that failed is asked again, so its error is the source's current one.
            let early = try? await preloaded?.value
            let result: [IosSourcePage]
            if let early {
                result = early
            } else {
                result = try await ReaderDownloads.pages(runtime: runtime, content: content, chapter: selected)
            }
            let saved = try await library?.position(content: content)
            try Task.checkCancellation()
            guard request == token else { return }
            pages = result
            chapterPages[selected.id] = result
            savedPosition = saved
            if startAtEnd {
                index = max(0, pages.count - 1); offset = 0
            } else if let savedPosition, savedPosition.chapterId == selected.id {
                index = min(max(0, Int(savedPosition.index)), max(0, pages.count - 1))
                offset = savedPosition.scroll
            }
            startAtEnd = false
            publishWindow()
            configure(size: viewport, preferences: preferences)
            restoreOrigin = currentOrigin
            awaitingRestore = preferences.mode >= 3
            loaded = true; pageRevision += 1
            preloadAdjacentChapters()
            await refreshBookmarks()
        } catch { if !Task.isCancelled && request == token { fail(error) } }
    }

    func loadImage(at target: Int) async {
        guard loaded, windowPages.indices.contains(target), images[target] == nil, !loading.contains(target) else {
            if target == windowIndex { prefetch(); if images[target] != nil { loadNeighbors() } }
            return
        }
        let token = request
        let page = windowPages[target]
        let key = window[target]
        guard let owner = chapterByID[key.chapterId] else { return }
        let cropMargins = preferences.cropMargins
        loading.insert(target)
        defer { if request == token { loading.remove(target) } }
        do {
            let loadedPage: ReaderLoadedPage
            if ReaderDownloads.isDownloaded(content: content, chapter: owner) {
                loadedPage = try await ReaderDownloads.loadedPage(
                    runtime: runtime, content: content, chapter: owner,
                    page: page, index: Int(key.index), cropMargins: cropMargins
                )
            } else {
                loadedPage = try await pageArtifact(at: target).loadedPage(cropMargins: cropMargins)
            }
            try Task.checkCancellation()
            guard request == token else { return }
            images[target] = loadedPage.image
            artifacts[target] = loadedPage.artifact
            pageFailures[target] = nil
            let compensated = engine.setImageSize(
                index: Int32(target),
                width: Int32(loadedPage.artifact.encodedWidth),
                height: Int32(loadedPage.artifact.encodedHeight),
                viewportX: Float(viewportOffset.x), viewportY: Float(viewportOffset.y)
            )
            slots = engine.slots()
            if preferences.mode >= 3 {
                if awaitingRestore {
                    // Keep the semantic target while its estimated size becomes exact, including long-page bookmarks.
                    requestScroll(to: currentOrigin)
                } else if let compensated {
                    let origin = CGFloat(preferences.mode == 3 ? compensated.y : compensated.x)
                    let previous = preferences.mode == 3 ? viewportOffset.y : viewportOffset.x
                    if abs(origin - previous) > 0.01 { requestScroll(to: origin) }
                }
            }
            if !slots.contains(where: { Int($0.index) == slotIndex && $0.placements.contains { Int($0.index) == windowIndex } }) {
                slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?.index ?? 0)
            }
            // Decoded images stay bounded. Immutable on-disk artifacts survive eviction.
            let keep = Set((max(0, windowIndex - 2)...min(max(0, windowPages.count - 1), windowIndex + 3))).union(neighborPages())
            images = images.filter { keep.contains($0.key) }
            artifacts = artifacts.filter { keep.contains($0.key) }
            if slots.first(where: { $0.placements.contains { Int($0.index) == windowIndex } })?
                .placements.contains(where: { Int($0.index) == target }) == true {
                prefetch()
                loadNeighbors()
                await persist()
            }
        } catch {
            if !Task.isCancelled && request == token {
                pageFailures[target] = PageFailure(message: SourceErrorText.describe(error),
                                                   challenge: SourceErrorText.challenge(error))
            }
        }
    }

    /// The page's downloaded artifact. A prefetch already under way is joined and moved ahead of other prefetches;
    /// a download only the screen wanted is cancelled once no page view waits for it, as Android releases it.
    private func pageArtifact(at target: Int) async throws -> IosSourceImage {
        let fetch = fetches[target].flatMap { $0.failed ? nil : $0 } ?? startFetch(at: target, isPrefetch: false)
        fetch.consumers += 1
        await pageGate.promote(fetch.ticket, to: ReaderLoading.visiblePriority)
        var released = false
        let release = { [weak self] in
            guard !released else { return }
            released = true
            fetch.consumers -= 1
            if fetch.consumers == 0, !fetch.isPrefetch, !fetch.finished {
                fetch.task.cancel()
                if self?.fetches[target] === fetch { self?.fetches[target] = nil }
            }
        }
        defer { release() }
        return try await withTaskCancellationHandler {
            try await fetch.task.value
        } onCancel: {
            Task { @MainActor in release() }
        }
    }

    @discardableResult
    private func startFetch(at target: Int, isPrefetch: Bool) -> PageFetch {
        let key = window[target]
        let prepared = chapterPreloads[key.chapterId]?.fetches[Int(key.index)]
        let fetch = prepared.flatMap { $0.failed ? nil : $0 } ?? makeFetch(page: windowPages[target], isPrefetch: isPrefetch)
        fetches[target] = fetch
        return fetch
    }

    /// A download of [page], not yet tied to a page of the open chapter (a preloaded next chapter's page).
    private func makeFetch(page: IosSourcePage, isPrefetch: Bool) -> PageFetch {
        let runtime = runtime, gate = pageGate
        let ticket = RequestGate.makeTicket()
        let priority = isPrefetch ? ReaderLoading.prefetchPriority : ReaderLoading.visiblePriority
        let task = Task { try await gated(gate, priority: priority, ticket: ticket) { try await runtime.image(page: page) } }
        let fetch = PageFetch(task: task, ticket: ticket, isPrefetch: isPrefetch)
        Task { [weak self] in
            let succeeded = (try? await task.value) != nil
            if succeeded { fetch.finished = true; return }
            fetch.failed = true
            guard let self, let target = self.fetches.first(where: { $0.value === fetch })?.key else { return }
            self.fetches[target] = nil
        }
        return fetch
    }

    private func pageIdentity(_ key: IosReaderPage) -> String { "\(key.chapterId):\(key.index)" }

    /// Android's ChapterPages and this host consume the same reading-order window policy.
    /// Metadata is bounded to three chapters; decoded images remain bounded to the visible neighborhood.
    private func publishWindow() {
        if loaded && (isUserPaging || isLoadingPages || isPageZoomed) { pendingWindow = true; return }
        pendingWindow = false
        func keys(_ chapter: IosSourceChapter?) -> [IosReaderPage] {
            guard let chapter, let pages = chapterPages[chapter.id] else { return [] }
            return pages.indices.map { IosReaderPage(chapterId: chapter.id, index: Int32($0)) }
        }
        let updated = engine.chapterWindow(previous: keys(previousChapter), current: keys(chapter),
                                           next: keys(nextChapter), adjacentPageCount: preferences.mode < 3 ? 2 : 0)
        guard updated != window else { return }
        let old = Dictionary(uniqueKeysWithValues: window.enumerated().map { (pageIdentity($0.element), $0.offset) })
        let oldImages = images, oldArtifacts = artifacts, oldFailures = pageFailures, oldFetches = fetches
        let segment = slots.first { Int($0.index) == slotIndex }?.placements.first { Int($0.index) == windowIndex }?.segment
        cancelNeighborLoads()
        for fetch in fetches.values where !fetch.finished { fetch.task.cancel() }
        if loaded { request = UUID() }
        loading = []
        images = [:]; artifacts = [:]; pageFailures = [:]; fetches = [:]
        for (position, key) in updated.enumerated() {
            guard let before = old[pageIdentity(key)] else { continue }
            images[position] = oldImages[before]; artifacts[position] = oldArtifacts[before]
            pageFailures[position] = oldFailures[before]
            if let fetch = oldFetches[before], fetch.finished { fetches[position] = fetch }
        }
        window = updated
        windowPages = updated.compactMap { chapterPages[$0.chapterId]?[Int($0.index)] }
        windowIndex = updated.firstIndex { $0.chapterId == chapter.id && Int($0.index) == index } ?? 0
        engine.setPages(pages: updated)
        slots = []
        configure(size: viewport, preferences: preferences)
        slotIndex = Int(slots.first { $0.placements.contains {
            Int($0.index) == windowIndex && (segment == nil || $0.segment == segment)
        }}?.index ?? 0)
        if preferences.mode >= 3 { requestScroll(to: currentOrigin) }
        windowRevision += 1; pageRevision += 1
        let retained = Set([chapter.id, previousChapter?.id, nextChapter?.id].compactMap { $0 })
        chapterPages = chapterPages.filter { retained.contains($0.key) }
        for (id, preload) in chapterPreloads where !retained.contains(id) {
            preload.cancel(); chapterPreloads[id] = nil
        }
        if loaded { loadNeighbors() }
    }

    private func preloadAdjacentChapters() {
        guard loaded else { return }
        let distance = preferences.mode >= 3 ? 10 : 2
        let targets = [index <= distance ? previousChapter : nil,
                       index >= pages.count - 1 - distance ? nextChapter : nil].compactMap { $0 }
        for target in targets where chapterPages[target.id] == nil && chapterPreloads[target.id] == nil {
            let runtime = runtime, content = content
            let preload = ChapterPreload(chapterId: target.id, pages: Task {
                try await ReaderDownloads.pages(runtime: runtime, content: content, chapter: target)
            })
            chapterPreloads[target.id] = preload
            Task { [weak self] in
                do {
                    let result = try await preload.pages.value
                    try Task.checkCancellation()
                    guard !result.isEmpty else { throw URLError(.zeroByteResource) }
                    guard let self, self.chapterPreloads[target.id] === preload else { return }
                    self.chapterPages[target.id] = result
                    if ReaderLoading.isPrefetchApplicable,
                       !ReaderDownloads.isDownloaded(content: self.content, chapter: target) {
                        let range = target.id == self.previousChapter?.id
                            ? max(0, result.count - 2)..<result.count
                            : 0..<min(result.count, ReaderLoading.prefetchLimit)
                        for index in range { preload.fetches[index] = self.makeFetch(page: result[index], isPrefetch: true) }
                    }
                    self.publishWindow()
                } catch {
                    guard let self, self.chapterPreloads[target.id] === preload else { return }
                    self.chapterPreloads[target.id] = nil
                    // A neighbor's metadata failure leaves the active chapter readable and can be retried.
                }
            }
        }
    }

    private func selectWindowPage(_ target: Int) {
        guard window.indices.contains(target) else { return }
        let key = window[target]
        if windowIndex != target { windowIndex = target }
        if key.chapterId != chapter.id,
           let owner = chapterByID[key.chapterId],
           let ownerPages = chapterPages[key.chapterId] {
            chapter = owner; pages = ownerPages; pendingWindow = true
        }
        if index != Int(key.index) { index = Int(key.index) }
        preloadAdjacentChapters()
        if pendingWindow && !isUserPaging {
            Task { @MainActor [weak self] in self?.publishWindow() }
        }
    }

    /// Android's reader offers the ten pages after the visible ones and keeps the nearest `prefetch limit` of them
    /// queued; pages that dropped out of that window are no longer wanted. Downloaded chapters need nothing.
    private func prefetch() {
        guard loaded, !windowPages.isEmpty else { return }
        let upper = slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?
            .placements.map { Int($0.index) }.max() ?? windowIndex
        preloadAdjacentChapters()
        let window = Set((upper + 1)..<min(windowPages.count, upper + 1 + ReaderLoading.prefetchWindow))
        for (target, fetch) in fetches where fetch.isPrefetch && !fetch.finished && fetch.consumers == 0 && !window.contains(target) {
            fetch.task.cancel()
            fetches[target] = nil
        }
        guard ReaderLoading.isPrefetchApplicable else { return }
        let chapterIDs = Set(window.map { self.window[$0].chapterId })
        let downloaded = Set(chapterIDs.compactMap { chapterByID[$0] }.filter {
            ReaderDownloads.isDownloaded(content: content, chapter: $0)
        }.map(\.id))
        let wanted = window.sorted().filter {
            fetches[$0] == nil && images[$0] == nil && !downloaded.contains(self.window[$0].chapterId)
        }.prefix(ReaderLoading.prefetchLimit)
        for target in wanted { startFetch(at: target, isPrefetch: true) }
    }

    /// Android's pager composes one slot on each side of the screen (`beyondViewportPageCount = 1`) and the webtoon
    /// strip keeps two screens ahead (`WEBTOON_AHEAD_CACHE_FRACTION`); those pages load at visible priority and are
    /// decoded before they scroll in, where a lazy stack would only start them once the page view appears.
    private func neighborPages() -> Set<Int> {
        guard let current = slots.firstIndex(where: { $0.placements.contains { Int($0.index) == windowIndex } }) else {
            return []
        }
        var result = Set<Int>()
        func add(_ position: Int) {
            guard slots.indices.contains(position) else { return }
            for placement in slots[position].placements { result.insert(Int(placement.index)) }
        }
        // The slot on screen too: a page view that found its page already loading waits for this load.
        add(current - 1); add(current)
        if preferences.mode >= 3 {
            let extent = preferences.mode == 3 ? viewport.height : viewport.width
            var covered: CGFloat = 0
            var position = current
            // The screen itself plus two more.
            while slots.indices.contains(position), covered < extent * 3 {
                add(position)
                if let placement = slots[position].placements.first {
                    covered += max(1, CGFloat(preferences.mode == 3 ? placement.height : placement.width))
                } else {
                    covered += 1
                }
                position += 1
            }
        } else {
            // Android composes one slot ahead; Aidoku keeps two pages ahead decoded (`Reader.pagesToPreload`).
            add(current + 1); add(current + 2)
        }
        return result
    }

    private func loadNeighbors() {
        guard loaded else { return }
        let wanted = neighborPages()
        for (target, task) in neighborLoads where !wanted.contains(target) {
            task.cancel()
            neighborLoads[target] = nil
        }
        let token = request
        for target in wanted.sorted() where neighborLoads[target] == nil && images[target] == nil
            && !loading.contains(target) {
            neighborLoads[target] = Task { [weak self] in
                await self?.loadImage(at: target)
                guard !Task.isCancelled, let self, self.request == token else { return }
                self.neighborLoads[target] = nil
            }
        }
    }

    /// Leaving the reader: nothing ahead of the screen is wanted any more.
    func stopPreloading() {
        cancelNeighborLoads()
        for preload in chapterPreloads.values { preload.cancel() }; chapterPreloads = [:]
        for fetch in fetches.values where !fetch.finished { fetch.task.cancel() }
        fetches = fetches.filter { $0.value.finished }
        request = UUID(); loading = []
        isLoadingPages = false
    }

    private func cancelNeighborLoads() {
        for task in neighborLoads.values { task.cancel() }
        neighborLoads = [:]
    }

    private func cancelFetches() {
        cancelNeighborLoads()
        for fetch in fetches.values where !fetch.finished { fetch.task.cancel() }
        fetches = [:]
    }

    func displayImage(for target: Int, segment: Int) -> UIImage? {
        guard let base = images[target] else { return nil }
        return segment == 1 || segment == 2 ? SourceImageFile.splitPage(base, segment: segment) : base
    }

    func pageDescription(at target: Int) -> String {
        guard window.indices.contains(target) else { return "漫画页" }
        let key = window[target]
        return "\(chapterByID[key.chapterId]?.title ?? "章节")，第 \(key.index + 1) 页"
    }

    func slotIdentity(_ slot: IosReaderSlot) -> String {
        slot.placements.compactMap { placement -> String? in
            guard window.indices.contains(Int(placement.index)) else { return nil }
            return "\(pageIdentity(window[Int(placement.index)])):\(placement.segment)"
        }.joined(separator: "/")
    }

    /// A slot the paged scroll view settled on under the reader's finger.
    func scrolledToSlot(_ selected: Int) {
        guard isUserPaging, let slot = slots.first(where: { Int($0.index) == selected }),
              slotIndex != selected || windowIndex != Int(slot.anchor) else { return }
        if slotIndex != selected { slotIndex = selected }
        selectSlot(selected)
    }

    func selectSlot(_ selected: Int) {
        guard let slot = slots.first(where: { Int($0.index) == selected }) else { return }
        selectWindowPage(Int(slot.anchor)); offset = 0
        Task { await persist() }
    }

    func continuousPosition(x: CGFloat, y: CGFloat) {
        guard loaded, !windowPages.isEmpty else { return }
        let limit = CGFloat(engine.scrollLimit())
        let primary = min(max(0, preferences.mode == 3 ? y : x), limit)
        viewportOffset = CGPoint(x: preferences.mode == 3 ? x : primary,
                                 y: preferences.mode == 3 ? primary : y)
        if awaitingRestore {
            let current = primary
            guard images[windowIndex] != nil, abs(current - restoreOrigin) < 2 else { return }
            awaitingRestore = false
        }
        let progress = engine.progress(x: Float(viewportOffset.x), y: Float(viewportOffset.y))
        let nextIndex = min(max(0, Int(progress.index)), max(0, windowPages.count - 1))
        if windowIndex != nextIndex { selectWindowPage(nextIndex) }
        offset = progress.offset
        let nextSlot = Int(slots.first { $0.anchor == Int32(windowIndex) }?.index ?? 0)
        if slotIndex != nextSlot { slotIndex = nextSlot }
    }

    func setStripZoom(_ value: CGFloat) {
        guard preferences.mode >= 3 else { return }
        let zoom = min(max(value, CGFloat(IosReader.companion.MINIMUM_ZOOM)), CGFloat(IosReader.companion.MAXIMUM_ZOOM))
        guard zoom != stripZoom else { return }
        stripZoom = zoom
        engine.setZoom(zoom: Float(zoom))
        slots = engine.slots()
    }

    func userScrolled() { awaitingRestore = false; autoScroll = false }

    private func requestScroll(to origin: CGFloat) {
        restoreOrigin = origin
        let current = preferences.mode == 3 ? viewportOffset.y : viewportOffset.x
        awaitingRestore = images[windowIndex] == nil || abs(current - origin) >= 2
        scrollRevision += 1
    }

    var readingPositionDescription: String {
        "第 \(index + 1) 页，缩放 \(Int(stripZoom * 100))%，页内偏移 \(Int(offset))，窗口 \(window.count) 页"
    }

    func jump(to page: Int, scroll: Float = 0) {
        guard pages.indices.contains(page) else { return }
        index = page; offset = scroll.isFinite ? max(0, scroll) : 0
        windowIndex = window.firstIndex { $0.chapterId == chapter.id && Int($0.index) == page } ?? 0
        slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?.index ?? 0)
        if preferences.mode < 3 { pagedWindowNeedsRestore = true }
        preloadAdjacentChapters()
        if preferences.mode >= 3 { requestScroll(to: currentOrigin) }
    }

    func openBookmark(_ bookmark: IosBookmark) async -> Bool {
        guard let target = content.chapters.first(where: { $0.id == bookmark.chapterId }) else { return false }
        userScrolled()
        if loaded, let position = window.firstIndex(where: {
            $0.chapterId == bookmark.chapterId && $0.index == bookmark.index
        }) {
            selectWindowPage(position)
            jump(to: Int(bookmark.index), scroll: Float(bookmark.scroll))
            pagedWindowNeedsRestore = preferences.mode < 3
            return true
        }
        if chapter.id != target.id { await moveChapter(target) }
        guard loaded, chapter.id == target.id, pages.indices.contains(Int(bookmark.index)) else { return false }
        jump(to: Int(bookmark.index), scroll: Float(bookmark.scroll))
        return true
    }

    func movePage(forward: Bool) {
        let next = slotIndex + (forward ? 1 : -1)
        guard slots.indices.contains(next) else {
            if let target = forward ? nextChapter : previousChapter {
                Task { await moveChapter(target, atEnd: !forward) }
            }
            return
        }
        slotIndex = next; selectSlot(next)
    }

    func moveChapter(_ target: IosSourceChapter, atEnd: Bool = false) async {
        guard !isLoadingPages else { return }
        let token = request
        isLoadingPages = true; error = nil; challenge = nil; failedChapter = nil
        defer {
            if request == token {
                isLoadingPages = false
                if pendingWindow { publishWindow() }
            }
        }
        let adopted = chapterPreloads[target.id]
        let prepared: [IosSourcePage]
        do {
            let early: [IosSourcePage]?
            if let cached = chapterPages[target.id] { early = cached }
            else { early = try? await adopted?.pages.value }
            try Task.checkCancellation()
            if let early { prepared = early }
            else { prepared = try await ReaderDownloads.pages(runtime: runtime, content: content, chapter: target) }
            guard !prepared.isEmpty else { throw URLError(.zeroByteResource) }
            try Task.checkCancellation()
            guard request == token else { return }
            await persist()
            try Task.checkCancellation()
            guard request == token else { return }
        } catch {
            if !Task.isCancelled && request == token {
                failedChapter = (target, atEnd)
                fail(error)
            }
            return
        }
        startAtEnd = atEnd
        request = UUID(); chapter = target; loaded = false; sceneRevision += 1
        pendingWindow = false
        isUserPaging = false; isPageZoomed = false
        // Explicit jumps start a new scene; normal boundary paging promotes the existing window instead.
        for preload in chapterPreloads.values where preload !== adopted { preload.cancel() }
        chapterPreloads = adopted.map { [target.id: $0] } ?? [:]
        cancelFetches()
        preloadedPages = Task { prepared }
        pages = []; window = []; windowPages = []; slots = []; images = [:]; artifacts = [:]; loading = []; pageFailures = [:]
        chapterPages = [target.id: prepared]
        index = 0; windowIndex = 0; slotIndex = 0; offset = 0; viewportOffset = .zero; engine.reset()
        await load()
    }

    var hasVisiblePageFailure: Bool {
        if preferences.mode >= 3 {
            let bounds = CGRect(origin: viewportOffset, size: viewport)
            return pageFailures.keys.contains { page in
                guard slots.indices.contains(page), let placement = slots[page].placements.first else { return false }
                return bounds.intersects(CGRect(x: CGFloat(placement.x), y: CGFloat(placement.y),
                                                 width: CGFloat(placement.width), height: CGFloat(placement.height)))
            }
        }
        return slots.indices.contains(slotIndex) && slots[slotIndex].placements.contains {
            pageFailures[Int($0.index)] != nil
        }
    }

    var isReadyForAutoScroll: Bool {
        loaded && !isLoadingPages && (preferences.mode < 3 || !awaitingRestore) && slots.indices.contains(slotIndex) &&
            slots[slotIndex].placements.allSatisfy { images[Int($0.index)] != nil }
    }

    func advanceChapterAutomatically() async -> Bool {
        guard let next = nextChapter else { return false }
        await moveChapter(next)
        return loaded && chapter.id == next.id && error == nil
    }

    func persist() async {
        guard pages.indices.contains(index), images[windowIndex] != nil, let library else { return }
        let lastVisible = slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?
            .placements.compactMap { placement -> Int? in
                guard window.indices.contains(Int(placement.index)) else { return nil }
                let key = window[Int(placement.index)]
                return key.chapterId == chapter.id ? Int(key.index) : nil
            }.max() ?? index
        do { try await library.recordPosition(content: content, chapter: chapter, index: Int32(index),
                                              count: Int32(pages.count), lastVisible: Int32(max(index, lastVisible)), scroll: offset) }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }

    func refreshBookmarks() async {
        do { bookmarks = try await library?.bookmarks(content: content) ?? [] }
        catch { self.error = error.localizedDescription }
    }

    func toggleBookmark() async {
        guard let library, let page = selectedPage else { return }
        do {
            _ = try await library.toggleBookmark(content: content, chapter: chapter, page: page,
                                                  index: Int32(index), count: Int32(pages.count), scroll: offset)
            await refreshBookmarks()
        } catch { self.error = error.localizedDescription }
    }

    func removeBookmark(_ bookmark: IosBookmark) async {
        do { try await library?.removeBookmark(content: content, bookmark: bookmark); await refreshBookmarks() }
        catch { self.error = error.localizedDescription }
    }

    private func fail(_ error: Error) {
        self.error = SourceErrorText.describe(error)
        challenge = SourceErrorText.challenge(error)
    }

    func retry() async {
        error = nil; challenge = nil
        if let failedChapter { await moveChapter(failedChapter.chapter, atEnd: failedChapter.atEnd) }
        else if loaded { await loadImage(at: windowIndex) } else { await load() }
    }

    func retryPage(at target: Int) async {
        guard windowPages.indices.contains(target), !loading.contains(target) else { return }
        pageFailures[target] = nil
        fetches[target]?.task.cancel()
        fetches[target] = nil
        await loadImage(at: target)
    }
}

struct ReaderPNG: FileDocument {
    static var readableContentTypes: [UTType] { [.png] }
    let data: Data
    init(image: UIImage) { data = image.pngData() ?? Data() }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct SourceReaderView: View {
    private let controlDiameter: CGFloat = 44
    @StateObject private var model: SourceReaderModel
    @StateObject private var settings: ReaderSettings
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Namespace private var chrome
    @State private var panel: ReaderPanel?
    @State private var controls = true
    @State private var export: ReaderPNG?
    @State private var exporting = false
    @State private var cropping = false
    @State private var croppedImage: UIImage?
    @State private var continuousPosition = ScrollPosition(idType: Int.self)
    @State private var scrollOffset = CGPoint.zero
    @State private var downloadStatus: String?
    @State private var showingDownloads = false
    @State private var isReaderVisible = false
    @State private var savedIdleTimerDisabled: Bool?
    private var downloadEntry: ReaderDownloadEntry? { downloads.entry(content: model.content, chapter: model.chapter) }
    private var downloading: Bool { downloadEntry?.state == .running || downloadEntry?.state == .queued }
    private var currentDownloadStatus: String? {
        if let downloadStatus { return downloadStatus }
        if downloadEntry?.state == .completed {
            return ReaderDownloads.isDownloaded(content: model.content, chapter: model.chapter)
                ? "本章已下载，可离线阅读" : "下载文件不完整，可重新下载本章"
        }
        return downloadEntry?.status
    }

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        _model = StateObject(wrappedValue: SourceReaderModel(runtime: runtime, content: content, chapter: chapter))
        _settings = StateObject(wrappedValue: ReaderSettings(contentID: content.id))
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                readingSurface(size: geometry.size)
                    .onTapGesture(coordinateSpace: .local) { location in tap(at: location, size: geometry.size) }
                if model.isLoadingPages { ProgressView("正在读取漫画页…").tint(.gray) }
                if let error = model.error {
                    VStack {
                        Text(error).foregroundStyle(.red).accessibilityIdentifier("reader.error")
                        if let challenge = model.challenge {
                            ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                                  sourceName: model.content.sourceName) { await model.retry() }
                        }
                        Button("重试") { Task { await model.retry() } }.buttonStyle(.glass)
                    }.padding().background(.regularMaterial, in: .rect(cornerRadius: 16))
                }
                if model.loaded && model.pages.isEmpty && model.error == nil {
                    ContentUnavailableView("暂无漫画页", systemImage: "doc").foregroundStyle(.gray)
                }
                if controls { chromeView(height: geometry.size.height) }
            }
            .clipped()
            .onChange(of: geometry.size, initial: true) { _, size in model.configure(size: size, preferences: settings.value) }
            .onChange(of: settings.value) { _, value in
                model.configure(size: geometry.size, preferences: value)
            }
        }
        // Outside the clipped canvas, so the page background also fills the status bar and home indicator areas.
        .background(settings.background.ignoresSafeArea())
        .background(EdgeSwipeBack())
        .toolbar(.hidden, for: .navigationBar, .tabBar)
        .statusBarHidden(!controls)
        .onAppear { isReaderVisible = true; updateIdleTimer() }
        .onChange(of: settings.value.keepScreenOn) { _, _ in updateIdleTimer() }
        .onChange(of: scenePhase) { previous, phase in
            updateIdleTimer()
            if phase == .active {
                Task { await model.load(); await model.loadImage(at: model.windowIndex) }
            } else if previous == .active {
                model.autoScroll = false
                model.stopPreloading()
                // Give the already-authorized position write a chance to finish before iOS suspends the app.
                let save = ReaderBackgroundSave()
                save.begin()
                Task {
                    defer { save.end() }
                    await model.persist()
                }
            }
        }
        .task {
            // A reader shown again keeps its model: it continues where it is rather than where this model began.
            let shownAgain = model.loaded
            model.library = library.library; await model.load()
            if settings.value.mode >= 3 {
                await Task.yield()
                scrollContinuous(to: shownAgain ? model.currentOrigin : model.restoreOrigin)
            }
        }
        .task(id: "\(model.windowRevision)-\(model.windowIndex)") {
            await model.loadImage(at: model.windowIndex); await model.persist()
        }
        .onChange(of: model.scrollRevision) { _, revision in
            let origin = model.restoreOrigin
            Task { @MainActor in
                await Task.yield()
                if model.scrollRevision == revision { scrollContinuous(to: origin) }
            }
        }
        .task(id: model.autoScroll) {
            var elapsed = 0
            while model.autoScroll && !Task.isCancelled {
                if scenePhase != .active || model.error != nil || model.hasVisiblePageFailure ||
                    (model.loaded && model.pages.isEmpty) {
                    model.autoScroll = false; break
                }
                if !model.isReadyForAutoScroll {
                    try? await Task.sleep(for: .milliseconds(16))
                    continue
                }
                if settings.value.mode >= 3 {
                    try? await Task.sleep(for: .milliseconds(16))
                    guard !Task.isCancelled, model.autoScroll else { break }
                    let current = settings.value.mode == 3 ? scrollOffset.y : scrollOffset.x
                    let direction = settings.value.continuousForwardSign
                    let next = current + direction * CGFloat(model.engine.autoScrollDistance(speed: Float(settings.value.autoSpeed), elapsedMillis: 16))
                    if next < 0 || next > CGFloat(model.engine.scrollLimit()) {
                        if await model.advanceChapterAutomatically() { elapsed = 0; continue }
                        model.autoScroll = false; await model.persist(); break
                    }
                    scrollContinuous(to: next)
                    elapsed += 16
                    if elapsed >= 5000 { elapsed = 0; await model.persist() }
                    continue
                }
                try? await Task.sleep(for: .milliseconds(Int64(model.engine.autoScrollDelay(speed: Float(settings.value.autoSpeed)))))
                guard !Task.isCancelled && model.autoScroll else { break }
                if model.slotIndex + 1 < model.slots.count {
                    withAnimation(pageAnimation) { model.movePage(forward: true) }
                } else if let next = model.nextChapter { await model.moveChapter(next) }
                else { model.autoScroll = false }
            }
        }
        .onDisappear {
            isReaderVisible = false
            updateIdleTimer()
            model.autoScroll = false
            model.stopPreloading()
            Task { await model.persist(); await library.refresh(); await ReaderTileBaseCache.shared.clear() }
        }
        .readerPresence()
        .sheet(isPresented: $showingDownloads) { ReaderDownloadsSheet() }
        .fileExporter(isPresented: $exporting, document: export, contentType: .png,
                      defaultFilename: "\(model.content.title)-\(model.index + 1)") { result in
            if case .failure(let error) = result { downloadStatus = error.localizedDescription }
        }
        .sheet(isPresented: $cropping, onDismiss: {
            if let image = croppedImage { croppedImage = nil; save(image) }
        }) {
            if let image = model.image { ReaderCropView(image: image) { croppedImage = $0 } }
        }
    }

    /// Android's ADVANCED and SIMULATION turns (stored as 4 and 5) need a pager that owns its travel
    /// (see `ReaderTurnPager`); the value passed on is Android's `ReaderAnimation` ordinal.
    private var turnStyle: Int? {
        let style = settings.value.animation
        return settings.value.mode < 3 && !reduceMotion && (style == 4 || style == 5) ? style - 2 : nil
    }

    @ViewBuilder
    private func readingSurface(size: CGSize) -> some View {
        let sceneRevision = model.sceneRevision
        if let style = turnStyle {
            ReaderTurnPager(
                count: model.slots.count,
                revision: model.windowRevision,
                current: Binding(get: { model.slotIndex }, set: {
                    if model.sceneRevision == sceneRevision { model.slotIndex = $0 }
                }),
                style: style, vertical: settings.value.mode == 2, mirrored: settings.value.mode == 1,
                engine: model.engine, size: size,
                isLocked: { model.isPageZoomed },
                onDragStart: { model.userScrolled(); model.isUserPaging = true },
                onSettle: { slot in
                    if model.sceneRevision == sceneRevision {
                        model.selectSlot(slot); model.isUserPaging = false
                    }
                }
            ) { slot in
                if let spread = model.slots.first(where: { Int($0.index) == slot }) {
                    // Opaque like Android's slot background: the next page waits pinned underneath.
                    ReaderSpread(model: model, settings: settings, slot: spread, size: size)
                        .id(model.slotIdentity(spread))
                        .background(settings.background)
                }
            }
            .id("\(settings.value.mode)-\(sceneRevision)")
            .accessibilityValue(model.readingPositionDescription)
        } else {
            scrollingSurface(size: size)
        }
    }

    @ViewBuilder
    private func scrollingSurface(size: CGSize) -> some View {
        // Android creates its pager with the reading page as `initialPage` once the pages are known (Aidoku sets its
        // page view controller to the reading page). A paged scroll view built before that would start on the first
        // slot and have to be moved there afterwards, which a lazy stack that has not laid out yet drops.
        if settings.value.mode >= 3 || model.loaded { scrollView(size: size) }
    }

    @ViewBuilder
    private func scrollView(size: CGSize) -> some View {
        let vertical = settings.value.mode == 2 || settings.value.mode == 3
        let continuous = settings.value.mode >= 3
        let windowRevision = model.windowRevision
        let sceneRevision = model.sceneRevision
        let mode = settings.value.mode
        // Continuous strips can pan across their reading axis after zooming.
        ScrollViewReader { proxy in
        ScrollView(continuous ? [.vertical, .horizontal] : vertical ? .vertical : .horizontal) {
            Group {
                if vertical {
                    LazyVStack(spacing: 0) { slots(size: size, continuous: continuous) }
                } else {
                    LazyHStack(spacing: 0) { slots(size: size, continuous: continuous) }
                }
            }
            .scrollTargetLayout()
        }
        .scrollIndicators(.hidden)
        .accessibilityIdentifier("reader.canvas")
        .accessibilityValue(model.readingPositionDescription)
        .scrollBounceBehavior(.always, axes: vertical ? .vertical : .horizontal)
        .modifier(ReaderPaging(paged: !continuous))
        .modifier(ReaderScrollPosition(continuous: continuous, position: $continuousPosition,
                                      slot: Binding<String?>(get: {
                                          model.slots.first { Int($0.index) == model.slotIndex }.map(model.slotIdentity)
                                      },
                                          set: {
                                              if model.windowRevision == windowRevision, model.sceneRevision == sceneRevision,
                                                 settings.value.mode == mode,
                                                 let value = $0,
                                                 let slot = model.slots.first(where: { model.slotIdentity($0) == value }) {
                                                  model.scrolledToSlot(Int(slot.index))
                                              }
                                          })))
        // All modes use physical scroll coordinates; RTL reverses slot order rather than the scroll coordinate system.
        .environment(\.layoutDirection, .leftToRight)
        .id("\(mode)-\(model.sceneRevision)")
        .onScrollGeometryChange(for: ReaderScrollViewport.self) { ReaderScrollViewport($0, vertical: vertical) }
        action: { _, viewport in
            guard model.windowRevision == windowRevision, model.sceneRevision == sceneRevision,
                  settings.value.mode == mode else { return }
            let point = continuous ? viewport.rawOffset : viewport.offset
            if !continuous, model.pagedWindowNeedsRestore, !model.isUserPaging {
                let extent = vertical ? size.height : size.width
                let physical = settings.value.readsRightToLeft ? model.slots.count - 1 - model.slotIndex : model.slotIndex
                let target = CGFloat(physical) * extent
                if abs((vertical ? point.y : point.x) - target) < 2 {
                    model.pagedWindowNeedsRestore = false
                } else if viewport.limit >= CGFloat(max(0, model.slots.count - 1)) * extent - 2 {
                    scrollToReadingSlot(proxy)
                }
                return
            }
            if continuous { scrollOffset = point; model.continuousPosition(x: point.x, y: point.y) }
            else if model.isUserPaging {
                reportPagedOffset(vertical ? point.y : point.x, extent: vertical ? size.height : size.width)
            }
        }
        .onScrollPhaseChange { previous, phase, context in
            guard model.windowRevision == windowRevision, model.sceneRevision == sceneRevision,
                  settings.value.mode == mode else { return }
            if !continuous, phase == .idle, model.isUserPaging {
                let viewport = ReaderScrollViewport(context.geometry, vertical: vertical)
                reportPagedOffset(vertical ? viewport.offset.y : viewport.offset.x,
                                  extent: vertical ? size.height : size.width)
            }
            if phase == .interacting {
                model.pagedWindowNeedsRestore = false
                model.userScrolled()
            }
            switch phase {
            case .tracking: break
            case .interacting, .decelerating: model.isUserPaging = true
            case .idle, .animating:
                // The settling report can arrive in the same update as the phase change.
                Task { @MainActor in
                    await Task.yield()
                    if model.windowRevision == windowRevision, model.sceneRevision == sceneRevision {
                        model.isUserPaging = false
                    }
                }
            @unknown default: break
            }
            if phase == .idle && !model.autoScroll { Task { await model.persist() } }
        }
        .modifier(ReaderStripZoom(enabled: continuous, zoom: model.stripZoom, size: size) { zoom, location in
            zoomStrip(to: zoom, at: location, size: size)
        })
        .onChange(of: model.loaded) { _, loaded in
            if continuous && loaded { scrollContinuous(to: model.restoreOrigin) }
        }
        .onChange(of: model.windowRevision) { _, _ in
            if continuous { scrollContinuous(to: model.restoreOrigin) }
            else {
                Task { @MainActor in
                    await Task.yield()
                    if !model.isUserPaging { scrollToReadingSlot(proxy) }
                }
            }
        }
        .onChange(of: model.slotIndex) { _, _ in
            guard !continuous, !model.isUserPaging else { return }
            Task { @MainActor in
                await Task.yield()
                if !model.isUserPaging { scrollToReadingSlot(proxy) }
            }
        }
        // Created at the reading slot through the position binding; as Android's pager applies its initial page once
        // the pages are laid out, it is also moved there once after the first layout, in case the binding was not
        // applied. Recreated when the reading mode or page-turn style changes, it keeps the page being read.
        .onAppear {
            guard !continuous else { return }
            Task { @MainActor in
                await Task.yield()
                if !model.isUserPaging { scrollToReadingSlot(proxy) }
            }
        }
        }
    }

    @ViewBuilder private func slots(size: CGSize, continuous: Bool) -> some View {
        if settings.value.readsRightToLeft {
            slotViews(model.identifiedSlots.reversed(), size: size, continuous: continuous)
        } else {
            slotViews(model.identifiedSlots, size: size, continuous: continuous)
        }
    }

    @ViewBuilder private func slotViews<Slots: RandomAccessCollection>(
        _ ordered: Slots, size: CGSize, continuous: Bool
    ) -> some View where Slots.Element == ReaderWindowSlot {
        ForEach(ordered) { item in
            let slot = item.slot
            if continuous, let placement = slot.placements.first {
                ReaderPageTile(
                    model: model,
                    settings: settings,
                    index: Int(placement.index),
                    segment: Int(placement.segment),
                    displaySize: CGSize(width: CGFloat(placement.width), height: CGFloat(placement.height)),
                    viewportOffset: scrollOffset,
                    zoomScale: 1
                )
                    .frame(width: CGFloat(placement.width), height: CGFloat(placement.height))
                    .id(Int(slot.index))
            } else {
                ReaderSpread(model: model, settings: settings, slot: slot, size: size)
                    .scrollTransition(.interactive, axis: settings.value.mode == 2 ? .vertical : .horizontal) { content, phase in
                        content.opacity(!reduceMotion && settings.value.animation == 2 && !phase.isIdentity ? 0 : 1)
                            .scaleEffect(!reduceMotion && settings.value.animation == 3 && !phase.isIdentity ? 0.85 : 1)
                    }
            }
        }
    }

    private func chromeView(height: CGFloat) -> some View {
        GlassEffectContainer(spacing: 8) {
            VStack(spacing: 12) {
                if !settings.value.bottomPanels { topChrome }
                Spacer(minLength: 0)
                if settings.value.bottomPanels { topChrome }
                if !floatingActions.isEmpty {
                    HStack(spacing: 12) {
                        Spacer()
                        ForEach(floatingActions, id: \.self) { action in
                            Button {
                                if action == "auto" { model.autoScroll.toggle() } else { moreAction(action) }
                            } label: {
                                Image(systemName: action == "bookmark" && model.isBookmarked ? "bookmark.fill" : ReaderMorePanel.actionSymbol(action))
                                    .frame(width: controlDiameter, height: controlDiameter)
                            }
                                .buttonStyle(.plain)
                                .glassEffect(.regular.tint(.accentColor).interactive(), in: .circle)
                                .accessibilityLabel(ReaderMorePanel.actionTitle(action))
                                .accessibilityIdentifier("reader.\(action)")
                                .accessibilityValue(action == "bookmark" ? (model.isBookmarked ? "已添加" : "未添加") : "")
                        }
                    }
                }
                progressBar
            }
            .padding(12)
            .foregroundStyle(.primary)
        }
    }

    private var progressBar: some View {
        HStack(spacing: 20) {
            Button("上一章", systemImage: "backward.end.fill") {
                if let chapter = model.previousChapter { Task { await model.moveChapter(chapter) } }
            }
            .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
            .frame(width: controlDiameter, height: controlDiameter)
            .disabled(model.previousChapter == nil || model.isLoadingPages)
            .accessibilityIdentifier("reader.previousChapter")
            HStack(spacing: 8) {
                Text("\(model.pages.isEmpty ? 0 : model.index + 1)")
                    .font(.caption.monospacedDigit()).fixedSize()
                    .accessibilityLabel("当前页码")
                    .accessibilityValue("\(model.pages.isEmpty ? 0 : model.index + 1)")
                    .accessibilityIdentifier("reader.currentPage")
                Slider(value: Binding(
                    get: { Double(model.index) },
                    set: { value in
                        model.userScrolled()
                        model.jump(to: Int(value.rounded()))
                    }), in: 0...Double(max(1, model.pages.count - 1)), step: 1,
                    onEditingChanged: { editing in if !editing { Task { await model.persist() } } })
                    .disabled(model.pages.count <= 1 || model.isLoadingPages)
                    .accessibilityLabel("阅读进度")
                    .accessibilityValue("第 \(model.pages.isEmpty ? 0 : model.index + 1) / \(model.pages.count) 页")
                    .accessibilityIdentifier("reader.progress")
                Text("\(model.pages.count)")
                    .font(.caption.monospacedDigit()).fixedSize()
                    .accessibilityLabel("总页码")
                    .accessibilityValue("\(model.pages.count)")
                    .accessibilityIdentifier("reader.totalPages")
            }
            .padding(.horizontal, 14)
            .frame(height: controlDiameter)
            .glassEffect(.regular, in: .capsule)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("reader.progressControl")
            Button("下一章", systemImage: "forward.end.fill") {
                if let chapter = model.nextChapter { Task { await model.moveChapter(chapter) } }
            }
            .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
            .frame(width: controlDiameter, height: controlDiameter)
            .disabled(model.nextChapter == nil || model.isLoadingPages)
            .accessibilityIdentifier("reader.nextChapter")
        }
        .font(.subheadline)
    }

    @ViewBuilder private var topChrome: some View {
        if let panel {
            VStack(spacing: 0) {
                HStack {
                    Text(panel == .chapters ? model.chapter.title : "更多").font(.headline).lineLimit(1)
                    Spacer()
                    Button("收起", systemImage: "xmark") { withAnimation(chromeAnimation) { self.panel = nil } }
                        .labelStyle(.iconOnly).buttonStyle(.borderless).accessibilityIdentifier("reader.panel.close")
                }.padding(16)
                Divider()
                if panel == .chapters {
                    ReaderChapterPanel(model: model, onJump: { page in model.jump(to: page); closePanel() },
                                       onChapter: { chapter in Task {
                                           await model.moveChapter(chapter); closePanel()
                                       } },
                                       onBookmark: { bookmark in Task {
                                           if await model.openBookmark(bookmark) { closePanel() }
                                       } })
                } else {
                    ReaderMorePanel(settings: settings, autoScroll: $model.autoScroll,
                                    isBookmarked: model.isBookmarked,
                                    status: currentDownloadStatus, downloading: downloading,
                                    onAction: moreAction)
                }
            }
            .frame(maxHeight: 540)
            .glassEffect(.regular, in: .rect(cornerRadius: 28))
            .glassEffectID(panel == .chapters ? "chapters" : "more", in: chrome)
            .glassEffectTransition(.matchedGeometry)
            // Grow out of the control that opened the panel: the centred chapter capsule or the trailing more button.
            .transition(.opacity.combined(with: .scale(scale: 0.96, anchor: panelAnchor(panel))))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(panel == .chapters ? "reader.chapterPanel" : "reader.morePanel")
        } else {
            HStack {
                Button("返回", systemImage: "chevron.left") { dismiss() }
                    .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
                    .frame(width: controlDiameter, height: controlDiameter).accessibilityIdentifier("reader.back")
                // Wider than the glass container's merge spacing: neighbouring controls must stay separate shapes,
                // or the panel morphs out of their union instead of out of the button that was tapped.
                Spacer(minLength: 16)
                Button { withAnimation(chromeAnimation) { panel = .chapters } } label: {
                    Label(model.chapter.title, systemImage: "list.bullet").font(.subheadline).lineLimit(1)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                }
                .buttonStyle(.plain).glassEffect(.regular.interactive(), in: .capsule)
                .glassEffectID("chapters", in: chrome).accessibilityIdentifier("reader.chapters")
                Spacer(minLength: 16)
                Button { withAnimation(chromeAnimation) { panel = .more } } label: {
                    Image(systemName: "ellipsis").frame(width: controlDiameter, height: controlDiameter)
                }.buttonStyle(.plain).glassEffect(.regular.interactive(), in: .circle)
                    .glassEffectID("more", in: chrome).accessibilityIdentifier("reader.more")
            }
        }
    }

    private func closePanel() { withAnimation(chromeAnimation) { panel = nil } }

    private func scrollToReadingSlot(_ proxy: ScrollViewProxy) {
        guard let slot = model.slots.first(where: { Int($0.index) == model.slotIndex }) else { return }
        proxy.scrollTo(model.slotIdentity(slot), anchor: .topLeading)
    }

    private func reportPagedOffset(_ offset: CGFloat, extent: CGFloat) {
        guard extent > 0, offset.isFinite, !model.slots.isEmpty else { return }
        let physical = min(max(0, Int((offset / extent).rounded())), model.slots.count - 1)
        let selected = settings.value.readsRightToLeft ? model.slots.count - 1 - physical : physical
        model.scrolledToSlot(selected)
    }

    private func updateIdleTimer() {
        let enabled = isReaderVisible && scenePhase == .active && settings.value.keepScreenOn
        let app = UIApplication.shared
        if enabled {
            if savedIdleTimerDisabled == nil { savedIdleTimerDisabled = app.isIdleTimerDisabled }
            app.isIdleTimerDisabled = true
        } else if let previous = savedIdleTimerDisabled {
            app.isIdleTimerDisabled = previous
            savedIdleTimerDisabled = nil
        }
    }

    private func panelAnchor(_ panel: ReaderPanel) -> UnitPoint {
        let bottom = settings.value.bottomPanels
        return panel == .more ? (bottom ? .bottomTrailing : .topTrailing) : (bottom ? .bottom : .top)
    }

    /// Android-style tap grid: edges turn pages (mirrored for right-to-left), the centre toggles the controls.
    private func tap(at location: CGPoint, size: CGSize) {
        if panel != nil { closePanel(); return }
        guard settings.value.tapZones, size.width > 0, size.height > 0 else {
            withAnimation(chromeAnimation) { controls.toggle() }; return
        }
        let x = location.x / size.width, y = location.y / size.height
        var forward: Bool?
        if x < 0.3 { forward = false } else if x > 0.7 { forward = true }
        else if y < 0.2 { forward = false } else if y > 0.8 { forward = true }
        guard var direction = forward else { withAnimation(chromeAnimation) { controls.toggle() }; return }
        let rtl = settings.value.readsRightToLeft
        if rtl && x < 0.3 || rtl && x > 0.7 { direction.toggle() }
        if settings.value.reverseTaps { direction.toggle() }
        if controls { withAnimation(chromeAnimation) { controls = false } }
        turn(forward: direction, size: size)
    }

    private func turn(forward: Bool, size: CGSize) {
        model.userScrolled()
        if settings.value.mode >= 3 {
            let vertical = settings.value.mode == 3
            let current = vertical ? scrollOffset.y : scrollOffset.x
            let direction = settings.value.continuousForwardSign
            let step = (vertical ? size.height : size.width) * 0.85 * (forward ? 1 : -1) * direction
            let limit = CGFloat(model.engine.scrollLimit())
            let fromStart = direction > 0 ? current : limit - current
            if forward && fromStart >= limit - 1, let next = model.nextChapter {
                Task { await model.moveChapter(next) }; return
            }
            if !forward && fromStart <= 1, let previous = model.previousChapter {
                Task { await model.moveChapter(previous, atEnd: true) }; return
            }
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.22)) { scrollContinuous(to: min(max(0, current + step), limit)) }
            return
        }
        let target = model.slotIndex + (forward ? 1 : -1)
        if model.slots.indices.contains(target) {
            withAnimation(pageAnimation) { model.movePage(forward: forward) }
        } else if let chapter = forward ? model.nextChapter : model.previousChapter {
            Task { await model.moveChapter(chapter, atEnd: !forward) }
        }
    }
    private func scrollContinuous(to origin: CGFloat) {
        // Keep the cross-axis pan while moving along the chapter.
        if settings.value.mode == 3 { continuousPosition.scrollTo(point: CGPoint(x: scrollOffset.x, y: origin)) }
        else { continuousPosition.scrollTo(point: CGPoint(x: origin, y: scrollOffset.y)) }
    }

    /// Lays the strip out at [zoom] and scrolls so the content under [location] (a point in the viewport) stays put.
    private func zoomStrip(to zoom: CGFloat, at location: CGPoint, size: CGSize) {
        model.userScrolled()
        let previous = model.stripZoom
        model.setStripZoom(zoom)
        let ratio = model.stripZoom / previous
        guard ratio != 1 else { return }
        let x = (scrollOffset.x + location.x) * ratio - location.x
        let y = (scrollOffset.y + location.y) * ratio - location.y
        let vertical = settings.value.mode == 3
        let limit = CGFloat(model.engine.scrollLimit())
        let target = CGPoint(x: min(max(0, x), vertical ? size.width * (model.stripZoom - 1) : limit),
                             y: min(max(0, y), vertical ? limit : size.height * (model.stripZoom - 1)))
        scrollOffset = target
        Task { @MainActor in
            // After the wider layout is in place, or the scroll view clamps to the old content size.
            await Task.yield()
            continuousPosition.scrollTo(point: target)
        }
    }
    private var chromeAnimation: Animation? { reduceMotion ? nil : .spring }
    private var pageAnimation: Animation? {
        reduceMotion || settings.value.animation == 0 ? nil : .snappy(duration: 0.22)
    }
    private var floatingActions: [String] {
        var actions = settings.value.floatingActions.filter { $0 != "bookmark" }
        if settings.value.bookmarkButton { actions.append("bookmark") }
        return Array(actions.suffix(4))
    }
    private func save(_ image: UIImage) { export = ReaderPNG(image: image); exporting = true }
    private func moreAction(_ action: String) {
        switch action {
        case "chapters": withAnimation(chromeAnimation) { panel = .chapters }
        case "bookmark": Task { await model.toggleBookmark() }
        case "save": if let image = model.image { closePanel(); save(image) }
        case "crop": if model.image != nil { closePanel(); cropping = true }
        case "direction":
            if settings.value.mode == 4 { settings.value.horizontalReversed.toggle() }
            else { settings.value.mode = settings.value.mode == 1 ? 0 : 1 }
        case "browser": if let url = URL(string: model.content.publicUrl) { UIApplication.shared.open(url) }
        case "downloads": showingDownloads = true
        case "download":
            guard !downloading else { return }
            do { try downloads.enqueue(content: model.content, chapters: [model.chapter]); downloadStatus = nil }
            catch { downloadStatus = error.localizedDescription }
        default: break
        }
    }
}

enum ReaderPanel { case chapters, more }

/// Paged geometry uses physical bounds; continuous point scrolling keeps the native offset used by restoration.
private struct ReaderScrollViewport: Equatable {
    let rawOffset: CGPoint
    let offset: CGPoint
    let limit: CGFloat

    init(_ geometry: ScrollGeometry, vertical: Bool) {
        let insets = geometry.contentInsets
        rawOffset = geometry.contentOffset
        offset = CGPoint(x: geometry.contentOffset.x + insets.leading, y: geometry.contentOffset.y + insets.top)
        let content = vertical ? geometry.contentSize.height + insets.top + insets.bottom
            : geometry.contentSize.width + insets.leading + insets.trailing
        let viewport = vertical ? geometry.containerSize.height : geometry.containerSize.width
        limit = max(0, content - viewport)
    }
}

/// A short position write owns its background-time lease, including the system's expiration path.
@MainActor
private final class ReaderBackgroundSave {
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    func begin() {
        identifier = UIApplication.shared.beginBackgroundTask(withName: "reader-save-position") { [weak self] in
            self?.end()
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}

/// Hiding the navigation bar also turns off the navigation controller's left-edge swipe back. While the reader
/// is on screen this restores that edge gesture (not the full-width content swipe, which would fight page turns),
/// and hands the previous delegate back when it leaves.
struct EdgeSwipeBack: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController, UIGestureRecognizerDelegate {
        private weak var previous: UIGestureRecognizerDelegate?
        private weak var recognizer: UIGestureRecognizer?
        /// SwiftUI turns the gesture off again while its bar is hidden; keep it on while the reader shows.
        private var enabledObservation: NSKeyValueObservation?

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard let gesture = navigationController?.interactivePopGestureRecognizer, gesture.delegate !== self else { return }
            previous = gesture.delegate
            recognizer = gesture
            gesture.delegate = self
            gesture.isEnabled = true
            enabledObservation = gesture.observe(\.isEnabled, options: [.new]) { gesture, change in
                if change.newValue == false { DispatchQueue.main.async { gesture.isEnabled = true } }
            }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            enabledObservation = nil
            if let recognizer, recognizer.delegate === self { recognizer.delegate = previous }
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (navigationController?.viewControllers.count ?? 0) > 1
        }

        /// The page scroll view and the reader's drags wait for the edge swipe, which fails at once for any touch that
        /// does not start at the screen edge, so turning pages is unaffected.
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool { other !== gestureRecognizer }
    }
}

/// The system `.paging` behavior maps to native scroll-view paging: one page per swipe with the platform's
/// release animation. Wrapping it in a custom behavior loses that and lets fast flicks project past pages.
/// Pinch and double-tap zoom for the vertical strip. The pinch previews as a transform about its centre; on release
/// the strip is laid out again at the new zoom (see `SourceReaderModel.setStripZoom`).
private struct ReaderStripZoom: ViewModifier {
    let enabled: Bool
    let zoom: CGFloat
    let size: CGSize
    let commit: (CGFloat, CGPoint) -> Void
    @State private var pinch: CGFloat = 1
    @State private var anchor = UnitPoint.center

    func body(content: Content) -> some View {
        if enabled {
            content
                .scaleEffect(pinch, anchor: anchor)
                .simultaneousGesture(
                    MagnifyGesture()
                        .onChanged { value in
                            anchor = value.startAnchor
                            let minimum = CGFloat(IosReader.companion.MINIMUM_ZOOM) / zoom
                            let maximum = CGFloat(IosReader.companion.MAXIMUM_ZOOM) / zoom
                            pinch = min(max(value.magnification, minimum), maximum)
                        }
                        .onEnded { value in
                            let target = zoom * pinch
                            pinch = 1
                            commit(target, value.startLocation)
                        }
                )
                .onTapGesture(count: 2) { location in commit(zoom > 1 ? 1 : 2, location) }
                .accessibilityAction(named: zoom > 1 ? "还原缩放" : "放大") {
                    commit(zoom > 1 ? 1 : 2, CGPoint(x: size.width / 2, y: size.height / 2))
                }
        } else {
            content
        }
    }
}

private struct ReaderPaging: ViewModifier {
    let paged: Bool
    func body(content: Content) -> some View {
        if paged { content.scrollTargetBehavior(.paging) } else { content }
    }
}

private struct ReaderScrollPosition: ViewModifier {
    let continuous: Bool
    @Binding var position: ScrollPosition
    let slot: Binding<String?>
    func body(content: Content) -> some View {
        if continuous { content.scrollPosition($position) }
        else { content.scrollPosition(id: slot, anchor: .topLeading) }
    }
}

private struct ReaderPageTile: View {
    @ObservedObject var model: SourceReaderModel
    @ObservedObject var settings: ReaderSettings
    @Environment(\.displayScale) private var displayScale
    let index: Int
    var segment = 0
    var displaySize = CGSize.zero
    var viewportOffset = CGPoint.zero
    var zoomScale: CGFloat = 1
    var body: some View {
        Group {
            if let image = model.displayImage(for: index, segment: segment) {
                ZStack(alignment: .topLeading) {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(width: displaySize.width > 0 ? displaySize.width : nil,
                               height: displaySize.height > 0 ? displaySize.height : nil)
                    if segment == 0, let artifact = model.artifacts[index], artifact.tiled,
                       displaySize.width > 1, displaySize.height > 1 {
                        let tiles = model.engine.visibleTiles(
                            x: Float(viewportOffset.x),
                            y: Float(viewportOffset.y),
                            index: Int32(index),
                            encodedWidth: Int32(artifact.encodedWidth),
                            encodedHeight: Int32(artifact.encodedHeight),
                            displayScale: Float(displayScale),
                            zoomScale: Float(max(1, zoomScale))
                        )
                        if !tiles.isEmpty {
                            ReaderTiledOverlay(artifact: artifact, tiles: tiles, displaySize: displaySize)
                        }
                    }
                }
                .brightness(settings.value.brightness).contrast(settings.value.contrast)
                .saturation(settings.value.grayscale ? 0 : 1)
                .modifier(ReaderInvert(enabled: settings.value.inverted))
                .shadow(color: .black.opacity(settings.value.bookEffect ? 0.4 : 0), radius: 8)
                .accessibilityIdentifier("reader.image")
                .accessibilityLabel(model.pageDescription(at: index))
            } else if let failure = model.pageFailures[index] {
                VStack(spacing: 12) {
                    Text(failure.message).foregroundStyle(.red).accessibilityIdentifier("reader.pageError.\(index)")
                    if let challenge = failure.challenge {
                        ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                              sourceName: model.content.sourceName) { await model.retryPage(at: index) }
                    }
                    Button("重试") { Task { await model.retryPage(at: index) } }
                        .buttonStyle(.glass).accessibilityIdentifier("reader.pageRetry.\(index)")
                }.padding()
            } else { ProgressView().tint(.gray) }
        }
        .task(id: "\(model.pageRevision)-\(index)") { await model.loadImage(at: index) }
    }
}

private struct ReaderTiledOverlay: View {
    let artifact: ReaderImageArtifact
    let tiles: [IosReaderTile]
    let displaySize: CGSize
    @State private var decodedTiles: [String: UIImage] = [:]

    private static func key(_ tile: IosReaderTile) -> String {
        "\(tile.decodeLeft):\(tile.decodeTop):\(tile.decodeWidth):\(tile.decodeHeight):\(tile.sampleSize)"
    }

    private var tileSignature: String {
        tiles.map(Self.key).joined(separator: "|")
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(Array(tiles.enumerated()), id: \.offset) { _, tile in
                if let image = decodedTiles[Self.key(tile)] {
                    let layout = SourceImageFile.tileLayout(
                        logical: CGRect(x: Int(tile.logicalLeft), y: Int(tile.logicalTop),
                                        width: Int(tile.logicalWidth), height: Int(tile.logicalHeight)),
                        decoded: CGRect(x: Int(tile.decodeLeft), y: Int(tile.decodeTop),
                                        width: Int(tile.decodeWidth), height: Int(tile.decodeHeight)),
                        sourceSize: CGSize(width: artifact.encodedWidth, height: artifact.encodedHeight),
                        displaySize: displaySize
                    )
                    Image(uiImage: image).resizable()
                        .frame(width: layout.decodedSize.width, height: layout.decodedSize.height)
                        .offset(layout.decodedOffset)
                        .frame(width: layout.frame.width, height: layout.frame.height, alignment: .topLeading)
                        .clipped()
                        .offset(x: layout.frame.minX, y: layout.frame.minY)
                }
            }
        }
        .allowsHitTesting(false)
        .task(id: tileSignature) {
            let activeKeys = Set(tiles.map(Self.key))
            decodedTiles = decodedTiles.filter { activeKeys.contains($0.key) }
            for tile in tiles {
                guard !Task.isCancelled else { break }
                let key = Self.key(tile)
                guard decodedTiles[key] == nil else { continue }
                if let image = try? await artifact.decodeTile(tile) {
                    guard !Task.isCancelled else { break }
                    decodedTiles[key] = image
                }
            }
        }
    }
}

private struct ReaderInvert: ViewModifier {
    let enabled: Bool
    func body(content: Content) -> some View { if enabled { content.colorInvert() } else { content } }
}

private struct ReaderSpread: View {
    @ObservedObject var model: SourceReaderModel
    @ObservedObject var settings: ReaderSettings
    let slot: IosReaderSlot
    let size: CGSize
    @State private var scale = 1.0
    @State private var baseScale = 1.0
    @State private var pan = CGSize.zero
    @State private var basePan = CGSize.zero
    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(Array(slot.placements.enumerated()), id: \.offset) { _, placement in
                ReaderPageTile(
                    model: model,
                    settings: settings,
                    index: Int(placement.index),
                    segment: Int(placement.segment),
                    displaySize: CGSize(width: CGFloat(placement.width), height: CGFloat(placement.height)),
                    viewportOffset: CGPoint(x: pan.width, y: pan.height),
                    zoomScale: scale
                )
                    .frame(width: CGFloat(placement.width), height: CGFloat(placement.height))
                    .offset(x: CGFloat(placement.x), y: CGFloat(placement.y))
            }
        }
        // Placements already include centering; keep their origin at the top-left of the viewport.
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .scaleEffect(scale).offset(pan)
        .frame(width: size.width, height: size.height).clipped()
        .environment(\.layoutDirection, .leftToRight)
        .simultaneousGesture(MagnifyGesture().onChanged { scale = min(6, max(1, baseScale * $0.magnification)) }
            .onEnded { _ in baseScale = scale; clamp() })
        .highPriorityGesture(DragGesture().onChanged {
            if canPan { pan = CGSize(width: basePan.width + $0.translation.width, height: basePan.height + $0.translation.height) }
        }.onEnded { _ in if canPan { clamp() } }, including: canPan ? .all : .subviews)
        .onTapGesture(count: 2) { withAnimation { scale = scale > 1 ? 1 : 2; baseScale = scale; clamp() } }
        .onChange(of: scale > 1) { _, zoomed in model.isPageZoomed = zoomed }
    }
    private var canPan: Bool {
        scale > 1 || (slot.placements.map { $0.x + $0.width }.max() ?? 0) > Float(size.width) ||
            (slot.placements.map { $0.y + $0.height }.max() ?? 0) > Float(size.height)
    }
    private func clamp() {
        let left = slot.placements.map { $0.x }.min() ?? 0
        let right = slot.placements.map { $0.x + $0.width }.max() ?? Float(size.width)
        let top = slot.placements.map { $0.y }.min() ?? 0
        let bottom = slot.placements.map { $0.y + $0.height }.max() ?? Float(size.height)
        pan = CGSize(width: CGFloat(model.engine.clampPan(value: Float(pan.width), contentMin: left, contentMax: right,
                                                           viewport: Float(size.width), scale: Float(scale))),
                     height: CGFloat(model.engine.clampPan(value: Float(pan.height), contentMin: top, contentMax: bottom,
                                                            viewport: Float(size.height), scale: Float(scale))))
        basePan = pan
    }
}
