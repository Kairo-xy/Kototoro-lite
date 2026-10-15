import KototoroShared
import SwiftUI

private enum DateGroup: String, CaseIterable, Comparable {
    case today = "今天"
    case yesterday = "昨天"
    case thisWeek = "本周"
    case earlier = "更早"

    static func group(for timestampMs: Int64) -> DateGroup {
        let date = Date(timeIntervalSince1970: Double(timestampMs) / 1000.0)
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return .today }
        if calendar.isDateInYesterday(date) { return .yesterday }
        if let weekAgo = calendar.date(byAdding: .day, value: -7, to: Date()), date > weekAgo {
            return .thisWeek
        }
        return .earlier
    }

    private var sortOrder: Int {
        switch self {
        case .today: return 0
        case .yesterday: return 1
        case .thisWeek: return 2
        case .earlier: return 3
        }
    }

    static func < (lhs: DateGroup, rhs: DateGroup) -> Bool {
        lhs.sortOrder < rhs.sortOrder
    }
}

private struct TimelineEvent: Identifiable {
    let id: String
    let content: IosSourceContent
    let timestamp: Int64
    let isUpdate: Bool
    let descriptionText: String
    let badge: String?
}

/// History and update records in date-grouped layouts and an optional unified timeline.
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
                        Text("时间轴").tag(2)
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
                        if selection == 0 {
                            history(runtime)
                        } else if selection == 1 {
                            updates(runtime)
                        } else {
                            timeline(runtime)
                        }
                    }
                }
                .padding(.bottom, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(selection == 0 ? "历史" : (selection == 1 ? "更新" : "时间轴"))
            .spaceSubtitle(spacesEnabled ? ContentSpace.subtitle(space) : "")
            .pageSearch(text: $query, prompt: selection == 0 ? "搜索历史" : (selection == 1 ? "搜索更新" : "搜索时间轴"))
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if spacesEnabled { SpaceMenu() }
                    if selection != 2 {
                        ContentListModeMenu(mode: selection == 0 ? $historyMode : $updatesMode)
                    }
                    if selection >= 1, let runtime = sources.runtime {
                        Button("检查更新", systemImage: "arrow.clockwise") { Task { await library.checkUpdates(runtime) } }
                            .disabled(library.isCheckingUpdates)
                            .accessibilityIdentifier("updates.check")
                    }
                }
            }
            .refreshable {
                if selection >= 1, let runtime = sources.runtime { await library.checkUpdates(runtime) }
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
            let matched = library.history.filter { matches($0.content) }
            if matched.isEmpty {
                filteredEmpty("阅读记录")
            } else {
                let grouped = Dictionary(grouping: matched) { entry in
                    DateGroup.group(for: entry.updatedAt)
                }
                ForEach(DateGroup.allCases, id: \.self) { group in
                    if let entries = grouped[group], !entries.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(group.rawValue)
                                .font(.headline.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .padding(.horizontal)
                                .padding(.top, 6)

                            let items = entries.map {
                                ContentItem(content: $0.content, progress: $0.hasProgress ? $0.progress : nil,
                                            subtitle: $0.lastChapterTitle)
                            }
                            ContentCollection(runtime: runtime, items: items, mode: historyMode, identifierPrefix: "history.") { item in
                                SourceDetailsView(runtime: runtime, content: item.content)
                            } actions: { item in
                                Button("删除记录", systemImage: "trash", role: .destructive) {
                                    Task { await library.removeHistory(item.content) }
                                }
                            }
                        }
                    }
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
        if library.isCheckingUpdates {
            HStack(spacing: 10) {
                ProgressView().scaleEffect(0.9)
                Text("正在检查收藏更新…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
        } else if let summary = library.updateSummary {
            Text(summary).font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
        }

        if library.updates.isEmpty {
            ContentUnavailableView("暂无新章节", systemImage: "arrow.triangle.2.circlepath",
                                   description: Text("检查收藏作品，获取最新章节。"))
                .padding(.top, 60)
        } else {
            let matched = library.updates.filter { matches($0.content) }
            if matched.isEmpty {
                filteredEmpty("更新")
            } else {
                let grouped = Dictionary(grouping: matched) { update in
                    DateGroup.group(for: update.checkedAt)
                }
                ForEach(DateGroup.allCases, id: \.self) { group in
                    if let updatesList = grouped[group], !updatesList.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(group.rawValue)
                                .font(.headline.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .padding(.horizontal)
                                .padding(.top, 6)

                            // Clean badge on cover without redundant text subtitle
                            let items = updatesList.map {
                                ContentItem(content: $0.content, badge: "+\($0.newChapters)")
                            }
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
            }
        }
    }

    @ViewBuilder
    private func timeline(_ runtime: IosSources) -> some View {
        let historyEvents = library.history.filter { matches($0.content) }.map {
            TimelineEvent(
                id: "h_\($0.content.sourceName)_\($0.content.id)_\($0.updatedAt)",
                content: $0.content,
                timestamp: $0.updatedAt,
                isUpdate: false,
                descriptionText: $0.lastChapterTitle.map { "看到：\($0)" } ?? "浏览过详情",
                badge: $0.hasProgress ? String(format: "%.0f%%", $0.progress * 100) : nil
            )
        }
        let updateEvents = library.updates.filter { matches($0.content) }.map {
            TimelineEvent(
                id: "u_\($0.content.sourceName)_\($0.content.id)_\($0.checkedAt)",
                content: $0.content,
                timestamp: $0.checkedAt,
                isUpdate: true,
                descriptionText: "新增 \($0.newChapters) 章节",
                badge: "+\($0.newChapters)"
            )
        }

        let allEvents = (historyEvents + updateEvents).sorted { $0.timestamp > $1.timestamp }

        if allEvents.isEmpty {
            ContentUnavailableView("暂无足迹", systemImage: "timeline.selection",
                                   description: Text("阅读或检查更新后会自动汇聚时间轴。"))
                .padding(.top, 60)
        } else {
            let grouped = Dictionary(grouping: allEvents) { event in
                DateGroup.group(for: event.timestamp)
            }

            LazyVStack(alignment: .leading, spacing: 16) {
                ForEach(DateGroup.allCases, id: \.self) { group in
                    if let events = grouped[group], !events.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(group.rawValue)
                                .font(.headline.weight(.bold))
                                .foregroundStyle(.primary)
                                .padding(.horizontal)

                            ForEach(events) { event in
                                NavigationLink(destination: SourceDetailsView(runtime: runtime, content: event.content)) {
                                    HStack(alignment: .center, spacing: 12) {
                                        // Timeline node icon
                                        ZStack {
                                            Circle()
                                                .fill(event.isUpdate ? Color.orange.opacity(0.15) : Color.blue.opacity(0.15))
                                                .frame(width: 36, height: 36)
                                            Image(systemName: event.isUpdate ? "bolt.fill" : "book.fill")
                                                .font(.system(size: 14, weight: .semibold))
                                                .foregroundStyle(event.isUpdate ? Color.orange : Color.blue)
                                        }

                                        // Work thumbnail
                                        CoverImage(runtime: runtime, content: event.content)
                                            .frame(width: 44, height: 60)
                                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

                                        // Work info
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(event.content.title)
                                                .font(.subheadline.weight(.medium))
                                                .lineLimit(1)
                                                .foregroundStyle(.primary)
                                            Text(event.descriptionText)
                                                .font(.caption)
                                                .foregroundStyle(event.isUpdate ? Color.orange : Color.secondary)
                                                .lineLimit(1)
                                            Text(formatTime(event.timestamp))
                                                .font(.caption2)
                                                .foregroundStyle(.tertiary)
                                        }

                                        Spacer(minLength: 4)

                                        if let badge = event.badge {
                                            Text(badge)
                                                .font(.caption2.weight(.bold))
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .background(event.isUpdate ? Color.orange.opacity(0.15) : Color.blue.opacity(0.15), in: Capsule())
                                                .foregroundStyle(event.isUpdate ? Color.orange : Color.blue)
                                        }
                                        Image(systemName: "chevron.right")
                                            .font(.caption2)
                                            .foregroundStyle(.tertiary)
                                    }
                                    .padding(.horizontal)
                                    .padding(.vertical, 4)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .padding(.top, 4)
        }
    }

    private func formatTime(_ timestampMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(timestampMs) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}
