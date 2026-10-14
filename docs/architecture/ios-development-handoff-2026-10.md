# iOS 开发阶段交接记录（2026-10-10）

本记录对应 KMP 主方案 §90–§92。当前已完成 arm64 模拟器上的原生分类库，以及真实 Mihon APK 的
列表 → 详情/封面 → 章节 → 基本图片阅读流程。完整 iOS 产品与真机门槛尚未完成。

2026-10-14 持续补全入口：先读 [iOS / Android 关键功能差距清单](./ios-android-feature-parity-2026-10.md)。
单文件本地视频、原生媒体校验及重导入身份稳定性已通过共享层与原生 UI 验收；
下一项为下载/缓存存储管理。完整差距、实施顺序与各项验收条件保存在该清单中。

最新产品状态以 [主要功能实施记录](./ios-product-implementation-2026-10.md) 为准：仓库安装、收藏／历史／进度、
共享阅读场景及应用级漫画下载队列已装配。下载支持前台串行执行、暂停／重试、逐页检查点与重启恢复；
系统后台下载和真实慢源中断／真机压力验收仍待，见主方案 §95。
扩展管理已按 SwiftUI 原生列表重整，加入搜索／状态／生态／语言筛选、仓库与扩展详情，见主方案 §96。
图书馆备份／恢复（与 Android 同格式）、浏览筛选面板、源图标与可读名称、详情页重做、阅读器原生分页与点击翻页见主方案 §97；扩展原生筛选控件与解析器扩展筛选项见 §98；仓库深链闪退与无库标记扩展安装见 §99；详情页全景封面见 §100；作品库与统一显示模式见 §101；TVBox 合并、JAR 插件安装与来源开关见 §102；播放器控制栏、全局／来源搜索与安装验收见 §103。

接手先读本文，再看仓库 `iosApp/README.md` 的具体构建命令，最后按需要查阅
[KMP 主方案](./kmp-ios-plan-2026-10.md)。§90–§92 是阶段历史，当前能力以最新产品实施记录为准。
作品库分类的原生拖拽排序与“在书架显示”开关见主方案 §104；复用共享数据库及备份字段。
来源最近使用／使用频率排序与全局搜索清空恢复见 §105；使用时间与作品数仍来自共享数据库。
新来源版本标记、首次目录基线与浏览页新来源筛选见 §106；沿用共享 `added_in` 与原有备份格式。
设备端 DEX 转换提速（k-parsers-r 解释器 800 s→89 s）、图片加载并发／预读对齐 Android、内容类型切换、阅读器翻页动画／左缘返回见 §107。
内容空间（可关闭）、就地搜索、继续阅读附件、分类悬浮条与作品卡片角标见 §108。
原生 Space 卡片切换面板与各媒体空间续读见 §109；原先 §108 的空间菜单已被面板取代。
视频外挂字幕（WebVTT/SubRip/ASS 叠加显示，外挂音轨仍待）见 §110；底栏继续阅读附件已改为跟随当前空间。
条漫双指缩放／双击缩放（按缩放宽度重新排版，进度与缩放无关）见 §111。
小说章节下载与离线阅读、阅读器内隐藏继续阅读附件见 §112；主界面页内搜索、作品库顶栏、附件封面与章节名修正见 §113；来源设置（域名／UA 预设／扩展设置）、Cloudflare 识别与内置浏览器验证见 §114。本地导入、Google Drive／iCloud 同步与备份兼容方案见 [ios-data-portability-plan-2026-10](./ios-data-portability-plan-2026-10.md)。

## 1. 代码基线与提交范围

- 开发起点：`08a6165d0`，本机现有分支 `devel`；本次没有新建分支或推送。
- 转换器修复独立提交：`bb6b94632`，标题为 `fix(dex): correct unresolved frame parent fallback`。
- iOS、共享会话、开发脚本、回归与本文一起提交，标题为
  `feat(ios): add shared library and embedded Mihon reader preview`。
- 查看这两项提交：
  `git log --oneline --grep="unresolved frame parent" --grep="embedded Mihon reader preview"`。
- 本次未纳入原有未跟踪 `app/schemas/` 与 `cat.xml`；不要把它们当作 iOS 必需改动一起提交。
- 第三方源码/JAR/APK、原生库、Java 模块、日志与截图均在 ignored build 目录，未纳入源码提交。
  根目录忽略 `*.sh`，`iosApp/.gitignore` 显式放行必需的 `Scripts/embed-jvm-resources.sh`。

## 2. 当前能力与关键约束

