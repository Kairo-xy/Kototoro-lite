import KototoroShared
import SwiftUI

/// Public catalog search and the metadata authority of a source work share the same picker.
struct TrackingSitesView: View {
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    var content: IosSourceContent? = nil
    var onChange: () -> Void = {}
    @AppStorage("tracking.preferredSite") private var service = 5
    @State private var mediaKind = "MANGA"
    @State private var query = ""
    @FocusState private var queryFocused: Bool
    @State private var submittedQuery = ""
    @State private var results: [IosTrackingItem] = []
    @State private var bindings: [IosTrackingItem] = []
    @State private var selectedKey: String?
    @State private var preview: IosTrackingItem?
    @State private var loading = false
    @State private var mutating = false
    @State private var usingKey: String?
    @State private var error: String?
    @State private var page: Int32 = 0
    @State private var hasMore = false
    @State private var request: UUID?

    private var searchIdentity: String { "\(service)|\(mediaKind)|\(submittedQuery)" }

    var body: some View {
        List {
            if content != nil { currentMetadata }
            Section {
                HStack(spacing: 8) {
                    if let tracking = library.library?.tracking {
                        ForEach(tracking.sites, id: \.id) { site in
                            Button { service = Int(site.id) } label: {
                                Text(site.title).font(.subheadline.weight(.medium))
                                    .padding(.horizontal, 14).padding(.vertical, 8)
                                    .background(service == Int(site.id) ? Color.accentColor.opacity(0.15) : Color(.secondarySystemFill), in: .capsule)
                                    .foregroundStyle(service == Int(site.id) ? Color.accentColor : .primary)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("tracking.site.\(site.id)")
                            .accessibilityAddTraits(service == Int(site.id) ? .isSelected : [])
                        }
                    }
                }
                if content == nil {
                    Picker("作品类型", selection: $mediaKind) {
                        Text("漫画").tag("MANGA")
                        Text("小说").tag("NOVEL")
                        Text("动画 / 影视").tag("VIDEO")
                    }
                    .accessibilityIdentifier("tracking.mediaKind")
                }
                HStack {
                    TextField("搜索作品名称", text: $query)
                        .focused($queryFocused)
                        .submitLabel(.search)
                        .onSubmit { submit() }
                        .accessibilityIdentifier("tracking.query")
                    Button("搜索") { submit() }
                        .disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("tracking.search")
                }
            } header: { Text(content == nil ? "检索追踪网站" : "换一份作品资料") }
            if let error {
                Section { Text(error).foregroundStyle(.red).accessibilityIdentifier("tracking.error") }
            }
            Section {
                ForEach(results, id: \.key) { item in
                    HStack(spacing: 10) {
                        Button { preview = item } label: { TrackingItemRow(item: item) }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("tracking.result.\(item.remoteId)")
                        if content != nil {
                            Button {
                                Task { await use(item) }
                            } label: {
                                if usingKey == item.key { ProgressView() }
                                else { Text(selectedKey == item.key ? "使用中" : "使用").font(.subheadline.weight(.semibold)) }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(mutating || selectedKey == item.key)
                            .accessibilityIdentifier("tracking.use.\(item.remoteId)")
                        }
                    }
                }
                if loading { ProgressView("正在检索…").frame(maxWidth: .infinity) }
                else if !submittedQuery.isEmpty && results.isEmpty && error == nil {
                    ContentUnavailableView.search(text: submittedQuery)
                } else if hasMore {
                    Button("加载更多") { Task { await search(reset: false) } }
                        .accessibilityIdentifier("tracking.more")
                }
            }
            if content == nil {
                Section {
                    Text("点击作品详情中的“资料”，可直接使用这里的封面与介绍。")
                    Text("当前提供公开资料检索与本地绑定，账号授权和网站阅读进度同步尚未接入。")
                }
                .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(content == nil ? "追踪网站" : "作品资料")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if content != nil {
                ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() }.disabled(mutating) }
            }
        }
        .interactiveDismissDisabled(mutating)
        .task {
            guard let tracking = library.library?.tracking else { return }
            if !tracking.sites.contains(where: { Int($0.id) == service }) { service = 5 }
            mediaKind = content?.mediaKind ?? "MANGA"
            if let content { query = content.title; submit() }
            await loadBindings()
        }
        .task(id: searchIdentity) { await search(reset: true) }
        .sheet(isPresented: Binding(get: { preview != nil }, set: { if !$0 { preview = nil } })) {
            if let item = preview, let tracking = library.library?.tracking {
                TrackingItemPreview(item: item, mediaKind: mediaKind, tracking: tracking, content: content) {
                    Task { await loadBindings(); await library.refresh(); onChange() }
                }
            }
        }
    }

    private var currentMetadata: some View {
        Section {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("当前资料 · \(bindings.first(where: { $0.key == selectedKey })?.siteTitle ?? "内容源")")
                        .font(.subheadline.weight(.medium))
                    Text(bindings.first(where: { $0.key == selectedKey })?.title ?? content?.title ?? "")
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                if selectedKey != nil {
                    Button("恢复源信息") { Task { await select(nil) } }
                        .font(.caption).accessibilityIdentifier("tracking.useSource")
                }
                if !bindings.isEmpty {
                    Menu("已关联资料", systemImage: "ellipsis.circle") {
                        ForEach(bindings, id: \.key) { item in
                            Button("使用 \(item.siteTitle) 资料") { Task { await select(item) } }
                            Button("解除 \(item.siteTitle) 关联", role: .destructive) { Task { await unbind(item) } }
                        }
                    }
                }
            }
            .disabled(mutating)
        } footer: { Text("更换封面与作品介绍，章节和进度保持原样。") }
    }

