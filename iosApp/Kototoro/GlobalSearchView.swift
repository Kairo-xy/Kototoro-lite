import KototoroShared
import SwiftUI

/// Android's multi-source search: every enabled source (pinned first, then the manual order) is queried with at most
/// four requests in flight; each source keeps its own row, state and retry, and the library and history are searched
/// locally first.
@MainActor
final class GlobalSearchModel: ObservableObject {
    enum Status {
        case loading
        case done([IosSourceContent])
        case failed(String)
    }

    struct Row: Identifiable {
        let source: IosSource
        var status: Status
        var id: String { source.name }
    }

    @Published private(set) var rows: [Row] = []
    @Published private(set) var completed = 0
    @Published private(set) var isSearching = false
    @Published private(set) var submitted = ""
    private var task: Task<Void, Never>?
    private static let parallelism = 6

    func search(_ query: String, sources: [IosSource], runtime: IosSources) {
        task?.cancel()
        submitted = query
        rows = sources.map { Row(source: $0, status: .loading) }
        completed = 0
        isSearching = !sources.isEmpty
        task = Task { await run(query, sources: sources, runtime: runtime) }
    }

    func retryFailed(runtime: IosSources) {
        let failed = rows.filter { if case .failed = $0.status { true } else { false } }.map(\.source)
        guard !failed.isEmpty, !isSearching else { return }
        for source in failed { update(source.name, .loading) }
        completed -= failed.count
        isSearching = true
        let query = submitted
        task = Task { await run(query, sources: failed, runtime: runtime) }
    }

    func cancel() {
        task?.cancel()
        task = nil
        submitted = ""
        rows = []
        completed = 0
        isSearching = false
    }

    private func run(_ query: String, sources: [IosSource], runtime: IosSources) async {
        await withTaskGroup(of: (String, Status).self) { group in
            var pending = sources[...]
            func startNext() {
                guard !Task.isCancelled, let source = pending.popFirst() else { return }
                group.addTask {
                    do {
                        let found = try await runtime.filteredPage(sourceName: source.name, page: 0, loadedCount: 0,
                                                                   query: query, selection: nil)
                        var seen = Set<String>()
                        return (source.name, .done(found.filter { seen.insert(ContentMarks.key($0)).inserted }))
                    } catch {
                        return (source.name, .failed(SourceErrorText.describe(error)))
                    }
                }
            }
            for _ in 0..<Self.parallelism { startNext() }
            for await (name, status) in group {
                guard !Task.isCancelled else { break }
                update(name, status)
                completed += 1
                startNext()
            }
        }
        if !Task.isCancelled { isSearching = false }
    }

    private func update(_ name: String, _ status: Status) {
        guard let index = rows.firstIndex(where: { $0.id == name }) else { return }
        rows[index].status = status
    }
}

