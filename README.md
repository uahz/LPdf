# LPdf 1.1.0 · Android PDF 阅读器

纯本地优先的 PDF 阅读器，界面采用 iOS 26 液态玻璃（Liquid Glass）风格。
Kotlin + Jetpack Compose，minSdk 33（Android 13）。

## 直接安装

```
dist/LPdf-1.1.0-debug.apk          # 已签名，可直接 adb install
dist/LPdf-1.1.0-release-unsigned.apk  # R8 混淆版，需自行用 apksigner 签名
```

```bash
adb install -r dist/LPdf-1.1.0-debug.apk
```

## 自行构建

```bash
export JAVA_HOME=/opt/jdk21
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 7 个单测
```

## 功能

| 页面 | 内容 |
|---|---|
| **P1 文件列表** | MediaStore 扫描、SAF 目录授权、单文件导入、HTTPS 链接导入、搜索、5 种筛选、3 种排序、长按玻璃预览卡片、损坏/加密角标、密码解锁面板 |
| **P2 阅读页** | PDFium 渲染、连续滚动 / 单页两种模式、双指缩放（1–5x，上限可配）、目录抽屉、页码胶囊、玻璃 Dock、编辑工具岛（高亮/墨迹/形状/批注）、阅读进度记忆、阅读常亮 |
| **P3 设置页** | 6 组共 24 项：阅读体验、外观与玻璃（模糊/折射/动效等级）、编辑（保存方式/高亮色/笔宽/撤销步数）、存储（缓存清理/清空记录/清空标注）、隐私与网络（离线模式/仅 Wi-Fi/链接导入）、通用 |
| **P4 工具箱** | 三档压缩、按页码范围拆分、单页 / 全部页导出 PNG·JPG、图片转 PDF（A4 或原尺寸） |

## 液态玻璃实现

- **组件库**：`Kyant0/AndroidLiquidGlass 1.0.0-rc01`，`drawBackdrop` + `lens`（AGSL 实时折射）+ `blur`
- **采样源**：Compose `GraphicsLayer`，**不使用 PixelCopy 截屏**
- **层级**：`LiquidGlassScope` 把内容层录进 GraphicsLayer，玻璃层单独放在覆盖层，避免「玻璃采到自己」的递归
- **视觉锁定**：圆角 28dp 超椭圆、模糊 10dp、折射 0.12、高光 0.15、蒙版 0.35
- **动效**：全部为欠阻尼弹簧（`Motion` 令牌表，11 组），无 tween

## 目录结构

```
app/src/main/java/com/lpdf/reader/
├── core/           模型、错误、调度器、格式化、网络
├── data/
│   ├── pdf/        PdfiumEngine（读）、PdfWriterEngine（写）、PdfValidator（验身）
│   ├── local/      MediaStore、Prefs(DataStore)、缩略图、标注
│   └── network/    链接导入、更新检查（不内置服务端地址）
└── ui/
    ├── glass/      液态玻璃组件库（13 个文件，图标全自绘）
    ├── page/       list / reader / settings / tools
    └── theme/      颜色、字体、圆角、弹簧令牌
```

## 已知限制

- **未提供签名 release APK**：`app-release-unsigned.apk` 需自行签名后才能安装。
- **未在真机 / 模拟器验证**：构建环境无 KVM，Android 模拟器无法启动。目前已验证的是：编译通过、R8 通过、7 个 JVM 测试通过（含标注坐标 JSON 往返）。玻璃的 AGSL 折射效果只能在真机上看，请实际安装确认。
- **表单填写 / 签名 / 水印 / 元数据编辑 / 合并**：引擎侧已实现（`PdfWriterEngine`），UI 尚未接入，属 V1.2 范围。
- **Lint 被关闭**：AGP 8.7.3 自带的 `NullableMutableLiveData` 检测器在 JDK 21 上抛 `IncompatibleClassChangeError`，已在 `app/build.gradle.kts` 中 `disable` + `abortOnError = false`。
