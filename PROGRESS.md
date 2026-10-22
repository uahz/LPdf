# LPdf 开发进度

> 更新时间：2026-10-22 10:25
> 当前版本：**v1.6.0**（versionCode 160）
> 仓库：https://github.com/uahz/LPdf

---

## 一、当前状态总览

| 指标 | 数值 |
| --- | --- |
| 当前版本 | v1.6.0 |
| versionCode | 160 |
| 最近发布 | [v1.6.0](https://github.com/uahz/LPdf/releases/tag/v1.6.0)（2026-10-22） |
| 单测数量 | **170 项，全绿** |
| 源码规模 | 57 个 Kotlin 文件 / 14275 行 |
| 测试文件 | 27 个 |
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
| **v1.6.0** | **五 Tab 导航改版 + 标注丢失根因修复** | ✅ **已发布** |
| v1.7.0 | 阅读体验增强 | ⬜ 待开始 |
| v2.0.0 | 性能与大文件 | ⬜ 待开始 |

---

## 二、v1.6.0 已完成的工作（本轮）

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

## 三、v1.5.x 已完成的工作

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

## 四、当前阻塞项

### 网盘上传通道失效

| 项 | 详情 |
| --- | --- |
| 现象 | COS 直传返回 HTTP 403 `SignatureDoesNotMatch` |
| 复现 | 连续 4 次（2 个不同版本的 APK、3 组不同临时凭证） |
| 排查 | 1 字节探测文件同样 403 —— 排除「大文件被拦」；带/不带 `x-cos-security-token` 均 403 |
| 结论 | 平台签发的临时凭证本身失效，非参数错误，服务端侧问题 |
| 当前网盘最新 | 仍是 **1.5.0** |
| 本地已备 | `dist/LPdf-1.5.2-release.apk` |

**待通道恢复后补传即可**，无需重新构建。

---

## 五、后续版本计划

### v1.6.1 · 下发补丁（紧接本次）

只做一件事：把 v1.6.0 上线后用户反馈的问题打包修复。若无反馈，直接进 v1.7.0。

已知待观察项：
- 首页「继续阅读」在文档很多（>500）时的首个帧耗时
- 书签页全量扫描在 1000+ 文件下的表现

### v1.7.0 · 阅读体验

- 文本选择与复制
- 双指缩放锚点改为以手指为中心（当前是左上角）
- 连续滚动跨页无缝
- 夜间模式页面反色（不改变标注颜色）
- 缩略图预生成队列

### v2.0.0 · 性能与大文件

- 按设备 heap 分级的渲染预算
- 多窗口 / 分屏适配
- 扫描件图像增强（对比度 / 去底色）

---

## 六、遗留待办（非阻塞）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| P1-4 元数据编辑 | 未开始 | 改标题/作者等 |
| P2-3 Lint 检测器崩溃 | 未开始 | AGP 8.7.3 自带 `NonNullableMutableLiveDataDetector` 在 JDK 21 抛 `IncompatibleClassChangeError`，已临时关闭该条规则 |
| 真机验证玻璃 | 已关闭 | 用户已在真机反馈并迭代多轮 |

---

## 七、已知限制

1. **渲染**：极端长页（如 50:1 的 CAD 图）会整体等比降级，保宽高比但牺牲清晰度 —— 这是 4 块上限的必然结果
2. **玻璃**：无 AGSL 支持的设备退化为纯模糊 + 底色，强度全局归零
3. **Robolectric**：无法加载 pdfium 的 `.so`，真实渲染只能在真机 instrumented test 验证
4. **签名**：`keystore.properties` 与 `keystore/` 不入库，密钥一旦丢失无法覆盖升级旧版本
