# iOS / Android 关键功能差距与补全清单（2026-10-14）

本清单对照当前 Android 产品入口、SwiftUI 实现和 KMP 平台装配。目标是逐项补全关键功能，
每项以真实行为和对应测试验收；共享模块已经存在、framework 链接成功或历史计划标为完成，
都不能单独证明 iOS 产品入口可用。

当前本轮补全项为**单文件本地视频导入**，SwiftUI 端到端验收已通过。
总体目标尚未完成。后续变更应更新本清单的状态与证据，保留未完成范围。

## 现状与证据入口

| 功能 | Android | iOS 当前行为 | 仍需补全 / 验收 |
| --- | --- | --- | --- |
| 作品库、分类与进度 | 收藏分类、排序、筛选、历史、书签与续读 | 同一 Room 数据库；多分类、拖拽排序、可见性、历史/书签/续读已装配 | 更完整的作品库筛选、批量操作和换源迁移 |
| 浏览与搜索 | 多生态来源、分页、源筛选、全局搜索 | 来源排序/置顶/开关、语言/类型筛选、单源/全局搜索与原生 FilterList 已装配 | 命名保存筛选、全局标签黑名单、来源相关推荐 |
| 漫画阅读 | 五种模式、分页/连续跨章、缩放、分块、书签、下载 | 五种模式、横向/纵向连续缩放、分页窗口及连续跨章、页级重试、离线修复已有实现 | 自动双页/设备输入等选项覆盖、大图组合功能、动画格式与真机性能矩阵；详见专项审计 |
| 小说阅读 | 分页/连续、样式、目录、书签、下载、朗读 | 原生分页/连续、共享 HTML/CSS 子集、样式、段落书签、EPUB 目录与离线正文/插图 | TTS 朗读、正文内搜索等高级操作与完整书籍样式覆盖 |
| 视频播放 | 多线路/清晰度、进度、音轨/字幕、视频下载、增强与投屏 | AVPlayer、自有控件、线路切换、倍速、续播、内嵌音轨/字幕、外挂字幕、系统 PiP/AirPlay | 视频下载；外挂音轨；PiP 外挂字幕；弹幕/DLNA/画质增强；更完整容器/编码覆盖 |
| 本地作品 | 漫画包、EPUB/TXT、视频文件与目录 | CBZ/ZIP、EPUB/TXT；本轮新增单视频文件到 `LOCAL_VIDEO`，导入前原生媒体校验，复用播放器/历史 | 视频目录/播放列表连同资源导入、自动封面、外挂字幕侧车；PDF/CBR/7z 等尚未支持 |
| 下载与存储 | 下载 worker、离线作品、网络约束与管理 | 漫画/小说前台串行队列、暂停/继续/重试、检查点与重启恢复；后台暂停 | 系统后台传输、视频下载、字节级续传、占用显示/离线文件删除/缓存预算 |
| 更新检测 | 调度、通知、分类策略 | 记录页/手动刷新触发收藏更新检测 | 后台尽力调度、系统通知、更新策略设置 |
| 数据备份 | Kototoro 与外部备份、设置等节 | Android 同格式 ZIP 合并；Mihon/Aniyomi/Venera 导入；来源、追踪记录等节已有实现 | 设置/阅读网格/保存筛选等剩余节的语义映射；本地媒体文件本体不随库备份传输 |
| 多设备同步 | Google Drive/WebDAV 与调度 | 协议与合并模型部分已共享，iOS 无同步产品入口 | 按既有数据互通方案暂缓；恢复实施后需共享引擎、授权与平台配置 |
| 内容空间 | 自定义语言/来源/类型范围与独立导航状态 | 三种内置媒体空间与续读卡片，共用选择/重启恢复，可关闭 | 自定义空间规则、各空间独立导航与来源策略 |
| 追踪与发现 | MAL、AniList、Kitsu、Bangumi 等授权/搜索/绑定/进度同步、追踪发现页 | 备份中的追踪记录可保留；没有对应账号与追踪产品流程 | 追踪接口共享、平台授权、作品绑定、观看/阅读进度提交、发现入口 |
| 扩展生态 | Mihon/Aniyomi/IReader、Legado、TVBox、Cloudstream、Tsuki 等 | 可选 JVM 中 Mihon/Tsundoku/Aniyomi 与 JAR parser；默认原生 CMS/TVBox type 0/1；安装/管理与来源设置已装配 | LNReader/Legado 原生 JS runtime、完整 TVBox spider/Cloudstream 支持与代表源覆盖 |
| WebView 与兼容运行时 | Android 原生环境与扩展客户端 | WKWebView、Cookie/JS 桥、Cloudflare 自动/手动验证、域名/UA 设置、独立 shim 与按需加载 | 真实挑战/镜像站/更多扩展与真机覆盖；不能由几个样本推出全部生态兼容 |
| 平台交付与适配 | Android 构建/真机/系统入口 | Arm64 模拟器/真机 framework 和应用归档可构建 | 真机 JVM 安装/运行/内存压力、iPad/横屏/VoiceOver、签名与分发验收 |