| 构建 | 当前可用能力 | 前置条件 |
|---|---|---|
| 默认 iOS | SwiftUI 分类创建/读取/重启恢复；共享 Room v84 | 共享 Kotlin framework；无需 JVM 运行时 |
| 独立 fixture 预览 | 枚举/列表/详情/章节/PNG 阅读、翻页、换章与返回导航 | 自行构建模拟器 Zero；自有测试 ABI/JAR |
| 真实 APK 预览 | 再漫画 1.6.0 在线列表、详情/封面/章节与首章图片阅读 | 同一 Zero；固定 Mobile API；Mac 上转换的 APK |
| 真实扩展预览（§94） | Mihon 漫画、Tsundoku 小说正文、Aniyomi 播放、JAR（kototoro-parsers 95 源）漫画/小说/视频 | 同一 Zero；`stage-mihon-preview.py` 固定 5 个样本 |
| 默认 iOS（§93） | 苹果 CMS / TVBox type 0/1 导入、浏览/分类/搜索、按线路选集、AVPlayer 播放 | 无需 JVM；`:source-tvbox` 原生运行 |

运行独立 fixture UI 回归会将 `JVMPreview` 安装到模拟器，结束后务必恢复 `RealApiPreview` 应用。
fixture 只有自有测试 ABI，不能安装真实 Mihon 扩展；扩展管理会显示原因并禁用安装入口。
CopyManga 1.4.85 和 MH1234 1.6.5 在此配置的初始化失败根因与回归记录见主方案 §103。

真实样本是再漫画 `MIHON_524579092615598717`。列表返回 10 项；《大理寺日志》返回 392 个章节，
首章两张图片已在模拟器实际解码并翻页。站点数据与网络可用性会变化，这些数量是本次验证结果。

早期 §90–§92 预览在 Mac JVM 转换 APK，再打包 JAR；后续已接入设备端 APK/DEX 转换、
仓库／文件安装、安装记录与运行中的来源刷新。当前完整运行时的模拟器安装验收见主方案 §103；
这不替代真机安装与扩展运行验收。

### 生命周期与数据

- `SourceHostSession` 是 JSONL/JNI 的共同执行会话，串行化 exchange/close；关闭幂等，清理尝试所有资源。
  `EmbeddedSourceHost` 只提供静态 `open(String, String)`、同步 `exchange(String)` 与 `close()` ABI。
- C/JNI 创建进程级 VM，同进程拒绝不同 home/classpath/options；关闭会话不销毁 VM。
  每次调用独立获取 JNIEnv/local frame，文本按显式 UTF-16 长度传递；不要缓存跨线程 JNIEnv。
- `MihonIosPlatform` 的兼容容器每 JVM 仅有一个生命周期；关闭后重建须重启进程。
  详情/阅读页借用父浏览页的会话，退出子页不能调用 runtime.close。
- Swift DTO 保留内部完整共享对象。不要用 Swift 的标题/URL 重建漫画、章节或页面：会丢失 memo、
  sourceData、headers、原生 index 和图片 fragment。64 位 ID 对 Swift 保持十进制字符串。
- 章节导航复用 `SourceChapterNavigation` 的来源/分支规则，不在 Swift 另造顺序逻辑。
- 封面/正文通过 JVM 源客户端下载和物化。Swift 不独立请求图片 URL，避免绕过扩展请求头/拦截器。
  文件名/长度/摘要校验与 ImageIO 解码共用 `SourceImageFile` / `SourceImageLoader`。
- 图片文件上限 64 MiB，解码长边上限 4096 像素，只保留当前页的解码图片。
  这不是完整 reader-core 场景/分块阅读器，也不是已经测量过的内存/性能保证。
- Swift Task 取消会丢弃旧结果，请求标识保护 loading 状态；同步 JNI 内的网络调用尚不能被其打断。

应用沙盒数据：

```text
Library/Application Support/Kototoro/library.db     # 同一 Room v84，不另建平行 schema
Library/Application Support/Kototoro/sources/prefs  # JVM 持久化偏好
Library/Application Support/Kototoro/sources/images # JVM/Swift 共享的图片文件
```

当前数据库没有导入 Android 旧 SQLite 的迁移装配；不要用破坏性 schema 回退来处理导入。

## 3. 主要文件与职责

