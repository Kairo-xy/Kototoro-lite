import KototoroShared
import SwiftUI

/// Common browser User-Agents offered next to every source's User-Agent field (Android's source settings offer the
/// same kind of list). Sites behind Cloudflare tie their clearance to the User-Agent the challenge was passed with.
enum UserAgentPresets {
    static let all: [(title: String, value: String)] = [
        ("iPhone Safari", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"),
        ("iPad Safari", "Mozilla/5.0 (iPad; CPU OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"),
        ("Android Chrome", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"),
        ("Windows Chrome", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"),
        ("Windows Edge", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0"),
        ("macOS Safari", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15"),
        ("Windows Firefox", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:142.0) Gecko/20100101 Firefox/142.0"),
    ]

    static func title(for value: String) -> String? { all.first { $0.value == value }?.title }
}

/// A source's own settings, opened from its browse page: the extension's preferences or the parser's config keys
/// (domain, User-Agent, ...) as the runtime declares them, or a CMS site's API address and User-Agent; plus the
/// site in the in-app browser.
struct SourceSettingsView: View {
    let runtime: IosSources
    let source: IosSource
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @State private var screen: IosPreferenceScreen?
    @State private var unsupported = false
    @State private var error: String?
    @State private var notice: String?
    @State private var editing: TextEdit?
    @State private var browsing: BrowserTarget?
    @State private var homepage: String?
    @State private var site: IosVideoSiteSettings?

    private var isCms: Bool { source.kind == "video" }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("来源", value: source.title)
                    LabeledContent("类型", value: "\(source.ecosystem) · \(ContentSpace.title(source.mediaKind))")
                    if let address = homepage ?? site?.api, let url = URL(string: address), url.scheme?.hasPrefix("http") == true {
                        Button { browsing = BrowserTarget(url: url, userAgent: currentUserAgent) } label: {
                            Label("在内置浏览器中打开", systemImage: "safari")
                        }
                        .accessibilityIdentifier("sourceSettings.browser")
                    }
                }
                if isCms { cmsSections } else { preferenceSections }
                if let error {
                    Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                }
            }
            .navigationTitle("来源设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.accessibilityIdentifier("sourceSettings.done")
                }
            }
            .task { await load() }
            .sheet(item: $editing) { edit in
                TextEditSheet(edit: edit) { value in await save(edit, value) }
            }
            .sheet(item: $browsing) { target in SourceWebBrowser(target: target, runtime: runtime, sourceName: source.name) }
            .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
                Button("好") { notice = nil }
            }
        }
        .accessibilityIdentifier("sourceSettings")
    }

    // MARK: Extension and parser sources

    @ViewBuilder
    private var preferenceSections: some View {
        if let screen {
            let rows = screen.nodes.filter { $0.kind != "GROUP" }
            let groups = screen.nodes.filter { $0.kind == "GROUP" }
            if !rows.isEmpty {
                Section("设置") { ForEach(rows, id: \.id) { row($0) } }
            }
            ForEach(groups, id: \.id) { group in
                Section(group.title) { ForEach(group.children, id: \.id) { row($0) } }
            }
            if rows.isEmpty && groups.isEmpty {
                Section {
                    Text("此来源没有可调整的设置。").foregroundStyle(.secondary)
                }
            }
        } else if unsupported {
            Section {
                Text("此来源没有可调整的设置。").foregroundStyle(.secondary)
            }
        } else if error == nil {
            Section { ProgressView().frame(maxWidth: .infinity) }
        }
    }

    @ViewBuilder
    private func row(_ node: IosPreferenceNode) -> some View {
        switch node.kind {
        case "TOGGLE":
            Toggle(isOn: Binding(get: { node.isOn }, set: { on in Task { await update(node) { try await runtime.updateToggle(screen: $0, node: node, on: on) } } })) {
                titled(node)
            }
            .disabled(!node.enabled)
            .accessibilityIdentifier("sourceSettings.node.\(node.id)")
        case "CHOICE":
            Picker(selection: Binding(get: { node.selected.first ?? "" }, set: { value in
                Task { await update(node) { try await runtime.updateChoice(screen: $0, node: node, choice: value) } }
            })) {
                ForEach(Array(zip(node.choiceTitles, node.choiceValues)), id: \.1) { title, value in Text(title).tag(value) }
            } label: { titled(node) }
            .disabled(!node.enabled)
            .accessibilityIdentifier("sourceSettings.node.\(node.id)")
        case "MULTI_CHOICE":
            NavigationLink {
                MultiChoiceList(node: node) { values in
                    await update(node) { try await runtime.updateChoices(screen: $0, node: node, choices: values) }
                }
            } label: {
                LabeledContent { Text("\(node.selected.count) 项") } label: { titled(node) }
            }
            .disabled(!node.enabled)
        case "TEXT":
            Button {
                editing = TextEdit(id: node.id, title: node.title, summary: node.summary, value: node.text,
                                   placeholder: node.defaultText, isUserAgent: node.isUserAgent)
            } label: {
                LabeledContent {
                    Text(textSummary(node)).lineLimit(1).truncationMode(.middle)
                } label: { titled(node) }
            }
            .foregroundStyle(.primary)
            .disabled(!node.enabled)
            .accessibilityIdentifier("sourceSettings.node.\(node.id)")
        case "INFO":
            titled(node)
        default:
            titled(node).foregroundStyle(.secondary)
        }
    }

    private func titled(_ node: IosPreferenceNode) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(node.title)
            if !node.summary.isEmpty && node.kind != "TEXT" {
                Text(node.summary).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func textSummary(_ node: IosPreferenceNode) -> String {
        // Parser keys store their default as a value (a full User-Agent), extensions as an empty string.
        if node.text.isEmpty || node.text == node.defaultText { return "默认" }
        return node.isUserAgent ? (UserAgentPresets.title(for: node.text) ?? "自定义") : node.text
    }

    private var currentUserAgent: String? {
        if isCms { return site?.userAgent.isEmpty == false ? site?.userAgent : nil }
        let node = screen?.nodes.first { $0.isUserAgent }
        return node.flatMap { $0.text.isEmpty ? nil : $0.text }
    }

    // MARK: CMS sites

    @ViewBuilder
    private var cmsSections: some View {
        if let site {
            Section {
                Button {
                    editing = TextEdit(id: "cms:api", title: "API 地址", summary: "站点的苹果 CMS 接口地址；更换域名时修改这里。",
                                       value: site.api, placeholder: "https://", isUserAgent: false)
                } label: {
                    LabeledContent("API 地址") { Text(site.api).lineLimit(1).truncationMode(.middle) }
                }
                .foregroundStyle(.primary)
                .accessibilityIdentifier("sourceSettings.cms.api")
                Button {
                    editing = TextEdit(id: "cms:ua", title: "User-Agent", summary: "留空使用默认值。",
                                       value: site.userAgent, placeholder: "", isUserAgent: true)
                } label: {
                    LabeledContent("User-Agent") {
                        Text(site.userAgent.isEmpty ? "默认" : (UserAgentPresets.title(for: site.userAgent) ?? "自定义"))
                    }
                }
                .foregroundStyle(.primary)
                .accessibilityIdentifier("sourceSettings.cms.ua")
            } header: { Text("网络") }
        } else if error == nil {
            Section { ProgressView().frame(maxWidth: .infinity) }
        }
    }

    // MARK: Loading and saving

    private func load() async {
        homepage = try? await runtime.homepage(sourceName: source.name)
        do {
            if isCms {
                site = try await sources.videoSiteSettings(source.name)
            } else {
                screen = try await runtime.preferences(sourceName: source.name)
            }
        } catch {
            // Sources without settings answer "unsupported"; anything else is worth showing.
            if "\(error)".contains("UNSUPPORTED") || "\(error)".localizedCaseInsensitiveContains("unsupported") {
                unsupported = true
            } else {
                self.error = error.localizedDescription
            }
        }
    }

    private func save(_ edit: TextEdit, _ value: String) async -> Bool {
        if edit.id.hasPrefix("cms:"), let site {
            let api = edit.id == "cms:api" ? value : site.api
            let agent = edit.id == "cms:ua" ? value : site.userAgent
            do {
                try await sources.updateVideoSite(source.name, api: api, userAgent: agent)
                self.site = try await sources.videoSiteSettings(source.name)
                return true
            } catch {
                notice = error.localizedDescription
                return false
            }
        }
        guard let node = (screen?.nodes ?? []).flatMap({ [$0] + $0.children }).first(where: { $0.id == edit.id }) else { return false }
        return await update(node) { try await runtime.updateText(screen: $0, node: node, text: value) }
    }

    @discardableResult
    private func update(_ node: IosPreferenceNode,
                        _ change: (IosPreferenceScreen) async throws -> IosPreferenceResult) async -> Bool {
        guard let screen else { return false }
        do {
            let result = try await change(screen)
            self.screen = result.screen
            switch result.status {
            case "ACCEPTED": return true
            case "PERSISTENCE_FAILED": notice = "设置未能保存，请重试。"
            default: notice = "“\(node.title)”的值不被此来源接受。"
            }
        } catch {
            notice = error.localizedDescription
            // A stale screen (another change in between) is reloaded rather than edited further.
            self.screen = try? await runtime.preferences(sourceName: source.name)
        }
        return false
    }
}

