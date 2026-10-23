# LPdf 1.7.5 · Android PDF 阅读器

纯本地优先的 PDF 阅读器，界面采用 iOS 26 液态玻璃（Liquid Glass）风格。
Kotlin + Jetpack Compose，minSdk 33（Android 13）。

## 下载安装

**只发布一个包：`LPdf-x.y.z-release.apk`（已签名）。**

- 最新版：[Releases](https://github.com/uahz/LPdf/releases/latest)
- 网盘、本地 `dist/` 目录中也只有 release 包

debug 包**不对外发布** —— 它有 `.debug` 包名后缀、包体大一倍、且未做资源收缩，
放出来只会让用户不知道该下哪个。开发排查请用 CI 的 Actions artifacts。

```bash
adb install -r dist/LPdf-1.7.5-release.apk
```

签名信息：RSA 4096，有效期至 2056 年，SHA-256 `1590d022df4b05e6b76f268fa096b4ae8a2fc18e5f22eca18eb692aa7f1e2851`。
自 1.1.0 至今签名未变，可跨版本覆盖升级。

## 自行构建

```bash
export JAVA_HOME=/opt/jdk21
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleRelease       # 产物 app/build/outputs/apk/release/
./gradlew :app:assembleDebug         # 仅供本地调试，不发布
./gradlew :app:testDebugUnitTest     # 226 个单测
./gradlew :app:lintDebug             # 静态检查，0 error 才算过
```

**签名配置**：`keystore.properties` 与 `keystore/` 均不入库。首次构建若无这两者，
release 仍会产出但处于未签名状态（不会构建失败）。要出签名包，二选一：

- 复制 `keystore.properties.example` 为 `keystore.properties` 并填入口令，密钥库放在 `keystore/lpdf-release.jks`
- 或设置环境变量 `LPdf_KEYSTORE_PATH` / `LPdf_KEYSTORE_PASSWORD` / `LPdf_KEY_ALIAS` / `LPdf_KEY_PASSWORD`

## 1.7.5 变更

外部代码审计提出 6 项问题，逐条复核后全部处理。这一版不新增功能，但把**四条最重的写出管线换掉了内存模型**。

### 工具箱 OOM（架构级）

合并、拆分、压缩、加水印、导出批注原来都是同一个模式：逐页渲染成位图 → 塞进 `PdfDocument` → 循环全部结束后一次性 `writeTo`。而 `android.graphics.pdf.PdfDocument` 会把**所有页的绘制命令留在内存里**直到落盘，没有任何 API 能中途释放。单页底图最宽 1600px（ARGB ≈ 10MB），在线导入上限 100MB 可能是数百页 —— 这个组合必然 OOM。

改为**分段落盘**（新增 `SegmentedPdfWriter`）：每 8 页写一段、立即 `close()` 释放，最后用 PDFBox `PDFMergerUtility` 做对象级合并。**峰值内存与总页数解耦。**

顺带修掉两个藏得更深的：超长页位图只限了宽没限高（`1600×30000` = 192MB，一张就能打死进程），以及异常路径上位图漏回收。

### HTTPS → HTTP 重定向降级

OkHttp 默认会跟随 https 跳 http。只在入口校验 `startsWith("https://")` 挡不住服务器 302 到明文地址 —— 中间人可以替换成「带合法 `%PDF-` 头」的恶意文件，魔数校验形同虚设。现在关闭内置跟随，改为**逐跳**验协议，最多 5 跳。

### 其余四项

- **进度计算**：导出全部页的进度显式改为 `(i + 1).toFloat() / total`，加注释防回退
- **%00 文件名**：URL 百分号编码不再原样进文件名（`%00` 会导致 `EINVAL` 这样的底层错误）
- **路径前缀**：`deleteImported` 不再把 `imported_backup` 这类兄弟目录算进来
- **pdfium 版本**：报告称内嵌 `1.3.0.1-motley` ≈ 2022 年快照。实测这是 pdfium 的**内部 API 号**，不是 Chromium 快照号 —— `2.0.3`（2026-07 发布）携带的 `libpdfium.so` 与 `1.0.35` 字节完全一致。升级仍排入 1.8.0，因为安全补丁无从从版本串判断

| 项 | 结果 |
| --- | --- |
| 单测 | **246 项，0 失败**（新增 17 项） |
| Lint | **0 error / 8 warning** |
| 包信息 | `versionCode=175` / `versionName=1.7.5` |
| APK | 25,175,492 字节（24.01 MB，含 PDFBox） |
| 签名 | 与 1.1.0 以来一致，可覆盖升级 |

## 1.7.4 变更

自查 1.7.3 时发现并修掉 3 个 bug，都是自适应尺寸的边界情况：

1. **连续模式误扣了页码胶囊的高度**。`contentHeightPx` 是给单页模式用的（扣顶部工具条 + 底部胶囊），连续模式却复用了它。连续模式里页码胶囊盖的是列表底部、页面正常从它下面滑过，扣掉它等于平白少 100dp 可用高度，矮屏上会被误判成「装不下」而缩小页面。新增 `scrollViewportHeightPx()` 只扣顶部
2. **横屏 / 平板上 A4 可能被拉成满宽**。宽度只受「按高算」和「按屏宽」两个约束，缺「不超过内容区 92%」的护栏。宽度富余、高度吃紧的窗口里会顶满整宽，高度随之超限、头尾又被玻璃条压回。新增 `singlePageDrawWidthPx()` 补上护栏（只作用于显示，不影响渲染分辨率）
3. **首屏宽高比被 A4 兜底值锁死**。`remember` 的 key 只有 `docKey`，打开那一瞬间渲染还没开始、拿到的是 A4 兜底值，于是被永久锁住。16:9 横排 PDF 明明一屏放得下，却被按 A4 判成溢出并白白缩小。key 里加入真实宽高比后修复

| 项 | 结果 |
| --- | --- |
| 单测 | **226 项，0 失败**（新增 5 项覆盖上述边界） |
| Lint | **0 error / 11 warning** |
| 包信息 | `versionCode=174` / `versionName=1.7.4` |
| APK | 22,346,473 字节 |
| 签名 | 与 1.1.0 以来一致，可覆盖升级 |

## 1.7.3 变更

### 打开 PDF 自动适配屏幕

1.7.2 只修了横向（左右不再各空一大块），纵向没人管 —— 打开文档第一眼仍是「中间一小条」。这一版补齐纵向：

- **单页模式**：先扣掉顶部工具条与底部页码胶囊的占位，再按「宽不够按宽、高不够按高」算页面尺寸，保证整页完整落在没有任何 UI 压着的区域里。隐藏工具栏时可用区变大，页面自动跟着放大
- **连续模式**：首屏若按满宽放不下一整页，自动缩到「一屏恰好一页」；用户开始滚动后不再干预。50:1 这类超长页不会被缩成一条缝，保持满宽交给纵向滚动
- 渲染宽度与最终显示宽度严格一致 —— 不会出现「按满宽渲完再缩一半显示」，翻开第一页不必多等一倍

### 修掉页高多算一个 padding

连续模式里页面占位高算的是 `可用宽 × 宽高比`，但外层还有左右留白。结果占位框比实际位图高出一截，A4 在小屏上每页底部都空约 17px —— 看起来像「页面没对齐」。

### 策略抽成纯函数并加测试

新增 `ui/page/reader/PageFit.kt`，把留白档位、玻璃条占位、一屏尺寸计算收成纯函数，连续与单页模式共用。配套 `PageFitTest` 15 项，覆盖 360×640dp、360×800dp、分屏半屏、平板横屏、50:1 超长页等屏型 —— 「各种屏幕上页面该多大」从此有可执行断言，不再只能靠真机肉眼看。

| 项 | 结果 |
| --- | --- |
| 单测 | **221 项，0 失败** |
| Lint | **0 error / 11 warning** |
| 包信息 | `versionCode=173` / `versionName=1.7.3` |
| APK | 22,346,469 字节 |
| 签名 | 与 1.1.0 以来一致，可覆盖升级 |

## 1.7.2 变更

三张真机截图驱动的修正版本。三个问题都出在「按猜的做」而不是「对着参照做」。

### 一、底栏重做：这次真的对齐 iOS 26

旧底栏是常驻玻璃药丸 + 五项全挂文字 + 支持横向拖拽。对照 iOS 26 与
[Kyant0/LiquidBottomTabs](https://github.com/Kyant0/AndroidLiquidGlass) 源码后，
三处都是结构性错误：

| 项 | 旧版 | iOS 26 | 现在 |
| --- | --- | --- | --- |
| 选中指示器 | 常驻玻璃药丸 | 按压时压出一滴玻璃，松手消散 | ✅ 按压可见 |
| 文字标签 | 五项全挂 | 纯图标，只有当前项有文字 | ✅ 仅选中项 |
| 横向拖拽 | 支持 | 不支持 | ✅ 移除 |
| 高度 | 单层 62dp | 64dp 外筐 + 56dp 内层 | ✅ 双层 |

玻璃滴要用 `rememberCombinedBackdrop(pageBackdrop, tabBarBackdrop)` 合成两层采样源 ——
它折射的是底栏自己的图标和文字，不是底下的页面。只用一个 backdrop 时滴里空无一物。

纯图标后文字不再是可见信息，已挂到 `contentDescription` 上，读屏仍能念出「文档」「书签」。

### 二、底栏不再「透明到看不清」

截图里设置页的「默认保存方式 / 覆盖…」直接压在底栏图标上。三层一起修：

- 底栏材料底色：`12% + 20%` 白 → `onDrawSurface` 25% 白 / 55% 黑
- 宿主底部渐变：`pageBg` 55% 单段 → 三段式 `0% → 72% → 100%`，起点从 78% 提到 62%
- 「我的」Tab 改为入口页，完整设置走独立路由（`Routes.SETTINGS`），不再把密集说明文字
  压在底栏上

### 三、PDF 页面自适应屏幕宽度

旧版在 `PageItem` 里固定 `padding(horizontal = 16.dp)`，外层又扣一次，
小屏上页面只剩屏幕宽度的 90% 左右 —— 看起来就是「只占中间一小条」。

现在按实测可用宽度分档：

```kotlin
val hPad = if (contentWidthDp < 360.dp) 6.dp else 10.dp
val pageWidthPx = (contentWidthPx - hPadPx * 2).toInt()
```

传下去的就是「扣完留白的宽度」，`PageItem` 内部不再二次相减，渲染宽度与显示宽度严格一致。

`contentWidthDp` 由 `contentWidthPx ÷ density` 算出，不用 `Configuration.screenWidthDp` ——
后者在分屏 / 折叠屏展开态下与实测宽度对不上（v1.5.1 踩过）。

### 验证

| 项 | 结果 |
| --- | --- |
| 单测 | ✅ 206 项 0 失败 |
| Lint | ✅ 0 error / 11 warning |
| APK | 22,346,473 字节 |
| 签名 | SHA-256 `1590d022…`，可覆盖升级 |

`DockIntegrationTest` 原本靠「五项都有文字节点」定位底栏，改为纯图标后必然失败 ——
这是正确的不通过。已改为按 `contentDescription` 定位。

## 1.7.1 变更

收尾版本，不加功能。两件事：**减重**、**把静态检查真正跑起来**。

### 一、APK 从 29.6 MB 降到 22.3 MB（−23.5%）

删掉了一个从未被使用的依赖：PDFBox。

一条 `grep -rn "PDFBox\|PDDocument" app/src/` 返回 0 处引用 —— 它从引入那天起就是个
纯负担。但删掉后减了 **7.26 MB**，而不是预期的 4.4 MB，多出来的部分是排查时才发现的：

**PDFBox 拖进来了 BouncyCastle。** APK 内 `org/bouncycastle/pqc/crypto/` 下有 4 个抗量子
密码（SIKE / PICNIC）的参数量化表，单个 `lowmc.properties` 就有 3.6 MB：

```
3,640,348  org/bouncycastle/pqc/crypto/picnic/lowmc.properties
1,939,925  org/bouncycastle/pqc/crypto/sike/p751.properties
1,154,869  org/bouncycastle/pqc/crypto/sike/p610.properties
  765,399  org/bouncycastle/pqc/crypto/sike/p503.properties
```

PDFBox 用它们做 PDF 签名验签。本应用不做 PDF 数字签名，**这 7.5 MB 一个字节都没用上**。

| 项 | v1.7.0 | v1.7.1 |
| --- | --- | --- |
| APK 总大小 | 29,589,108 | **22,330,098** |
| `classes.dex` | 4.43 MB | 2.97 MB |
| APK 内 `org/` 目录 | 7.9 MB | 0 |

### 二、Lint 恢复进 CI

原来的配置是 `abortOnError = false` —— 跑完也不管结果，等于没查。但它不是"忘了开"，
而是**一开就崩**，当初只好关掉。

真因：Compose BOM 2025.04.01 的 lint 包按 Kotlin 1.x 编译，那时 `KaFunctionCall` /
`KaSimpleVariableAccessCall` 是接口；Kotlin 2.1 把它们改成了类，检测器加载时 JVM 直接
抛 `IncompatibleClassChangeError`。

关一条会崩换下一条接着崩，索性反编译 `lint.jar` 一次查清，把所有依赖 `KaCallInfoKt` /
`KaSessionProvider` 的 5 个检测器全部 disable：

| 检测器 | issue id | 作用 |
| --- | --- | --- |
| `AutoboxingStateCreation` | 同名 | 建议用 `mutableIntStateOf` 代替 `mutableStateOf(0)` |
| `RememberInComposition` | 同名 | 禁止在 Composable 函数体里调 `remember` |
| `FrequentlyChangingValue` | 同名 | 高频变化值不应被 remember 读取 |
| `ComposableStateFlowValue` | **`StateFlowValueCalledInComposition`** | 禁止组合中直接读 `StateFlow.value` |
| `MutableCollectionMutableState` | 同名 | 禁止 `mutableStateOf(mutableListOf())` |

这五个都是代码风格类检查，不涉及行为正确性。

> 踩坑记录：`ComposableStateFlowValue` 不是 issue id，写它会报 `UnknownIssueId` 且**不生效**；
> `FrequentlyChangingValue` 会崩是因为它复用了 `RememberInCompositionDetectorKt` 的辅助函数，
> 只关前者没用。

CI 新增独立 `lint` job（`--no-parallel` 防 OOM），`build` 改为 `needs: [ test, lint ]`。

**效果：2 error / 26 warning → 0 error / 11 warning。**

### 三、Lint 跑起来后暴露的 3 个真实缺陷

#### ① 静态 `SimpleDateFormat` —— 并发错乱 + 切语言失效

```kotlin
// 原来
private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
```

- **线程不安全**：内部有可变 `Calendar`，并发 `format()` 互相污染，可能抛 `ArrayIndexOutOfBoundsException`
- **Locale 冻结**：在 `object` 初始化那一刻定死，用户运行中切语言后日期格式不跟着变

改为每次新建（开销几微秒，正确性优先）。

#### ② 阅读页常亮用 `LocalContext.current as? Activity`

Compose 会往 `LocalContext` 里塞 `ContextWrapper`（主题包装等），强转拿到 `null`，
`activity?.window` 全部静默跳过 —— 表现是"常亮开关点了，界面没变化，也不报错"。
改用 `LocalActivity.current`。

#### ③ 缺少 `monochrome` 图标层

Android 13+ 主题图标会取单色层做渲染。缺失时本应用在开了主题图标的桌面上显示成一个
彩色方块，与周围适配过的应用格格不入。新增 `ic_launcher_monochrome.xml`：几何形状与原
前景一致，但做成"实心文档 + 镂空文字横线"—— 系统只取 alpha 通道再统一刷色，
直接用彩色原图会把文字线和底色糊成一片。

### 四、其余 Lint 项

真问题就修（`ContextCastToActivity`、`ConstantLocale`、`MonochromeLauncherIcon`、
`DataExtractionRules`、`UseOfNonLambdaOffsetOverload`、`UseTomlInstead`），
误报就加抑制并写明原因（`Recycle` ×3 —— 那些 `query()` 全部紧跟 `?.use { }`，
`Closeable.use` 保证异常路径也关闭，Lint 识别不了这种写法）；
防御性代码保留并 `@Suppress`（`ObsoleteSdkInt`，"将来降 minSdk 不用改"）。

`GradleDependency`（lifecycle 2.8.7 → 2.9.4）不动：跨版本升级留到 v1.8.0 与 Compose 一起动。

### 五、验证

| 项 | 结果 |
| --- | --- |
| 编译（`--rerun-tasks` 全量重编） | ✅ |
| 单元测试 | ✅ 206 项，0 失败 |
| Lint | ✅ 0 error / 11 warning |
| APK 版本 | ✅ `versionCode=171, versionName=1.7.1` |
| 签名 | ✅ 与 1.6.1 / 1.7.0 一致，可覆盖升级 |
| 包内残留 | ✅ 无 `tom_roush`、无 `org/bouncycastle` |
| Manifest | ✅ `dataExtractionRules` 生效、`CAMERA` 权限未出现在最终包 |

## 1.7.0 变更

阅读体验四项：文本选择与复制、缩放以手指为锚点、夜间模式页面真反色、缩略图预生成队列。

- **文本选择**：拖选语义是「起点行取到行尾、终点行从行首开始、中间行整行」。
  用索引区间会得到 `DE\nFGHIJ` 这种错误结果，正确应是 `CDE\nFGH` —— 这个区别在
  `TextSelectionTest` 里用布局表注释锁住了
- **缩放锚点**：`c = (p - t) / s` 要求缩放后 c 仍在 p 处，推出 `t' = p - (p - t)·s'/s`
- **夜间反色**：真反色，不叠滤色片。滤色片把整页亮度压低，白底变深灰、黑字变更黑，对比度反而更低
- **缩略图预热**：`Channel` 容量 32 + 串行 pump + 去重，`PREWARM_AHEAD = 12`

## 1.5.2 / 1.5.1 变更

> **为什么有两个版本号**：1.5.1 已在 GitHub Release 发布（`v1.5.1` tag）。
> 发布时发现网盘上传通道失效，改用 1.5.2 重打了一次包以便两边指向同一个二进制。
> **两者代码完全相同**，只有 `versionCode` / `versionName` 不同。

1.5.0 把「渲染」和「玻璃」两件事都推到了新架构上，也顺手留下了三处需要收尾的地方。
这一版不加功能，只做加固。

### 一、渲染宽度的真凶找到了：`screenWidthDp` 不可信

1.5.0 加了 `sanitizePageSize`，能把畸形的 `1168×13657` 修正回来 —— 但那是**兜底**，
没人喜欢兜底被触发。这一版回到源头。

反推：`13657 ÷ 1.4142 ≈ 9657`，渲染宽度是屏幕物理宽的 **8.3 倍**。而当时的代码是：

```kotlin
val screenWidthPx = with(density) {
    LocalConfiguration.current.screenWidthDp.dp.toPx().toInt()
}
```

在普通手机上这行恰好等于物理宽，所以「一直看着是对的」。它错在语义：
`screenWidthDp` 描述的是**窗口**的 dp 宽，受自由窗口 / 分屏 / 折叠屏展开 /
厂商改写的 `densityDpi` 影响，一旦和实际布局宽度脱钩，再乘一次 density 就被放大。

现在改成内容区的**实测物理像素**：

```kotlin
var contentWidthPx by remember { mutableIntStateOf(0) }
Box(Modifier.fillMaxSize().onSizeChanged { if (it.width > 0) contentWidthPx = it.width }) { ... }
```

拿到之后不再有任何 dp ↔ px 换算，这条路径物理上不可能再被放大。
`sanitizePageSize` 保留，降级为纯粹的最后一道网。

顺带修掉一个连带问题：`ensureRendered` 原本「已完整就 return」，而渲染宽度现在
会随窗口尺寸变化 —— 横屏或拉伸窗口后页面会一直是旧的低分辨率版本，看着糊却刷不新。
现在把已渲染宽度纳入判断（差值 < 10% 视为等效，避免窗口微抖时反复重渲）。

### 二、`MAX_RENDER_WIDTH` 从 2048 提到 4096

1.5.0 的分块上限是 `TileLimits.MAX_EDGE = 8192`（单张位图单边），而渲染宽度上限
还停在 2048 —— 意味着**一页最宽只能渲到 2048 像素**。平板 / 桌面模式下放大后，
块内清晰度和块边界会明显断层。

4096 是 8192 的一半，既保证分块算法在绝大多数页面上退化成单块（A4 @4096 宽只有
5792 高，仍是一块），也给了平板足够的清晰度余量。

### 三、`PdfPage` 缓存 6 → 12，减少翻页时的重复 open

分块渲染让同一页的 `plan()` 和 `renderTiles()` 各调一次 `openPageLocked()`，
一次滚动会摸到更多页。窗口偏小时会出现「plan 打开 → 被挤出 LRU → render 再打开」
的重复开销。12 页的 `PdfPage` 引用只占 KB 级 native 内存，和位图（MB 级）不是一个量级。

### 四、测试

135 个单测，新增 16 个：

| 文件 | 守什么 |
| --- | --- |
| `RealPdfGeometryTest`（12） | 用真实 399 页 A4 PDF 的 `/MediaBox` 跑完整几何链路；`sanitizePageSize` 的六条防污染用例 |
| `RenderWidthSourceTest`（4） | 渲染宽度只能来自实测像素；上限截断与下限抬升；尺寸变化能触发状态更新 |

> `RenderWidthSourceTest` 里有一条看起来自相矛盾的断言：沙箱中 `screenWidthDp`
> 与实测宽度**必须相等**。这恰恰是要说明的事 —— Robolectric 是「理想设备」，
> 真实设备上的漂移在本地永远测不出来，所以才必须改用实测宽度。

**反向验证**：把 `MAX_RENDER_WIDTH` 改回 2048，`RealPdfGeometryTest` 立刻 2 项红。
确认新测试不是摆设。

### 五、顺手记一笔：`android.util.Log` 在纯 JVM 单测下会抛

`RealPdfGeometryTest` 不带 Robolectric，`sanitizePageSize` 里的一句 `Log.w`
会把测试炸掉（`Method w in android.util.Log not mocked`）。
`testOptions { unitTests.isReturnDefaultValues = true }` 解决 ——
纯逻辑测试没必要为了打日志去启动整个 Android 运行时。

## 1.5.0 变更

两个目标：**不管页面多大都要渲染**、**液态玻璃做到 Apple Liquid Glass 的程度**。

### 一、渲染：从「拒绝渲染」到「一定能渲染」

真机截图报的是 `页面尺寸超出设备渲染上限（1168×13657），已跳过渲染`。

#### 根因不是阈值太小，是模型错了

Android GPU 单边纹理上限是 **8192**（物理事实，不是可调参数）。PDF 里
CAD 施工图、海报、地图导出这类页面，按屏幕宽缩放后高度动辄上万 ——
**整页塞一张位图**这条路在物理上就走不通。旧代码在 `createBitmap` 前拦一道，
于是变成「跳过渲染」，用户看到一张白纸。

更隐蔽的是旧 `renderSize()` 的第三层兜底会**直接截断高度**：超长页被压成
「矮胖」，宽高比静默被破坏，而标注用归一化坐标，页面一被压扁标注全部错位。
这个 bug 一直存在且没有任何日志。

#### 现在：一页 = 一组 tile

```
整页理想尺寸 ──► planTiles() ──► 若干合法矩形 ──► renderTiles() 逐块回调
                                   │
                                   ├─ 每块 ≤ 8192 单边、≤ 64MB
                                   └─ 整页最多 4 块
```

在**同一个 Compose Canvas** 里多次 `drawImage` 拼回去。不用 N 个 `Image`：
每个 `Image` 都是独立图层，高 DPI 下相邻两张会漏 1px 白线，长页上就是横纹。

新增 `PageTiling.kt`（纯函数，不碰 Bitmap）与 `PageRender.kt`（运行时状态）。
渲染用 pdfiumandroid 的**矩阵版** `renderPageBitmap(bmp, matrix, clipRect, ...)`
直通 `nativeRenderPageBitmapWithMatrix`，比用负偏移可靠。

#### 降级策略：二分求最大可行缩放

预算内（≤4 块）走 1:1 全清晰度。超预算时**不是套公式** ——
直观写法 `s = sqrt(4×64MB / 总像素)` 对 `1080×122103` 会算出
`72×8192`，技术上"渲染了"，实际一片糊。

合法性由「整数网格 + 单块 64MB」决定，是分段函数，闭式解写不出来。
改为**二分 60 次**求这段函数上的最大值：

| 页面 | 旧结果 | 新结果 |
| --- | --- | --- |
| 1168 × 13657（截图那页） | 拒绝渲染 | **2 块，1:1 全清晰** |
| 1080 × 122103（崩溃报告） | 拒绝渲染 | 4 块，`290×32768` |
| 2048 × 409600 | 拒绝渲染 | 4 块，`164×32768` |
| 16000 × 200 | 拒绝渲染 | 2 块横向切分，1:1 |
| 50000 × 50000 | 拒绝渲染 | 4 块，`8192×8192` |

关键不变量：**`widthPx/heightPx` 永远等于 PDF 真实宽高比**，即使降级。
所以标注的归一化坐标零迁移，位置永远对。

其余配套：

- `ReaderViewModel` 缓存从 `Bitmap` 改为 `PageRender`，页数上限 7 → **3**（长页每页最多 4 块）
- 逐块回调 → 第一块到达就能显示，长页是「从上往下长出来」而不是转很久的圈
- `PdfReaderPage` 占位高度改用真实比例，不再硬编码 A4 的 1.414（超长页会先占矮框再突然撑高）
- `PdfFlattener` 导出改走会话 + 逐 tile 贴到同一页，超长页不再中断整本导出

### 二、玻璃：三处方向性错误

用户说"太烂了"。对照 Apple HIG 与 Metal/AGSL 的采样管线
（折射 → 色散 → 模糊 → 亮度整理 → 边缘高光），旧版这三处是错的：

| # | 问题 | 后果 |
| --- | --- | --- |
| 1 | `lens(..., depthEffect, chromaticAberration)` 第 4 参传的是 **`false`** | 色散从未开启，玻璃边缘是纯灰边 —— 这正是「玻璃」与「半透明塑料」最直接的区别 |
| 2 | 霜化层 **55% 白** | 把折射糊成乳白。Apple 的 `regular` 变体只做 "blur + adjust luminosity"，不大面积盖白 |
| 3 | `lens(refr*100f, 24f, ...)` | 折射位移 12、折射带 24，采样点被推出控件外，边缘采到背景色 |

新的采样管线：

```kotlin
lens(height = 14px, amount = 12px, depthEffect = true, chromaticAberration = true)
blur(22dp)
colorControls(brightness = 1.06, contrast = 0.94, saturation = 1.35)  // ← "adjusts the luminosity"
```

配套调整：

- 霜化层 55% → **20%**（深色 35% → 14%），亮度交给 `colorControls` 做
- 删掉自绘的 `drawTopHighlight()`（一条 40% 高的白色渐变带，效果是"白雾压顶"），
  边缘高光改用库自带的 `HighlightStyle.Default(intensity, angle, falloff)`
- `GlassSpec` 参数重命名对齐物理量：`refractionHeightPx` / `refractionAmountPx` /
  `chromaticAberration` / `brightness` / `contrast` / `saturation` / `highlightAngle` / `highlightFalloff`
- 删掉死字段 `dispersion` / `maskAlpha` / `highlightHeightRatio` / `allowRefraction`

#### 修掉一个真 bug：规范值从未生效过

`AppSettings.glassBlurDp` 默认 `10f`，而 `toGlassSpec()` 直接拿它覆盖
`GlassSpec` 的默认值 —— 所以"v1.3.5 把模糊提到 18dp"在真机上**从未生效**。
更糟的是当时的 `GlassTokensTest` 断言的是 `GlassSpec()` 的默认值，
测的是运行时根本不会用的数，因此测试全绿、体验照旧。

同时 `intensity = glassBlurDp / 10f` 把「模糊」和「折射」两个不相干的物理量
强行耦合：用户一调模糊，折射跟着变。现在两者独立。

#### 新增 `GlassContentCard`：内容层不能折射

Apple HIG 原文：

> Don't use Liquid Glass in the content layer.
> Use Liquid Glass effects sparingly.

内容层的职责是**可读**。折射会让文字边缘跟着底层一起弯曲，长列表滚起来整页都在扭。
所以把 3 处内容层调用点从 `LiquidGlass` 迁到新的 `GlassContentCard`（纯模糊 + 底色，无折射）：

| 文件 | 组件 |
| --- | --- |
| `GlassControls.kt:384` | `GlassGroup` 设置分组 |
| `GlassFeedback.kt:248` | `GlassStateCard` 状态卡 |
| `GlassList.kt:118` | 列表行按压反馈 |

其余 26 处都是功能层（底栏、按钮、面板、浮卡），保留折射玻璃。

### 三、测试

**119 项全绿**（原 103 项）。新增 / 重写：

| 文件 | 项数 | 守什么 |
| --- | --- | --- |
| `PageTilingTest`（新） | 9 | 每块合法、块间无缝隙无重叠、降级不破宽高比、任何尺寸都不被拒绝 |
| `RenderSizeLimitTest`（重写） | 11 | 改名断言语义（判断对象是 tile 不是整页），新增「同样页面切块后每块都合法」 |
| `GlassTokensTest`（重写） | 13 | **断言方向反转**：从锁「厚重」改为锁「像 Apple 玻璃」—— 雾化克制、色散开启、折射像素级、设置项不覆盖规范值 |

两个文件都做了**反向验证**（把旧 bug 注入回去，确认测试确实会红）：

- 把霜化层改回 55%、色散改回 `false`、`glassBlurDp` 改回 10f
  → `GlassTokensTest` **4 项失败**
- 把降级改回 `sqrt(单块预算/总像素)` 公式
  → `PageTilingTest` **3 项失败**

## 1.4.0 变更

底栏换成 iOS 26 同款的**悬浮玻璃 + 药丸滑块 + 可拖拽浮卡**。

### 底栏：药丸滑块浮在玻璃之上

旧底栏是「一排平铺按钮装进一个玻璃盒」，选中项只是加一层淡底色 —— 没有层次。

新版每一项外面可以浮一片**独立玻璃药丸**。它有自己的折射、自己的边缘色散，
压在整条底板之上；两片玻璃重叠处的折射差异会自然产生参考图里那圈彩色描边。

- **弹簧滑动**：切换时药丸不是瞬移，用 `spring(0.72, MediumLow)` 追目标位置，
  中间留一点惯性
- **横向拖动**：手指划过整条底栏时药丸跟着走，松手吸附到最近一项，
  拖出两端用 `Motion.rubberBand` 加阻尼
- **按压回弹**：按下去 0.90，松手用欠阻尼弹簧收回，会轻微过冲

首页五项：`全部 / 最近 / ＋导入（主按钮）/ 加密 / 目录`。
中央「导入」是实心圆底主按钮，与参考图一致。

阅读页底栏同样处理（8 项紧凑模式，只有图标），选中态跟随当前工具
（目录 / 书签 / 搜索 / 高亮 / 墨迹 / 批注）。

### 长按浮卡：浮起 + 跟手形变 + 抛掷动量

新增 `GlassFloatingCard`：

| 阶段 | 行为 |
| --- | --- |
| 浮起 | 0.88 → 1 放大 + −1.5° 倾斜归零，欠阻尼弹簧收尾，会过冲 |
| 跟手 | 位移只作用在卡片这层，蒙版完全不动（否则整屏跟着晃） |
| 形变 | 按水平拖动速度做剪切，上限 ±4°；松手后慢一档归零，像真有惯性 |
| 抛掷 | 读 `VelocityTracker` 真实速度，先滑一小段，再用 `Motion.dragReturn` 弹回 |
| 越界 | 出安全区后 `Motion.rubberBand` 递增阻尼，不硬顶 |

蒙版同时加深（0.28 → 0.42）并叠一层竖向渐变，让上层玻璃的霜化层能抓出层次。

### 回归测试

新增 9 项（`GlassTabBarTest` 4 项、`GlassFloatingCardTest` 3 项，
`DockIntegrationTest` 改写 1 项）。总计 **103 项，全绿**。

`DockIntegrationTest` 做过反向验证：撤掉 `Box.align(BottomCenter)` 后
该测试确实失败，说明它守的是真实定位而不是「高度 > 64dp」这种假断言。

## 1.3.5 变更

真机反馈修的三件事：**打开 PDF 一直转圈**、**底栏太透明看不清**、**首页顶部文字重叠**。

### 转圈：一次打开，反复渲染

`renderPage` 原来每次渲染一页都要 `openFileDescriptor` → `newDocument` → 渲染 →
`closeDocument` → `close`，全部串在**一把全局锁**上，还要和 `pageCount`、
`tableOfContents` 抢。连续滚动一次可见 2~3 页，每页都走一遍完整的打开流程，
399 页的书首次进入就是一个转不完的圈。

新增 `PdfiumEngine.DocSession`：文档只打开一次，渲染时仅 `openPage` + `render`，
自带独立互斥锁不再抢全局锁，并缓存最近 6 个 `PdfPage`。目录也走同一会话。

另外两处配套：

- **重试有上限**：页面渲染循环原来没有次数限制，一旦某页永远渲染不出来，圆环就永远转。现在最多 8 次，超时后把转圈换成可读的错误提示 —— 无限转圈是最糟的失败方式。
- **失败页不重试**：`renderErrors` 记住失败的页与原因，避免反复占用渲染锁拖慢其它页。

### 玻璃：从「透明塑料」变成「磨砂玻璃」

| 项 | 原值 | 现值 | 原因 |
|---|---|---|---|
| 霜化层 `glassFrost` | 无 | 55% 白（深色 35% 黑） | **这是主因**。原来只有 6% 白本底，底下 PDF 正文原样透上来，图标和文字糊成一片 |
| 本底 `glassBase` | 6% 白 | 18% 白 | 补上实体感 |
| 模糊半径 | 10dp | **18dp** | 10dp 时底层正文仍清晰可辨，玻璃像透明塑料；参考图里内容已被糊成色块 |
| 边缘描边 | 单圈 22% 白 | 上缘 69% 亮线 → 下缘暗线渐变 | 单圈均匀描边像贴纸；参考图边缘有明显明暗差，像有厚度的玻璃砖 |

深色模式下霜化层用**黑**而非白 —— 深色主题叠白雾会变成发灰的脏块。

### 首页顶部文字重叠

启动提示「需要读取文档权限才能自动列出本地 PDF…」放在 `topInset + 8dp`，
与「LPdf」标题完全同高，两行字叠成一坨。现在下移到标题下方（`+68dp`）。

底栏后面补了一层从透明到底色的竖向渐变 —— Dock 浮在列表上方，列表滚到底时
最后几行会从玻璃底下透出来，玻璃再厚也挡不住「内容压着内容」的脏乱感。

### 验证

- **96 项单测全绿**（新增 9 项：`GlassTokensTest` 7 项锁住玻璃光学参数、`HomeHeaderOverlapTest` 2 项锁住顶部布局）
- 顶部重叠修复做了反向验证：把 `+68dp` 改回 `+8dp`，测试立即失败
- `GlassCapabilityTest` 里两条把 `blurDp = 10f` 写死的断言已同步更新

## 1.3.4 变更

这一版没有新功能，只做**体检**：把那些「不崩溃、不报错，但会悄悄吃内存、
或者让文件点不开又说不出原因」的问题一次性清掉。

| 类型 | 内容 |
|---|---|
| **修复** | **PDFium native 句柄泄漏（7 处）**：`pageCount` / `renderPage` / `tableOfContents` / `pageSize` / `metadata` / `search` 原来只在成功路径 `closeDocument`，`getPageCount()`、`renderPageBitmap()` 等抛异常时 native 文档永不释放。反复打开损坏 PDF 会把 native 内存吃满直到进程被杀。全部改为 `doc` / `page` 提到 try 外、`finally` 统一释放 |
| **修复** | **搜索循环内的单页泄漏**：`doc.openPage(p)` / `page.openTextPage()` 是 native 调用，损坏页会抛异常直接跳到 `finally`，当前页的 `PdfPage` / `PdfTextPage` 没被关。改为单页 try/finally 兜住并 `continue`，一页坏了不再拖垮整次搜索 |
| **修复** | **位图缓存只看页数不看字节**：`pageCache` 原来限 5 页，但 A4@2048px 一页就 16MB，5 页 80MB，低端机直接 OOM。改为 LRU 双限：页数 **+** 字节（堆的 1/4，32MB–192MB 自适应） |
| **修复** | **切换文档不清页缓存**：`bind()` 换文档时旧页位图仍留在缓存里，既浪费内存，还可能在新文档里命中旧页号显示成别的内容。现在 `bind()` 开头 `clearPageCache()` 并重置 `currentPage` / `toc` / `bookmarks` |
| **修复** | **打开文档的竞态**：连续点两个文档时两次 `open()` 并发跑，谁后返回谁写 state —— 后点开的反而可能被先点开的覆盖（点了 A 显示 B）。新增 `openJob`，新一次 `bind()` 先取消上一次 |
| **修复** | **列表缩略图累积**：原来 `remember(path) { decodeFile(...) }`，`LazyColumn` 滚过的项要等 GC 才释放，滚几百个文档就攒几十 MB。新增 `ThumbnailCache`：LRU，120 条 / 24MB 双限 |
| **修复** | **授权失效后无法自救**：`takePersistableUriPermission` 的异常被 `runCatching` 静默吞掉，SAF 授权被撤销或文件被移走时列表只给一句「无法打开该文件」。现在 `FileNotFoundException` 归到 `NotFound`，玻璃提示条给出「**重新选择**」按钮直接拉起系统选择器 |
| **修复** | **导入时的持久化失败被吞**：`PdfItem.persistedUriPermission` 原来硬编码 `true`，现在按真实结果写入；目录授权失败也会提示「重启后可能需要重新选择」 |
| **修复** | **导出失败不清理临时文件** / **启动不清理陈旧缓存**：`cacheDir/flat`、`cacheDir/staging` 遗留文件现在启动时清理超过 1 小时的残留 |
| **修复** | **水印合成的位图泄漏**：`PdfWriterEngine` 循环内 `render` / `drawBitmap` 抛异常时 `bmp.recycle()` 走不到，长文档中途失败会漏掉一张几千万像素的位图。改为 try/finally |
| **测试** | 新增 15 项：`ThumbnailCacheTest`(8) 覆盖 LRU 命中/淘汰/字节上限/非法文件，`PdfResourceSafetyTest`(7) 覆盖失效授权归类与渲染尺寸闸门。总计 **87 项全绿**，并对 `NotFound` 修复做了反向验证（撤掉修复 → 2 项失败） |

## 1.3.2 变更

| 类型 | 内容 |
|---|---|
| **修复** | **点开图标立刻消失**：`SquircleShape` 只实现 `Shape` 且返回 `Outline.Generic`，而液态玻璃库的 `lens()` 要求 `shape is CornerBasedShape`，否则直接抛 `UnsupportedOperationException`。每个玻璃组件首帧必崩。改为继承 `CornerBasedShape` 并实现 `copy()` / `createOutline(size, 四角半径, layoutDirection)` |
| **修复** | `enableEdgeToEdge()` 被放在 `super.onCreate()` 之前 —— 该方法第一步就要拿 `decorView`，window 未 attach 必崩 |
| **修复** | `LpdfApplication` 启动清理挂在 `SupervisorJob` 上，异常会杀进程；改为 `runCatching` |
| **新增** | **玻璃能力探测**：启动时构造并编译一个最小 AGSL 着色器，编译过才算真支持，而非只看 `SDK_INT`（Android 13 只是暴露 API，实际编译在 GPU 驱动层，ROM 可阉割） |
| **新增** | **三级降级**：`FULL`（折射+模糊+高光）→ `BLUR_ONLY`（无折射）→ `PLAIN`（加深半透明底）。`lens()` 与 `blur()` 各自 `runCatching`，失败即降级并触发重组 |
| **新增** | **全局同步降级**：能力状态单例，一处降级全局一起降，避免一半折射一半不折射 |
| **新增** | **崩溃留痕**：`Thread.setDefaultUncaughtExceptionHandler` + 首屏可查看/复制报告；界面异常走 `FatalErrorScreen` 而非直接退出 |
| **新增** | 设置 → 通用 → 玻璃渲染：显示当前等级与降级轨迹，用户反馈时可直接读到 |
| **改进** | **发布策略**：Release 只发 release 包，debug 包不再对外（包名带 `.debug` 后缀、体积大一倍，放出来只让用户困惑） |

## 1.3.0 变更

| 类型 | 内容 |
|---|---|
| **新增** | **书签**：阅读页 Dock 一键加书签，面板内可跳转、内联重命名、删除；按文档独立存储于 `filesDir/bookmarks/` |
| **新增** | **全文搜索**：走 PDFium `findStart` / `findNext` 文本层，逐页命中高亮（淡黄为普通命中、橙为当前命中），支持大小写敏感、上一处 / 下一处循环跳转、进度提示 |
| **新增** | **签名板**：手写（二次贝塞尔平滑，逐像素裁剪留白）→ 存入签名库（`filesDir/signatures/`，非缓存目录）→ 一键盖到当前页，可拖动缩放；签名库支持重命名与删除 |
| **新增** | **水印**：工具箱新增「水印」分组，文字水印支持字号 / 角度 / 浓淡 / 平铺四项调节、三档预置、全部页或指定页码范围，输出为新文件 |
| **改进** | 编辑工具岛扩展为 8 个按钮（新增书签、搜索），按钮宽度与间距重新配平，避免溢出 |

## 1.2.0 变更

| 类型 | 内容 |
|---|---|
| **新增** | **标注扁平化导出**：把高亮 / 墨迹 / 形状 / 批注 / 文本框 / 签名烧录进 PDF，另存为副本，解决「换设备标注全丢」的问题。入口在阅读页编辑工具岛 → 下载图标，通过 SAF 选择保存位置 |
| **新增** | **正式签名**：release APK 现已签名，可直接安装 |
| **新增** | **GitHub Actions CI**：push 跑测试与 debug 构建；打 `v*` tag 自动构建双 APK 并发布 Release |
| **改进** | 导出过程有逐页进度遮罩，支持中断；原文件永不修改，标注 JSON 仍保留 |

## 功能

| 页面 | 内容 |
|---|---|
| **P1 文件列表** | MediaStore 扫描、SAF 目录授权、单文件导入、HTTPS 链接导入、搜索、5 种筛选、3 种排序、长按玻璃预览卡片、损坏/加密角标、密码解锁面板 |
| **P2 阅读页** | PDFium 渲染、连续滚动 / 单页两种模式、双指缩放（1–5x，上限可配）、目录抽屉、页码胶囊、玻璃 Dock、编辑工具岛（高亮/墨迹/形状/批注/签名 + 撤销/清空/导出）、书签面板、全文搜索、阅读进度记忆、阅读常亮 |
| **P3 设置页** | 6 组共 24 项：阅读体验、外观与玻璃（模糊/折射/动效等级）、编辑（保存方式/高亮色/笔宽/撤销步数）、存储（缓存清理/清空记录/清空标注）、隐私与网络（离线模式/仅 Wi-Fi/链接导入）、通用 |
| **P4 工具箱** | 三档压缩、按页码范围拆分、单页 / 全部页导出 PNG·JPG、图片转 PDF（A4 或原尺寸）、文字水印 |

## 液态玻璃实现

- **组件库**：`Kyant0/AndroidLiquidGlass 1.0.0-rc01`，`drawBackdrop` + `lens`（AGSL 实时折射）+ `blur`
- **采样源**：Compose `GraphicsLayer`，**不使用 PixelCopy 截屏**
- **层级**：`LiquidGlassScope` 把内容层录进 GraphicsLayer，玻璃层单独放在覆盖层，避免「玻璃采到自己」的递归
- **视觉锁定**：圆角 28dp 超椭圆、模糊 10dp、折射 0.12、高光 0.15、蒙版 0.35
- **动效**：全部为欠阻尼弹簧（`Motion` 令牌表，11 组），无 tween

## 栅格化输出的取舍

标注导出与水印都走**栅格方案**：逐页渲染底图 → 叠加标注 / 水印 → 组装新 PdfDocument。

- ✅ 实现确定，跨阅读器显示完全一致
- ❌ 源文件文字变为位图，**不可再选中 / 复制 / 搜索**，体积也会变大

矢量方案（改写 PDF 内容流）能保留可选文字且体积更小，但需要完整 PDF 对象模型操作，
当前无可靠库支撑，风险不可控。若后续确有「保留可选文字」需求，应向矢量方案演进。

> 同理，**加了水印的文件会失去可选文字**，这是该功能的固有代价，需在确认前告知使用者。

## 目录结构

```
app/src/main/java/com/lpdf/reader/
├── core/           模型、错误、调度器、格式化、网络
├── data/
│   ├── pdf/        PdfiumEngine（读 + 全文搜索）、PdfWriterEngine（写 + 水印）、PdfFlattener（标注烧录）、PdfValidator（验身）
│   ├── local/      MediaStore、Prefs(DataStore)、缩略图、标注、书签、签名库
│   └── network/    链接导入、更新检查（不内置服务端地址）
└── ui/
    ├── glass/      液态玻璃组件库（13 个文件，图标全自绘）
    ├── page/       list / reader / settings / tools
    └── theme/      颜色、字体、圆角、弹簧令牌
```

## 已知限制

- **未在真机 / 模拟器验证**：构建环境无 KVM，Android 模拟器无法启动。已验证的是：编译通过、R8 通过、96 个 JVM 测试通过（含标注坐标 JSON 往返、扁平化坐标换算、水印几何计算）。**玻璃的 AGSL 折射效果只能在真机上看，请实际安装确认。**
- **标注导出未做端到端验证**：依赖 PDFium native so 与 PdfDocument 写入，JVM 侧只能覆盖坐标换算，实际导出结果需真机确认。
- **形状只画矩形**：`Annotation.Shape.kind` 支持 `RECT`/`OVAL`/`ARROW`，但阅读页只渲染 RECT，导出同步了这一行为。
- **表单填写 / 元数据编辑**：引擎与数据模型已具备，UI 尚未接入，属 v2.0 范围。
- **签名只支持图片形式**：手写签名保存为 PNG 后作为图片标注叠加，不是 PDF 数字签名（不含证书与时间戳），不具备法律意义上的防篡改能力。
- **搜索不做跨页合并**：跨页断行的词会被拆成两处命中，属于文本层特性，暂未做后处理。
- **Lint 覆盖不全**：5 个 Compose 检测器（`AutoboxingStateCreation` / `RememberInComposition` /
  `FrequentlyChangingValue` / `StateFlowValueCalledInComposition` / `MutableCollectionMutableState`）
  因上游 lint 包与 Kotlin 2.1 分析 API 不兼容而崩溃，已 `disable`。其余检查已恢复并在 CI 强制
  `abortOnError = true`，当前 0 error / 11 warning。根因修复要等 Compose 发布适配 Kotlin 2.1 的
  lint 包，届时删掉那几行即可。
- **未做国际化**：全部 UI 文案硬编码中文（462 处），`strings.xml` 仅 1 条，`HardcodedText` 已 disable。
  这是 v2.0.0 的头号事项。
