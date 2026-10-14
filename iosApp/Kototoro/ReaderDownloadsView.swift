import SwiftUI

struct ReaderDownloadsSheet: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ReaderDownloadsView()
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { dismiss() }.accessibilityIdentifier("downloads.close")
                    }
                }
        }
    }
}

struct ReaderDownloadsView: View {
    @EnvironmentObject private var queue: ReaderDownloadQueue
    @State private var actionError: String?

    var body: some View {
        List {
            if let error = actionError ?? queue.storageFailure ?? queue.error {
                Text(error).foregroundStyle(.red).accessibilityIdentifier("downloads.error")
            }
            ForEach(queue.entries) { entry in
                VStack(alignment: .leading, spacing: 8) {
                    Text(entry.request.title).font(.headline)
                    Text(entry.request.chapterTitle).foregroundStyle(.secondary)
                    Text(entry.status).font(.caption).accessibilityIdentifier("download.status.\(entry.id)")
                    if entry.state == .paused && queue.isFinishing(entry.id) {
                        Text("正在等待当前源请求结束…").font(.caption).foregroundStyle(.secondary)
                    }
                    if entry.totalPages > 0 {
                        ProgressView(value: Double(entry.completedPages), total: Double(entry.totalPages))
                    }
                    HStack {
                        switch entry.state {
                        case .queued, .running:
                            Button("暂停", systemImage: "pause") { perform { try queue.pause(entry.id) } }
                                .accessibilityIdentifier("download.pause.\(entry.id)")
                        case .paused, .failed:
                            Button(entry.state == .failed ? "重试" : "继续", systemImage: "arrow.clockwise") {
                                perform { try queue.resume(entry.id) }
                            }.accessibilityIdentifier("download.resume.\(entry.id)")
                        case .completed: EmptyView()
                        }
                        Spacer()
                        Button("移除任务", systemImage: "xmark", role: .destructive) {
                            perform { try queue.remove(entry.id) }
                        }.accessibilityIdentifier("download.remove.\(entry.id)")
                    }.buttonStyle(.borderless)
                }.padding(.vertical, 4)
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel("\(entry.request.title) · \(entry.request.chapterTitle)")
                    .accessibilityIdentifier("download.entry.\(entry.id)")
            }
            Section {
                NavigationLink("存储管理", destination: ReaderStorageView()).accessibilityIdentifier("downloads.storage")
                Text("仅在应用前台下载，进入后台会暂停。继续时校验已保存的图片并从未完成页面恢复；移除任务保留已下载文件。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .overlay {
            if queue.entries.isEmpty && queue.error == nil && queue.storageFailure == nil {
                ContentUnavailableView("暂无下载任务", systemImage: "arrow.down.circle",
                                       description: Text("在漫画或小说阅读器中下载章节。已保存文件可从“存储管理”查看。"))
                    .allowsHitTesting(false)
            }
        }
        .navigationTitle("下载")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func perform(_ action: () throws -> Void) {
        do { try action(); actionError = nil }
        catch { actionError = error.localizedDescription }
    }
}