| 入口 | 职责 |
|---|---|
| `ios-shared/.../IosLibrary.kt` | 共享 Room 初始化、分类应用接口 |
| `ios-shared/.../IosSources.kt` | SourceProtocolClient、完整对象投影、详情/页/图片身份检查 |
| `iosApp/Kototoro/NativeSourceTransport.swift` | JNI 会话所有权、沙盒/配置/classpath 装配 |
| `iosApp/Native/KototoroJVM.cpp` / `include/KototoroJVM.h` | 进程 VM、线程/字符串/引用与异常桥 |
| `iosApp/Kototoro/SourcesView.swift` | 浏览会话与列表导航 |
| `SourceDetailsView.swift` / `SourceReaderView.swift` | 页面状态、章节、图片阅读与前后导航 |
| `SourceImageFile.swift` / `SourceImageLoader.swift` | 文件校验、后台有界解码；封面/正文共用 |
| `source-host/.../SourceHostSession.kt` / `EmbeddedSourceHost.kt` | 原 JSONL 与 JNI 共用 host 生命周期 |
| `mihon-ios-compat/.../MihonIosPlatform.kt` | 可选 Mobile Application、Looper、Koin/Injekt 与偏好桥 |
| `dex-convert/.../DexJarConverter.kt` | 原共享 APK 转换入口；缓存版本现为 4 |
| `iosApp/Scripts/*.py` / `embed-jvm-resources.sh` | 固定源码构建、预览装配、JNI 烟测和 Xcode 打包 |

省略的 Kotlin 包路径为 `org/skepsun/kototoro/{ios,source/host,dex}`，分别按模块定位。
`iosApp/Kototoro.xcodeproj` 已提交，不需要重新生成 Xcode 工程或引入 CocoaPods/XcodeGen。

## 4. 本机环境与重新运行

- 已验证：Apple Silicon arm64 Mac；Xcode 27.0（27A266a）；iOS 27 runtime（24A434）。
- 专用设备：`Kototoro iPhone` / iPhone 18 Pro，UUID `0CC7F59C-A1BA-43EC-A7BC-FC4D7F92DCA9`。
  其他机器先运行 `xcrun simctl list devices available` 获取实际 UUID，不要把此 UUID 当作通用设备配置。
- Gradle 需 JDK 17+；Zero 本地构建和可选 Mobile API 模块需 JDK 24。
  运行时 Java 26 模块来自固定 IPA，与固定 VM 源版本匹配；不要混入更新的模块 snapshot。
- GNU m4/autoconf 只安装到 `iosApp/build/tools`；没有全局安装或改动系统工具。
- Kotlin/Native 依赖首次下载可能需要仓库约定代理 `127.0.0.1:7890`，命令见 `iosApp/README.md`。

从未生成预览的干净检出开始，按顺序执行：

```bash
python3 "iosApp/Scripts/build-jvm-preview.py"
python3 "iosApp/Scripts/test-jvm-bridge.py"
python3 "iosApp/Scripts/stage-mihon-preview.py"
```

随后执行 `iosApp/README.md` 对应的 Xcode 命令。三套 DerivedData 分别用
`DefaultPreview`（或 `DerivedData`）、`JVMPreview`、`RealApiPreview`；不要混用。
默认构建检测到产物目录残留 JVM 资源会明确失败，不会自动清理用户目录。

**修改 JVM host、兼容适配器或 fixture 后，须重新装配对应预览，再运行 Xcode。**
Xcode 自动构建 Kotlin/Native framework 与原生桥，但不会自动更新预览中的 JVM JAR。
独立 fixture 用 `build-jvm-preview.py` 重新装配，真实 API 用 `stage-mihon-preview.py`。
`inventory.json` 记录真实 API 预览全部本地产物的摘要。

源码/IPA/补丁下载版本与 SHA-256 在 `iosApp/Scripts/jvm-preview-pins.json`。
Mobile AndroidCompat/server/android-jar 的三个 API pin 在
`mihon-ios-compat/compatibility-pins.properties`，与 Windows pin 不同，不能互换。
第三方原始许可证保留在本地下载目录中；分发许可 S3 门槛尚未完成。

## 5. 已执行的验证与证据

