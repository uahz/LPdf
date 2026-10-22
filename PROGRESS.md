# LPdf 开发进度

> 更新时间：2026-10-22 20:45
> 当前版本：**v1.7.3**（versionCode 173）
> 仓库：https://github.com/uahz/LPdf

---

## 一、当前状态总览

| 指标 | 数值 |
| --- | --- |
| 当前版本 | v1.7.3 |
| versionCode | 173 |
| 最近发布 | [v1.7.3](https://github.com/uahz/LPdf/releases/tag/v1.7.3)（2026-10-22） |
| 单测数量 | **221 项，全绿** |
| Lint | **0 error / 11 warning**（已进 CI，`abortOnError = true`） |
| APK 体积 | **22.35 MB**（22,346,469 字节） |
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
| **v1.7.3** | **打开 PDF 自动适配屏幕（单页一屏看全 / 连续首屏适配）+ 页高修正** | ✅ **已发布** |
| v1.8.0 | 元数据编辑 | ⬜ 待开始 |
| v2.0.0 | 多语言 + 性能与大文件 | ⬜ 待开始 |

---

## 二、v1.7.3 已完成的工作（本轮）

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

## 三、v1.7.2 已完成的工作

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

## 四、v1.7.1 已完成的工作

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

## 五、v1.7.0 已完成的工作

| 功能 | 实现要点 |
| --- | --- |
| 文本选择与复制 | `TextSelection.kt` 拖选语义：起点行取到行尾、终点行从行首开始、中间行整行。用索引区间会得到错误结果 |
| 缩放锚点 | `c = (p - t) / s` 推导出 `t' = p - (p - t)·s'/s`，保证缩放前后手指下方的点不动 |
| 夜间反色 | 真反色而非叠滤色片。滤色片把整页亮度压低，白底变深灰、黑字变更黑，对比度反而更低 |
| 缩略图预生成 | `ThumbnailPrewarmer` 串行队列 + 去重，`PREWARM_AHEAD = 12` |

---

## 六、网盘同步状态

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
| **v1.7.3** | ✅ 签名包 `app-release.apk` | ✅ `file_id = eQwUNShlnGmq` |

> 网盘 `LPdf` 目录现含 1.2.0 ~ 1.7.3 共 15 个 APK。

> 发版时发现 GitHub 仓库**从未配置过签名 secrets**，CI 一直产出的是未签名的
> `app-release-unsigned.apk` —— 用户下载后无法安装。本次已补配
> `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 四个 secret，
> 重跑 CI 后拿到签名包，并删掉了 Release 上的未签名资产。
> **此前的 v1.6.0 ~ v1.7.0 的 CI 产物同样是未签名的**，那些版本请以网盘 / 本地 `releases/`
> 目录里的 APK 为准。

---

## 七、后续版本计划

### v1.8.0 · 元数据编辑

- 读取已有元数据（pdfium `PdfDocument.getDocumentMeta()` 已能读，但**尚无任何调用点**，等于没做）
- 写入标题 / 作者 / 主题 / 关键词

**技术选型待定**：pdfium 只有读、没有公共写入 API。可选路线：

1. 复用 PDFBox —— 与本次减重方向相反，需重新引入 7 MB
2. 自写最小 PDF 字典改写 —— 只动 `/Info` 对象，不碰页面内容流，风险可控但需要处理增量更新（`/Prev`）与 xref
3. 输出为新文件 + 应用内数据库覆盖显示名（不改原文件，最保守）

倾向 **2 或 3**，v1.8.0 立项时定。

### v1.9.0 · 分屏与横屏适配（提前，原属 v2.0.0）

v1.7.3 的 `PageFit` 已经照顾了宽度（不同屏宽分档留白）和高度（一屏看全），
但**固定 dp 的底部留白**还散落着 8 处（阅读页 6 处、列表页 2 处，如 `+ 150.dp`、`+ 96.dp`）：

- 分屏下半屏（高度仅 300~400dp）：底部留白吃掉一半屏高，浮动工具条会压住正文
- 横屏 / 折叠屏展开：内容区很宽很矮，需要双栏（左 TOC + 右正文，正文限宽 720dp）
- 拖拽改变分屏比例时不得重建页面、不得丢阅读位置

### v2.0.0 · 多语言

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

3. **heap 分级渲染预算** —— `ReaderViewModel` 已按 heap 的 1/4 做缓存上限（32–192MB），
   下一步是把 `TileLimits` 常量也按设备内存分级；但 `PageTiling` 是纯函数、被大量单测覆盖，改动要极小心
4. **扫描件图像增强** —— 对比度 / 去底色，需在 GPU/CPU 上做后处理，性能风险大

原计划的"缩略图预生成"已在 v1.7.0 完成。

---

## 八、遗留待办（非阻塞）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| P1-4 元数据编辑 | 未开始 | 见 v1.8.0 技术选型 |
| P2-3 Lint 检测器崩溃 | ✅ **已解决** | 5 个 Compose 检测器 disable + `abortOnError=true`，已进 CI |
| 真机验证玻璃 | 已关闭 | 用户已在真机反馈并迭代多轮 |

---

## 九、已知限制

1. **渲染**：极端长页（如 50:1 的 CAD 图）会整体等比降级，保宽高比但牺牲清晰度 —— 这是 4 块上限的必然结果
2. **玻璃**：无 AGSL 支持的设备退化为纯模糊 + 底色，强度全局归零
3. **Robolectric**：无法加载 pdfium 的 `.so`，真实渲染只能在真机 instrumented test 验证
4. **签名**：`keystore.properties` 与 `keystore/` 不入库，密钥一旦丢失无法覆盖升级旧版本
5. **Lint 覆盖**：5 个 Compose 检查因上游兼容性缺失而关闭。根因修复要等 Compose 发布适配 Kotlin 2.1 的 lint 包，届时删掉那几行 `disable` 即可
6. **国际化**：全部 UI 文案硬编码中文，`HardcodedText` 已 disable 以避免淹没真实问题


---


## 附录：历史版本详情

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

