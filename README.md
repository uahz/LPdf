# LPdf 1.5.0 · Android PDF 阅读器

纯本地优先的 PDF 阅读器，界面采用 iOS 26 液态玻璃（Liquid Glass）风格。
Kotlin + Jetpack Compose，minSdk 33（Android 13）。

## 下载安装

**只发布一个包：`LPdf-x.y.z-release.apk`（已签名）。**

- 最新版：[Releases](https://github.com/uahz/LPdf/releases/latest)
- 网盘、本地 `dist/` 目录中也只有 release 包

debug 包**不对外发布** —— 它有 `.debug` 包名后缀、包体大一倍、且未做资源收缩，
放出来只会让用户不知道该下哪个。开发排查请用 CI 的 Actions artifacts。

```bash
adb install -r dist/LPdf-1.5.0-release.apk
```

签名信息：RSA 4096，有效期至 2056 年，SHA-256 `1590d022df4b05e6b76f268fa096b4ae8a2fc18e5f22eca18eb692aa7f1e2851`。
自 1.1.0 至今签名未变，可跨版本覆盖升级。

## 自行构建

```bash
export JAVA_HOME=/opt/jdk21
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleRelease       # 产物 app/build/outputs/apk/release/
./gradlew :app:assembleDebug         # 仅供本地调试，不发布
./gradlew :app:testDebugUnitTest     # 119 个单测
```

**签名配置**：`keystore.properties` 与 `keystore/` 均不入库。首次构建若无这两者，
release 仍会产出但处于未签名状态（不会构建失败）。要出签名包，二选一：

- 复制 `keystore.properties.example` 为 `keystore.properties` 并填入口令，密钥库放在 `keystore/lpdf-release.jks`
- 或设置环境变量 `LPdf_KEYSTORE_PATH` / `LPdf_KEYSTORE_PASSWORD` / `LPdf_KEY_ALIAS` / `LPdf_KEY_PASSWORD`

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
- **Lint 被关闭**：AGP 8.7.3 自带的 `NullableMutableLiveData` 检测器在 JDK 21 上抛 `IncompatibleClassChangeError`，已在 `app/build.gradle.kts` 中 `disable` + `abortOnError = false`，计划 v2.0 修复。
