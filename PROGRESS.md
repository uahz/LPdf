# LPdf 开发进度

> 更新时间：2026-10-21 21:00
> 当前版本：**v1.5.2**（versionCode 152）
> 仓库：https://github.com/uahz/LPdf

---

## 一、当前状态总览

| 指标 | 数值 |
| --- | --- |
| 当前版本 | v1.5.2 |
| versionCode | 152 |
| 最近发布 | [v1.5.2](https://github.com/uahz/LPdf/releases/tag/v1.5.2)（2026-10-21） |
| 单测数量 | **135 项，全绿** |
| 源码规模 | 51 个 Kotlin 文件 / 13195 行 |
| 测试文件 | 23 个 |
| 签名 | RSA 4096，SHA-256 `1590d022…`，自 1.1.0 未变，可跨版本覆盖升级 |

### 版本时间线

| 版本 | 主题 | 状态 |
| --- | --- | --- |
| v1.2.0 | 编辑能力 + 设置页 | ✅ 已发布 |
| v1.3.0 | 稳定性 | ✅ 已发布 |
| v1.3.2 / 1.3.4 / 1.3.5 | 修崩 / 修转圈 / 玻璃与首页重叠 | ✅ 已发布 |
| v1.4.0 | iOS 玻璃药丸底栏 + 可拖拽长按浮卡 | ✅ 已发布 |
| v1.5.0 | 任意尺寸分块渲染 + 液态玻璃对齐 Apple | ✅ 已发布 |
| **v1.5.1 / v1.5.2** | **渲染宽度根因修复** | ✅ **已发布** |
| v1.6.0 | 五 Tab 导航改版 | ⬜ 待开始 |
| v1.7.0 | 阅读体验增强 | ⬜ 待开始 |
| v2.0.0 | 性能与大文件 | ⬜ 待开始 |

---

## 二、v1.5.x 已完成的工作（本轮）

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

## 三、当前阻塞项

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

## 四、后续版本计划

### v1.6.0 · 五 Tab 导航改版（下一个）

**现状**：只有 4 个路由（LIST / READER / TOOLS / SETTINGS），无 Tab 结构。

| 项 | 内容 |
| --- | --- |
| 底栏 | 五 Tab：首页 / 文档 / 书签 / 发现 / 我的 |
| 新增页面 | 书签页（跨文档聚合）、发现页、我的页 |
| 玻璃 | 选中指示器用玻璃滑块，转场带动效 |
| 迁移 | 现有列表页挂「文档」，设置页挂「我的」 |
| 规范依据 | `pdf-liquid.txt` 五 Tab 底栏 + 分层结构 |

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

## 五、遗留待办（非阻塞）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| P1-4 元数据编辑 | 未开始 | 改标题/作者等 |
| P2-3 Lint 检测器崩溃 | 未开始 | AGP 8.7.3 自带 `NonNullableMutableLiveDataDetector` 在 JDK 21 抛 `IncompatibleClassChangeError`，已临时关闭该条规则 |
| 真机验证玻璃 | 已关闭 | 用户已在真机反馈并迭代多轮 |

---

## 六、已知限制

1. **渲染**：极端长页（如 50:1 的 CAD 图）会整体等比降级，保宽高比但牺牲清晰度 —— 这是 4 块上限的必然结果
2. **玻璃**：无 AGSL 支持的设备退化为纯模糊 + 底色，强度全局归零
3. **Robolectric**：无法加载 pdfium 的 `.so`，真实渲染只能在真机 instrumented test 验证
4. **签名**：`keystore.properties` 与 `keystore/` 不入库，密钥一旦丢失无法覆盖升级旧版本