| 验证 | 本次结果 | 本地日志（`iosApp/build/`） |
|---|---|---|
| 最终 `:source-host:test` | 89 项，0 失败/跳过 | `ios-precommit-jvm-test.log` |
| 最终 `:dex-convert:test` | 15 项，0 失败；2 个未配置外部样本用例跳过 | 同上 |
| `:ios-shared:iosSimulatorArm64Test` | 4 项，0 失败/跳过；数据库/隐藏字段/图片身份 | `ios-reader-native-test.log` |
| 本机 C/JNI 烟测 | 中文/emoji/NUL/64 位 ID/20 次多线程/错误恢复/关闭通过 | `jni-smoke.log` |
| 默认 XCTest | 分类创建/重启与图片文件回归，2 项通过 | `ios-reader-default-test.log` |
| fixture 最终 XCTest | 重启、详情、读图、翻页、换章、返回与图片文件回归，2 项通过 | `ios-reader-fixture-final-test.log` |
| 真实 APK XCTest | 在线列表/详情/章节/首章两页实际图片，1 项通过 | `ios-reader-real-apk-test.log` |
| 最终 iOS 预览构建 | 成功，并安装/启动于专用模拟器 | `ios-reader-final-build.log` |
| Android Kotlin 编译 | 成功 | `ios-android-final-compile.log` |
| 原生两个切片/framework | arm64 真机/模拟器 Debug XCFramework 构建成功；不等于真机运行 | 主方案 §90 |
| 干净源码树 Zero 重建 | 成功，原生模拟器库重新编译 | `jvm-preview-repro.log` |
| `npm run docs:build` | 成功；修正主方案原有站点外 desktop README 死链 | `ios-handoff-docs-build.log` |

JPEG 的本机 JVM 验证记录为 `ios-reader-jvm-probe.log`。真实详情/阅读 XCTest 截图已导出并实际检查，
位于 `iosApp/build/reports/real-reader`。这些日志和截图只在本机缓存；换机器需重新执行验证。
在线 XCTest 使用公开源，没有使用用户 Cookie/账号；失败时先区分网络/站点变化与桥/模型回归。

快速 JVM 回归（仓库根目录）：

```bash
./gradlew :dex-convert:test :source-host:test --no-daemon
```

### 已定位并修复的问题

- dex2jar 2.4.38 在缺少外部父类型 classpath 时错误回退到 `java/util/Object`。
  只修正输出栈帧类型，不改写指令/字段/字符串；`DexFrameFallbackTest` 先红后绿，实际加载执行两条分支。
  缓存版本由 3 升为 4，Windows 共享转换入口同步获益。
- 预览依赖过滤曾误排除 `serialization-json-okio`，缺失 `OkioStreamsKt`。
  已保留 json-okio 和 coroutines-jdk8；基础 JSON/coroutines/Kotlin 仍用仓库固定版本。
- 自有测试 PNG 的 IDAT CRC 已修正；真实源图片没有被修改。HTML 解码拒绝用例可能产生 ImageIO
  诊断输出，应以 XCTest 的失败计数判断结果。
- Native `Dispatchers.IO` 需显式导入 `kotlinx.coroutines.IO`；Groovy XCFramework 使用
  `XCFrameworkConfig(project, name)`。首次工具链/下载经验见主方案 §90。

## 6. 后续接手顺序与验收点

> §93 苹果 CMS、§94 Aniyomi/Tsundoku/JAR 已接入。后续：设备端 APK/DEX 转换与用户安装界面、WKWebView/Cloudflare 桥
> （BAOZIMH、WENKU8 等需要）、AVPlayer 外挂音轨（外挂字幕已完成，§110）→ LNReader/Legado（iOS 用
> JavaScriptCore 实现原生 `SourceRuntime`，与 CMS 同样走 `RoutedSourceRuntime`）→ Cloudstream（延后）。
> 小说阅读器建议先从 Android 抽取分页/排版无关的 `novel-core`（章节正文 HTML → 段落模型、进度锚点），iOS 用 TextKit 2 渲染。

1. **用户扩展导入/安装记录**：先验证共享转换器能在 Mobile JVM 执行；复用校验与缓存版本，
   不另建 Swift 转换实现。同时设计现有常驻 registry 的加载入口，不能关闭兼容平台再在同进程重建。
2. **可用漫画库**：源搜索/列表分页、收藏/历史、阅读进度持久化，复用已有 core-domain/core-db 规则。
   基本阅读当前没有恢复进度，也没有离线下载；共享模块被链接不代表这些服务已经装配。
3. **完整阅读场景**：接入 reader-core、缩放/分块/预读，对超长页和真实内存占用建立设备基线。
4. **扩展兼容性**：WKWebView、完整 WebKit Cookie、JavaScript/图形 native 桥；Mobile shim 的 native
   方法尚未注册，不能因再漫画成功就认定它们可用。扩大代表源测试后再声明兼容范围。
5. **真机与分发**：Zero 真机启动、网络/内存/性能与 S0/G0/G2/S3；原生真机 framework 链接成功
   不能替代这些门槛。可选 JVM 的 Xcode 打包脚本目前仅允许 arm64 模拟器。

接续时优先扩展现有模块；沿用完整 DTO 与源客户端/图片物化链路，避免复制 Windows 的另一套协议、
网络栈或数据库。每个阶段更新本记录和主方案当前状态，并记录实际运行平台与验证范围。
