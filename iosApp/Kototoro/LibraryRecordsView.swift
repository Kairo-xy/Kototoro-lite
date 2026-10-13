import KototoroShared
import SwiftUI

/// History and update records in the shared work layouts; each tab remembers its own display mode.
struct LibraryRecordsView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @State private var selection = 0
    @AppStorage("listMode.history") private var historyMode = ContentListMode.list
    @AppStorage("listMode.updates") private var updatesMode = ContentListMode.list
    @AppStorage("records.kind") private var pageKind = ""
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @State private var query = ""

    private var availableKinds: Set<String> {
        Set(selection == 0 ? library.history.map(\.content.mediaKind) : library.updates.map(\.content.mediaKind))
    }

    /// The content space (or this page's switch) and the in-place search over titles and authors.
    private func matches(_ content: IosSourceContent) -> Bool {
        let kind = ContentSpace.filter(enabled: spacesEnabled, space: space, page: pageKind, available: availableKinds)
        if let kind, content.mediaKind != kind { return false }
        let text = query.trimmingCharacters(in: .whitespaces)
        return text.isEmpty || content.title.localizedCaseInsensitiveContains(text) ||
            content.authors.contains { $0.localizedCaseInsensitiveContains(text) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Picker("记录", selection: $selection) {
                        Text("历史").tag(0)
                        Text("更新").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                    if !spacesEnabled && ContentKindPicker.isShown(availableKinds) {
                        ContentKindPicker(selection: $pageKind, available: availableKinds).padding(.horizontal)
                    }
                    if let error = library.error {
                        Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.red)
                            .padding(.horizontal)
                    }
                    if let runtime = sources.runtime {
                        if selection == 0 { history(runtime) } else { updates(runtime) }
                    }
                }
                .padding(.bottom, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(selection == 0 ? "历史" : "更新")
            .spaceSubtitle(spacesEnabled ? ContentSpace.subtitle(space) : "")
            // The system search field under the title; the search tab stays the app-wide search.
            .pageSearch(text: $query, prompt: selection == 0 ? "搜索历史" : "搜索更新")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if spacesEnabled { SpaceMenu() }
                    ContentListModeMenu(mode: selection == 0 ? $historyMode : $updatesMode)
                    if selection == 1, let runtime = sources.runtime {
                        Button("检查更新", systemImage: "arrow.clockwise") { Task { await library.checkUpdates(runtime) } }
                            .disabled(library.isCheckingUpdates)
                            .accessibilityIdentifier("updates.check")
                    }
                }
            }
            .overlay { if library.isCheckingUpdates { ProgressView("正在检查收藏更新…") } }
            .refreshable {
                if selection == 1, let runtime = sources.runtime { await library.checkUpdates(runtime) }
                else { await library.refresh() }
            }
            .task { await library.refresh() }
        }
    }

    @ViewBuilder
    private func history(_ runtime: IosSources) -> some View {
        if library.history.isEmpty {
            ContentUnavailableView("暂无阅读记录", systemImage: "clock",
                                   description: Text("阅读漫画、小说或播放视频后会自动记录进度。"))
                .padding(.top, 60)
        } else {
            let items = library.history.filter { matches($0.content) }
                .map { ContentItem(content: $0.content, progress: $0.hasProgress ? $0.progress : nil) }
            if items.isEmpty { filteredEmpty("阅读记录") }
            ContentCollection(runtime: runtime, items: items, mode: historyMode, identifierPrefix: "history.") { item in
                SourceDetailsView(runtime: runtime, content: item.content)
            } actions: { item in
                Button("删除记录", systemImage: "trash", role: .destructive) {
                    Task { await library.removeHistory(item.content) }
                }
            }
        }
    }

    @ViewBuilder
    private func filteredEmpty(_ what: String) -> some View {
        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            SpaceEmptyView(what: what).padding(.top, 40)
        } else {
            ContentUnavailableView.search(text: query).padding(.top, 40)
        }
    }

    @ViewBuilder
    private func updates(_ runtime: IosSources) -> some View {
        if let summary = library.updateSummary {
            Text(summary).font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
        }
        if library.updates.isEmpty {
            ContentUnavailableView("暂无新章节", systemImage: "arrow.triangle.2.circlepath",
                                   description: Text("检查收藏作品，获取最新章节。"))
                .padding(.top, 60)
        } else {
            let items = library.updates.filter { matches($0.content) }.map {
                ContentItem(content: $0.content, badge: "+\($0.newChapters)", subtitle: "新增 \($0.newChapters) 章")
            }
            if items.isEmpty { filteredEmpty("更新") }
            ContentCollection(runtime: runtime, items: items, mode: updatesMode, identifierPrefix: "update.") { item in
                SourceDetailsView(runtime: runtime, content: item.content)
                    .task {
                        try? await library.library?.markUpdateRead(content: item.content)
                        await library.refresh()
                    }
            } actions: { _ in EmptyView() }
        }
    }
}
