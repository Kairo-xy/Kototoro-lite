import KototoroShared
import SwiftUI

/// Source selection and paged search reuse the installed catalog and its existing runtime.
struct SourceChangeView: View {
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage("sources.showAdult") private var showAdult = false
    let runtime: IosSources
    let content: IosSourceContent
    let onChanged: (IosSourceContent) -> Void

    @State private var sourceQuery = ""

    private var targets: [IosSource] {
        let base = sources.ordered(sources.sources.filter {
            $0.name != content.sourceName && $0.mediaKind == content.mediaKind && $0.ecosystem != "LOCAL" &&
                sources.isEnabled($0) && (showAdult || !$0.isAdult || content.contentType.hasPrefix("HENTAI_"))
        })
        let query = sourceQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return base }
        return base.filter {
            $0.title.localizedCaseInsensitiveContains(query) ||
            $0.locale.localizedCaseInsensitiveContains(query) ||
            $0.name.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(content.title).font(.headline)
                    Text("选择其他来源，搜索并确认同一部作品。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("选择来源") {
                    ForEach(targets, id: \.name) { source in
                        NavigationLink {
                            SourceChangeSearchView(runtime: runtime, source: source, original: content, onChanged: onChanged)
                        } label: {
                            HStack {
                                SourceIcon(runtime: runtime, source: source, size: 32)
                                VStack(alignment: .leading) {
                                    Text(source.title)
                                    Text(source.locale).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .accessibilityIdentifier("sourceChange.source.\(source.name)")
                    }
                    if targets.isEmpty {
                        Text(sources.sources.isEmpty ? "没有其他已启用的同类型来源。请先在浏览页安装或启用来源。" : "未找到匹配的来源。")
                            .foregroundStyle(.secondary).accessibilityIdentifier("sourceChange.empty")
                    }
                }
            }
            .searchable(text: $sourceQuery, prompt: "搜索来源")
            .navigationTitle("换源")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }.accessibilityIdentifier("sourceChange.cancel")
                }
            }
        }
    }
}

private struct SourceChangeSearchView: View {
    @StateObject private var model: SourceBrowseModel
    let original: IosSourceContent
    let onChanged: (IosSourceContent) -> Void

    init(runtime: IosSources, source: IosSource, original: IosSourceContent,
         onChanged: @escaping (IosSourceContent) -> Void) {
        _model = StateObject(wrappedValue: SourceBrowseModel(runtime: runtime, source: source, initialQuery: original.title))
        self.original = original
        self.onChanged = onChanged
    }

