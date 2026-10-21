# LPdf 1.3.2 · Android PDF 阅读器

纯本地优先的 PDF 阅读器，界面采用 iOS 26 液态玻璃（Liquid Glass）风格。
Kotlin + Jetpack Compose，minSdk 33（Android 13）。

## 下载安装

**只发布一个包：`LPdf-x.y.z-release.apk`（已签名）。**

- 最新版：[Releases](https://github.com/uahz/LPdf/releases/latest)
- 网盘、本地 `dist/` 目录中也只有 release 包

debug 包**不对外发布** —— 它有 `.debug` 包名后缀、包体大一倍、且未做资源收缩，
放出来只会让用户不知道该下哪个。开发排查请用 CI 的 Actions artifacts。

```bash
adb install -r dist/LPdf-1.3.2-release.apk
```

签名信息：RSA 4096，有效期至 2056 年，SHA-256 `1590d022df4b05e6b76f268fa096b4ae8a2fc18e5f22eca18eb692aa7f1e2851`。
自 1.1.0 至今签名未变，可跨版本覆盖升级。

## 自行构建

```bash
export JAVA_HOME=/opt/jdk21
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleRelease       # 产物 app/build/outputs/apk/release/
./gradlew :app:assembleDebug         # 仅供本地调试，不发布
./gradlew :app:testDebugUnitTest     # 59 个单测
```

**签名配置**：`keystore.properties` 与 `keystore/` 均不入库。首次构建若无这两者，
release 仍会产出但处于未签名状态（不会构建失败）。要出签名包，二选一：

- 复制 `keystore.properties.example` 为 `keystore.properties` 并填入口令，密钥库放在 `keystore/lpdf-release.jks`
- 或设置环境变量 `LPdf_KEYSTORE_PATH` / `LPdf_KEYSTORE_PASSWORD` / `LPdf_KEY_ALIAS` / `LPdf_KEY_PASSWORD`

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

- **未在真机 / 模拟器验证**：构建环境无 KVM，Android 模拟器无法启动。已验证的是：编译通过、R8 通过、26 个 JVM 测试通过（含标注坐标 JSON 往返、扁平化坐标换算、水印几何计算）。**玻璃的 AGSL 折射效果只能在真机上看，请实际安装确认。**
- **标注导出未做端到端验证**：依赖 PDFium native so 与 PdfDocument 写入，JVM 侧只能覆盖坐标换算，实际导出结果需真机确认。
- **形状只画矩形**：`Annotation.Shape.kind` 支持 `RECT`/`OVAL`/`ARROW`，但阅读页只渲染 RECT，导出同步了这一行为。
- **表单填写 / 元数据编辑**：引擎与数据模型已具备，UI 尚未接入，属 v2.0 范围。
- **签名只支持图片形式**：手写签名保存为 PNG 后作为图片标注叠加，不是 PDF 数字签名（不含证书与时间戳），不具备法律意义上的防篡改能力。
- **搜索不做跨页合并**：跨页断行的词会被拆成两处命中，属于文本层特性，暂未做后处理。
- **Lint 被关闭**：AGP 8.7.3 自带的 `NullableMutableLiveData` 检测器在 JDK 21 上抛 `IncompatibleClassChangeError`，已在 `app/build.gradle.kts` 中 `disable` + `abortOnError = false`，计划 v2.0 修复。
