import Combine
import KototoroShared
import SwiftUI

/// Details fetched this session, so reopening a work, or going back to it, shows its chapters at once while the
/// source is asked again. Long lists come from many sequential page requests (a 2,000-chapter novel takes about a
/// minute on the interpreter-only JVM), so waiting on every open is not acceptable.
@MainActor
enum DetailsCache {
    private static var entries: [String: IosSourceContent] = [:]
    private static var order: [String] = []
    private static let limit = 24

    private static func key(_ content: IosSourceContent) -> String { content.sourceName + "\n" + content.url }

    static func get(_ content: IosSourceContent) -> IosSourceContent? { entries[key(content)] }

    static func put(_ content: IosSourceContent) {
        let key = key(content)
        order.removeAll { $0 == key }
        order.append(key)
        entries[key] = content
        while order.count > limit { entries[order.removeFirst()] = nil }
    }
}

/// Both child screens borrow the browsing session; leaving a screen must not close the process VM.
@MainActor
final class SourceDetailsModel: ObservableObject {
    @Published private(set) var details: IosSourceContent? {
        didSet { chapters = details?.chapters ?? [] }
    }
    /// Bridged once per details: every `IosSourceContent.chapters` read maps the whole list again.
    private(set) var chapters: [IosSourceChapter] = []
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    @Published private(set) var isLoading = false
    @Published var selectedLine: String?
    let runtime: IosSources
    let content: IosSourceContent
    private var detailsRequest: UUID?
    /// The shown details came from the source in this screen, not from the cache or the saved record.
    private var fetched = false

    init(runtime: IosSources, content: IosSourceContent) {
        self.runtime = runtime
        self.content = content
    }

    @Published var selectedBranch: String?

    /// Translation branches in source order (manga/novels); a single unnamed branch is one nil entry.
    var branches: [String?] {
        var seen = Set<String?>()
        return chapters.map(\.branch).filter { seen.insert($0).inserted }
    }

    /// List chapters by the numbers the extension declared instead of the source's own order (the default, as Mihon
    /// lists them): some extensions misparse numbers from titles, which would scramble a number-sorted list.
    @Published var orderByNumber = false

    /// Stable: chapters with equal numbers keep the source order.
    private func ordered(_ list: [IosSourceChapter]) -> [IosSourceChapter] {
        guard orderByNumber else { return list }
        return list.enumerated()
            .sorted { ($0.element.number, $0.offset) < ($1.element.number, $1.offset) }
            .map(\.element)
    }

    func chapterList(query: String, newestFirst: Bool) -> [IosSourceChapter] {
        var chapters = ordered(self.chapters)
        if branches.count > 1 { chapters = chapters.filter { $0.branch == selectedBranch } }
        let text = query.trimmingCharacters(in: .whitespaces)
        if !text.isEmpty { chapters = chapters.filter { $0.title.localizedCaseInsensitiveContains(text) } }
        return newestFirst ? chapters.reversed() : chapters
    }

    func refresh() async {
        do {
            let result = try await runtime.refreshDetails(content: details ?? content)
            apply(result); fetched = true; error = nil; challenge = nil
            DetailsCache.put(result)
        } catch { if !Task.isCancelled { fail(error) } }
    }

    /// Readable text, and whether the site asked for browser verification.
    private func fail(_ error: Error) {
        self.error = SourceErrorText.describe(error)
        challenge = SourceErrorText.challenge(error)
    }

    var isVideo: Bool { content.mediaKind == "VIDEO" }
    var isNovel: Bool { content.mediaKind == "NOVEL" }

    /// Playback lines in source order; a single unnamed line is represented by one nil entry.
    var lines: [String?] {
        var seen = Set<String?>()
        return chapters.map(\.line).filter { seen.insert($0).inserted }
    }

    var visibleChapters: [IosSourceChapter] {
        guard isVideo, lines.count > 1 else { return ordered(chapters) }
        let line = selectedLine ?? lines.first ?? nil
        return ordered(chapters.filter { $0.line == line })
    }