**平台差异**：Android APK/DEX 的安装形式、WorkManager 和 APK OTA 不能原样移入 iOS。
验收应要求等价的用户能力及合理平台实现；例如后台任务使用 iOS 调度/传输机制，投屏的 AirPlay
也不能作为 DLNA 已实现的证据。OCR/翻译/日志按产品实施记录中的既有约定暂缓。

主要对照代码：

- Android：`app/.../local/data/importer/LocalImportSupport.kt`、`local/data/input/LocalMangaParser.kt`、
  `filter/data/SavedFiltersRepository.kt`、`migration/`、`space/`、`scrobbling/`、
  `tracking/discovery/`、`download/ui/worker/`、`reader/novel/tts/`、`core/parser/`。
- iOS：`iosApp/Kototoro/{ContentView,LibraryView,LibraryRecordsView,SourceBrowseView,SourceDetailsView,
  SourceReaderView,NovelReaderView,VideoPlayerView,ReaderDownloadQueue,ReaderDownloads,BackupView}.swift`。
- 平台装配：`ios-shared/src/iosMain/.../{IosSources,IosLibrary,IosBackup,IosExternalBackup}.kt`；
  本地规则：`core-local/src/commonMain/.../local/shared/`。
- 详细证据与既有回归见 [产品实施记录](./ios-product-implementation-2026-10.md)、
  [漫画阅读器对照审计](./ios-manga-reader-android-parity-audit-2026-10.md)、
  [数据互通方案](./ios-data-portability-plan-2026-10.md)；构建命令位于仓库 `iosApp/README.md`。

## 补全顺序与验收条件

| 顺序 | 补全项 | 完成所需证据 | 当前状态 |
| --- | --- | --- | --- |
| 1 | 本地视频与导入身份稳定性 | 文件选择器导入→实际播放→关闭保存→重启→再次导入同文件→从原位置继续；坏文件不入库；原漫画/小说导入回归 | 单文件流程 UI 已通过；目录/播放列表等仍待 |
| 2 | 下载/缓存存储管理 | 分别显示离线与缓存占用；明确选择删除对象；正在使用/正在下载/失败和重启规则；保留库记录 | 待实现 |
| 3 | 视频离线下载 | 直接媒体/HLS 的真实下载与离线播放；队列生命周期、暂停/失败恢复、质量/线路身份、占用管理 | 待实现 |
| 4 | 保存筛选、黑名单与相关推荐 | 按来源恢复可命名筛选；动态控件身份保护；黑名单作用于搜索/浏览；详情相关推荐可进入 | 待实现 |
| 5 | 换源与作品库批量管理 | 复用共享章节匹配/迁移计划；收藏/历史/书签归属正确；取消和失败不损坏旧作品 | 待实现 |
| 6 | 小说高级操作 | TTS 的暂停/恢复/跨章与进度；正文内搜索跳转；样式/本地与在线章节回归 | 待实现 |
| 7 | 自定义空间 | 类型/语言/来源规则、空间编辑与持久化、跨空间切换与独立导航恢复 | 待实现 |
| 8 | 后台检测与系统调度 | iOS 合法调度下尽力更新/下载；网络/电量策略；通知与取消；不能承诺固定运行频率 | 待实现 |
| 9 | 追踪账号与发现 | 授权/撤销、账号安全存储、查询绑定、阅读/观看提交、失败重试与进度冲突规则 | 待实现 |
| 10 | 剩余来源生态与视频能力 | 分生态固定代表源跑浏览/搜索/详情/章节/阅读或播放；外挂音轨/字幕/增强/投屏分别验收 | 待实现 |
| 11 | 真机/平板/辅助功能与性能门槛 | Release 真机指标、长章/大图/离线/并发压力；iPad/横屏/VoiceOver；签名分发结果 | 未充分验收 |