    var body: some View {
        List {
            ForEach(model.items.filter { $0.mediaKind == original.mediaKind && $0.sourceName != original.sourceName },
                    id: \.id) { item in
                NavigationLink {
                    SourceChangeConfirmView(runtime: model.runtime, original: original, target: item, onChanged: onChanged)
                } label: {
                    HStack(alignment: .top) {
                        CoverImage(runtime: model.runtime, content: item).frame(width: 48, height: 72).clipped()
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.title).font(.headline)
                            if !item.authors.isEmpty {
                                Text(item.authors.joined(separator: "、")).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .accessibilityIdentifier("sourceChange.result.\(item.id)")
                .onAppear {
                    if item.id == model.items.last?.id, model.error == nil { Task { await model.loadMore() } }
                }
            }
            if model.isLoading { ProgressView("正在搜索…") }
            if let error = model.error {
                Text(error).foregroundStyle(.red).accessibilityIdentifier("sourceChange.searchError")
                if let challenge = model.challenge {
                    ChallengeVerifyButton(challenge: challenge, runtime: model.runtime, sourceName: model.source.name) {
                        await model.loadMore()
                    }
                }
                Button("重试") { Task { await model.loadMore() } }.disabled(model.isLoading)
            } else if !model.isLoading, model.items.isEmpty {
                Text("没有找到作品，请调整搜索词后重试。")
                    .foregroundStyle(.secondary).accessibilityIdentifier("sourceChange.noResults")
            }
        }
        .navigationTitle(model.source.title)
        .searchable(text: $model.query, prompt: "搜索目标作品")
        .onSubmit(of: .search) { Task { await model.submitSearch() } }
        .task { await model.loadMore() }
    }
}

private struct SourceChangeConfirmView: View {
    @EnvironmentObject private var library: LibraryModel
    @StateObject private var model: SourceDetailsModel
    @State private var keepOriginal = false
    @State private var isSaving = false
    @State private var error: String?
    let original: IosSourceContent
    let onChanged: (IosSourceContent) -> Void

    init(runtime: IosSources, original: IosSourceContent, target: IosSourceContent,
         onChanged: @escaping (IosSourceContent) -> Void) {
        _model = StateObject(wrappedValue: SourceDetailsModel(runtime: runtime, content: target))
        self.original = original
        self.onChanged = onChanged
    }

    var body: some View {
        List {
            Section("目标作品") {
                let shown = model.details ?? model.content
                HStack(alignment: .top) {
                    CoverImage(runtime: model.runtime, content: shown).frame(width: 72, height: 108).clipped()
                    VStack(alignment: .leading, spacing: 8) {
                        Text(shown.title).font(.headline)
                        if !shown.authors.isEmpty {
                            Text(shown.authors.joined(separator: "、")).font(.subheadline).foregroundStyle(.secondary)
                        }
                        if model.isLoading {
                            Text("正在读取章节…").font(.subheadline).foregroundStyle(.secondary)
                        } else {
                            Text("\(model.chapters.count) 个章节").accessibilityIdentifier("sourceChange.chapterCount")
                        }
                    }
                }
                if let summary = shown.summary { Text(summary).font(.footnote).lineLimit(8) }
                if model.isLoading { ProgressView("正在读取目标章节…") }
                if let message = model.error {
                    Text(message).foregroundStyle(.red)
                    if let challenge = model.challenge {
                        ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                              sourceName: model.content.sourceName) { await model.load() }
                    }
                    Button("重试") { Task { await model.load() } }.disabled(model.isLoading)
                } else if !model.isLoading, model.details != nil, model.chapters.isEmpty {
                    Text("目标作品没有可用章节，无法换源。").foregroundStyle(.orange)
                }
            }
            Section {
                Toggle("保留原作品", isOn: $keepOriginal).accessibilityIdentifier("sourceChange.keepOriginal")
                Text(keepOriginal ? "复制收藏分类和阅读进度，保留原作品的收藏与历史。"
                     : "迁移收藏分类和阅读进度，移除原作品的收藏与历史。")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("原有离线文件和书签保留在旧作品下。章节优先按卷号、章节号匹配，否则按章节位置匹配。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let error { Text(error).foregroundStyle(.red).accessibilityIdentifier("sourceChange.error") }
            Button {
                Task { await save() }
            } label: {
                HStack {
                    if isSaving { ProgressView() }
                    Text(keepOriginal ? "确认复制到此来源" : "确认换源")
                }.frame(maxWidth: .infinity)
            }
            .disabled(isSaving || model.isLoading || model.error != nil || model.chapters.isEmpty || library.library == nil)
            .accessibilityIdentifier("sourceChange.confirm")
        }
        .navigationTitle("确认换源")
        .navigationBarBackButtonHidden(isSaving)
        .interactiveDismissDisabled(isSaving)
        .disabled(isSaving)
        .task { await model.load() }
    }

    @MainActor
    private func save() async {
        guard !isSaving, let database = library.library, let target = model.details, !model.chapters.isEmpty else { return }
        isSaving = true
        defer { isSaving = false }
        error = nil
        do {
            try await database.changeSource(content: original, target: target, keepOriginal: keepOriginal)
            await library.refresh()
            onChanged(target)
        } catch { self.error = error.localizedDescription }
    }
}
