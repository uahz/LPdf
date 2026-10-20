# LPdf 1.2.0 · Android PDF 阅读器

纯本地优先的 PDF 阅读器，界面采用 iOS 26 液态玻璃（Liquid Glass）风格。
Kotlin + Jetpack Compose，minSdk 33（Android 13）。

## 直接安装

```
dist/LPdf-1.2.0-release.apk   # 已签名，推荐安装
dist/LPdf-1.2.0-debug.apk     # 已签名，applicationId 带 .debug 后缀，可与正式版共存
```

```bash
adb install -r dist/LPdf-1.2.0-release.apk
```

签名信息：RSA 4096，有效期至 2056 年，SHA-256 `1590d022df4b05e6b76f268fa096b4ae8a2fc18e5f22eca18eb692aa7f1e2851`。

## 自行构建

```bash
export JAVA_HOME=/opt/jdk21
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 17 个单测
```

**签名配置**：`keystore.properties` 与 `keystore/` 均不入库。首次构建若无这两者，
release 仍会产出但处于未签名状态（不会构建失败）。要出签名包，二选一：

- 复制 `keystore.properties.example` 为 `keystore.properties` 并填入口令，密钥库放在 `keystore/lpdf-release.jks`
- 或设置环境变量 `LPdf_KEYSTORE_PATH` / `LPdf_KEYSTORE_PASSWORD` / `LPdf_KEY_ALIAS` / `LPdf_KEY_PASSWORD`

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
| **P2 阅读页** | PDFium 渲染、连续滚动 / 单页两种模式、双指缩放（1–5x，上限可配）、目录抽屉、页码胶囊、玻璃 Dock、编辑工具岛（高亮/墨迹/形状/批注 + 撤销/清空/导出）、阅读进度记忆、阅读常亮 |
| **P3 设置页** | 6 组共 24 项：阅读体验、外观与玻璃（模糊/折射/动效等级）、编辑（保存方式/高亮色/笔宽/撤销步数）、存储（缓存清理/清空记录/清空标注）、隐私与网络（离线模式/仅 Wi-Fi/链接导入）、通用 |
| **P4 工具箱** | 三档压缩、按页码范围拆分、单页 / 全部页导出 PNG·JPG、图片转 PDF（A4 或原尺寸） |

## 液态玻璃实现

- **组件库**：`Kyant0/AndroidLiquidGlass 1.0.0-rc01`，`drawBackdrop` + `lens`（AGSL 实时折射）+ `blur`
- **采样源**：Compose `GraphicsLayer`，**不使用 PixelCopy 截屏**
- **层级**：`LiquidGlassScope` 把内容层录进 GraphicsLayer，玻璃层单独放在覆盖层，避免「玻璃采到自己」的递归
- **视觉锁定**：圆角 28dp 超椭圆、模糊 10dp、折射 0.12、高光 0.15、蒙版 0.35
- **动效**：全部为欠阻尼弹簧（`Motion` 令牌表，11 组），无 tween

## 标注扁平化的取舍

导出走的是**栅格方案**：逐页 PDFium 渲染底图 → 叠加标注 → 组装新 PdfDocument。

- ✅ 实现确定，跨阅读器显示完全一致
- ❌ 源文件文字变为位图，**不可再选中 / 复制 / 搜索**，体积也会变大

矢量方案（改写 PDF 内容流）能保留可选文字且体积更小，但需要完整 PDF 对象模型操作，
当前无可靠库支撑，风险不可控。若后续确有「保留可选文字」需求，应向矢量方案演进。

## 目录结构

```
app/src/main/java/com/lpdf/reader/
├── core/           模型、错误、调度器、格式化、网络
├── data/
│   ├── pdf/        PdfiumEngine（读）、PdfWriterEngine（写）、PdfFlattener（标注烧录）、PdfValidator（验身）
│   ├── local/      MediaStore、Prefs(DataStore)、缩略图、标注
│   └── network/    链接导入、更新检查（不内置服务端地址）
└── ui/
    ├── glass/      液态玻璃组件库（13 个文件，图标全自绘）
    ├── page/       list / reader / settings / tools
    └── theme/      颜色、字体、圆角、弹簧令牌
```

## 已知限制

- **未在真机 / 模拟器验证**：构建环境无 KVM，Android 模拟器无法启动。已验证的是：编译通过、R8 通过、17 个 JVM 测试通过（含标注坐标 JSON 往返、扁平化坐标换算）。**玻璃的 AGSL 折射效果只能在真机上看，请实际安装确认。**
- **标注导出未做端到端验证**：依赖 PDFium native so 与 PdfDocument 写入，JVM 侧只能覆盖坐标换算，实际导出结果需真机确认。
- **形状只画矩形**：`Annotation.Shape.kind` 支持 `RECT`/`OVAL`/`ARROW`，但阅读页只渲染 RECT，导出同步了这一行为。
- **表单填写 / 签名板 / 水印 / 元数据编辑**：引擎与数据模型已具备，UI 尚未接入，属 v1.3 / v2.0 范围。
- **Lint 被关闭**：AGP 8.7.3 自带的 `NullableMutableLiveData` 检测器在 JDK 21 上抛 `IncompatibleClassChangeError`，已在 `app/build.gradle.kts` 中 `disable` + `abortOnError = false`，计划 v2.0 修复。