struct GlobalSearchView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @StateObject private var model = GlobalSearchModel()
    @Binding var query: String
    @AppStorage("search.recent") private var storedRecent = ""
    @AppStorage("search.kinds") private var hiddenKinds = ""
    @AppStorage("search.pinnedOnly") private var pinnedOnly = false
    @AppStorage("sources.showAdult") private var showAdult = false

    private var recent: [String] { storedRecent.split(separator: "\n").map(String.init) }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if model.submitted.isEmpty { suggestions } else { results }
                }
                .padding(.vertical, 12)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("搜索")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { filterMenu }
            }
        }
        .searchable(text: $query, prompt: "搜索全部来源中的作品")
        .onSubmit(of: .search) { submit(query) }
        .onChange(of: query) { _, value in
            if value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { model.cancel() }
        }
    }

    /// Enabled sources in browse order (pinned first, then manual order), after the search filters.
    private var targets: [IosSource] {
        let kinds = SourceFilterOption.decode(hiddenKinds)
        let enabled = sources.sources.filter { source in
            sources.isEnabled(source) && (showAdult || !source.isAdult) && !kinds.contains(source.mediaKind) &&
                (!pinnedOnly || sources.isPinned(source))
        }
        let ordered = sources.ordered(enabled)
        return ordered.filter(sources.isPinned) + ordered.filter { !sources.isPinned($0) }
    }

    private func submit(_ text: String) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, let runtime = sources.runtime else { return }
        query = value
        storedRecent = ([value] + recent.filter { $0 != value }).prefix(12).joined(separator: "\n")
        model.search(value, sources: targets, runtime: runtime)
    }

    // MARK: Before searching

    @ViewBuilder
    private var suggestions: some View {
        if recent.isEmpty {
            ContentUnavailableView("搜索作品", systemImage: "magnifyingglass",
                                   description: Text("同时搜索作品库、历史和所有已启用的来源（\(targets.count) 个）。"))
                .padding(.top, 60)
        } else {
            HStack {
                Text("最近搜索").font(.headline)
                Spacer()
                Button("清除") { storedRecent = "" }.font(.subheadline)
                    .accessibilityIdentifier("search.recent.clear")
            }
            .padding(.horizontal, 20)
            FlowLayout(spacing: 8) {
                ForEach(recent, id: \.self) { text in
                    Button(text) { submit(text) }
                        .buttonStyle(.glass)
                        .accessibilityIdentifier("search.recent.\(text)")
                }
            }
            .padding(.horizontal, 16)
        }
    }

    // MARK: Results

    @ViewBuilder
    private var results: some View {
        let text = model.submitted
        HStack(spacing: 8) {
            if model.isSearching { ProgressView().controlSize(.small) }
            Text(model.isSearching ? "正在搜索 \(model.completed)/\(model.rows.count) 个来源"
                 : "已搜索 \(model.rows.count) 个来源")
                .font(.footnote).foregroundStyle(.secondary)
                .accessibilityIdentifier("search.progress")
            Spacer()
            let failed = model.rows.filter { if case .failed = $0.status { true } else { false } }.count
            if failed > 0, !model.isSearching, let runtime = sources.runtime {
                Button("重试 \(failed) 个失败的来源") { model.retryFailed(runtime: runtime) }.font(.footnote)
                    .accessibilityIdentifier("search.retry")
            }
        }
        .padding(.horizontal, 20)
        if let runtime = sources.runtime {
            localSection("作品库", identifier: "search.library", entries: library.favourites, text: text, runtime: runtime)
            localSection("历史", identifier: "search.history", entries: library.history, text: text, runtime: runtime)
            ForEach(model.rows) { row in
                switch row.status {
                case .loading:
                    sourceHeader(row.source, count: nil, runtime: runtime)
                    ProgressView().frame(maxWidth: .infinity, minHeight: 60)
                case .failed(let message):
                    sourceHeader(row.source, count: nil, runtime: runtime)
                    Label(message, systemImage: "exclamationmark.triangle")
                        .font(.footnote).foregroundStyle(.orange).lineLimit(2)
                        .padding(.horizontal, 20)
                case .done(let found) where !found.isEmpty:
                    sourceHeader(row.source, count: found.count, runtime: runtime)
                    carousel(found, runtime: runtime)
                case .done:
                    EmptyView()
                }
            }
            let empty = model.rows.filter { if case .done(let found) = $0.status { found.isEmpty } else { false } }
            if !empty.isEmpty {
                Text("\(empty.count) 个来源没有结果").font(.footnote).foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
                    .accessibilityIdentifier("search.emptySources")
            }
        }
    }

    @ViewBuilder
    private func localSection(_ title: String, identifier: String, entries: [IosLibraryEntry], text: String,
                              runtime: IosSources) -> some View {
        let matches = entries.map(\.content).filter {
            $0.title.localizedCaseInsensitiveContains(text) || $0.altTitles.contains { $0.localizedCaseInsensitiveContains(text) }
        }
        if !matches.isEmpty {
            HStack(spacing: 8) {
                Image(systemName: identifier == "search.library" ? "books.vertical" : "clock")
                    .foregroundStyle(.tint).frame(width: 24)
                Text(title).font(.headline)
                Text("\(matches.count)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 20)
            .accessibilityIdentifier(identifier)
            carousel(matches, runtime: runtime)
        }
    }

    private func sourceHeader(_ source: IosSource, count: Int?, runtime: IosSources) -> some View {
        HStack(spacing: 8) {
            SourceIcon(runtime: runtime, source: source, size: 24)
            Text(source.title).font(.headline).lineLimit(1)
                .accessibilityIdentifier("search.source.\(source.name)")
            if let count { Text("\(count)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary) }
            Spacer()
            NavigationLink {
                SourceBrowseView(runtime: runtime, source: source, initialQuery: model.submitted)
                    .submitScope()
            } label: {
                Text("更多").font(.subheadline)
            }
            .accessibilityIdentifier("search.more.\(source.name)")
        }
        .padding(.horizontal, 20)
    }

    private func carousel(_ items: [IosSourceContent], runtime: IosSources) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(items.map { ContentItem(content: $0) }, id: \.identity) { entry in
                    let item = entry.content
                    NavigationLink {
                        SourceDetailsView(runtime: runtime, content: item)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Color.clear.frame(width: 100, height: 150)
                                .overlay { CoverImage(runtime: runtime, content: item) }
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            Text(item.title).font(.caption.weight(.medium)).lineLimit(2, reservesSpace: true)
                                .frame(width: 100, alignment: .leading)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("search.result.\(item.id)")
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private var filterMenu: some View {
        Menu {
            Section("内容类型") {
                ForEach(SourceFilterOption.kinds, id: \.id) { kind in
                    Toggle(isOn: Binding(get: { !SourceFilterOption.decode(hiddenKinds).contains(kind.id) }, set: { visible in
                        var hidden = SourceFilterOption.decode(hiddenKinds)
                        if visible { hidden.remove(kind.id) } else { hidden.insert(kind.id) }
                        hiddenKinds = SourceFilterOption.encode(hidden)
                    })) { Label(kind.title, systemImage: kind.icon) }
                }
            }
            Toggle("仅搜索置顶来源", systemImage: "pin", isOn: $pinnedOnly)
            Toggle("包含成人内容源", isOn: $showAdult)
        } label: {
            Label("筛选", systemImage: hiddenKinds.isEmpty && !pinnedOnly
                  ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill")
        }
        .menuActionDismissBehavior(.disabled)
        .accessibilityIdentifier("search.filter")
    }
}
