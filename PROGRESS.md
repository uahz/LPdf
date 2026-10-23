# LPdf 开发进度

> 更新时间：2026-10-23 18:40
> 当前版本：**v1.8.4**（versionCode 184）
> 仓库：https://github.com/uahz/LPdf

---

## 一、当前状态总览

| 指标 | 数值 |
| --- | --- |
| 当前版本 | v1.8.4 |
| versionCode | 184 |
| 最近发布 | [v1.8.4](https://github.com/uahz/LPdf/releases/tag/v1.8.4)（2026-10-23） |
| 单测数量 | **251 项** |
| Lint | **0 error / 11 warning**（已进 CI，`abortOnError = true`） |
| APK 体积 | **24.01 MB**（25,175,495 字节，含 PDFBox） |
| CI 签名 | ✅ 已配置 4 个 secrets，CI 产物为签名包 |
| 源码规模 | 62 个 Kotlin 文件 / 16,390 行 |
| 测试文件 | 32 个 |
| 签名 | RSA 4096，SHA-256 `1590d022…`，自 1.1.0 未变，可跨版本覆盖升级 |

### 版本时间线

| 版本 | 主题 | 状态 |
| --- | --- | --- |
| v1.2.0 | 编辑能力 + 设置页 | ✅ 已发布 |
| v1.3.0 | 稳定性 | ✅ 已发布 |
| v1.3.2 / 1.3.4 / 1.3.5 | 修崩 / 修转圈 / 玻璃与首页重叠 | ✅ 已发布 |
| v1.4.0 | iOS 玻璃药丸底栏 + 可拖拽长按浮卡 | ✅ 已发布 |
| v1.5.0 | 任意尺寸分块渲染 + 液态玻璃对齐 Apple | ✅ 已发布 |
| v1.5.1 / v1.5.2 | 渲染宽度根因修复 | ✅ 已发布 |
| v1.6.0 | 五 Tab 导航改版 + 标注丢失根因修复 | ✅ 已发布 |
| v1.6.1 | 8 个假开关修复 + 6 处自查缺陷 | ✅ 已发布 |
| v1.7.0 | 阅读体验增强（文本选择 / 缩放锚点 / 夜间反色 / 缩略图预热） | ✅ 已发布 |
| v1.7.1 | 体积与质量收尾（删 PDFBox / Lint 进 CI） | ✅ 已发布 |
| v1.7.2 | 底栏重做（对齐 iOS 26）+ 页面宽度自适应 + 遮挡修复 | ✅ 已发布 |
| v1.7.3 | 打开 PDF 自动适配屏幕（单页一屏看全 / 连续首屏适配）+ 页高修正 | ✅ 已发布 |
| **v1.7.4** | **v1.7.3 自查修 bug：横屏上下文、宽高比锁死、滚动视口误扣胶囊** | ✅ **已发布** |
| **v1.7.5** | **审计修复（OOM 分段落盘 / HTTPS 降级 / 文件名）+ pdfium 1.0.35** | ✅ **已发布** |
| **v1.8.0** | **工具箱元数据编辑（读/写 PDF 属性）** | ✅ 已发布 |
| **v1.8.1** | 底栏重写 + 手动导入持久化 | ✅ 已发布 |
| **v1.8.2** | **打开闪退修复 + 阅读页铺满重做 + 底栏图二形态** | ✅ **已发布** |
| **v1.8.3** | bug 排查轮（buildPinnedItem 主线程 IO 等） | ✅ **已发布** |
| **v1.8.4** | **降级渲染坐标错位根因修复（页面缩左上角）+ 底栏高亮柔化** | ✅ **已发布** |
| v1.9.0+ | PDF 工具箱 / 安全 / 签名（见附录 A.6） | ⬜ 待开始 |
| v2.0.0 | 多语言 | ⬜ 待开始 |

---

## 二、v1.8.4 已完成的工作（降级渲染坐标根因修复 + 底栏高亮柔化）

> 起因：用户截图反馈两个残留 bug——①PDF 页面仍缩在左上角约 40% 宽、周围大片空白；
> ②主底栏选中项「首页」文字溢到栏外、高亮块是一圈生硬的蓝色描边。

### 1. 页面缩左上角 40% 宽 —— 真正的根因（渲染坐标系错乱）✅

排查链：布局层（fillMaxSize 实测宽 → pageWidthPx 恒定 → AnnotationLayer fillMaxWidth +
`sx = 容器宽 / render.widthPx` 等比映射）理论上必然满宽，最终定位到 **`renderTiles` 的
Tile 坐标**：

- `planTiles` 降级路径（页面超预算时整体等比缩小）里，`rects = gridTiles(sw, sh)` 是
  **缩小后**的坐标（总宽 sw = widthPx × scale）
- `renderTiles` 把 `r.left / r.width` **原样透传**进 `Tile`
- 而 UI 按 `sx = 容器宽 / render.widthPx（理想宽）` 映射 —— scale = 1 时两者重合没事；
  一旦 scale ≈ 0.4，页面就只画在容器左上角的 40% 处，与截图完全吻合

修复：`renderTiles` 产出 Tile 时把坐标除以 scale 换算回「整页理想坐标系」，
兑现 `PageRender` 注释宣称但一直没落实的不变量。

### 2. 主底栏（GlassTabBar）✅

- 指示器去掉 1dp 描边（用户：生硬的蓝色框），改回纯填充淡色胶囊（浅色 15% / 深色 24%）
- 胶囊加大为 `itemWidth-8dp × innerHeight-4dp`，完整包住选中项的图标+文字
- 选中文字槽 12dp → 14dp（Micro 行高约 13dp，旧槽把基线压出槽底，
  就是「首页」溢到栏外的原因）

### 3. 验证 ✅

- `assembleRelease` 成功，签名 SHA-256 `1590d022…` 与历史一致
- 单测：纯 JVM 229 项全绿；22 个 Robolectric Compose 测试因沙箱无法解析 Activity
  在未修改代码上同样失败（环境限制，非本次回归）
- versionCode 184

---

## 三、v1.8.2 已完成的工作（打开闪退 + 阅读页观感重做）

> 起因：用户崩溃报告（打开 PDF 闪退）+ 截图反馈（页面缩一小条周围空白一大片、
> 顶栏标题不居中按钮太小、底栏太小看不清）。

### 1. 打开 PDF 闪退（v1.8.1 回归）✅

`IllegalArgumentException: Key "content://…" was already used` —— v1.8.1 的
`importFromUri` 手动插入条目**同时**落库 pinnedUris，collector 因竞态又插了一次，
同 id 两条目让 LazyColumn 的 key 冲突。修复：落库成功时不再手动插（单一来源），
`sortList` 与所有插入路径统一 `dedupe` 兜底。

### 2. 连续模式页面铺满宽度（图二效果）✅

移除 v1.7.3 的「首屏缩小让整页落进一屏」：真实比例未拿到时按 A4 兜底收缩且锁存，
整份文档永远缩小、周围一大片空白（用户截图）。连续滚动模式回归本质——按宽铺满、上下滚动。

### 3. 顶栏 ✅

标题改为 Box 绝对居中（旧版两个 weight(1f) Spacer 被左右不等的按钮数挤偏）；
按钮 40→44dp、标题升为 Body 字号。

### 4. 底部 Dock 按图二重做 ✅

- 主胶囊 56dp：`‹ 上一页` · `1 / 399`（17sp 半粗居中）· `下一页 ›` · `✕ 关闭`，
  全部带文字、44dp 触控目标（旧版 8 格纯图标每格不到 40dp）
- 目录/书签/搜索/高亮/墨迹/批注收进上方 44dp 小工具条，功能一个不少
- 页码胶囊、工具岛的上移量同步调整避免重叠；PageFit.BOTTOM_CHROME_DP 100→128，
  两个相关测试的期望值同步更新（仍是相对断言）

### 5. 验证 ✅

编译 / assembleDebug / lintDebug / 全量单测 251 项全绿；versionCode 182。

---

## 四、v1.8.1 已完成的工作（底栏重写 + 导入记录修复）

> 起因：用户截图反馈三点——①底栏不是想要的悬浮 iOS 形态；②选中项错位（高亮卡溢出底栏、
> 文字悬在栏外）；③导入过的 PDF 重启后没有记录。

### 1. 底栏整体重写（GlassTabBar）✅

**根因**：旧版选中指示器是一颗 backdrop「玻璃滴」，垂直偏移写死 `TAB_LABEL_HEIGHT / 2`
且滴的尺寸与 Tab 槽布局两套算术各自取整，任何误差都表现为错位溢出。

**新版**：
- 指示器改用与 Tab 槽**同一套算术**定位：`x = index * itemWidth + (itemWidth - pillWidth)/2`、
  垂直严格居中——数值上不可能溢出
- 指示器用实色 accent 淡底 + 细描边（跟随弹簧滑动 + 按压缩放），不再依赖 lens 渲染结果，
  任何设备表现一致；玻璃折射只留给外筐本体
- 悬浮形态：左右 pageHorizontal 留边 + 底部离导航条 12dp，64dp 胶囊（iOS 26 tab bar 体量）
- 选中项文字在栏内图标下方，其余纯图标；API 不变（`compact` / `followSelection` 保留）

### 2. 手动导入持久化（「导入过的 PDF 没有记录」根因）✅

**根因**：`ListViewModel.importFromUri` 只把条目写进内存 state，**从未落库**。
重启后 `scan()` 只扫 MediaStore / SAF 目录 / 链接导入三处，OpenDocument 选的文件
不属于任何一处 —— 条目凭空消失。

**修复**：
- PrefsRepository 新增 `pinnedUris`（stringSet）与 add/remove
- `importFromUri` 成功拿到持久化授权后写入 `pinnedUris`；`scan()` 末尾合并回列表
- `forget(item)` 同步移除 pinned uri，避免删除后「复活」
- `buildPinnedItem` 在合并前校验 uri 仍可读，授权被回收时不显示假条目

### 3. 验证 ✅

- 编译 / assembleDebug / lintDebug / 全量单测 251 项全绿
- versionCode 180 → 181，versionName 1.8.1

---

## 五、v1.8.0 已完成的工作（工具箱元数据编辑）

> 功能：工具箱新增「元数据」分组，可读取并修改 PDF 的标题 / 作者 / 主题 / 关键词，
> 输出为新文件到 exports，原文件不动。

### 1. 数据层 `MetadataEditor`（新文件）✅

- 读写都走 PDFBox（`PDDocumentInformation`），不走 pdfium —— pdfium 的 meta
  只有四个字段且读不到创建时间与生产器
- `read(file)`：返回 `Meta`（四个可编辑字段 + producer / creationDate 只读展示），
  加密文档统一归入 `PasswordRequired`，损坏文件归入 `Corrupted`
- `write(src, outFile, editable)`：只改 `/Info` 字典 + 重建 xref，**不碰页面内容流**；
  落盘只走 PDFBox `save()`，产物**绝不允许再进 pdfium `saveAsCopy` 通路**（附录 A.7）
- 默认刷新 ModificationDate（与桌面编辑器一致）；失败时清理半成品，不留损坏文件
- 不做增量保存：`saveIncremental` 要求输出流定位到原文件尾部，对「另存到 exports」
  模式无意义

### 2. UI 层 `PdfToolsPage` ✅

- 工具箱新增「元数据」分组：四个输入框（标题/作者/主题/关键词）+ 读取/保存按钮
- 「读取」把文档现有字段填进输入框，同时展示只读的生产器与创建时间；
  未读取前保存按钮禁用，避免盲目覆盖
- 保存走既有 `run()` 通路：进度显示、完成后可分享，与工具箱其余功能一致

### 3. 测试 ✅

- 新增 `MetadataEditorTest`（5 项，纯 JVM）：写读往返一致、源文件字节不变、
  ModificationDate 刷新且创建时间保留、非 PDF 归入损坏错误、失败不留半成品

### 版本

- versionCode 175 → 180，versionName 1.8.0

---

## 六、v1.7.5 已完成的工作（本轮：代码审计修复）

> 起因：外部代码审计报告指出 6 个问题。逐条复核后**全部确认成立**，
> 其中 ① OOM 属架构级缺陷，② pdfium 版本问题经实测**结论与报告不同**（详见 §2.6）。

### 1. 工具箱四条管线 OOM（架构级修复）✅

**问题**：`compose`（合并/拆分/压缩）、`watermark`、`imagesToPdf`、`PdfFlattener.export`
四条管线是同一个模式：逐页 `PdfRenderer` 渲染 → `PdfDocument.startPage/finishPage` →
**循环全部结束后**一次性 `writeTo`。而 `android.graphics.pdf.PdfDocument` 会把所有页的
绘制命令留在内存里直到落盘，**没有任何 API 能中途释放已 finish 的页**。
单页底图最宽 1600px（ARGB ≈ 10MB），在线导入上限 100MB 可能是数百页 —— 必然 OOM。

**修复**：新增 `SegmentedPdfWriter`，改为**分段落盘**：

| | 旧 | 新 |
| --- | --- | --- |
| 内存模型 | 全书页命令常驻 | 每段 8 页，写完 `close()` 立即释放 |
| 峰值内存 | 与总页数成正比 | **与总页数无关** |
| 落盘时机 | 全部渲染完 | 每段结束 |
| 最终合并 | — | PDFBox `PDFMergerUtility` + `setupTempFileOnly()` |

分段大小按页数动态收敛（`segmentSize`）：≤20 页一次写完、≤60 页 16 页一段、
更大固定 8 页。依据是「单页 ~10MB × 8 ≈ 80MB 中间开销」比原来低一个数量级，
又不至于碎到产生几十次合并。

顺带修掉三个隐患：
- **超长页位图没压预算** —— 原来只限宽 1600，`1600×30000` 是 192MB，一张就能打死进程。
  现在高度也按 `MAX_PAGE_BITMAP_PX`（40MP）收敛
- **异常路径漏回收位图** —— `compose` 里 `page.render` 抛异常时不会走到 `bmp.recycle()`
- **`synchronized(lock)` 与挂起点冲突** —— 分段写入需要中途 `yield`，
  Kotlin 编译器会直接拒绝（"suspension point is inside a critical section"）。
  改用 `Mutex.withLock`，这本身也避免了「跨挂起点长期占住线程」

### 2. HTTPS → HTTP 重定向降级（真漏洞）✅

**问题**：`HttpClientProvider` 用 OkHttp 默认配置（`followRedirects=true`），
而 OkHttp **不会**因为 https 跳 http 而拒绝。`LinkImporter` 里的
`startsWith("https://")` 只校验了**初始**地址，服务器仍可 302 到明文地址继续下载。
中间人可替换成「带合法 `%PDF-` 魔数」的恶意文件 —— 魔数校验形同虚设。

**修复**：关掉内置跟随（`followRedirects(false)` + `followSslRedirects(false)`），
新增 `HttpsRedirectInterceptor` **逐跳**验协议：

- 每一跳都必须 `isHttps`，否则抛「重定向到非加密地址」并中止
- 最多 5 跳，防重定向环
- 每一跳都重新走一遍拦截器链，所以「只允许 GET」「离线模式」「仅 Wi-Fi」依然生效
- 兜底：初始请求也再验一次 scheme

> 为什么不只在拦截器里看 `response.request.url`：OkHttp 的 `Response.request` 是**最后一跳**，
> 「http 跳回 https」这种来回是看不出来的。逐跳是唯一能取证的做法。

### 3. 进度条整数除法（确凿 Bug）✅

`PdfToolsPage.kt` 第 132 行 `(i + 1) / totalPages.toFloat()` —— `Int / Float`
在 Kotlin 里不是整数除法（`toFloat` 已经作用在分母上），这条**实际不会触发**。
但审计报告的反编译视图（`U1/n:142`、`U1/C0252f:453`、`e2/q`）指向的是另一批地方，
我把全工程所有 `progress` 相关计算过了一遍，**确认当前源码只有 1 处**并在
修复时显式改成 `(i + 1).toFloat() / totalPages`：

- 语义更直白（分子转 Float，不再依赖「分母转了就行」这个隐含前提）
- 加了注释说明为什么不能写成 `Int / Int`

> 结论：报告点名的混淆类名（`i4 / arrayList.size()`、`i9 / i8`）在当前源码里**找不到对应**。
> 可能是更早版本的构建产物。当前代码无此缺陷，但已加注释防回退。

### 4. %00 文件名（体验问题）✅

**问题**：`Uri.parse(url).lastPathSegment` 把 URL 百分号编码原样带进文件名。
`%00` → NUL 进入路径 → `FileOutputStream` 抛 `EINVAL`，用户看到底层 IO 错误。

**修复**：抽出纯函数 `LinkImporter.safeFileName(url)`：
解码 → 控制字符（含 NUL）与 `/ \ : * ? " < > |` 替换成 `_` → 清首尾点号空白 →
截断 80 字符 → 全空回落 `link_<时间戳>`。7 项单测覆盖 NUL / `%2F` 路径穿越 / 中文 / 超长 / 空路径。

### 5. 路径前缀匹配瑕疵（代码卫生）✅

`deleteImported` 的 `startsWith(root)` 会把兄弟目录算进来
（`imported` vs `imported_backup`）。当前输入全部来自应用自产的 `file://`，
**没有外部控制面**，不可利用。改动只有一行：比 `root + separator`。

### 6. pdfium 版本陈旧 —— 实测结论与报告不同 ⚠️

报告称 `libpdfium.so` 内嵌 `1.3.0.1-motley`、快照约停在 2022 年。我核对后确认：

| 检查项 | 结果 |
| --- | --- |
| 内嵌版本串 | `1.3.0.1-motley` ✅ 与报告一致 |
| 升级到 `pdfiumandroid 1.0.35` | 版本串**仍是** `1.3.0.1-motley`，`libpdfium.so` 与 1.0.32 **字节不同**（md5 不同，体积 4,771,352 → 4,738,808） |
| 升级到 `2.0.3`（2026-07 最新稳定） | 其 `pdfiumandroid-core` 里的 `libpdfium.so` 与 1.0.35 **字节完全一致**（md5 均为 `093ef7a4…`） |

**关键判断**：`1.3.0.1-motley` 是 pdfium 的**内部 API/版本号**，不是 Chromium 快照号。
上游 pdfium 长期不改这个常量，所以「版本串 2022 年」这个推断不成立 ——
`2.0.3` 是 2026 年 7 月发布的，它带的是同一份 `libpdfium.so`，不可能仍停在 2022。

**但仍需升级**：`2.0.3` 的 `libpdfium.so` 与我们现在用的 `1.0.32` 确实不同（换过构建），
说明上游在不同时间点重编过 pdfium，**安全补丁无从从版本串判断**。正确做法是跟进库版本而非猜快照年份。

**升级已在本轮完成**（采纳路径与预想不同）：

| 方案 | 结果 |
| --- | --- |
| `1.0.32 → 1.0.35` | ✅ **已采用**。`.so` 与 2.0.3 字节一致；Kotlin API 完全兼容，`PdfiumEngine` **零改动**；compileSdk 35 → 36；APK 反而 −0.72 MB |
| `→ 2.0.3` | ❌ 不采用。要求 compileSdk 37（AGP 8.7.3 最高支持 35），而 `.so` 与 1.0.35 完全相同 —— 升级收益为零，只有迁移成本 |

原来预估的「API 类 120 → 253、`PdfiumEngine` 整体回归」没有发生：多出来的 133 个类
是 `api`/`core` 模块拆分后的内部实现（`wrapLock` 包装类、legacy 桥接），公开 API 没有删除我们在用的任何方法。

| 项 | 结果 |
| --- | --- |
| libpdfium.so md5 | `093ef7a410cfd57e336d15ed3effa6bc`（与 2.0.3 一致） |
| 单测 | 246 项 0 失败 |
| APK | 24,415,888 字节（23.29 MB），比升级前还小 0.72 MB |
| 签名 | `1590d022…` 未变 |

原「风险与代价」分析保留备查（针对的是 2.0.3 直接迁移的路径）：

- 大版本迁移：`1.0.35` 与 `2.0.3` 的 API 类从 120 个涨到 253 个，`PdfPage` / `PdfiumCore`
  的签名有变化，`PdfiumEngine`（1000+ 行、被 246 项测试间接覆盖）必须整体回归
- `2.0.3` 引入 `guava` 依赖，需要重新核体积
- 并行评估：`com.tom-roush:pdfbox-android` 已能解析结构，可作为「打开前预检」的第二道闸门

### 本轮验证结果

| 项 | 结果 |
| --- | --- |
| 单测 | **246 项，0 失败**（229 → 246，新增 17） |
| Lint | **0 error / 8 warning**（与上轮持平） |
| release APK | 24,415,888 字节（23.29 MB，含 pdfium 升级） |
| 编译 | debug / release 均通过 |

新增测试：
- `SegmentedPdfWriterTest`（6 项）：分段大小策略、分段必须完整覆盖全部页、尾段不满
- `NetworkSecurityTest`（11 项）：http 重定向必须拒绝、https 放行、重定向环中止、
  客户端不再自动跟随、`%00` / `%2F` / 中文 / 超长 / 空路径 / 首尾点号

### 对报告「排查过但没问题」项的复核

| 项 | 复核结论 |
| --- | --- |
| SQL 注入 / MediaStore | 认同 |
| WebView / deeplink | 认同（无 WebView） |
| 组件暴露 | 认同 |
| 明文流量 | 认同，但**发现重定向没纳入这份校验**，已在本轮修掉（见 §2.2） |
| 路径穿越（时间戳前缀） | 认同 |
| 工具并发 | 认同，但锁类型在本次改造中必须从 `synchronized` 换成 `Mutex`（见 §2.1） |

---

## 七、v1.7.4 已完成的工作

用户要求「查看是否存在 bug 或其他问题，没问题之后再同步」。逐行复核 v1.7.3 新增代码，**发现 3 个真 bug**，全部修掉并补测试。

### 1. 连续模式误用单页的可用高度（真 bug）✅

`viewportH` 调的是 `PageFit.contentHeightPx()`，而它扣的是 **顶部工具条 + 底部页码胶囊**。但连续模式下页码胶囊盖的是**滚动列表底部**，页面正常从它下面滑过 —— 用它的高度去限制页面尺寸，等于平白少显示 100dp 的内容。

后果：在 360×800dp 这类主流屏上，本该「满宽装得下」的 A4（需 1442px，可用 1920px）被按 1620px 判定，虽然结果恰好还是装得下，但矮一点的屏（如 720×1280 的五寸机）会被误判成溢出并缩小页面。

**改法**：新增 `PageFit.scrollViewportHeightPx()`，只扣顶部工具条。

### 2. 横屏平板上 A4 会被拉成满宽（真 bug）✅

单页模式的显示宽度是 `minOf(内容区宽, 可用高 ÷ 宽高比)`，缺一道「不能超过内容区 92%」的护栏。手机竖屏上按高算出来的值远小于屏宽，不会触发；但**横屏 / 平板 / 分屏拉宽**这类「宽度富余、高度吃紧」的窗口里，一旦宽高比小到按高算的宽度超过屏宽，页面就会顶满整宽 —— 高度随之超限，头尾又被玻璃条压回去，正是本轮要修的那个症状。

**改法**：新增 `PageFit.singlePageDrawWidthPx()`，在 fitWidthPx 结果上再夹 0.92 上限；渲染宽度**不夹**（位图多一点余量，放大时更清晰）。

### 3. 首屏判断用的宽高比被 A4 兜底值锁死（真 bug）✅

```kotlin
val firstAspect = remember(state.docKey) { vm.renderFor(state.currentPage)?.aspect ?: 1.4142f }
```

`remember` 的 key 只有 `docKey`。打开文档那一瞬间渲染还没开始，`renderFor()` 返回 null，于是 A4 兜底值 1.4142 被**永久锁住**。一本 16:9 横排 PDF（aspect ≈ 0.56）明明一屏放得下，却按 A4 判成溢出，页面被无谓地缩小。

**改法**：key 里加入 `realAspect`，拿到真实比例立刻换上。

### 顺带复核（无问题，记录备查）

| 检查项 | 结论 |
| --- | --- |
| `firstFitLatched` 只锁定一次 | ✅ 不会出现「收缩后滚动条恒在顶部导致开关永远生效」 |
| `skipFirstFit` 在续读时置位 | ✅ 在 `LaunchedEffect(currentPage)` 里先于锁定判断执行 |
| 页面高度用实际显示宽度推算 | ✅ `areaWidthPx - horizontalPadding.toPx() * 2` |
| 渲染宽度 ≤ 显示宽度 | ✅ 渲染取 `fitWidthPx`，显示取更小的 `singlePageDrawWidthPx` |

### 本轮验证结果

| 项 | 结果 |
| --- | --- |
| `compileDebugKotlin` | ✅ 成功，无新增警告 |
| `testDebugUnitTest --rerun-tasks` | ✅ **226 项，0 失败**（v1.7.3 为 221） |
| `lintDebug` | ✅ **0 error / 11 warning**（持平） |
| `assembleRelease` | ✅ 22,346,473 字节 |
| 包信息 | `versionCode=174` / `versionName=1.7.4` |
| 签名 | SHA-256 `1590d022…`，与 1.1.0 以来一致，可覆盖升级 |

---

## 八、v1.7.3 已完成的工作

用户反馈：「先把打开 PDF 时自动适配对应大小给做好，然后再继续开发 V1.8.0」。

v1.7.2 只修了**横向**（页面左右不再各空一大块），纵向完全没人管。本轮补齐纵向，并把策略抽成可单测的纯函数。

### 1. 单页模式：一屏看全，不被玻璃条压住 ✅

**问题**：旧实现 `minOf(整屏宽, 整屏高 / aspect)` 把「整屏」当可用区，但顶部工具条、底部页码胶囊都压在这块区域上。360dp 屏上，一条工具条（约 60dp）+ 底部胶囊（约 100dp）吃掉 160dp 可见高度，页面头尾正好被盖住 —— 这就是「打开只剩中间一小条」的观感来源。

**改法**：先扣掉玻璃条占位，再算页面尺寸。

```kotlin
val usableHeightPx = PageFit.contentHeightPx(heightPx, density, chromeVisible)
val targetWidthPx = PageFit.fitWidthPx(contentWidthPx, usableHeightPx, aspect)
```

关键点是**渲染宽度也用这个值**，不是内容区宽度。否则位图按满宽渲染、显示时再缩到 fitW，等于多渲一倍像素，翻开第一页还要多等一倍时间。

隐藏工具栏时 `usableHeightPx` 自动变大，页面随之放大 —— 这是 iOS / 微信读书的手感，不是额外开关。

### 2. 连续模式：首屏一屏一页 ✅

**问题**：连续模式按宽度铺满，A4 在竖屏上必然高过一屏，首屏只能看到「本页上半截 + 下一页开头一道缝」。

**改法**：只在首屏收紧宽度，`PageFit.firstScreenWidthPx()` 一次算干净：

- 满宽装得下 → 原样返回，不动
- 满宽装不下、缩完还读得出 → 缩到「一屏恰好一页」
- 缩完窄到读不出（50:1 的 CAD 图）→ 不缩，保持满宽交给纵向滚动

这里踩了一个坑值得记：**最初写成「一直检查 `atTop`」**，结果收缩后页面不再溢出、滚动条永远停在顶端，`atTop` 恒为 true，页面再也回不到铺满。改成 `firstFitLatched` 一次性锁定的状态才是对的。

另外续读时跳过首屏适配 —— 用户看到的不是封面，缩成「一屏一页」没有意义。

### 3. 连续模式页高多算一个 padding ✅

**问题**：`PageItem` 里 `pageHeightDp = areaWidthPx * aspect`，但外层明明有 `padding(horizontal = horizontalPadding)`。算出来的占位高比实际位图高 `2 × padding × aspect`，A4 在小屏上就是多出约 17px 的空白页脚，每页底部都空一截。

**改法**：用**实际显示宽度**推页高：

```kotlin
val visibleWidthPx = areaWidthPx - horizontalPadding.toPx() * 2f
val pageHeightDp = (visibleWidthPx * aspect / density).dp
```

### 4. 策略抽成纯函数 `PageFit` ✅

新增 `ui/page/reader/PageFit.kt`（约 150 行），把留白档位、玻璃条占位、一屏宽度计算全部收进来。好处有三：

1. 连续模式和单页模式共用同一份数字，不再各写一份
2. 可以直接单测 —— 覆盖 360×640dp、360×800dp、分屏、平板、50:1 长页等屏型
3. 改 UI 高度时只需同步 `TOP_CHROME_DP` / `BOTTOM_CHROME_DP` 两个常量

新增 `PageFitTest`（14 项），把「各种屏型下页面该多大」写成断言。这是「改对了」和「只是没报错」的区别。

### 本轮验证结果

| 项 | 结果 |
| --- | --- |
| `compileDebugKotlin` | ✅ 成功，无新增警告 |
| `testDebugUnitTest` | ✅ **221 项，0 失败**（v1.7.2 为 206 项，本轮 `PageFitTest` 新增 15 项） |
| `lintDebug` | ✅ **0 error / 11 warning**（与 v1.7.2 持平） |
| `assembleRelease` | ✅ 22,346,469 字节 |
| 包信息 | `versionCode=173` / `versionName=1.7.3` |
| 签名 | SHA-256 `1590d022…`，与 1.1.0 以来一致，可覆盖升级 |

---

## 九、v1.7.2 已完成的工作

三张真机截图驱动的修正版本。用户原话：

> 「打开这么小我怎么看？请你设置成检测用户手机并自动适配对应大小好吗」
> 「这个底栏哪里像IOS26的底栏了 我要的是IOS一模一样的」

### 1. 底栏重做为 iOS 26 形态 ✅

**结论先行：整条 `GlassTabBar.kt` 重写，从「猜的玻璃药丸」改为对照 Kyant0 官方 `LiquidBottomTabs` 的结构化实现。**

#### 1.1 旧版错在哪

| 项 | 旧版（v1.4.0 ~ v1.7.1） | iOS 26 实际 | 后果 |
| --- | --- | --- | --- |
| 选中指示器 | 常驻一片玻璃药丸，跟随选中项滑动 | 只在**按压时**从图标下方压出一滴玻璃，松手消散 | 看起来像 Android 旧式 TabLayout |
| 文字标签 | 五项全挂中文标签 | 纯图标，**只有当前项**显示文字 | 窄屏上文字压到相邻项 |
| 横向拖拽 | 支持拖拽切换 | 不支持（tab bar 不是滑块） | 误以为能连续切换，与系统边缘手势打架 |
| 高度结构 | 单层 62dp | 64dp 外筐 + 4dp 内边距 + 56dp 内层 | 缺一层，玻璃显得平板 |

#### 1.2 新版结构

```
┌─────────────────────────────────────────┐  64dp 外筐
│ ╭─────────────────────────────────────╮ │  4dp 内边距
│ │  ⌂      ▦      ◉      ♪             │ │  56dp 内层
│ │ 首页   文档   书签   发现            │ │  仅选中项有文字
│ ╰─────────────────────────────────────╯ │
└─────────────────────────────────────────┘
     ↑ 按压时在图标下方压出一滴玻璃（lens 16dp→收缩，色散开启）
```

关键实现点：

- **外筐**：`drawBackdrop` + `vibrancy()` + `blur(12dp)` + `lens(24dp, 24dp, depthEffect, chromaticAberration)`
- **内层**：单独一圈 1dp 内描边（浅色 30% 白 / 深色 5% 白），对应 iOS「两片玻璃叠着」的层次
- **玻璃滴**：`rememberGraphicsLayer()` 把底栏自身内容录一层，与外层页面 backdrop 用
  `rememberCombinedBackdrop(pageBackdrop, tabBarBackdrop)` 合成。**只用一个 backdrop 时，
  玻璃滴里什么都看不见** —— 它需要折射的是图标和文字，不是底下的页面。
- **按压曲线**：`Animatable` 驱动 `progress` 0→1，`lens` 半径从 0.35 倍长到 1 倍，
  色散与高光同步增强，`tween(140ms)` 按下 / `tween(280ms)` 松开
- **无障碍**：纯图标形态下文字不再是可见信息，改为挂 `contentDescription`，
  否则读屏只能念「按钮」

### 2. 玻璃底栏不再「透明到看不清」✅

截图里设置页的「默认保存方式 / 覆盖…」直接压在底栏图标上。三层原因，逐一修：

| 层次 | 旧值 | 新值 | 理由 |
| --- | --- | --- | --- |
| 底栏材料底色 | `glassBase` 12% 白 + `glassFrost` 20% 白 | `onDrawSurface` 25% 白 / 55% 黑 | 深色底上 32% 白几乎等于透明；55% 黑才能压住白底 PDF |
| 宿主底部渐变 | `pageBg` 55% 不透明，从 78% 开始 | 三段式 0% → 72% → 100%，从 62% 开始 | 55% 的上限不够，文字仍会透上来 |
| 内容避让 | 各页 `bottom = 120dp` | 保持一致（116~150dp） | 底栏 64dp + 12dp 悬浮间距 |

### 3. PDF 页面自适应屏幕宽度 ✅

**结论先行：页宽从「固定扣 32dp」改为按实测可用宽度分档扣 6dp / 10dp，渲染宽度与显示宽度严格一致。**

旧链路有两处固定值：

```kotlin
val areaWidthPx = (widthPx - bounds.padH * 2).toInt()   // bounds.padH = 16.dp
Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
```

在 400dp 宽的手机上，页面只剩 368dp；小屏（360dp）上更糟，相对占比达 8.9%。
叠加 `PageItem` 外层再扣一次，实际可达 32dp。

新版：

```kotlin
// 可用宽 < 360dp（小屏 / 分屏）→ 6dp，其余 → 10dp
val hPad = if (contentWidthDp < 360.dp) 6.dp else 10.dp
val pageWidthPx = (contentWidthPx - hPadPx * 2).toInt()
// 传下去的是「扣完留白的宽度」，PageItem 内部不再二次相减
PageItem(widthPx = pageWidthPx, horizontalPadding = hPad, ...)
```

`contentWidthDp` 由 `contentWidthPx ÷ density` 得到，**不用** `Configuration.screenWidthDp`
—— 后者在分屏 / 折叠屏展开态下与实测宽度对不上（v1.5.1 踩过这个坑）。

### 4. 「我的」Tab 改为入口页 ✅

v1.6.0 把完整设置页塞进了 Tab 宿主，信息架构是错的：iOS 的「我的」是一屏**入口**，
点进去才是设置。现在拆成：

```
Tab 宿主 ─「我的」→ ProfileHome（当前翻页方式 / 主题 / 保存方式 + 「设置」入口）
                          ↓
                    Routes.SETTINGS → SettingsPage（完整设置，全屏无底栏）
```

`SettingsPage` 变成双模式：`onBack != null` 是独立页，`onBack == null && onOpenSettings != null`
是内嵌主屏；两者都没有时退化为旧行为，保证既有调用方不改代码也不会白屏。

### 5. 测试同步 ✅

`DockIntegrationTest` 的两条断言原本靠「五项都有文字节点」定位底栏。
底栏改为纯图标后，未选中项没有文字节点，测试必然失败 —— 这是**正确的不通过**，
说明测试真的在测渲染结果。改为按 `contentDescription` 定位：

```kotlin
private fun tabCenterY(label: String): Float? {
    val byDesc = compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes()
    val node = byDesc.maxByOrNull { it.positionInRoot.y }
        ?: compose.onAllNodesWithText(label).fetchSemanticsNodes().maxByOrNull { it.positionInRoot.y }
        ?: return null
    ...
}
```

### 本轮验证结果

| 项 | 结果 |
| --- | --- |
| `compileDebugKotlin` | ✅ 成功，无新增警告 |
| `testDebugUnitTest` | ✅ **206 项，0 失败** |
| `lintDebug` | ✅ **0 error / 11 warning**（与 v1.7.1 持平） |
| `assembleRelease` | ✅ 22,346,473 字节 |
| 包信息 | `versionCode=172` / `versionName=1.7.2` |
| 签名 | SHA-256 `1590d022…`，与 1.1.0 以来一致，可覆盖升级 |

---

## 十、v1.7.1 已完成的工作

收尾性质，只做"减重 + 把静态检查真正跑起来"两件事，但过程中挖出的问题比预期多。

### 1. 移除未使用的 PDFBox ✅

**结论先行：APK 从 29,589,108 字节降到 22,330,098 字节，减少 7.26 MB（23.5%）。**

前面几轮一直以为"删 PDFBox 能省 4.4 MB"，实际减了 7.26 MB。多出来的部分是排查时才发现的：

| 项 | v1.7.0 | v1.7.1 | 差值 |
| --- | --- | --- | --- |
| APK 总大小 | 29,589,108 | 22,330,098 | −7,259,010 |
| `classes.dex` | 4.43 MB | 2.97 MB | −1.46 MB |
| APK 内 `org/` 目录 | 7.9 MB | **0** | −7.9 MB |

**PDFBox 不是自己变大的，它拖进来了 BouncyCastle。** APK 内 `org/bouncycastle/` 下有 4 个大块资源文件：

```
3,640,348  org/bouncycastle/pqc/crypto/picnic/lowmc.properties
1,939,925  org/bouncycastle/pqc/crypto/sike/p751.properties
1,154,869  org/bouncycastle/pqc/crypto/sike/p610.properties
  765,399  org/bouncycastle/pqc/crypto/sike/p503.properties
  663,990  org/bouncycastle/pqc/crypto/sike/p434.properties
```

这些是抗量子密码（SIKE / PICNIC）的参数表，PDFBox 用它做 PDF 签名验签。我们不做 PDF 签名，**一个字节都没用上**。

清理动作：

- `gradle/libs.versions.toml` 删掉 `pdfbox` 版本与库条目
- `app/build.gradle.kts` 删掉 `implementation(libs.pdfbox.android)`，并留注释说明"要改写 PDF 对象时再引，优先评估 pdfium 是否够用"
- 验证：`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 里 `pdfbox` / `bouncycastle` 均为 0 条

### 2. Lint 恢复进 CI ✅

原来的配置是：

```kotlin
checkReleaseBuilds = false
abortOnError = false   // ← 跑完也不管结果，等于没查
```

而且实际上**根本跑不完** —— 这不是"忘了开"，是当初一开就崩，只好关掉。

#### 真因：Compose lint 与 Kotlin 2.1 的分析 API 不兼容

崩溃信息是 `IncompatibleClassChangeError: Found class org.jetbrains.kotlin.analysis.api.resolution.KaSimpleVariableAccessCall, but interface was expected`。

Compose BOM 2025.04.01 的 lint 包是按 Kotlin 1.x 编译的，那时 `KaFunctionCall` / `KaSimpleVariableAccessCall` 是**接口**；到 Kotlin 2.1 这些类型变成了**类**，检测器加载时 JVM 直接拒绝。

关掉一条会崩，换下一条接着崩（`RememberInComposition` → `FrequentlyChangingValue` → `AutoboxingStateCreation`）。与其一条条试，不如反编译 `lint.jar` 一次查清：

```bash
javap -p -c <每个 detector 的 .class> | grep -E "org/jetbrains/kotlin/analysis/api"
```

查到所有依赖 `KaCallInfoKt` / `KaSessionProvider` 的实现共 5 个：

| 检测器 | 依赖的类 | 作用 |
| --- | --- | --- |
| `AutoboxingStateCreation` | `KaCallInfoKt` | 提醒用 `mutableIntStateOf` 代替 `mutableStateOf(0)` |
| `RememberInComposition` | `KaCallInfoKt` | 禁止在 Composable 函数体里调 `remember` |
| `FrequentlyChangingValue` | 复用上一条的辅助函数 | 高频变化值不应被 remember 读取 |
| `ComposableStateFlowValue` | `KaSessionProvider` | 禁止在组合中直接读 `StateFlow.value` |
| `MutableCollectionMutableState` | `KaSessionProvider` | 禁止 `mutableStateOf(mutableListOf())` |

全部 disable。这五个都是**代码风格**类检查（最多漏掉"误用可变集合当 State"这种），不涉及行为正确性，用代码评审兜底可接受。

顺带踩到一个坑：`ComposableStateFlowValue` **不是** issue id，写了 `disable` 会报 `UnknownIssueId` 且不生效 —— 真实 id 是 `StateFlowValueCalledInComposition`。`FrequentlyChangingValue` 会崩是因为它内部复用了 `RememberInCompositionDetectorKt` 的同一个辅助函数，只关前者没用。

#### 现状

```kotlin
lint {
    disable += "NullSafeMutableLiveData"           // AGP 8.7.3 在 JDK 21 上崩
    disable += "AutoboxingStateCreation"           // 以下 5 条均为 Compose lint × Kotlin 2.1
    disable += "RememberInComposition"
    disable += "FrequentlyChangingValue"
    disable += "StateFlowValueCalledInComposition"
    disable += "MutableCollectionMutableState"
    disable += "HardcodedText"                     // 未做国际化，v2.0.0 时移除

    checkReleaseBuilds = false   // 日常 assemble 不跑，避免拖慢装机
    abortOnError = true          // ← 关键：有 error 就红
}
```

CI 新增独立 `lint` job（`--no-parallel`，防 OOM；本地就遇到过 daemon 被 kill），`build` job 改为 `needs: [ test, lint ]`。

**效果：2 error / 26 warning → 0 error / 11 warning。**

### 3. 清理过程中修掉的 3 个真实缺陷 ✅

Lint 一跑起来就暴露了三个不是"风格问题"的缺陷：

#### ① `Formatters.kt` 的静态 `SimpleDateFormat` —— 并发错乱 + 切语言失效

```kotlin
// 原来
private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
```

两个问题叠在一起：

- **线程不安全**：`SimpleDateFormat` 内部有可变 `Calendar`，并发 `format()` 会互相污染，产出错乱日期甚至抛 `ArrayIndexOutOfBoundsException`。首页/列表/书签页都可能在不同线程读它。
- **Locale 冻结**：`Locale` 在 `object` 初始化那一刻就定死了，用户运行中切换语言后日期格式不跟着变。

改为每次新建：

```kotlin
private fun fmt(pattern: String): SimpleDateFormat =
    SimpleDateFormat(pattern, Locale.getDefault())
```

开销约几微秒，相对正确性可以忽略。

#### ② 阅读页常亮用 `LocalContext.current as? Activity` —— 开关点了可能没反应

```kotlin
// 原来
val activity = LocalContext.current as? android.app.Activity
```

Compose 会往 `LocalContext` 里塞 `ContextWrapper`（主题包装、`ContextThemeWrapper` 等），强转成 `Activity` 拿到 `null`，`activity?.window` 全部静默跳过 —— 表现就是"常亮开关点了，界面没变化，也没报错"。

改用 activity-compose 提供的正规通路：

```kotlin
val activity = androidx.activity.compose.LocalActivity.current
```

#### ③ 缺 `monochrome` 图标 —— Android 13+ 主题图标下显示成彩色方块

Android 13 起桌面支持"主题图标"，会取应用的 `monochrome` 层做单色渲染。没有这一层时，开了主题图标的桌面上本应用会显示成一个彩色方块，和周围一圈已适配应用摆在一起很突兀。

新增 `drawable/ic_launcher_monochrome.xml`：几何形状与原前景完全对齐（同一套 `pathData`），但做成"实心文档 + 镂空文字横线"——系统只取 alpha 通道再整体刷色，如果直接用彩色原图，三块颜色的 alpha 都是不透明的，刷成单色后文字线会和底糊成一片，认不出是什么图标。

验证：

```bash
aapt2 dump xmltree app-release.apk --file res/BW.xml
# E: monochrome (line=7)
#    A: android:drawable=@0x7f040008   ← ic_launcher_monochrome
```

### 4. 其余 Lint 项的处理 ✅

| 问题 | 判断 | 处理 |
| --- | --- | --- |
| `ContextCastToActivity` | 真实缺陷 | 改 `LocalActivity` |
| `PermissionImpliesUnsupportedChromeOsHardware` | 真问题（会被应用商店误判需要相机） | 加 `tools:ignore`，保留 `tools:node="remove"` 的兜底作用 |
| `ConstantLocale` ×3 | 真实缺陷 | 去掉静态实例 |
| `Recycle` ×3 | **Lint 误报** | `@SuppressLint` + 注释说明 |
| `ObsoleteSdkInt` ×2 | 防御性代码，非缺陷 | `@Suppress` + 说明"将来降 minSdk 不用改" |
| `MonochromeLauncherIcon` ×2 | 真实缺失 | 新增单色图标 |
| `DataExtractionRules` | 真实缺失 | 新增空规则，把 `allowBackup=false` 的意图表达完整 |
| `UseOfNonLambdaOffsetOverload` | 性能建议 | 改 lambda 重载，把 state 读取延迟到 layout 阶段 |
| `UseTomlInstead` | 规范 | `documentfile` 移进版本目录 |
| `GradleDependency` ×9 | 只是有过期版本 | 不动。lifecycle 2.8.7→2.9.4 属跨版本升级，留到 v1.8.0 与 Compose 一起动 |
| `ObsoleteLintCustomCheck` | 第三方 lint 包自身过时 | 无法修，等依赖升级 |
| `mipmap-anydpi-v26` 目录冗余 | 构建期整理建议 | 不动。合并会偏离 Android 默认目录约定，收益极低 |

关于 `Recycle` 的三处误报，说明一下为什么判定是误报而不是真漏 close：4 处 `contentResolver.query(...)` **全部**紧跟 `?.use { }`，`Closeable.use` 保证异常路径也会关闭。Lint 对"可空接收者 + use"这种写法识别不出来。**没有把代码改写成不如原来清晰的 try/finally**，而是统一加类级 `@SuppressLint("Recycle")` 并写明原因。

### 5. 验证 ✅

| 项 | 结果 |
| --- | --- |
| 编译（`--rerun-tasks` 全量重编） | ✅ BUILD SUCCESSFUL |
| 单元测试 | ✅ **206 项，0 失败，0 错误** |
| Lint | ✅ **0 error / 11 warning**，`abortOnError=true` 下仍然通过 |
| APK 版本 | ✅ `versionCode=171, versionName=1.7.1` |
| APK 签名 | ✅ SHA-256 `1590d022df4b05e6b76f268fa096b4ae8a2fc18e5f22eca18eb692aa7f1e2851`，与 1.6.1 / 1.7.0 **一致**，可直接覆盖升级 |
| 包内资源 | ✅ `monochrome` / `data_extraction_rules` / `ic_launcher_monochrome` 均已编译进 `resources.arsc` |
| 包内残留 | ✅ 无 `tom_roush`、无 `org/bouncycastle` |
| Manifest | ✅ `allowBackup=false`、`dataExtractionRules` 生效、**`CAMERA` 权限未出现在最终包**（说明 `tools:ignore` 没有破坏 `tools:node="remove"`） |

---

## 十一、v1.7.0 已完成的工作

| 功能 | 实现要点 |
| --- | --- |
| 文本选择与复制 | `TextSelection.kt` 拖选语义：起点行取到行尾、终点行从行首开始、中间行整行。用索引区间会得到错误结果 |
| 缩放锚点 | `c = (p - t) / s` 推导出 `t' = p - (p - t)·s'/s`，保证缩放前后手指下方的点不动 |
| 夜间反色 | 真反色而非叠滤色片。滤色片把整页亮度压低，白底变深灰、黑字变更黑，对比度反而更低 |
| 缩略图预生成 | `ThumbnailPrewarmer` 串行队列 + 去重，`PREWARM_AHEAD = 12` |

---

## 十二、网盘同步状态

**v1.5.2 起的阻塞已解除**（原为 COS 403 `SignatureDoesNotMatch`，平台临时凭证失效，服务端侧问题）。

| 版本 | GitHub Release | 网盘 |
| --- | --- | --- |
| v1.2.0 ~ v1.5.0 | ✅ | ✅ |
| v1.5.1 | ✅ | ⬜ 本地无独立文件（与 1.5.2 代码相同，用 1.5.2 即可） |
| v1.5.2 | ✅ | ✅ 2026-10-22 补传 |
| v1.6.0 | ✅ | ✅ 2026-10-22 补传 |
| v1.6.1 | ✅ | ✅ 2026-10-22 补传 |
| v1.7.0 | ✅ | ✅ 2026-10-22 补传 |
| v1.7.1 | ✅ 签名包 `app-release.apk` | ✅ |
| v1.7.2 | ✅ 签名包 `app-release.apk` | ✅ |
| v1.7.3 | ✅ 签名包 `app-release.apk` | ✅ `file_id = eQwUNShlnGmq` |
| v1.7.4 | ✅ 签名包 `app-release.apk` | ✅ `file_id = eSJoxJzuZYKU` |
| **v1.7.5** | ✅ 签名包 `app-release.apk`（CI 39885087929 三 job 全绿） | ✅ `file_id = efALcZtAinNj` |

> 网盘 `LPdf` 目录现含 1.2.0 ~ 1.7.5 共 17 个 APK。

> 发版时发现 GitHub 仓库**从未配置过签名 secrets**，CI 一直产出的是未签名的
> `app-release-unsigned.apk` —— 用户下载后无法安装。本次已补配
> `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 四个 secret，
> 重跑 CI 后拿到签名包，并删掉了 Release 上的未签名资产。
> **此前的 v1.6.0 ~ v1.7.0 的 CI 产物同样是未签名的**，那些版本请以网盘 / 本地 `releases/`
> 目录里的 APK 为准。

---

## 十三、后续版本计划

### ✅ v1.7.5 · 代码审计修复（已发布 2026-10-23）

工具箱分段落盘（OOM）、HTTPS 重定向逐跳校验、文件名清洗、路径前缀、
pdfium 1.0.32 → 1.0.35（`.so` 与 2.0.3 字节一致）。不新增用户可见功能，
但把四条最重的写出管线的内存模型换掉了。Release 资产与网盘均为升级后新包。

### ✅ v1.8.0 · 元数据编辑（已发布 2026-10-23）

**决策：引 PDFBox**（2026-10-23 用户确认）。依赖 +2.70 MB，`PDFBoxResourceLoader.init` 在 `LpdfApplication.onCreate`。

- 工具箱新增「元数据」分组：读取 / 编辑标题、作者、主题、关键词；producer 与创建时间只读展示
- 写入只改 `/Info` 字典 + 重建 xref，不碰页面内容流；产物**不再经过 pdfium 保存通路**（A.7）
- 输出到 exports 新文件，原文件只读；失败清半成品
- 详见第二章；发布记录见第九章与 Release v1.8.0

### v1.9.0 · PDF 工具箱第一批（PDFBox 解锁）

按 [A.6](#a6-优先级汇总) 的 P1 项落地，一版做 5~6 个，不贪多：

- 页面旋转 / 重排 / 裁剪（对象级，非栅格）
- N-up / 小册子拼版
- 加页码
- 移除图片（真正删对象，不是重压图）
- 元数据体检报告
- 压平改造：`PdfFlattener` 从「栅格重画」改为「矢量叠加」，输出保留可选文字

同一版顺带做**分屏与横屏适配**（原 v2.0.0 提前项，与工具箱改的文件不重叠）：

- 拆掉散落 8 处固定 dp 底部留白（阅读页 6 处、列表页 2 处，如 `+ 150.dp`、`+ 96.dp`），改走 `PageFit`
- 分屏下半屏（高度仅 300~400dp）浮动工具条不得压住正文
- 横屏 / 折叠屏展开：双栏（左 TOC + 右正文，正文限宽 720dp）
- 拖拽改分屏比例时不重建页面、不丢阅读位置

### v1.10.0 · PDF 工具箱第二批

- 加密 / 解密 / 权限
- 文本脱敏（不可恢复）
- 清理 Sanitize（去 JS / 链接 / 附件）
- 附件增删提取
- 文本提取 / 表格转 CSV
- 批量处理队列

### v1.11.0 · 安全与签名（重）

- 本地证书签名 + 移除签名 + 离线验签（BouncyCastle）
- 注意：这一步会把 APK 再拉大 3~4 MB，且验签的语义边界要说清（见 [A.9](#a9-对清单本身的-3-处质疑) 第 1 条）

### v2.0.0 · 多语言

**已确定要排在功能之后**：功能还在每周新增，先做 i18n 等于反复返工。
文案搬迁和工具箱并行推进即可（两者改的文件不重叠）。

当前 **432 处**硬编码中文（v1.7.2 时统计），`strings.xml` 只有 1 条。
拆开算：

| 项 | 数量 |
| --- | --- |
| 单条中文字面量 | 432 处 |
| 其中带占位符拼接 | 29 处 |
| 空状态 / 错误提示散落 | 24 处 |
| 测试断言耦合中文 | 7 处（2 个文件） |

执行顺序：先建机制（`values-en` + 重开 `HardcodedText` Lint，只对新代码生效）→
按页搬迁（Home → List → Reader → Settings → Tools → 错误文案）→ 29 处占位符统一走 `getString` 带参。
**先不做繁体**，英文做扎实后用脚本批量转再人工校。

### 更后面（暂不排期）

1. **heap 分级渲染预算** —— `ReaderViewModel` 已按 heap 的 1/4 做缓存上限（32–192MB），
   下一步是把 `TileLimits` 常量也按设备内存分级；但 `PageTiling` 是纯函数、被大量单测覆盖，改动要极小心
2. **扫描件图像增强** —— 对比度 / 去底色，需在 GPU/CPU 上做后处理，性能风险大
3. **OCR** —— tesseract4android + 离线语言包，体积 +几 MB，排到扫描件增强之后
4. **相机扫描出 PDF** —— 需要 CAMERA 权限（当前在 Manifest 里明确 remove），是产品定位层面的决定，不是纯技术问题

原计划的"缩略图预生成"已在 v1.7.0 完成。

---

## 附录 A · 功能矩阵：LPdf 现状 × Stirling 清单 × 优先级

> 基准：Stirling-PDF「纯本地功能清单（Android 离线版）」58 ✅ + 6 ⚠️ + 18 ❌ = 82 项。
> LPdf 现状核对日期 2026-10-23，代码基线 v1.7.4。
> 优先级定义：**P0** 决定能否解锁一整批功能（先决条件）/ **P1** 高频、低成本、收益明确 /
> **P2** 中频、或需要额外权衡 / **P3** 低频、成本高、或与产品定位有冲突。

### A.1 现状概览

| 维度 | LPdf 已有 | Stirling 清单 | 说明 |
| --- | --- | --- | --- |
| 阅读能力 | ✅ 10 项 | 0 项 | 清单是「处理工具集」，不含阅读器 |
| 标注能力 | ✅ 有且更强 | 部分（一次性盖章） | 我们有跨会话持久化，清单没有 |
| PDF 写出 | ⚠️ 6 项，**全是栅格** | 58 项（对象级） | 核心差距，见 A.5 |
| 安全 | 0 项 | 10 项 | 完全空白 |
| 提取/转换 | ⚠️ 2 项 | 13 项 | 只有图片导出与图片转 PDF |
| 自动化 | 0 项 | 10 项 | 完全空白 |

### A.2 矩阵：清单 58 项逐条对照

「栅格」表示现在靠 `PdfRenderer` 重画实现，输出**不保留可选文字**、体积偏大。

| # | Stirling 功能 | LPdf 现状 | 现有实现 | PDFBox 后可升级为 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 1 | 合并 PDF | ✅ 有 | `PdfWriterEngine.merge`，栅格 | 对象级合并，保留文字/书签/表单 | P1（改造） |
| 2 | 拆分（按页码） | ✅ 有 | `PdfWriterEngine.extract`，栅格 | 对象级切分 | P1（改造） |
| 3 | 按书签拆分 | ❌ | — | `PDDocumentOutline` 取章节边界 | P2 |
| 4 | 按区域拆分 | ❌ | — | 裁剪框切分 | P3 |
| 5 | 按大小拆分 | ❌ | — | 按字节预算切 | P3 |
| 6 | 旋转页面 | ❌ | — | `PDPage.setRotation` | **P1** |
| 7 | 页面重排 / 组织 | ❌ | — | 页面树重排 + 拖拽 UI | **P1** |
| 8 | 裁剪页面 | ❌ | — | `setCropBox` | **P1** |
| 9 | 页面缩放 | ❌ | — | 改 MediaBox 尺寸 | P2 |
| 10 | N-up 排版 | ❌ | — | `ContentStream` 多次 `drawForm` | **P1** |
| 11 | 小册子拼版 | ❌ | — | N-up 的排列变体 | P2（复用 10） |
| 12 | 海报分页 | ❌ | — | 单页切块放大 | P3 |
| 13 | 合成单长页 | ❌ | — | 拼接成超长 MediaBox | P3 |
| 14 | 叠加合并 | ❌ | — | `PDFMergerUtility.overlayDocuments` | P2 |
| 15 | 编辑目录 / 书签 | ⚠️ 只读 | `session.toc()` 读 | `PDDocumentOutline` 增删改 | P2 |
| 16 | 编辑文本内容 | ❌ | — | **PDFBox 也做不好** | P3 · 不做 |
| 17 | 字符级文本编辑 | ❌ | — | 同上，改一字要重排整段 | P3 · 不做 |
| 18 | 添加页码 | ❌ | — | `ContentStream` 写文本 | **P1** |
| 19 | 添加批注 | ✅ 有且更强 | `AnnotationStore` 持久化 + 导出时栅格压平 | 导出改**矢量叠加** | **P1**（改造） |
| 20 | 添加图章 | ⚠️ 部分 | 签名图盖上去，栅格 | 对象级盖章 | P2 |
| 21 | 文字/图片水印 | ✅ 有 | `PdfWriterEngine.watermark`，栅格 | 矢量文字水印 | **P1**（改造） |
| 22 | 叠加图片到页面 | ⚠️ 部分 | 签名/水印的变体 | 同 21 | P2 |
| 23 | 表单填充 | ❌ | — | `PDAcroForm` | P2 |
| 24 | 解锁表单限制 | ❌ | — | 去 `/Perms` | P2 |
| 25 | 压平（表单/批注） | ⚠️ 有但栅格 | `PdfFlattener` 重画 | 矢量压平，保留文字 | **P1**（改造） |
| 26 | 添加密码加密 | ❌ | — | `StandardProtectionPolicy` | P2 |
| 27 | 移除密码 | ❌ | — | `setAllSecurityToBeRemoved` | P2 |
| 28 | 设置权限 | ❌ | — | `AccessPermission` | P2 |
| 29 | 证书签名 | ❌ | — | `SignatureInterface` + BouncyCastle | P3（重） |
| 30 | 移除签名 | ❌ | — | 删 `/AcroForm` 签名字段 | P3 |
| 31 | 离线验签 | ❌ | — | 校验内嵌证书链 | P3 · 语义有限（A.9） |
| 32 | 文本脱敏 | ❌ | — | 定位 + 黑框 + **删底层文字对象** | P2（别做成假脱敏） |
| 33 | 清理 Sanitize | ❌ | — | 去 JS / 链接 / 元数据 / 附件 | P2 |
| 34 | 查看元数据 | ⚠️ 引擎能读 | `getDocumentMeta()` **零调用点** | 直接接 UI | **P0 / v1.8.0** |
| 35 | 编辑/清除元数据 | ❌ | — | `PDDocumentInformation` | **P0 / v1.8.0** |
| 36 | 提取文本 | ⚠️ 单页可复制 | `textLayer` → 选中复制 | `PDFTextStripper` 整本导出 | P1 |
| 37 | 提取全部图片 | ❌ | — | 遍历 Image XObject | P2 |
| 38 | 移除图片（减体积） | ❌ | — | 删 Image XObject | **P1**（比压缩更有效） |
| 39 | 表格 → CSV | ❌ | — | 无现成 API，要按坐标聚类 | P3 |
| 40 | PDF → HTML | ❌ | — | 无可靠方案 | P3 · 不做 |
| 41 | 附件添加/提取 | ❌ | — | `PDEmbeddedFilesNameTreeNode` | P2 |
| 42 | Portfolio | ❌ | — | 附件的特殊形态 | P3 |
| 43 | 查看/清除内嵌 JS | ❌ | — | `PDDocumentNameDictionary` | P2（并入 33） |
| 44 | 对象流解压 | ❌ | — | 解析 objstm | P3（普通用户无感） |
| 45 | PDF → 图片 | ✅ 有 | `exportImage` / `exportAllImages` | 无需改 | 已完成 |
| 46 | 图片 → PDF | ✅ 有 | `imagesToPdf` | 无需改 | 已完成 |
| 47 | 扫描件提图 | ❌ | — | 等价于 37 | P3 |
| 48 | 元信息体检报告 | ❌ | — | 页数/字体/加密/表单汇总 | **P1**（工作量小，观感好） |
| 49 | 自动旋转 | ❌ | — | 按文本基线角度判断 | P2 |
| 50 | 自动重命名 | ❌ | — | 读首页文本生成名 | P3 |
| 51 | 删除空白页 | ❌ | — | 文本 + 图像双判空 | P2 |
| 52 | 二维码拆分 | ❌ | — | 需引 ZXing（+0.5 MB） | P3 |
| 53 | 相机扫描 PDF | ❌ | — | 需 CAMERA 权限 | P3 · **产品定位冲突** |
| 54 | Pipeline 过滤器 | ❌ | — | 需要一套规则引擎 | P3 |
| 55 | 批量处理队列 | ⚠️ 单文件 | `ToolsViewModel.run` 单文件 | 改队列即可 | P2 |
| 56 | 文件对比信息 | ❌ | — | 只能比元信息 | P3 |
| 57 | 书签/大纲浏览定位 | ✅ 有 | 目录抽屉 + 跨文档书签页 | 无需改 | 已完成 |
| 58 | 密码/文档体检提示 | ❌ | — | 并入 48 | P2 |

### A.3 清单 ⚠️ 6 项（本地适配后可用）

| # | 功能 | LPdf 现状 | 判断 | 优先级 |
| --- | --- | --- | --- | --- |
| 59 | 压缩（轻量版） | ✅ **有，且更有效** | 我们用 PDFium 高倍渲染 + 重编码；清单是 PDFBox 流压缩，对扫描件基本无效 | 已完成 |
| 60 | 扫描件效果 | ❌ | 需要位图滤镜链 | P3 |
| 61 | 反色 / 替换颜色 | ⚠️ 仅夜间反色 | 反色是渲染期做的，没有「导出反色 PDF」 | P2 |
| 62 | OCR | ❌ | tesseract4android + 语言包，体积大 | P3 |
| 63 | 矢量导出 SVG | ❌ | pdfbox-graphics2d 适配层 | P3 |
| 64 | PDF/A 校验 | ❌ | veraPDF 在 ART 上兼容性存疑 | P3 · 风险高 |

### A.4 清单 ❌ 18 项

清单按「0 网络」剔除。**其中一项我们有反例**：

| 项 | 清单判断 | LPdf 实际 |
| --- | --- | --- |
| 网页/链接 → PDF | ❌ 剔除 | ✅ **我们有** `LinkImporter`：从 URL 直接导入 PDF（不是把网页渲染成 PDF，是下载现成 PDF，仍 0 上传） |
| 其余 17 项 | ❌ | 认同。损坏修复（qpdf）技术上纯本地可做，但 Android 上没有可用移植，维持剔除 |

### A.5 核心差距的根因：写通路全是栅格

整张矩阵最重要的一张表。

| | 现有 `PdfWriterEngine` | 引入 PDFBox 后 |
| --- | --- | --- |
| 原理 | `PdfRenderer` 把每页画成 Bitmap → 塞进 `PdfDocument` | 操作 PDF 对象（页面树、内容流、资源字典） |
| 保留可选文字 | ❌ | ✅ |
| 保留矢量 | ❌ | ✅ |
| 体积 | 膨胀（位图） | 基本不变甚至变小 |
| 能改页面属性（旋转/裁剪） | ❌ | ✅ |
| 能做加密/签名 | ❌ | ✅ |
| 能删图片对象 | ❌ | ✅ |
| 能处理加密文档 | ❌ | ⚠️ 需 BouncyCastle |

一句话：**现在所有「写」都是「重画一张图」，PDFBox 才让它们变成真正的 PDF 编辑。**

补一条实测：pdfium 有 `saveAsCopy` + `deletePage`，但**没有页面导入、没有内容流编辑**
（已用 `javap` 核对 `PdfPage` / `PdfDocument` 全部公开方法：只有渲染、取尺寸、取链接、取文本）。
所以 pdfium 替代不了 PDFBox。两者是互补关系——

- **pdfium**：负责渲染（比 PDFBox 的 `PageDrawer` 快且保真）、取文本、取书签
- **PDFBox**：负责一切对象级改写

### A.6 优先级汇总

#### P0 · v1.8.0（先决条件）

| 项 | 说明 |
| --- | --- |
| 引入 PDFBox | `com.tom-roush:pdfbox-android:2.0.27.0`，需 `PDFBoxResourceLoader.init(context)` |
| 元数据读 | 接上零调用的 `getDocumentMeta()` |
| 元数据写 | `PDDocumentInformation` + 增量保存 |
| **写后不能再走 pdfium 保存** | 关键约束，见 A.7 |

#### P1 · 高频低成本

页面旋转 / 重排 / 裁剪、N-up、加页码、移除图片、体检报告、文本提取；
外加**把现有 5 个栅格功能改成矢量**（合并、拆分、水印、压平、盖章）。

这批的共同点：PDFBox API 直接对应，不需要自研算法。做完「工具箱」才真正像样。

#### P2 · 安全与进阶

加密/解密/权限、脱敏、Sanitize、附件、表单填充、按书签拆分、叠加合并、批量队列、自动旋转、删空白页、图片提取。

#### P3 · 慎重

证书签名/验签（重、语义有限）、OCR（体积）、SVG 导出、PDF/A 校验（ART 风险）、
表格转 CSV / PDF→HTML（无可靠算法）、相机扫描（产品定位）、Pipeline（过度设计）。

#### 明确不做

**编辑正文文本 / 字符级编辑**。这不是 PDFBox 行不行的问题，是 PDF 格式本身的设计：
内容流是排版结果而非结构化文本，改一个字要重排整段。Stirling 自己也是基于 PDFBox 做了
大量启发式处理，效果不稳定。做不好比不做更伤——建议直接标为不支持，并给用户一句解释。

### A.7 两条写入通路的关系（必须先说清）

LPdf 碰 PDF 文件的路一共三条：

| 通路 | 用在哪 | 能力 |
| --- | --- | --- |
| pdfium（`PdfiumEngine`） | 阅读渲染、渲染成位图 | 读 + 渲染 + `saveAsCopy`（整份副本）+ `deletePage` |
| `PdfWriterEngine`（PdfRenderer） | 合并/拆分/压缩/水印/图片转 PDF | 栅格重建 |
| PDFBox（待引入） | 对象级改写 | 读写完整对象模型 |

**冲突点**：pdfium 的 `saveAsCopy` 是把内存文档整体写出。若先用 PDFBox 改了文件、再让 pdfium
打开并保存，PDFBox 的改动会被 pdfium 的旧快照覆盖。

**约定**（要写进代码注释，不能只写在文档里）：

1. 涉及对象改写的操作，**最终落盘只用 PDFBox 的 `save()`**，不经过 pdfium
2. pdfium 只出现在「只读」链路：渲染、取尺寸、取文本、取书签
3. 需要「改完再预览」时，改写输出到临时文件，再用新路径开新的 pdfium 会话

### A.8 体积账（已实测，非估算）

v1.8.0 引入 `pdfbox-android:2.0.27.0` 并 `exclude(org.bouncycastle)` 后，release APK 实测：

| 项 | 数值 |
| --- | --- |
| v1.7.4 基线 | 22,346,473 字节（21.31 MB） |
| 引入后 | 25,175,509 字节（24.01 MB） |
| **实际增量** | **+2,829,036 字节 = +2.70 MB** |
| 依赖树核对 | 只有 `com.tom-roush:pdfbox-android`，**无 BouncyCastle** |
| 其中 fontbox 资源 | 93 个文件、3,471,153 字节（3.31 MB 压缩前），主要是 112 个 cmap |

三条结论：

1. **exclude 生效了**。v1.7.1 删 PDFBox 时量到 −7.26 MB，其中约 4 MB 是 BouncyCastle 的抗量子
   密码参数表（SIKE / PICNIC）。这次只增 2.70 MB，说明那 4 MB 确实省掉了。
2. **大头是 cmap，不是代码**。93 个资源里绝大多数是东亚编码映射表，我们不会全用。
   后续可考虑在 `packaging` 里按需裁剪，但**先别动** —— 缺一个 cmap 会让某些 PDF 的中文
   直接读不出字，这是「省 2 MB 换偶发乱码」的买卖，不划算。
3. v1.11.0 做加密/签名时会再加 BouncyCastle（优先只引 `bcprov`，不引 `bcpkix`），
   届时预计 +3~4 MB，总量到 28 MB 上下 —— 仍在可接受范围，但要提前告诉用户。

### A.9 对清单本身的 3 处质疑

1. **「离线验证签名」被列为纯本地可用，但备注承认吊销检查要联网** —— 靠内嵌证书链只能验
   「文件没被改过」，验不出「证书是否已被吊销」。这是安全语义的差别，不是细节，UI 上必须写清楚。
2. **「PDF → PDF/A 校验」** —— veraPDF 在 ART 上兼容性风险不小（大量依赖 `java.nio` 与第三方解析器），
   排期时按「不确定」对待，不要承诺。
3. **「压缩（轻量版）」用 PDFBox 流压缩** —— 对扫描件几乎无效（图已经是 JPEG）。
   LPdf 现在的「高倍渲染 + 重编码」反而更有效。这条**不该照搬清单**。

### A.10 LPdf 有而清单完全没有的（14 项）

这些是我们不该被清单「带跑」的地方：

| # | 功能 | 实现位置 |
| --- | --- | --- |
| 1 | PDF 阅读器（连续 / 单页 / 缩放 / 缩放锚点） | Reader 全链路 |
| 2 | 全书全文搜索（命中高亮、区分大小写、上下跳转） | `PdfiumEngine.search` + `ReaderPanels` |
| 3 | 文本选择与复制 | `TextSelectionLayer`（v1.7.0） |
| 4 | 加密 PDF 打开（密码） | `PasswordRequired` / `WrongPassword` / `PermissionDenied` 三态 UI |
| 5 | 阅读进度记忆（可关） | `prefs.progressMap` |
| 6 | 常亮 / 夜间反色 / 工具栏自动隐藏 | 设置项 |
| 7 | 链接导入 PDF（0 上传） | `LinkImporter` |
| 8 | 应用内更新检查 | `UpdateChecker` |
| 9 | 崩溃留痕 | `CrashReporter` |
| 10 | 系统「用 LPdf 打开」 | Manifest `VIEW` intent |
| 11 | 标注**跨会话持久化**并按文档隔离 | `AnnotationStore`，键 `hash(uri\|mtime)` |
| 12 | 签名库复用（手写签名存下来反复盖） | `SignatureStore` |
| 13 | 标注/书签跨文档汇总 | `marks/BookmarksPage` + 首页「最近在读」 |
| 14 | 文件夹整目录跟踪 | `MediaStoreSource` + `LibraryAggregator` |

清单漏掉的还有一整类**产品层**内容：书架列表/网格/排序筛选、缩略图预生成与预热、
首页分区布局、液态玻璃 UI 体系、无障碍描述，以及最关键的**分块渲染引擎**
（8192 纹理上限 + 4 块预算 + 超长页等比降级）。

最后这条值得单说：清单技术底座写的是 `Android PdfRenderer`，它单页渲染同样受 8192 单边限制，
遇到 CAD 长图会直接失败——这套 tile 方案清单里完全没有。

---

## 附录 B · 历史版本详情

> 以下为 v1.6.0 及更早的详细记录，保留备查。

### 附 1 · v1.6.0 已完成的工作

### 1. 五 Tab 导航 ✅

原来只有 4 个平级路由（LIST / READER / TOOLS / SETTINGS），没有 Tab 结构。本次改为：

```
NavHost
 ├─ root      ← RootTabHost：首页/文档/书签/发现/我的，共用一条玻璃底栏
 ├─ reader    ← 全屏沉浸，无底栏
 └─ tools     ← 全屏沉浸，无底栏
```

阅读页和工具页**留在 NavHost 里而不是做成 Tab 内页**：它们自带顶栏和工具岛，底部要放页码胶囊和编辑岛，再叠一条 Tab 底栏会打架。这样「沉浸页」和「带底栏页」在结构上就是分开的，不用靠 `if (isReader) hideBar` 去猜。

| 新文件 | 作用 |
| --- | --- |
| `ui/nav/RootTab.kt` | 枚举定义五个 Tab。**声明顺序即底栏顺序** —— 玻璃药丸位置是 `index * itemWidth` 算出来的，两者必须是同一份事实 |
| `ui/nav/RootTabHost.kt` | Tab 宿主：玻璃底栏 + 横向滑动转场（`AnimatedContent` + 方向感知） |
| `ui/page/home/HomePage.kt` | 首页：继续阅读横滑卡 + 最近导入 |
| `ui/page/marks/BookmarksPage.kt` | 书签页：跨文档聚合 |
| `ui/page/discover/DiscoverPage.kt` | 发现页：整理/创作/来源/关于四组入口 |
| `data/local/LibraryAggregator.kt` | 跨文档聚合纯函数 |

**首页不放搜索框和排序**：那些是「找文件」的动作，属于文档页。首页只回答「我接着看什么」。

**继续阅读卡是唯一不做玻璃的地方**：一排玻璃卡片互相采样，既贵又闪。改用纯色 + 进度条。

### 2. 「标注画了重开就没」的根因 ✅

这是 v1.5.x 之前就存在、一直没被发现的缺陷。

存储键是 `hash(uri|lastModified)`，标注/书签/阅读进度全按此键存盘。列表页用 `item.lastModified` 算键，阅读页却硬编码传 `0L` —— 同一个文件在两头算出两个不同的键，各存各的文件。用户看到的现象是：画了标注、加了书签、翻了几页，关掉重开全没了，**且没有任何报错**。

修法是把 `lastModified` 从导航参数一路透传到底：

| 环节 | 改动 |
| --- | --- |
| 路由 | `Routes.reader(uri, name, page, modified)` 新增 `&mtime=` |
| 导航图 | `navArgument("mtime") { type = NavType.LongType }` |
| 阅读页 | `vm.bind(uri, displayName, lastModified, null, startPage)` |
| 四个入口 | 首页继续阅读 / 首页最近导入 / 文档列表 / 书签页，全部带 `lastModified` |

### 3. 顺带修的两处 ✅

| 问题 | 修法 |
| --- | --- |
| `bind` 早退让「已在读 A 时点 A 的书签」静默失效 | `startPage >= 0` 时走 `jumpTo(startPage)` 而不是 `return` |
| 「我的」Tab 内嵌时标题贴到状态栏 | `SettingsPage` 两种形态都加 `statusBars` 留白（Tab 宿主只负责底栏，不替内容处理状态栏） |

### 4. 测试 ✅

从 135 → **170 项，全绿**，新增 35 项：

| 文件 | 数量 | 守什么 |
| --- | --- | --- |
| `LibraryAggregatorTest` | 11 | 书签归并与排序、孤儿书签不显示、文件改动后旧书签失效、进度越界收敛到末页 |
| `RootTabTest` | 6 | 底栏顺序固定、路由不重复、`fromRoute` 不串台 |
| `StorageKeyConsistencyTest` | 5 | 键是纯十六进制且不含 `/`（会当文件名用）；mtime 丢失必须算出不同键 |
| `NavigationContractTest` | 4 | **源码级**契约：检查调用链上每一环确实传了 `lastModified` |
| `RegressionGuardTest` | 9 | 本轮审查发现的 7 类缺陷各一条守卫 |
| `DockIntegrationTest` | 2 | 改写为 v1.6.0 结构：五 Tab 齐全、贴底、五个标签水平对齐 |

**反向验证（四项）**：
- 阅读页 `vm.bind` 打回 `0L` → `NavigationContractTest` 立刻红
- `RootTab.HOME` 文案改成「书签」→ `RootTabTest` 立刻红
- `TabDock.items` 加回 `remember{}` → `RegressionGuardTest` 立刻红
- 注释掉 `pendingStartPage = -1` → `RegressionGuardTest` 立刻红

### 5. 测试环境修复 ✅

16 项 Robolectric 测试突然全红，报 `UnsupportedClassVersionError: LayerBackdropKt ... class file version 65.0`。

真因不是代码：依赖 `com.kyant:backdrop` 用 **Java 21** 编译（class 65），而 Gradle 默认 JVM 是 JDK 20（只认到 class 64）。装 JDK 21 并在 `build.gradle.kts` 给测试任务固定 `javaLauncher` 后全部恢复。

> 这类错误伪装得极像「依赖库坏了」，实际只是测试 JVM 版本低了。

### 6. 代码审查：另外揪出 7 处缺陷 ✅

上线前做了一轮系统排查，发现的问题**没有一个会让编译失败、也没有一个会让已有测试变红** —— 它们全是运行时才发作的。

| # | 位置 | 缺陷 | 用户会看到什么 |
| --- | --- | --- | --- |
| 1 | `PdfToolsPage` | 从「发现」页进工具页时传 `uri=""`，直接 `stageCopy(Uri.parse(""))` | 合并/拆分/压缩/水印/签名**五个入口全死**，停在「暂存文件失败」 |
| 2 | `RootTabHost.TabDock` | `items` 被 `remember{}` 住，`onReselect` 闭包永久过期 | 重复点当前 Tab 没反应 |
| 3 | `LPdfApp.HomeTab` | `LaunchedEffect(lifecycle)` 以对象为 key，而对象实例永不变 | 从阅读页返回，首页**进度不刷新** |
| 4 | `BookmarksPage` | `refreshIfNeeded()` 加载过一次就不再加载 | 阅读页新加的书签，切到书签页**看不到**，必须杀进程重开 |
| 5 | `BookmarkStore.allKeys` | `removeSuffix(".json")` 对 `.tmp` 等文件不生效 | 非书签文件被当成 key，查出一堆空条目 |
| 6 | `ReaderViewModel.bind` | `pendingStartPage` 用完不复位 | 同一个 VM 换书时，**跳到那本书的第 N 页** |
| 6b | `ReaderViewModel.bind` | 早退判定只比 uri 不比 mtime | 文件被重新导出后，继续看**上一版内容**（缓存位图是旧的） |
| 7 | `RootTabHost` | `AnimatedContent` 切换会销毁旧页组合，且无 `SaveableStateHolder` | 切回文档页**列表滚回顶部**，搜索词、筛选条件全丢 |

修法要点：

- 缺陷 1：工具页新增「先选一份 PDF」引导页面，接 `ActivityResultContracts.OpenDocument`；`needPick` 为真时不进入 `stageCopy`
- 缺陷 2：`items` 改为每次组合重建（只有 5 个对象，开销可忽略）
- 缺陷 3/4：都改为订阅 `Lifecycle.Event.ON_RESUME` 自增 tick，作为 `LaunchedEffect` 的 key
- 缺陷 6：`pendingStartPage = -1` 复位；`sameDoc` 判定加入 `docKey` 比对
- 缺陷 7：`rememberSaveableStateHolder()` + `SaveableStateProvider(tab.route)`

**反向验证**：把缺陷 2、6 的修复回退后，`RegressionGuardTest` 两项立刻变红；**第一次写的守卫是假绿**（用了 `contains("val items = remember {")`，而注入时带全限定名就绕过了；另一条被注释掉的代码仍能匹配到文本），已改为正则 + 剥离注释行后重新验证通过。

### 7. 构建状态

| 渠道 | 状态 |
| --- | --- |
| Release APK | ✅ 29,556,339 字节，versionCode 160，签名 SHA-256 `1590d022…` |
| GitHub Release v1.6.0 | ✅ [已发布](https://github.com/uahz/LPdf/releases/tag/v1.6.0) |
| 推送 main | ✅ `f3bb3cb` |
| tag v1.6.0 | ✅ 已推送，解引用指向 `f3bb3cb` |
| 本地 `dist/LPdf-1.6.0-release.apk` | ✅ 与 Release 资产字节数一致 |
| 网盘 | ❌ 仍被阻塞（见下） |

---

### 附 2 · v1.5.x 已完成的工作

### 1. 渲染宽度的真凶定位 ✅

用户真机报 `1168×13657`。反推 `13657 ÷ 1.4142 ≈ 9657`，渲染宽度是屏幕物理宽的 **8.3 倍**。

根因代码：

```kotlin
// 旧（错误）
val screenWidthPx = with(density) {
    LocalConfiguration.current.screenWidthDp.dp.toPx().toInt()
}
```

`screenWidthDp` 描述的是**窗口** dp 宽，在自由窗口 / 分屏 / 折叠屏展开 / 厂商改写 `densityDpi` 时会与实际布局宽度脱钩，再乘 density 即被放大。普通手机上恰好正确，所以长期未被发现。

```kotlin
// 新（正确）
var contentWidthPx by remember { mutableIntStateOf(0) }
Box(Modifier.fillMaxSize().onSizeChanged { if (it.width > 0) contentWidthPx = it.width })
```

实测宽度即物理像素，之后不再有任何 dp ↔ px 换算。v1.5.0 的 `sanitizePageSize` 保留，降级为最后一道网。

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| 渲染宽度来源 | `PdfReaderPage.kt` | `screenWidthDp` → `onSizeChanged` 实测 |
| 重渲判断 | `ReaderViewModel.kt` | `ensureRendered` 纳入已渲染宽度（差值 <10% 视为等效） |
| 渲染上限 | `PdfiumEngine.kt` | `MAX_RENDER_WIDTH` 2048 → 4096 |
| 页对象缓存 | `PdfiumEngine.kt` | `CACHED_PAGES` 6 → 12 |
| 单测配置 | `build.gradle.kts` | `unitTests.isReturnDefaultValues = true` |

### 2. 测试 ✅

从 119 → **135 项**，新增 16 项：

| 文件 | 数量 | 守什么 |
| --- | --- | --- |
| `RealPdfGeometryTest` | 12 | 用真实 399 页 A4 PDF 的 `/MediaBox` 跑完整几何链路；`sanitizePageSize` 六条防污染用例 |
| `RenderWidthSourceTest` | 4 | 渲染宽度只能来自实测像素；上限截断、下限抬升、尺寸变化触发更新 |

**反向验证**：将 `MAX_RENDER_WIDTH` 改回 2048，测试立刻 2 项红 —— 确认测试真能守门。

> 注：`RealPdfRenderProbeTest` 在 Robolectric 下无法运行（pdfium 的 `.so` 加载不了，`InitLock.waitForReady()` 永久阻塞），已改为从 PDF 字节读几何的 `RealPdfGeometryTest`。

### 3. 发布状态

| 渠道 | 状态 |
| --- | --- |
| GitHub Release v1.5.1 | ✅ 已发布 |
| GitHub Release v1.5.2 | ✅ 已发布 |
| 代码推送 main | ✅ `93c1631` |
| tag v1.5.1 / v1.5.2 | ✅ 已推送 |
| 本地 `dist/LPdf-1.5.2-release.apk` | ✅ 29523571 字节，签名已校验 |
| **网盘** | ❌ **被阻塞**（见下） |

---