    private func submit() {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        queryFocused = false
        if text == submittedQuery { Task { await search(reset: true) } }
        else { submittedQuery = text }
    }

    @MainActor private func search(reset: Bool) async {
        guard let tracking = library.library?.tracking, !submittedQuery.isEmpty, reset || !loading else { return }
        let token = UUID()
        let identity = searchIdentity
        request = token
        loading = true; error = nil
        if reset { results = []; page = 0; hasMore = false }
        let next = page + 1
        defer { if request == token { loading = false } }
        do {
            let items = try await tracking.search(service: Int32(service), query: submittedQuery,
                                                  mediaKind: mediaKind, page: next)
            guard !Task.isCancelled, request == token, identity == searchIdentity else { return }
            var keys = Set(results.map(\.key))
            results += items.filter { keys.insert($0.key).inserted }
            page = next
            hasMore = items.count >= (service == 5 ? 10 : 20)
        } catch {
            if !Task.isCancelled, request == token, identity == searchIdentity {
                self.error = error.localizedDescription
            }
        }
    }

    @MainActor private func loadBindings() async {
        guard let content, let tracking = library.library?.tracking else { return }
        do {
            bindings = try await tracking.bindings(content: content)
            selectedKey = try await tracking.selected(content: content)?.key
        } catch { self.error = error.localizedDescription }
    }

    @MainActor private func select(_ item: IosTrackingItem?) async {
        guard selectedKey != item?.key else { return }
        await mutate { tracking, content in try await tracking.select(content: content, item: item) }
    }

    @MainActor private func use(_ item: IosTrackingItem) async {
        usingKey = item.key
        defer { usingKey = nil }
        await mutate { tracking, content in try await tracking.bind(content: content, item: item) }
    }

    @MainActor private func unbind(_ item: IosTrackingItem) async {
        await mutate { tracking, content in try await tracking.unbind(content: content, service: item.service) }
    }

    @MainActor private func mutate(_ body: (IosTracking, IosSourceContent) async throws -> Void) async {
        guard !mutating, let tracking = library.library?.tracking, let content else { return }
        mutating = true; error = nil
        defer { mutating = false }
        do {
            try await body(tracking, content)
            await loadBindings(); await library.refresh(); onChange()
        } catch { self.error = error.localizedDescription }
    }
}

private struct TrackingItemRow: View {
    let item: IosTrackingItem
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AsyncImage(url: item.coverUrl.flatMap(URL.init(string:))) { image in
                image.resizable().scaledToFill()
            } placeholder: { Rectangle().fill(.quaternary).overlay { Image(systemName: "book.closed") } }
                .frame(width: 54, height: 78).clipShape(.rect(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title).font(.headline)
                if let alternate = item.alternateTitles.first { Text(alternate).font(.caption).foregroundStyle(.secondary) }
                Text("\(item.siteTitle) · #\(item.remoteId)").font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct TrackingItemPreview: View {
    let item: IosTrackingItem
    let mediaKind: String
    let tracking: IosTracking
    let content: IosSourceContent?
    let onBound: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var details: IosTrackingItem?
    @State private var error: String?
    @State private var loading = true
    @State private var saving = false

    var body: some View {
        NavigationStack {
            List {
                TrackingItemRow(item: details ?? item)
                if loading { ProgressView("正在读取详情…") }
                if let error {
                    Text(error).foregroundStyle(.red)
                    if details == nil { Button("重试") { Task { await load() } } }
                }
                if let details {
                    if let score = details.score {
                        LabeledContent("评分", value: String(format: "%.1f / %.0f", score.floatValue, details.scoreMax))
                    }
                    if !details.authors.isEmpty { LabeledContent("作者", value: details.authors.joined(separator: "、")) }
                    if !details.tags.isEmpty { Text(details.tags.joined(separator: " · ")).font(.subheadline) }
                    if let summary = details.summary { Text(summary).textSelection(.enabled) }
                    if let url = URL(string: details.url) { Link("在网站查看", destination: url) }
                    if let content {
                        Button(saving ? "正在绑定…" : "绑定并使用此作品的信息") {
                            Task {
                                saving = true; error = nil
                                defer { saving = false }
                                do {
                                    try await tracking.bind(content: content, item: details)
                                    onBound(); dismiss()
                                } catch { self.error = error.localizedDescription }
                            }
                        }
                        .disabled(saving)
                        .accessibilityIdentifier("tracking.bind")
                    }
                }
            }
            .navigationTitle("作品资料")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() }.disabled(saving) } }
            .interactiveDismissDisabled(saving)
            .task { await load() }
        }
    }

    @MainActor private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            let result = try await tracking.details(item: item, mediaKind: mediaKind)
            if !Task.isCancelled { details = result }
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