多设备同步按既有要求暂缓，不因共享 HTTP 协议存在而标为完成。需要授权/账号或实际设备的项目，
应先完成可本地验证的完整实现，再明确请求必需的外部配置；其余项目可以持续推进。

## 本轮实现边界

本地视频使用 Android 同名 `LOCAL_VIDEO` 和 `VIDEO` 内容类型；共享层复制并流式校验文件，
输出沙盒内 `file://` 媒体地址，播放仍交给原 AVPlayer，收藏、历史、继续播放仍交给原共享库。
支持的单文件扩展名包括 MP4/M4V/MOV/MKV/TS/WebM/AVI，**能否导入取决于系统实际检测的容器和编码**，
不宣称所有 MKV/AVI 均可播放。AVFoundation 的本地 URL 资源与异步可播放属性依据
[Apple AVURLAsset 文档](https://developer.apple.com/documentation/avfoundation/avurlasset)。

重导入按文件名、长度和流式 SHA-256 核对已有副本；同一文件保留作品/章节 ID，旧版导入记录也适用。
同名同长度但字节不同的文件另建作品，失败不删除旧副本。没有新增依赖、schema 或另一套视频/历史模型。
单个 M3U8 不能保证其分片随之复制，当前明确拒绝，目录/播放列表资源包仍在待补范围。

SwiftUI 最终定向验收 **4 项，0 失败**，日志为 `iosApp/build/local-video-ui-final.log`：

- 损坏 MP4 在媒体校验阶段拒绝，不出现在本地视频列表；
- H.264/AAC MP4 经系统文件选择器导入，实际播放/拖动进度，重启后再次选择同一文件，继续播放恢复原位置；
- 原 CBZ 漫画导入/显示与 GB18030 TXT 分章/正文阅读回归。

已实际检查播放画面、重启续播与损坏文件拒绝的截图，保存在
`iosApp/build/local-video-attachments/`。首轮 TXT 用例选择到了文本预览，视频重启后文件选择器准备等待失败；
辅助方法已限定文档提供者中的可选择 Cell、处理旧选择并等待选择器关闭，四项重跑全部通过。
中断前未完成的首次测试没有计入通过数量。

共享层最终 **13 项 JVM + 59 项 iOS Native 测试，0 失败/错误/跳过**；
`compileKotlinIosArm64` 同轮通过，日志为 `iosApp/build/local-video-native-final.log`。
Native 增量验证收藏、历史及播放秒数在数据库重开/重复导入后保留；JVM 增量验证复制/重开/删除、
同名同长度内容冲突、导入失败保留旧文件、空视频及单独播放列表拒绝。以上不代表真机播放验收。
真实扩展预览再次构建通过（`local-video-real-preview-build.log`），已安装并启动于专用模拟器；
工程原有真机 JVM/启动配置保留，本轮模拟器原生测试通过命令行覆盖构建参数。
文档站点检查 `local-video-docs-final.log` 仍被 **149 个既有失效源码链接**阻断，位于漫画/小说专项审计
和 KMP 主方案；本轮新增清单没有失效链接。没有关闭链接校验或把该结果记为构建成功。