struct TextEdit: Identifiable {
    let id: String
    let title: String
    let summary: String
    let value: String
    let placeholder: String
    let isUserAgent: Bool
}

/// One text setting; User-Agent fields add the presets and a reset to the source's default.
private struct TextEditSheet: View {
    let edit: TextEdit
    let save: (String) async -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(edit.placeholder.isEmpty ? edit.title : edit.placeholder, text: $text, axis: .vertical)
                        .lineLimit(1...6)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .keyboardType(edit.isUserAgent ? .default : .URL)
                        .accessibilityIdentifier("sourceSettings.text")
                } footer: {
                    if !edit.summary.isEmpty { Text(edit.summary) }
                }
                if edit.isUserAgent {
                    Section("常用 User-Agent") {
                        ForEach(UserAgentPresets.all, id: \.title) { preset in
                            Button {
                                text = preset.value
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(preset.title).foregroundStyle(.primary)
                                        Text(preset.value).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                    Spacer()
                                    if text == preset.value { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                                }
                            }
                            .accessibilityIdentifier("sourceSettings.uaPreset.\(preset.title)")
                        }
                        Button("恢复来源默认值", role: .destructive) { text = "" }
                    }
                } else if text != edit.placeholder {
                    // Domains and other texts: back to what the source itself uses.
                    Section { Button("恢复来源默认值", role: .destructive) { text = edit.placeholder } }
                }
            }
            .navigationTitle(edit.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        saving = true
                        Task {
                            if await save(text.trimmingCharacters(in: .whitespacesAndNewlines)) { dismiss() }
                            saving = false
                        }
                    }
                    .disabled(saving)
                    .accessibilityIdentifier("sourceSettings.save")
                }
            }
            .onAppear { text = edit.value }
        }
        .presentationDetents(edit.isUserAgent ? [.large] : [.medium, .large])
    }
}

private struct MultiChoiceList: View {
    let node: IosPreferenceNode
    let save: (Set<String>) async -> Void
    @State private var selected: Set<String> = []

    var body: some View {
        List {
            ForEach(Array(zip(node.choiceTitles, node.choiceValues)), id: \.1) { title, value in
                Button {
                    if selected.contains(value) { selected.remove(value) } else { selected.insert(value) }
                    let next = selected
                    Task { await save(next) }
                } label: {
                    HStack {
                        Text(title).foregroundStyle(.primary)
                        Spacer()
                        if selected.contains(value) { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                    }
                }
            }
        }
        .navigationTitle(node.title)
        .onAppear { selected = node.selected }
    }
}