    /// Shows this session's details, the record the screen was opened with (library, history) or the details saved
    /// when the work was last opened, before [load] asks the source; the fresh list replaces it when it arrives.
    func showCached(stored library: IosLibrary?) async {
        guard details == nil else { return }
        if let cached = DetailsCache.get(content) ?? (content.chapterCount > 0 ? content : nil) {
            apply(cached)
        } else if let library, let saved = try? await library.storedDetails(content: content), details == nil {
            apply(saved)
        }
    }

    /// Keeps the chosen line and branch when they still exist.
    private func apply(_ result: IosSourceContent) {
        details = result
        if !lines.contains(selectedLine) { selectedLine = lines.first ?? nil }
        if !branches.contains(selectedBranch) { selectedBranch = branches.first ?? nil }
    }

    func load() async {
        guard !isLoading, !fetched || error != nil else { return }
        let request = UUID()
        detailsRequest = request
        isLoading = true
        error = nil; challenge = nil
        defer { if detailsRequest == request { isLoading = false } }
        do {
            let result = try await runtime.details(content: content)
            try Task.checkCancellation()
            if detailsRequest == request {
                apply(result)
                fetched = true
                DetailsCache.put(result)
            }
        } catch {
            // A shown cached or saved list stays (offline reading); the error offers a retry above it.
            if !Task.isCancelled, detailsRequest == request { fail(error) }
        }
    }
}

