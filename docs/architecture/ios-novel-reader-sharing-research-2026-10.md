# iOS/KMP 小说阅读器共享与 EPUB 排版研究（2026-10-13）

## 范围与证据边界

研究对象：小说阅读器（在线来源正文与本地 TXT/EPUB），以及 EPUB 排版还原。不涉及漫画和视频。本轮以当前工作树代码为证据，只做了只读研究：没有改产品代码、没有启动应用，也没有做真机或性能验收。文中的“可迁移”只说明依赖分析的结论，不等于已经编译验证。

## 结论摘要

1. **Android 也不还原 EPUB 排版。** Android 的每个入口都会先把 HTML 转成纯文本（插图转成 `📷 [图片: src]` 占位），再按阅读器的统一设置重新排版。因此只“共享 Android 阅读器”能补齐分页、主题、翻页、TTS、翻译和批注，但解决不了排版还原。排版还原需要单独做一套结构化文档模型，Android 也会受益。
2. **iOS 不跑 Compose。** 按 [kmp-ios-plan](./kmp-ios-plan-2026-10.md#L153) 的 D1 和 [§213](./kmp-ios-plan-2026-10.md#L213)，iOS 用原生 SwiftUI，不搬 Compose UI；`core-ui` 只有 android/jvm 目标。共享方式应和漫画阅读器的 `reader-core` 一样：共享模型和算法，渲染由各平台实现。
3. **两端的进度语义不一致。** Android 的 `scroll` 存“章节进度 ×10000”，iOS 存“段落序号 + 0”，同步或备份后定位会出错（见 N4）。

## 现状对比

### iOS

| 方面 | 现状 | 依据 |
| --- | --- | --- |
| 正文解析 | Swift 正则去掉全部标签，只剩“纯文本段落 + 插图” | [NovelReaderView.swift:14](../../iosApp/Kototoro/NovelReaderView.swift#L14) |
| 渲染 | `ScrollView` + `LazyVStack` 连续滚动，没有分页 | [NovelReaderView.swift:195](../../iosApp/Kototoro/NovelReaderView.swift#L195) |
| 设置 | 只有字号和行距两项 | [NovelReaderView.swift:260](../../iosApp/Kototoro/NovelReaderView.swift#L260) |
| 已有能力 | 目录、段落书签、离线下载、挑战验证、带请求头的插图加载 | 同文件 |
| 缺失 | 分页与翻页动画、双页、主题/字体/边距/缩进、TTS、翻译、划线批注、替换规则、跨章窗口 | — |

### 本地 EPUB（共享 `core-local`，iOS 使用）

- `epubChapter` 只取 `<body>`，所以 `<link>` 引入的 CSS 和 `class` 属性都没了；图片改写成 `<img src>`，`alt`、尺寸和类名也都丢弃。见 [LocalSourceRuntime.kt:245](../../core-local/src/commonMain/kotlin/org/skepsun/kototoro/local/shared/LocalSourceRuntime.kt#L245)。
- 目录：`resolveHref` 会去掉 `#fragment`（[LocalXml.kt:60](../../core-local/src/commonMain/kotlin/org/skepsun/kototoro/local/shared/LocalXml.kt#L60)），章节按 spine 文件划分（[LocalBook.kt:142](../../core-local/src/commonMain/kotlin/org/skepsun/kototoro/local/shared/LocalBook.kt#L142)、[LocalBook.kt:180](../../core-local/src/commonMain/kotlin/org/skepsun/kototoro/local/shared/LocalBook.kt#L180)）。一个 XHTML 里有多个目录项时，只保留第一个标题，后面的小节不会成为可跳转的章节。
- 解析用的是正则（`LocalXml`），不是 XML/HTML 解析器。

### Android

| 方面 | 现状 | 依据 |
| --- | --- | --- |
| 在线正文 | Jsoup Safelist 清洗后转成纯文本 | [NovelContentLoader.kt:258](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelContentLoader.kt#L258)、[:793](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelContentLoader.kt#L793)、[NovelHtmlNormalizer.kt](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelHtmlNormalizer.kt) |
| EPUB 正文 | 用正则去掉 head、style 和全部标签，转成纯文本 | [EpubHtmlText.kt:9](../../app/src/main/kotlin/org/skepsun/kototoro/local/epub/EpubHtmlText.kt#L9) |
| 文档模型 | 纯文本 → 段落 → `NovelComposeBlock.Text/Image` | [NovelComposeDocument.kt](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelComposeDocument.kt)、[NovelInlineImageSupport.kt:63](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelInlineImageSupport.kt#L63) |
| 分页 | Compose `TextMeasurer` 量行，`splitNovelPageLineRanges` 按页高切分 | [ComposeNovelReader.kt:1386](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/ComposeNovelReader.kt#L1386)、[:2076](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/ComposeNovelReader.kt#L2076) |
| 体验 | 分页/滚动、仿真/滑动翻页、双页、主题、字体、TTS、翻译对照、划线摘录、替换规则 | `reader/novel/` 约 1.7 万行 |

EPUB 原有的标题层级、居中、粗斜体、引用、列表、表格和脚注，两端都会丢。ruby 注音两端都会出错：`<ruby>漢<rt>かん</rt></ruby>` 会显示成“漢かん”，对日文轻小说影响明显。

## 问题清单

### N1：EPUB 排版信息在两端都被丢弃

**状态：缺失；优先级高。** 问题出在解析阶段：所有入口都把 HTML 降成纯文本，渲染层拿不到结构和样式，所以只改 iOS 的渲染层修不了。

### N2：HTML→文本有三份重复实现

**状态：需要合并。** Android 的 `htmlToPlainText` 和 `epubHtmlToText`，加上 iOS 的 `NovelText.blocks`，规则各不相同：块级标签的处理、实体解码范围、图片占位格式都不一样。同一章节在两端拆出的段落数可能不同，这会直接影响 N4 的进度定位和段落书签。

### N3：EPUB 目录精度不足

**状态：部分实现。** 一个 spine 文件里如果有多个目录项，靠 fragment 区分的小节会丢失。Android 由 `EpubReader` 和章节映射表处理，两端的章节划分是否一致还没核对。

### N4：阅读进度语义不兼容

**状态：确定的语义差异；优先级高。**

- Android 写入的 `page` 是当前排版下的分页序号，`scroll` 是章节进度 ×10000（[NovelReadingPosition.kt:22](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelReadingPosition.kt#L22)）。恢复时只用这个比例（[NovelReaderActivity.kt:3748](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelReaderActivity.kt#L3748)）。
- iOS 用 `recordPage` 写入段落序号，`scroll = 0`（[NovelReaderView.swift:145](../../iosApp/Kototoro/NovelReaderView.swift#L145)、[SourceLibrary.kt:225](../../core-library/src/commonMain/kotlin/org/skepsun/kototoro/core/library/SourceLibrary.kt#L225)）。

结果：iOS 写的记录在 Android 上会回到章首；Android 的分页序号在 iOS 上会被当成段落号。此结论来自静态分析，还没用实际同步数据复现。

### N5：iOS 阅读体验差距

**状态：缺失。** 见上方 iOS 现状表的“缺失”一行。

## 可共享代码盘点

| 类别 | 文件 | 平台依赖 | 处理 |
| --- | --- | --- | --- |
| 文档模型 | `NovelComposeDocument`、`NovelParagraphSplitter`、`NovelTranslationModels` | 无 | 直接迁移 |
| 图片占位解析 | `parseNovelImages`（`NovelInlineImageSupport`） | 文件里的 `ReplacementSpan` 等 | 解析部分迁出，Span 留在 Android |
| 分页算法 | `splitNovelPageLineRanges`、`NovelChapterPages` | `LongSparseArray` | 改成按行度量回调；iOS 由 TextKit 2 提供行高 |
| 设置模型 | `NovelReaderSettings` 的数据类、枚举、取值范围、`normalized`，以及主题色板 | SharedPreferences、`R`、`Color` | 数值模型共享，存储和资源留在平台 |
| 文本处理 | `NovelTextProcessor`（替换规则）、`NovelTypography.isChapterTitleLine` | `MessageDigest`、Spannable | 哈希改用 okio，Span 留在平台 |
| HTML 清洗 | `NovelHtmlNormalizer`、`NovelHtmlImageResolver` | Jsoup | 需要 KMP 可用的 HTML/XHTML 解析器（自研容错分词器或第三方 KMP 库，需评估） |
| 进度和书签 | `NovelReadingPosition`、`NovelBookmarkNavigation` | 无 | 迁移，并同时修正 N4 |
| 目录 | `NovelChapterDirectory` | 已在 `core-ui` commonMain | 纯逻辑部分下沉到新模块 |

不迁移的部分：`ComposeNovelReader`、各类 Sheet、`NovelReaderActivity`、TTS 引擎、`NovelTextSelection` 等 Compose/Android UI 和服务。iOS 用 SwiftUI/TextKit 2/AVSpeechSynthesizer 实现。

## EPUB 排版还原方案

### 方案 A：结构化中间表示（推荐作为主线）

- 共享模块把 XHTML 加 CSS 解析成两层结构：
  - 块级：标题层级、段落、引用、列表、分隔线、带尺寸的图片、表格、`pre`。
  - 行内：粗斜体、ruby、上下标、链接、脚注。
- 解析 EPUB 自带 CSS 的一个子集：`text-align`、`text-indent`、`margin`、相对字号、字重/字形、`display:none`、分页断点，支持 class 和标签选择器。
- 用户设置决定基准字号、字体和主题，书内样式在此基础上叠加。提供“忽略书内样式”开关，回到当前的统一排版。
- 两端分别转成 `NSAttributedString`（TextKit 2 分页）和 `AnnotatedString`。
- 好处：分页、选择、TTS、翻译、书签和进度共用一套模型，按“块 + 字符偏移”定位，同时消除 N2。

### 方案 B：WKWebView 原版模式（只给 EPUB 用）

- 用 CSS 多栏分页，或接入 Readium Swift toolkit，保真度最高。
- 代价：选择、TTS、翻译和进度模型会形成第二套实现，只适合做成可切换的“原版排版”模式。

### 不推荐：在 iOS 嵌入 Compose

要先推翻 D1；原生文本选择和系统手感会变差，包体积也会增加。

## 建议顺序

1. 新建纯 Kotlin 的 KMP 模块（暂名 `reader-novel`，目标 android/jvm/ios），迁入上表中依赖少的模型和算法，并补 `jvmTest`。可移植性用 `compileCommonMainKotlinMetadata` 检查。
2. 共享 HTML/EPUB 解析：先用一个入口换掉三份正则实现（N2），同时修正 ruby、标题和带 fragment 的目录（N3）。
3. 统一进度语义（N4）：改成“章节内字符偏移 + 进度比例”，并和备份/同步核对旧记录的兼容性。
4. iOS 用 TextKit 2 做分页和滚动渲染，再补设置面板（主题、字体、边距、缩进、翻页动画），处理 N5。
5. 加入结构化样式层（方案 A），Android 同步接入。
6. 最后评估是否需要 WebView 原版模式（方案 B）。

## 验收建议

- 准备一组 EPUB fixture：中文段首缩进、日文 ruby、居中标题和插图页、一个文件内多个目录 fragment、CSS 隐藏元素、表格，在 `jvmTest` 中断言结构化输出。
- 同一章节在 Android 与 iOS 拆出的块数和字符偏移一致。
- 交叉恢复：iOS 写入的进度在 Android 上定位到同一段，反之亦然。

## 进度

### 第 1 步：`reader-novel` 模块（2026-10-13）

新增纯 Kotlin KMP 模块 `reader-novel`（jvm、iosArm64、iosSimulatorArm64），`app` 依赖它；包名保持
`org.skepsun.kototoro.reader.novel`，同包调用方不需要改 import。迁入内容：

| 共享声明 | 来源 | 说明 |
| --- | --- | --- |
| `NovelChapterTranslation`、`NovelParagraph`、`NovelParagraphType`、`NovelTranslationDisplayMode` | `NovelTranslationModels.kt` | 原样移动 |
| `NovelParagraphSplitter` | 同名文件 | 原样移动 |
| `NovelDocumentBlock`、`buildNovelDocument`、`formatNovelParagraphText`、`NOVEL_PARAGRAPH_INDENT` | `compose/NovelComposeDocument.kt` | 去掉 Compose 字样改名（原 `NovelComposeBlock`/`buildNovelComposeDocument`），行为不变 |
| `parseNovelImages`、`ParsedNovelImages` | `NovelInlineImageSupport.kt` | 只迁解析；Span、Canvas 绘制与图片尺寸缓存留在 Android |
| `isNovelChapterTitleLine` | `NovelTypography.isChapterTitleLine` | Android 方法改为委托 |
| `splitNovelPageLineRanges` | `ComposeNovelReader.kt` | 改为公开，平台提供行度量 |
| `ReadingMode`、`TextDirection`、`NovelPageTurnAnimation`、`NovelReaderThemePreset` | `NovelReaderSettings.kt` | 原样移动；`NovelReaderFont` 依赖字符串资源，留在 Android |

`NovelReadingPosition` 没有迁移，留到第 3 步统一进度语义时重新设计。测试放在 `commonTest`（`kotlin.test`），
原 Android 的文档块与切行测试迁入，并补充标题行、图片占位、段落切分用例；正则在 Kotlin/Native 上同样验证。

验证：`:reader-novel:jvmTest` 与 `:reader-novel:iosSimulatorArm64Test` 各 15/15 通过，
`:reader-novel:compileCommonMainKotlinMetadata` 通过。

### 第 2 步：共享 HTML→文本转换（2026-10-13）

`reader-novel` 新增 `novelHtmlToText`（`NovelHtml.kt`）：逐字符分词而不是正则替换，语义沿用 Android EPUB 路径——
块元素之间空一行、`<br>` 换行、图片成为 `📷 [图片: src]` 段落。相对原实现的修正：

- ruby 只保留正文，`<rt>`/`<rp>` 读音不再混入（“漢かん”问题）；
- 源码里的折行按 HTML 规则合并为空格，`<pre>` 保留原样；`&nbsp;` 合并，全角空格 `\u3000` 保留；
- 命名实体与十进制/十六进制数值实体只解码一次，越界值替换为 U+FFFD，未知实体原样保留；
- 注释、CDATA、`<!DOCTYPE>`/`<?xml?>`、大写标签、引号属性里的 `>`、未闭合的 `<` 都能正确处理；
- SVG `<image xlink:href>`（常见于 EPUB 封面页）也会成为图片段落。

另加 `buildNovelLineDocument`：每个非空行一个块，供按行定位/书签的 iOS 阅读器使用，块序与原 Swift 实现一致。

接入情况：

| 调用方 | 改动 |
| --- | --- |
| Android EPUB（`local/epub/EpubHtmlText.kt`） | `epubHtmlToText` 改为委托共享实现；既有 4 个用例不变通过，新增 ruby/数值实体用例 |
| iOS 阅读器（`NovelReaderView.swift`）与小说下载（`ReaderDownloads.swift`） | 删除 Swift 正则解析 `NovelText`，改用 `IosChapterText.blocks()`（`ios-shared` 新增 `IosNovelBlock`） |
| Android 在线正文（`NovelContentLoader.htmlToPlainText`） | **暂未切换**，见下 |

暂未处理，留待后续步骤：

- **Android 在线正文路径。** 它的“`<p>` 之间只有单换行”被 `NovelContentLoaderBaselineTest` 明确固定，翻译段落、
  替换规则和划线都建立在这个段落粒度上，同时已有缓存保存的是旧格式文本。改用共享转换会改变段落切分，
  应和第 3 步（进度改为字符偏移）或第 5 步（结构化块模型）一起切换并重写基线。
- **EPUB 目录 fragment 拆章（N3）。** 本地章节 ID 是 `stableId("local://<id>/<index>")`，章节按 spine 序号编号；
  按 fragment 拆分会让已导入作品的章节序号整体偏移，历史和书签指向错误章节，`recordPage` 也会因章节不匹配拒绝写入。
  需要先为本地作品引入章节方案版本（旧导入保持原划分，或迁移历史），再做拆分。
- **标题层级。** 纯文本格式表达不了标题级别，仍由 `isNovelChapterTitleLine` 按文字规则识别；留给第 5 步结构化样式层。

验证：`:reader-novel` 在 JVM 与 iOS 模拟器各 25/25 通过；Android `local.epub.*` 与 `reader.novel.*` 单测 274 项 0 失败；
iOS 在只含本次改动的临时 worktree（基于 HEAD）中 `xcodebuild` 成功。主工作区的 iOS 构建当前被另一项未完成改动阻塞
（`ReaderDownloads.swift` 引用的新文件 `ReaderDownloadStore.swift` 未加入 Xcode 工程），与本步无关。
未做真机/模拟器上的阅读验收。

### 第 3 步：统一阅读进度（2026-10-13）

**约定。** 小说位置以“章节进度”存入历史的 `scroll`，单位 1/10000（`NOVEL_PROGRESS_SCALE`）。页号、行号依赖阅读器和排版，
只在 `scroll == 0`（章首，或旧记录）时作为各端自己的回退。沿用既有字段，不需要数据库迁移，备份/同步格式不变；
Android 过去写入的 `scroll` 本来就是这个含义，旧记录直接可用。共享换算在 `reader-novel/NovelChapterProgress.kt`：

- `novelProgressScroll`/`novelScrollProgress`：比例 ↔ 存储值；非章首的位置至少存 1，不会被当成旧记录；
- `novelOffsetScroll`/`novelScrollOffset`：字符偏移 ↔ 存储值，整数运算，写入向上取整、读取向下取整，
  恢复点只会落在原字符或其后一个单位内，不会退到上一行（行长于“章长/10000”时逐行精确）；
- `novelPageForProgress`：比例 → 当前分页里的页，恰好是 Android“页/(页数-1)”写法的逆运算；
- `novelBlockAtOffset`：偏移 → 文档块（`NovelDocumentBlock` 新增 `sourceRange`，图片块也带范围）。

**Android 现状问题（静态分析）。** 恢复时设置了 `desiredProgressRatio` 却从未读取：分页模式按历史里的排版页号
`requestPage(page)` 恢复，换字号/边距或读取 iOS 记录都会错位；滚动模式冷启动时 `position` 为空，按代码会回到章首。

**Android 改动。** `restoreComposePosition` 统一处理新发布的章节：有恢复比例时，分页模式发出带 `progress` 的
`NovelPageRequest`（`resolveNovelPageRequest` 按当前分页换算），滚动模式发布带该比例的 `NovelReadingPosition`
（连续阅读器已有按比例换算首个可见块的逻辑）；没有比例时保持原来的页号请求。写入改用 `novelProgressScroll`。

**iOS 改动。** `ios-shared` 新增 `IosNovelDocument`（行块 + 位置规则），Swift 阅读器：

- 写入：`scroll` 为首个可见行的字符偏移换算值（原来固定为 0），`page` 仍写行号；
- 恢复：`scroll > 0` 时按字符偏移找行（可读 Android 记录），否则用行号（兼容旧 iOS 记录）；
- 书签：改用 Android 同一套 `core-domain` 的 `resolveNovelBookmarkPosition`，按摘录文本定位、用分支进度区分重复，
  找不到摘录时退回存储的行号（Android 此时不跳转，iOS 保持原有可用性）；
- Swift 块与共享文档行一一对应（无法解析的图片 URL 变成空文本块，而不是被丢弃导致序号错位）。

**仍有的边界。**

- 旧 iOS 记录（`scroll = 0`、`page` 为行号）在 Android 上仍按页号解释；iOS 端开发时间很短，未做迁移。
- Android 分页进度按页数计算，iOS 按字符计算；插图页较多的章节两端会有偏差，属于比例层面的近似。
- iOS 图片书签的摘录是文件名，Android 用文本定位找不到时不会跳转。

验证：`:reader-novel` JVM 与 iOS 模拟器各 31/31；Android `reader.novel.*`、`local.epub.*` 275 项 0 失败（新增按比例请求页的用例）；
`ios-shared` 新增 `IosNovelDocumentTest` 4/4（iOS 模拟器）；只含本步改动的临时 worktree 中 `xcodebuild` 成功。
未在设备上做交叉恢复验收。

### 第 4 步：iOS 分页阅读与阅读设置（2026-10-13）

**共享层新增（`reader-novel`）。**

- `NovelReaderStyle.kt`：`NovelReaderLimits`（字号 14–24、行距倍数 1.2–2.0、段间空行 0–3、边距 12–120 及步长/默认值）与
  `novelReaderColors`（四套主题 × 明暗共 8 组 ARGB）。Android 的 `NovelReaderSettings` 取值范围/默认值与
  `novelReaderPalette` 改为引用它们；迁移前用脚本逐项比对，8 组色值完全一致。
- `NovelPageSegments.kt`：把行文档组织成分页源——连续文本行按 Android 的显示规则拼接（非标题行两个全角空格缩进、
  段间 N 个空行），每张插图单独成页；提供段内偏移 ↔ 文档块/块内偏移的双向映射。

**iOS（`ios-shared` + `NovelReaderView.swift`）。**

- `IosNovelPages`：分页源与位置换算（页起点 → 可见行与历史 `scroll`；历史/书签 → 片段与偏移），
  `IosNovelStyle`：取值范围、主题色、标题行判定，以及调用共享 `splitNovelPageLineRanges` 的 `pageStarts`。
- 分页：TextKit 2（`NSTextLayoutManager`）按内容宽度排出整段文本，逐行取上下边界，交给共享切页规则得到每页首行；
  每页用同一组属性的 `UITextView` 绘制。行高与 Android 相同（字号 × 行距倍数，固定行高）。
- 交互：横向分页滑动；点击左/右三分之一翻页，在章首/章末跨到上一章末页/下一章首页；点击中间显隐工具栏与状态栏。
  页面铺满屏幕，工具栏浮在正文上方（与 Android 一致），不为工具栏预留空间；正文只避开传感器挖孔一侧
  （竖屏顶部的灵动岛/状态栏，横屏时灵动岛所在的一侧，转屏时更新），底部 Home 指示条区域不留白。
  显隐工具栏不改变排版尺寸，不会重排。
- 页脚显示章节名与“页/总页数”；翻页写入历史（页起点在行内的精确位置，沿用第 3 步的进度约定）；
  重新排版（改字号/边距/旋转）时保持当前页起点；打开、书签跳转、换章由模型的 `NovelReadingTarget` 驱动，
  并等待对应章节的排版完成后再定位。
- 阅读设置面板：分页/滚动、四套主题、字体（衬线/无衬线/等宽）、字号、行距、段间空行、段首缩进、左右/上下边距；
  滚动模式也应用这些设置。默认分页（与 Android 一致）。
- 兼容：分页首页带 `novel.firstParagraph` 标识与静态文本特征，既有 UI 测试的查找方式不变。

**模拟器验证**（独立模拟器、只含本系列改动的临时 worktree；临时验证用例未提交）：长篇 TXT 共 6 页，滑动与点击翻页到第 3 页；
隐藏工具栏后仍为 3/6；切换石板主题后页码保持；退出重进回到第 3 页；切到滚动模式显示正文。
竖屏正文避开灵动岛、页脚贴近底边；横屏左/右分别避开左/右侧灵动岛，两个方向均为 10 页。原有
`testImportedTextNovelSplitsChapters` 在分页默认下通过过一次；之后在同一模拟器上重复导入同一文件时出现导入后白屏，
属于重复导入的不稳定，未在全新模拟器上复测。

**尚未实现。** 仿真翻页（`NovelPageTurnAnimation.SIMULATION`，漫画侧的 `ReaderTurnPager` 依赖漫画引擎）、
分页模式下的文字选择与划线、TTS、翻译、替换规则、亮度与竖排；滑动越过章首/章末不会跨章（只有点击会）；
在线字体（思源宋体、霞鹜文楷等）未接入；iOS 默认无宋体，衬线使用系统衬线设计，中文由系统回退字体显示。
新 Swift 代码暂放在已在工程内的 `NovelReaderView.swift`，以免和进行中的工程文件改动冲突；拆分文件需要同时修改 Xcode 工程。

### 第 5 步：书籍排版（结构化样式层，2026-10-13）

**共享层（`reader-novel`）。** `novelHtmlToStyledText` 在 `novelHtmlToText` 的同一遍分词中记录样式，纯文本逐字相同
（`novelHtmlToText` 改为取其 `.text`），第 3 步的进度与书签不受书籍排版开关影响：

- 段落样式 `NovelParagraphStyle`：标题级别、对齐（始/中/末/两端）、首行缩进（em，未指定为 null）、引用、预格式化；
  `takesIndent` 表示是否沿用阅读器的段首缩进（标题、居中/右对齐、`text-indent: 0` 的段落不缩进）。
- 行内样式 `NovelStyleSpan`：粗体、斜体、下划线、删除线、上下标、相对字号；ruby 读音 `NovelRubySpan` 挂在正文字符上，
  不进入纯文本。
- CSS 子集 `NovelStyleSheet`（`NovelStyles.kt`）：类型/类/id 选择器，后代与子选择器取最后一段复合选择器近似，
  含伪类或属性选择器的规则忽略；按特异性与源顺序应用，`style` 属性最后；属性限于 `font-weight`、`font-style`、
  `font-size`、`text-align`、`text-indent`、`text-decoration`、`vertical-align`；`@media`/`@font-face` 跳过。
  不支持 `display:none`（会改变正文，从而改变位置）、外边距与颜色。
- 元素默认样式：`h1`–`h6` 加粗并按浏览器比例放大，`b/strong`、`i/em`、`u`、`s/del`、`sup/sub`、`small/big`、
  `center`、`blockquote`、`pre`。新块级元素会关闭未闭合的 `<p>` 及其内部未闭合的行内元素，避免漏写 `</b>` 让整章变粗。
- 建造器不再事后重写文本（空行上限、行首换行、行尾空格均在写入时控制），样式偏移即最终文本偏移；
  `<pre>` 行尾空格现在保留（此前会被去掉）。

**本地 EPUB（`core-local`）。** `epubChapter` 把 `<head>` 中按顺序出现的样式表（`<link rel="stylesheet">` 指向的
包内 CSS 文件与 `<style>` 元素）合并为正文前的一个 `<style>` 元素，正文中的 `class`/`style` 原样保留。

**iOS。** `IosNovelPages.styles/paragraphs` 把样式映射到分页文本坐标（含缩进前缀），`IosNovelDocument.lineStyles/
lineParagraph` 映射到单行坐标。分页模式用 TextKit 属性：标题等放大行按比例增加固定行高，含注音的行不限最大行高；
注音用 `CTRubyAnnotation`（TextKit 2 已实测可显示）；字体没有粗体/斜体字形时以描边/倾斜模拟。滚动模式用 SwiftUI
富文本（不显示注音）。阅读设置新增“书籍排版”开关（默认开）。

**模拟器验证**（带样式表的 EPUB fixture）：标题居中放大加粗、“——序——”居中、粗体与下划线、注音（ゆき/よる/しず/ふ）、
上标、`text-indent: 0` 不缩进、署名右对齐均与样式表一致；关闭开关回到统一排版；滚动模式样式生效。
发现中文斜体未显示：iOS 默认没有安装宋体，衬线字体实际是系统 New York（有拉丁斜体字形），中日韩字形来自无斜体的回退字体。
现在字体没有对应字形时以描边/倾斜模拟，有斜体字形时仍对斜体范围内的中日韩字符加倾斜；用独立的模拟器进程以相同字体与规则
渲染核对（中文倾斜、英文为真斜体、粗体与粗斜体正常），主工作区增量构建中该文件编译通过。
（验证期间开发机磁盘一度只剩约 300MB，滚动模式截图出现的 SQLite “database or disk is full” 由此引起。）

**测试。** `reader-novel` JVM/iOS 模拟器各 40/40（新增 `NovelStyledTextTest`），`ios-shared` `IosNovelDocumentTest` 5/5
（新增分页样式映射），`core-local` 8/8（新增样式表内联）。

**未做。** Android 尚未接入书籍样式：Android 的正文在进入阅读器前会经过替换规则、翻译和划线等基于纯文本偏移的处理，
样式偏移需要随替换规则重新映射，这部分留作后续；`display:none`、外边距、颜色、图片尺寸、表格布局、
EPUB 目录按片段拆章（需要本地章节方案版本，见第 2 步）均未支持。

### 第 6 步评估：EPUB“原版版式”模式（2026-10-13，未实现）

**第 5 步之后仍然做不到的 EPUB 类型。** 文本引擎（共享样式层 + TextKit）覆盖了绝大多数流式中文/日文小说的排版需求，
但以下几类本质上需要浏览器级排版，用当前路线补齐的代价接近重写一个 CSS 引擎：

| 类型 | 现状 | 影响面 |
| --- | --- | --- |
| 固定版式 EPUB（`rendition:layout` = `pre-paginated`，绘本、漫画、杂志） | 当作流式文本读，版面完全错误 | 小众但一读就错 |
| 竖排（`writing-mode: vertical-rl`，日文轻小说常见） | 被读成横排；Android 也没有（`ReadingMode.VERTICAL` 枚举无实际实现） | 日文书用户明显 |
| 书内字体（`@font-face`）、浮动、表格、外边距/颜色、图片尺寸 | 忽略 | 排版“味道”不同，内容可读 |
| 脚注弹窗、页内锚点跳转、SVG/MathML | 不支持或只显示为图片/文字 | 少量书籍 |

**两种实现路线。**

1. **自建 WKWebView**：每章加载 XHTML（带书内 CSS），用 CSS 多栏分页、注入主题/字号 CSS、JS 桥上报位置。
   依赖最少，但分页边界、选择、跨章、进度定位、竖排翻页方向都要自己处理，工作量和维护成本都高。
2. **Readium Swift toolkit**（BSD-3-Clause，SwiftPM，iOS 15+；流式 EPUB 支持分页/滚动、RTL、搜索、高亮、TTS，
   固定版式支持分页与 RTL；当前 3.11，另有 4.0 alpha）。成熟度高，用户设置（字号、颜色等）有现成 API；
   CJK 竖排在 Swift 导航器上的实际表现文档未明确说明，需要实测。代价是引入第一个 SwiftPM 依赖、
   第二套阅读器 UI，以及它自带的位置模型（Locator：资源 href + 资源内进度 + 文本片段）。

**与现有设计的衔接（Readium 路线）。**

- 进度：本地 EPUB 在 `core-local` 中按 spine 一项一章，Readium 的 Locator 也以 spine 资源为单位，资源内 `progression`
  可直接写入第 3 步约定的 `scroll`（章节进度 × 10000），文本阅读器与原版模式、iOS 与 Android 可以互相续读（按排版比例，近似）。
- 书签：Locator 带有所在文本片段，可作为书签摘录，沿用 `resolveNovelBookmarkPosition` 的文本定位。
- 不可复用：替换规则、翻译、划线批注、我们自己的 TTS 管线与分页设置都不作用于原版模式；Readium 的 TTS/高亮是另一套。
- 只适用于本地 EPUB；在线来源与 TXT 继续走文本引擎。

**建议。**

- 不把原版模式作为默认，也不做成全局开关；作为**本地 EPUB 的可选模式**，并在识别到固定版式或竖排时提示/自动切换。
- 先做两件小事再决定是否投入：
  1. **书库体检**：在 `core-local` 导入时（或一次性脚本）统计 OPF 的 `rendition:layout`、`page-progression-direction`、
     CSS 中的 `writing-mode`/`@font-face`，看用户实际书库里这几类书占多少；
  2. **Readium 原型**（独立分支）：只接入打开本地 EPUB → 分页阅读 → 进度写回 `scroll`，实测竖排日文书、固定版式书、
     包体积增量与启动耗时，再决定是否进入正式迭代。
- 如果体检显示竖排日文书占比高而固定版式很少，也可以考虑只在文本引擎内补竖排（TextKit 的竖排支持有限，
  需另行评估），但这条路线风险高于 Readium 原型。

### 后续四项（2026-10-13）

**iOS 仿真翻页与分页文字选择。** 分页容器由 SwiftUI 横向 `ScrollView` 改为 `UIPageViewController` 包装（`NovelPager`）：
“滑动”用 `.scroll` 样式，“仿真”（对应 Android `NovelPageTurnAnimation.SIMULATION`）用系统 `.pageCurl`；阅读设置新增
“翻页效果”。点击由分页器统一识别：左右三分之一带动画翻页（仿真模式即卷页），章首/章末跨到相邻章，中间显隐工具栏；
系统卷页自带的边缘点击被关闭，避免与之冲突。页面 `UITextView` 打开选择，长按选中弹出系统菜单（拷贝、查询、翻译等），
有选区时单击只取消选择。没有复用漫画的 `ReaderPageTurn.swift`（依赖漫画引擎，且另一项工作正在修改）。
模拟器验证（默认构建、单独 DerivedData，安装在开发用模拟器上，临时用例已删除）：点击翻到 2/7；长按出现“拷贝”菜单；
有选区时点击仍为 2/7；切换仿真后点击翻到 3/7。卷页动画中间帧未截到。

**EPUB 按目录小节拆章（`core-local`，iOS 使用）。** `LocalItem.chapterScheme`：旧元数据缺省为 1（每个 spine 文档一章），
新导入写 2：同一文档在目录中有多个条目、且后续条目的锚点（`id`/`name`）在文档中存在时，从文档开头到第二个锚点为第一节
（标题取第一个条目），其后每个锚点开始一节；正文按锚点元素在 `<body>` 中的位置切片。目录只取 EPUB 3 导航文档中的
`toc` 部分（不再混入 landmarks 等列表），NCX 照旧。章节 ID 仍由章节序号决定，所以旧导入保持原划分，历史与书签不受影响；
重新导入同一文件会按新规则划分（与此前重新导入生成新作品的行为一致）。Android 的 EPUB 章节仍是每个 spine 文档一章：
其章节映射存在数据库中，改变划分需要迁移，未在本轮处理。`core-local` 新增用例覆盖拆分、切片与旧作品保持原划分（9/9）。

**Android 接入书籍排版。** Android 的 EPUB 文本与共享转换一致，`EpubChapterStyles` 读取章节 XHTML 与其链接的样式表得到
`NovelStyledText`；当前章渲染、连续模式预加载相邻章、异步解析图片上下文三处在得到 EPUB 文件与章节路径时加载，存入
`NovelComposeReaderUiState.bookStyles`（只保留窗口内章节）。共享的 `NovelStyledText.formatParagraph` 按顺序把 Android 的段落块
在样式文本中定位，按 `formatNovelParagraphText` 的规则格式化，标题/居中/右对齐/`text-indent: 0` 的行不缩进；
找不到（被替换规则或翻译改变）的段落照旧显示。分页与两条滚动路径都用带样式的 `AnnotatedString`：行内粗体/斜体/下划线/
删除线/上下标/字号，行级对齐，放大行按比例加大行高；`highlightedNovelText` 改为保留原有样式再叠加高亮。设置新增
“书籍排版”开关（默认开，`book_styles`），文案加入英文与三种中文资源。Android 不显示 ruby 注音。
验证：编译通过，`reader.novel.*`、`local.epub.*` 279 项 0 失败（新增 `EpubChapterStylesTest`、`NovelBookStyleTextTest`）；
本机没有连接设备，空间不足以新建模拟器，**未做 Android 运行时验收**。已知限制：开关从关切到开时，当前章的样式要到换章后才加载。

**可移植性。** `reader-novel` 的 `compileCommonMainKotlinMetadata` 通过（第 5 步引入的 `RegexOption.DOT_MATCHES_ALL`
已改为 `[\s\S]`）；`core-local` 的该检查在 HEAD 即因 `FileSystem.SYSTEM` 等失败，本轮未新增此类用法。