struct SourceDetailsView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @StateObject private var model: SourceDetailsModel
    @State private var playing: PlaybackRequest?
    @State private var reading: PlaybackRequest?
    @State private var isSummaryExpanded = false
    @State private var isFavourite = false
    @State private var recordError: String?
    @State private var resume: IosReadingPosition?
    @State private var editingCategories = false
    @State private var savingFavourite = false
    @State private var chapterQuery = ""
    @AppStorage("details.newestFirst") private var newestFirst = false
    /// "source" (default) or "number"; see SourceDetailsModel.orderByNumber.
    @AppStorage("details.chapterOrder") private var chapterOrder = "source"
    @AppStorage(PanoramaKeys.enabled) private var panorama = true
    @AppStorage(PanoramaKeys.layout) private var panoramaLayout = PanoramaLayout.top
    @State private var scrollOffset: CGFloat = 0
    /// Opened from "continue reading": go straight on with the chapter being read, once.
    private let autoResume: Bool
    @State private var resumed = false

    init(runtime: IosSources, content: IosSourceContent, autoResume: Bool = false) {
        _model = StateObject(wrappedValue: SourceDetailsModel(runtime: runtime, content: content))
        self.autoResume = autoResume
    }

    private func resumeIfAsked() {
        guard autoResume, !resumed, let details = model.details,
              let target = resumeChapter() ?? model.chapters.first else { return }
        resumed = true
        if model.isVideo { playing = PlaybackRequest(content: details, chapter: target) }
        else { reading = PlaybackRequest(content: details, chapter: target) }
    }

    var body: some View {
        List {
            header
            if let recordError { Text(recordError).foregroundStyle(.red) }
            if model.isLoading && model.details == nil {
                ProgressView("正在读取详情…").frame(maxWidth: .infinity).listRowBackground(Color.clear)
            } else if model.isLoading {
                // A cached or saved list is already shown and usable; the source is being asked again.
                Label { Text("正在更新章节…") } icon: { ProgressView() }
                    .font(.footnote).foregroundStyle(.secondary)
                    .accessibilityIdentifier("details.updating")
            }
            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(.red).accessibilityIdentifier("details.error")
                    if let challenge = model.challenge {
                        ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                              sourceName: model.content.sourceName) { await model.load() }
                    }
                    Button("重试") { Task { await model.load() } }.disabled(model.isLoading)
                }
            }
            if let details = model.details {
                if model.isVideo { episodes(details) } else { chapters(details) }
            }
        }
        .listStyle(.insetGrouped)
        .listSectionSpacing(.compact)
        .scrollContentBackground(.hidden)
        .backgroundPreferenceValue(DetailsCoverBoundsKey.self) { bounds in
            GeometryReader { geometry in
                backdrop(coverBottom: bounds.map { geometry[$0].maxY + scrollOffset })
            }
            .ignoresSafeArea()
        }
        .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y + $0.contentInsets.top }) { _, offset in
            scrollOffset = offset
        }
        // Over the panorama the bar stays clear; the title appears once the header has scrolled away.
        .navigationTitle(panorama && scrollOffset < 220 ? "" : model.content.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            // Continuing from the library or history needs no fresh list: resume from the saved one at once.
            await model.showCached(stored: library.library)
            if model.details != nil {
                await refreshRecords()
                resumeIfAsked()
            }
            await sources.recordUsage(sourceName: model.content.sourceName)
            await model.load()
            await refreshRecords()
            await sources.refreshSettings()
            resumeIfAsked()
        }
        .refreshable { await model.refresh(); await refreshRecords(); await sources.refreshSettings() }
        .onAppear { model.orderByNumber = chapterOrder == "number" }
        .onChange(of: chapterOrder) { _, order in withAnimation { model.orderByNumber = order == "number" } }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let url = URL(string: model.content.publicUrl), url.scheme?.hasPrefix("http") == true {
                    ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up") }
                    Menu("更多", systemImage: "ellipsis") {
                        Button("在浏览器中打开", systemImage: "safari") { UIApplication.shared.open(url) }
                        if isFavourite { Button("整理分类", systemImage: "folder") { editingCategories = true } }
                    }
                }
            }
        }
        .sheet(isPresented: $editingCategories, onDismiss: { Task { await refreshRecords() } }) {
            FavouriteCategoriesView(content: model.details ?? model.content)
        }
        .onChange(of: reading == nil) { _, closed in if closed { Task { await refreshRecords() } } }
        .navigationDestination(item: $reading) { request in
            Group {
                if model.isNovel { NovelReaderView(runtime: model.runtime, content: request.content, chapter: request.chapter) }
                else { SourceReaderView(runtime: model.runtime, content: request.content, chapter: request.chapter) }
            }
            .id(request.id)
        }
        .fullScreenCover(item: $playing, onDismiss: { Task { await refreshRecords() } }) { request in
            VideoPlayerView(runtime: model.runtime, content: request.content, chapter: request.chapter)
                .id(request.id)
        }
    }

    private func removeFavourite() {
        Task {
            savingFavourite = true
            defer { savingFavourite = false }
            do {
                try await library.setFavourite(model.details ?? model.content, favourite: false)
                isFavourite = false
            } catch { recordError = error.localizedDescription }
        }
    }

    private func refreshRecords() async {
        guard let content = model.details, let database = library.library else { return }
        do {
            try await database.saveDetails(content: content)
            isFavourite = try await database.isFavourite(content: content).boolValue
            resume = try await database.position(content: content)
            // Open the chapter list on the branch being read.
            if let chapter = resumeChapter(), model.branches.contains(chapter.branch) { model.selectedBranch = chapter.branch }
        } catch { recordError = error.localizedDescription }
    }

    private var header: some View {
        let shown = model.details ?? model.content
        return Section {
            VStack(alignment: .leading, spacing: 16) {
                // Leave the upper part of the panorama banner uncovered, as Android's header does.
                if panorama && panoramaLayout == .top { Color.clear.frame(height: 48) }
                HStack(alignment: .top, spacing: 16) {
                    CoverImage(runtime: model.runtime, content: shown)
                        .frame(width: 118, height: 177)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
                        .accessibilityLabel("封面")
                        .accessibilityIdentifier("details.cover")
                        .anchorPreference(key: DetailsCoverBoundsKey.self, value: .bounds) { $0 }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(shown.title).font(.title3.weight(.bold)).lineLimit(4)
                            .textSelection(.enabled)
                            .accessibilityIdentifier("details.title")
                        if let alt = shown.altTitles.first {
                            Text(alt).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                        }
                        if !shown.authors.isEmpty {
                            Label(shown.authors.joined(separator: "、"), systemImage: "person")
                                .font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Label(sources.sources.first { $0.name == shown.sourceName }?.title ?? shown.sourceName, systemImage: "globe").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        HStack(spacing: 6) {
                            if let state = shown.state { pill(Self.stateTitle(state), tint: Self.stateTint(state)) }
                            if shown.rating > 0 {
                                pill(String(format: "★ %.1f", Double(shown.rating) * 5), tint: .orange)
                            }
                            if model.details != nil {
                                // Video lines repeat the same episodes; count the shown line only.
                                let count = model.isVideo ? model.visibleChapters.count : model.chapters.count
                                pill(model.isVideo ? "\(count) 集" : "\(count) 章", tint: .secondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                actions(shown)
                if !shown.tags.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(shown.tags, id: \.self) { tag in
                            Text(tag).font(.caption).padding(.horizontal, 10).padding(.vertical, 5)
                                .background(.quaternary, in: .capsule)
                        }
                    }
                }
                if let summary = shown.summary.map(Self.plainText), !summary.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(summary)
                            .font(.callout)
                            .lineLimit(isSummaryExpanded ? nil : 4)
                            .textSelection(.enabled)
                            .accessibilityIdentifier("details.summary")
                        if summary.count > 120 || summary.filter({ $0 == "\n" }).count >= 4 {
                            Button(isSummaryExpanded ? "收起" : "展开") { withAnimation { isSummaryExpanded.toggle() } }
                                .font(.footnote.weight(.semibold))
                        }
                    }
                }
            }
            .padding(.vertical, 8)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20))
        }
        .listRowSeparator(.hidden)
    }

    /// Android's panorama cover at the top (or the softly blurred cover when it is turned off), faded into the
    /// grouped background.
    private func backdrop(coverBottom: CGFloat?) -> some View {
        ZStack(alignment: .top) {
            Color(uiColor: .systemGroupedBackground)
            if panorama {
                DetailsPanorama(runtime: model.runtime, content: model.details ?? model.content,
                                scrollOffset: scrollOffset, coverBottom: coverBottom)
            } else {
                CoverImage(runtime: model.runtime, content: model.details ?? model.content, plain: true)
                    .frame(height: 420).frame(maxWidth: .infinity)
                    .blur(radius: 50).opacity(0.5)
                    .mask(LinearGradient(colors: [.black, .black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// Primary read/continue action plus favourite, mirroring Android's details action bar.
    @ViewBuilder
    private func actions(_ shown: IosSourceContent) -> some View {
        HStack(spacing: 12) {
            if let details = model.details, let target = resumeChapter() ?? model.chapters.first {
                let resuming = resumeChapter() != nil
                let title = resuming ? (model.isVideo ? "继续播放" : "继续阅读") : (model.isVideo ? "开始播放" : "开始阅读")
                Group {
                    if model.isVideo {
                        Button { playing = PlaybackRequest(content: details, chapter: target) } label: {
                            primaryLabel(title, subtitle: target.title, icon: "play.fill")
                        }
                    } else {
                        // A NavigationLink inside a List row would turn the whole header into a row link.
                        Button { reading = PlaybackRequest(content: details, chapter: target) } label: {
                            primaryLabel(title, subtitle: target.title, icon: "book.fill")
                        }
                    }
                }
                .buttonStyle(.glassProminent)
                .accessibilityIdentifier(resuming ? "details.resume" : "details.start")
            } else {
                Button {} label: { primaryLabel(model.isVideo ? "开始播放" : "开始阅读", subtitle: nil, icon: "book.fill") }
                    .buttonStyle(.glassProminent).disabled(true)
            }
            Button(isFavourite ? "整理收藏" : "收藏", systemImage: isFavourite ? "heart.fill" : "heart") {
                editingCategories = true
            }
                .labelStyle(.iconOnly)
                .font(.title3)
                .foregroundStyle(isFavourite ? Color.pink : Color.primary)
                .frame(width: 52, height: 52)
                .buttonStyle(.glass).buttonBorderShape(.circle)
                .accessibilityIdentifier("details.favourite")
                .disabled(model.details == nil || savingFavourite)
                .contextMenu {
                    Button("收藏分类", systemImage: "folder") { editingCategories = true }
                    if isFavourite {
                        Button("取消收藏", systemImage: "heart.slash", role: .destructive, action: removeFavourite)
                    }
                }
        }
    }

    private func primaryLabel(_ title: String, subtitle: String?, icon: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.headline)
                if let subtitle { Text(subtitle).font(.caption).opacity(0.85).lineLimit(1) }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, minHeight: 40)
        .padding(.horizontal, 6)
    }

    private func pill(_ text: String, tint: Color) -> some View {
        Text(text).font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 4)
            .foregroundStyle(tint)
            .background(tint.opacity(0.15), in: .capsule)
    }

    private func resumeChapter() -> IosSourceChapter? {
        guard let resume else { return nil }
        return model.chapters.first { $0.id == resume.chapterId }
    }

    static func stateTitle(_ state: String) -> String {
        switch state {
        case "ONGOING": "连载中"
        case "FINISHED": "已完结"
        case "ABANDONED": "已放弃"
        case "PAUSED": "暂停更新"
        case "UPCOMING": "即将推出"
        case "RESTRICTED": "受限"
        default: state
        }
    }

    static func stateTint(_ state: String) -> Color {
        switch state {
        case "ONGOING": .green
        case "FINISHED": .blue
        case "ABANDONED", "RESTRICTED": .red
        default: .orange
        }
    }

    @ViewBuilder
    private func episodes(_ details: IosSourceContent) -> some View {
        Section {
            if model.chapters.isEmpty { Text("暂无可播放的剧集").accessibilityIdentifier("details.empty") }
            if model.lines.count > 1 {
                if model.lines.count <= 3 {
                    linePicker.pickerStyle(.segmented)
                } else {
                    linePicker.pickerStyle(.menu)
                }
            }
            if Self.hasLongTitles(model.visibleChapters) {
                // Long episode names (e.g. "Episode 1 - I'm Luffy!") read better one per row.
                ForEach(model.visibleChapters, id: \.id) { chapter in
                    Button {
                        playing = PlaybackRequest(content: details, chapter: chapter)
                    } label: {
                        Label(Self.episodeTitle(chapter), systemImage: "play.circle")
                    }
                    .accessibilityIdentifier("chapter.\(chapter.id)")
                }
            } else {
            // Rows of four keep long series (1000+ episodes) lazy: a grid inside one List row would build every button.
            ForEach(Self.rows(model.visibleChapters), id: \.first?.id) { row in
                HStack(spacing: 10) {
                    ForEach(row, id: \.id) { chapter in
                        Button {
                            playing = PlaybackRequest(content: details, chapter: chapter)
                        } label: {
                            Text(Self.episodeTitle(chapter))
                                .font(.subheadline)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glass)
                        .accessibilityIdentifier("chapter.\(chapter.id)")
                    }
                    ForEach(row.count..<Self.rowSize, id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                }
                .listRowSeparator(.hidden)
            }
            }
        } header: {
            Text("剧集")
        }
    }

    private var linePicker: some View {
        Picker("线路", selection: $model.selectedLine) {
            ForEach(model.lines, id: \.self) { line in
                Text(line ?? "默认").tag(line)
            }
        }
        .accessibilityIdentifier("details.line")
    }

    @ViewBuilder
    private func chapters(_ details: IosSourceContent) -> some View {
        let branches = model.branches
        let visible = model.chapterList(query: chapterQuery, newestFirst: newestFirst)
        let current = resumeChapter()
        let currentIndex = current.flatMap { chapter in model.chapters.firstIndex { $0.id == chapter.id } }
        Section {
            if model.chapters.count > 20 {
                TextField("搜索章节", text: $chapterQuery).textFieldStyle(.roundedBorder)
                    .listRowSeparator(.hidden)
            }
            if branches.count > 1 {
                Picker("分支", selection: $model.selectedBranch) {
                    ForEach(branches, id: \.self) { Text($0 ?? "默认").tag($0) }
                }
                .pickerStyle(.menu)
            }
            if visible.isEmpty { Text("暂无章节").foregroundStyle(.secondary).accessibilityIdentifier("details.empty") }
            ForEach(visible, id: \.id) { chapter in
                let index = model.chapters.firstIndex { $0.id == chapter.id }
                let isCurrent = chapter.id == current?.id
                let isRead = !isCurrent && index != nil && currentIndex != nil && index! < currentIndex!
                NavigationLink {
                    if model.isNovel {
                        NovelReaderView(runtime: model.runtime, content: details, chapter: chapter)
                    } else {
                        SourceReaderView(runtime: model.runtime, content: details, chapter: chapter)
                    }
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(chapter.title).font(isCurrent ? .body.weight(.semibold) : .body)
                                .foregroundStyle(isRead ? .secondary : .primary)
                                .lineLimit(2)
                            if let subtitle = Self.chapterSubtitle(chapter) {
                                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                        if isCurrent, let resume {
                            Text("\(Int(resume.progress * 100))%").font(.caption.monospacedDigit())
                                .foregroundStyle(.tint)
                        }
                    }
                }
                .listRowBackground(isCurrent ? Color.accentColor.opacity(0.12) : nil)
                .accessibilityIdentifier("chapter.\(chapter.id)")
            }
        } header: {
            HStack {
                Text("章节（\(model.chapters.count)）")
                Spacer()
                Menu {
                    Picker("排序依据", selection: $chapterOrder) {
                        Text("源顺序").tag("source")
                        Text("章节号").tag("number")
                    }
                    .pickerStyle(.inline)
                    Picker("方向", selection: $newestFirst) {
                        Text("最早在前").tag(false)
                        Text("最新在前").tag(true)
                    }
                    .pickerStyle(.inline)
                } label: {
                    Label((chapterOrder == "number" ? "章节号 · " : "") + (newestFirst ? "最新在前" : "最早在前"),
                          systemImage: "arrow.up.arrow.down")
                }
                .labelStyle(.titleAndIcon).font(.caption).textCase(nil)
                .accessibilityIdentifier("details.sort")
            }
        }
    }

    static func chapterSubtitle(_ chapter: IosSourceChapter) -> String? {
        var parts: [String] = []
        if let scanlator = chapter.scanlator { parts.append(scanlator) }
        if chapter.uploadDate > 0 {
            parts.append(Date(timeIntervalSince1970: Double(chapter.uploadDate) / 1000).formatted(date: .abbreviated, time: .omitted))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    static let rowSize = 4

    static func hasLongTitles(_ chapters: [IosSourceChapter]) -> Bool {
        chapters.prefix(50).contains { episodeTitle($0).count > 7 }
    }

    static func rows(_ chapters: [IosSourceChapter]) -> [[IosSourceChapter]] {
        stride(from: 0, to: chapters.count, by: rowSize).map { Array(chapters[$0..<min($0 + rowSize, chapters.count)]) }
    }

    /// Episode titles repeat the line name ("线路A - 第1集"); the picker already shows it.
    static func episodeTitle(_ chapter: IosSourceChapter) -> String {
        guard let line = chapter.line, chapter.title.hasPrefix("\(line) - ") else { return chapter.title }
        return String(chapter.title.dropFirst(line.count + 3))
    }

    /// CMS descriptions are often HTML fragments.
    static func plainText(_ html: String) -> String {
        html.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// One opening of a reader or player. Each opening is its own identity: reopening the chapter just closed must build
/// a new reader, which reads the saved position again, rather than get back the previous reader's state.
struct PlaybackRequest: Identifiable, Hashable {
    let content: IosSourceContent
    let chapter: IosSourceChapter
    let id = UUID()
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
