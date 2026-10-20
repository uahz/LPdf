# LPdf — 安卓 PDF 阅读与编辑工具 · 文档合集

> **文档版本**：V1.1（范围变更：允许联网 + 增加 PDF 编辑套件 + 新增设置页）
> **状态**：待评审　**编制日期**：2026-10-20
> **包含四部分**：① PRD 产品需求文档　② 开发约束规范文档　③ iOS26 液态玻璃 UI 视觉规范文档　④ UI 绘图提示词（用于生成效果图确认）

---

## 目录

- [第一部分　PRD 产品需求文档](#第一部分prd-产品需求文档)
- [第二部分　开发约束规范文档](#第二部分开发约束规范文档)
- [第三部分　iOS26 液态玻璃 UI 视觉规范文档](#第三部分ios26-液态玻璃-ui-视觉规范文档)
- [第四部分　UI 绘图提示词](#第四部分ui-绘图提示词)

---

## V1.1 变更说明（相对 V1.0）

| # | 变更点 | V1.0 | V1.1 |
|---|---|---|---|
| 1 | 网络 | 无网络权限、纯离线 | **允许联网**（仅用于在线文档/链接抓取、页面渲染；无广告、无上传、无统计） |
| 2 | 编辑能力 | 禁止编辑/批注/OCR | **新增编辑套件**：页面管理、标注批注、表单、签名、合并拆分、压缩转图、图片转 PDF；OCR 仍不做 |
| 3 | 页面数 | 2 页 | **3 页**：列表首页 / 阅读页 / **设置页**（另含 1 个编辑子页） |
| 4 | 出版取向 | 极简阅读器 | **"阅读 + 轻办公"工具**，功能完整度对齐主流 PDF 应用 |
| 5 | 隐私 | 纯本地 | **本地优先**：文件默认不上传；联网行为显式可关；设置页提供"仅 Wi-Fi / 完全离线模式" |
| 6 | 权限 | 仅 READ_MEDIA_DOCUMENTS | 增加 `INTERNET`、`ACCESS_NETWORK_STATE`；保存编辑结果走 SAF，仍不申请写存储 |

> **保留不变的硬约束**：Kotlin + Compose、PDFium 内核、Kyant0 液态玻璃、minSdk 33、无广告/无推送/无开屏、禁止原生 Toast/AlertDialog、禁止 Material 块状 UI。

---

# 第一部分　PRD 产品需求文档

## 1. 产品概述

### 1.1 基本信息

| 项目 | 内容 |
|---|---|
| 产品名称 | **LPdf** |
| 产品定位 | 以液态玻璃为视觉语言的 Android PDF 阅读 + 编辑工具；本地优先，联网可选 |
| 目标平台 | Android 13（API 33）及以上；**明确放弃 Android 12 及更低版本** |
| 技术底座 | Kotlin + Jetpack Compose；PDF 渲染 PDFium（barteksc android-pdf-viewer） |
| UI 底座 | 开源库 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) |
| 网络模型 | 允许联网，但**无广告、无推送、无数据上报**；文件默认不上传，联网可一键关闭 |
| 安装包形态 | 单 APK，含网络权限；支持"完全离线模式"（设置页开关，开启后禁用所有网络请求） |
| 核心卖点 | iOS26 风格**实时液态玻璃**：AGSL RuntimeShader GPU 实时光学折射，非静态磨砂；阅读 + 编辑一体化；文件默认不上云 |

### 1.2 产品定位一句话

> **一块会呼吸的玻璃，装下你所有的 PDF——读得舒服，改得顺手，文件留在自己手机里。**

### 1.3 设计原则

1. **本地优先**：所有 PDF 处理（渲染、编辑、压缩、合并）均在设备端完成；联网仅作为可选增强。
2. **玻璃即语言**：页面结构、弹层、开关、滑块全部是液态玻璃；无 Material 块状 UI。
3. **功能完整但克制**：编辑能力对标主流工具（页面管理、标注、表单、签名、导出），但**不做** OCR、不做云协作、不做账号体系。
4. **可预期**：每个编辑动作都可撤销；保存前总是写出新文件，绝不静默覆盖原文件。

### 1.4 目标用户与场景

| 用户画像 | 核心诉求 | 对应功能 |
|---|---|---|
| 职场用户 | 签合同、改表单、合并材料 | 标注、签名、表单填写、合并拆分 |
| 学生/研究者 | 读论文、做笔记、摘录 | 高亮、下划线、便签、文本复制、目录跳转 |
| 行政/HR | 批量整理、压缩后发邮件 | 页面管理、压缩、转图片、拆分 |
| 隐私敏感用户 | 文件不上云 | 默认本地处理 + 完全离线模式 |
| 设计审美用户 | iOS 质感动效 | 液态玻璃全套组件 |

### 1.5 版本规划

| 版本 | 范围 |
|---|---|
| **V1.0** | 阅读器基础：扫描、阅读、缩放、Dock、玻璃组件、设置页基础项 |
| **V1.1（本期）** | 编辑套件：页面管理、标注批注、表单、签名、合并拆分、压缩、导出图片、图片转 PDF；设置页完整化 |
| V2.0 | 大文件性能优化、扫描件增强、多窗口；**不做 OCR** |

---

## 2. 页面结构与导航

### 2.1 页面清单（3 个主页面 + 1 个编辑子页）

```
                    ┌──────────────┐
                    │ P1 文件列表首页 │◀────────┐
                    └──────┬───────┘         │
            点击 PDF        │        ⚙ 齿轮    │ 返回
                    ┌──────▼───────┐   ┌─────┴─────┐
                    │ P2 阅读页     │   │ P3 设置页  │
                    └──────┬───────┘   └───────────┘
                 ✎ 编辑模式  │  （Dock 扩展）
                    ┌──────▼───────┐
                    │ P2-E 编辑工作台│
                    └──────────────┘
```

| 编号 | 页面 | 核心职责 |
|---|---|---|
| P1 | PDF 文件列表首页 | 扫描/筛选/排序、选择目录、长按预览、选中弹出玻璃框、进入设置 |
| P2 | PDF 阅读页 | 渲染、缩放、页码、玻璃 Dock（含编辑入口） |
| P2-E | 编辑工作台 | 标注工具栏 + 页面管理 + 表单 + 签名（作为 P2 的覆盖层/子页，非独立 Tab） |
| P3 | 设置页 | 阅读、外观、编辑、存储、隐私与网络、关于 |

> **仍然禁止**：开屏页、引导页、推荐页、付费页、登录/账号页、云同步页。

### 2.2 全局层级规则（自上而下）

```
┌───────────────────────────────────────────────┐
│ L5  玻璃模态层（编辑工具栏 / 键盘浮层 / 确认卡片）      │
├───────────────────────────────────────────────┤
│ L4  玻璃悬浮组件层（Dock / 悬浮框 / 拖拽卡片）          │
├───────────────────────────────────────────────┤
│ L3  蒙版层（柔和暗化 0.35，仅覆盖非玻璃区域）           │
├───────────────────────────────────────────────┤
│ L2  内容层（P1 列表 / P2 PDF 画布 / P3 设置列表）      │
├───────────────────────────────────────────────┤
│ L1  背景层（页面底色 / 柔和光晕）                      │
└───────────────────────────────────────────────┘
```

- PDF 画布必须位于 L2；蒙版 L3 在画布之上；玻璃永远在 L3 之上。
- 编辑模式下：标注图元绘制在 PDF 画布之上、L3 蒙版之下（属于 L2 的一个子层 L2.5）。

---

## 3. 功能需求

### 3.1 F1　文件与列表（P1）

#### F1-01　自动扫描本地 PDF　`P0`

1. `MediaStore.Files` 查询 `MIME_TYPE="application/pdf"` 或 `_display_name LIKE '%.pdf'`。
2. 列表项展示：**文件名**、**大小**、**修改时间**；损坏条目标注"可能已损坏"。
3. 扫描异步、分批（每批 30 条）刷新；首屏 ≤ 800ms。
4. 空态显示玻璃卡片 + "选择目录"按钮。

#### F1-02　手动选择目录　`P0`

1. `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`（不申请 `MANAGE_EXTERNAL_STORAGE`）。
2. 与 MediaStore 结果合并去重；手动目录文件带玻璃角标。
3. 授权失效时移除条目 + 顶部玻璃提示条。

#### F1-03　损坏 PDF 过滤　`P0`

1. 异步校验：文件头 `%PDF-` 魔数 + trailer + 页数探测；3s 超时判失败。
2. 失败不进入阅读页，玻璃提示条 + "从列表隐藏"按钮；不删除文件。
3. 加密 PDF：V1.1 **支持密码输入**（玻璃数字/文字键盘浮层），错误密码提示"密码不正确"。

#### F1-04　列表筛选与排序　`P0`（V1.1 新增）

| 项 | 规则 |
|---|---|
| 排序 | 修改时间↓（默认）／名称 A→Z／大小↓／最近阅读 |
| 筛选 | 全部 / 最近阅读 / 已标注 / 已加密 / 手动目录 |
| 切换方式 | 顶部标题右侧玻璃胶囊（显示当前排序/筛选），点击弹出玻璃下拉浮层 |

#### F1-05　搜索　`P0`（V1.1 新增）

- 顶部下拉出玻璃搜索条：按文件名实时过滤（本地模糊匹配，200ms 防抖）。
- 不做全文内容搜索（留待 V2.0）。

#### F1-06　文件操作　`P1`（V1.1 新增）

通过**选中悬浮框**或**长按拖拽卡片**触发，动作列表为玻璃纵向菜单：

| 动作 | 说明 |
|---|---|
| 打开 | 进入阅读页 |
| 重命名 | 玻璃输入浮层，SAF 或 MediaStore 重命名（需 `MediaStore.createWriteRequest` 授权单个文件） |
| 复制 / 移动 | SAF 目标目录选择器 |
| 分享 | `ACTION_SEND` 系统分享面板（仅本地文件流） |
| 删除 | 玻璃二次确认卡片（红字"删除"），`createDeleteRequest` |
| 详情 | 路径、大小、页数、创建/修改时间、是否加密 |
| 隐藏 | 仅本地隐藏 |

> 所有写操作（重命名/删除/移动）通过 `MediaStore.createWriteRequest` / `createDeleteRequest` 弹出**系统**授权确认，这是唯一允许出现的系统级对话框。

---

### 3.2 F2　PDF 阅读（P2）

#### F2-01　垂直滑动翻页　`P0`

- 连续垂直滚动，页间距 16dp；滚动惰性 + 低清占位→高清升级。
- 支持单页模式与连续模式（设置页可切，默认连续）。

#### F2-02　双指缩放与拖拽　`P0`

- 缩放 1.0x~5.0x（设置页可调上限 2x/3x/5x）；缩放中心跟随焦点；放大后单指平移。

#### F2-03　页码显示与跳转　`P0`

- 右上角玻璃胶囊 `12 / 356`；点击/长按进入页码跳转浮层，校验 1~总页数。

#### F2-04　大纲目录　`P1`（V1.1 新增）

- 解析 PDF 书签（Outline）；Dock 目录按钮 → 左侧玻璃抽屉，树形展开，点击跳页。

#### F2-05　文字选择与复制　`P0`（V1.1 新增）

- 长按文字 → 玻璃选择浮层（复制 / 高亮 / 搜索 / 翻译入口预留）。
- 选择手柄为玻璃小圆点；选区高亮为 20% 主色。

#### F2-06　阅读状态记忆　`P0`

- 记录每个文件的最后页码、缩放倍率、阅读模式（DataStore，本地）。

#### F2-07　屏幕常亮　`P1`

- 阅读页可保持屏幕常亮（设置项，默认关；使用 `FLAG_KEEP_SCREEN_ON`，无需权限）。

---

### 3.3 F3　PDF 编辑套件（P2-E）

> **通用编辑规范**
> - 所有编辑**无损叠加**：标注层单独存储为本地 JSON（`documentId + mtime` 为键），不直接改写 PDF 原始流；"导出/另存"时才栅格化或写入注释字典。
> - 支持**无限撤销/重做**（单文档会话内，上限 50 步）。
> - 所有写盘操作异步执行，显示玻璃进度环（可取消）。
> - 保存策略：默认"另存为新文件"，文件名后缀 `_edited`；原文件不动。

#### F3-01　页面管理　`P0`

| 能力 | 说明 |
|---|---|
| 缩略图网格 | 玻璃底 + 双列网格缩略图，长按进入多选 |
| 旋转 | 单页/多页 90° 步进 |
| 删除 | 多选删除，二次确认 |
| 重排 | 长按拖动缩略图，弹簧吸附落位（复用 C4 拖拽动效） |
| 复制页 | 多选复制到指定位置 |
| 插入空白页 | 指定位置插入 A4 空白页 |
| 从其他 PDF 插入 | SAF 选择源 PDF + 页范围（如 `1-3,7`） |
| 提取页 | 多选导出为**新 PDF** |

#### F3-02　标注与批注　`P0`

| 工具 | 规格 |
|---|---|
| 高亮 | 4 色（黄/绿/蓝/粉），20% 透明度，混合模式 Multiply |
| 下划线 / 删除线 | 主色 2dp，随文字行移动 |
| 手绘 | 自由笔迹，笔宽 1/2/4dp，可选毛毡/圆珠笔触 |
| 形状 | 矩形/圆/箭头，可选描边或填充 |
| 便签 | 点击位置生成玻璃小图钉 + 弹出注释输入浮层 |
| 文本框 | 插入文字，字号/颜色/对齐可调 |
| 评论列表 | 侧边玻璃面板，按页列出所有批注，点击定位 |
| 橡皮擦 | 点选删除单个批注 |

**交互**：选中标注可拖动、缩放、旋转（手柄），松手弹簧回弹；长按弹出玻璃工具条（颜色/粗细/删除/层级）。

#### F3-03　表单填写　`P0`

- 解析 AcroForm 域：文本框、复选框、单选、下拉、签名域。
- 点击域弹出玻璃输入浮层或玻璃选项列表（复用同一套浮层组件）。
- 支持"扁平化"导出（将填写内容烧进页面）。

#### F3-04　签名　`P0`

- 手写签名板（玻璃画布，笔迹平滑），可保存最多 3 个签名模板（本地）。
- 插入后可拖动、缩放、旋转；可设置仅当前文档可用。
- 支持日期戳自动插入。

#### F3-05　合并 / 拆分　`P0`

- 合并：多选 2~20 个 PDF，拖拽排序后合并为新 PDF。
- 拆分：按页范围 / 每 N 页 / 提取奇数偶页，输出多个 PDF 到指定目录。

#### F3-06　压缩　`P1`

- 三档：轻度（画质优先，约 80%）、标准（默认，约 50%）、强力（体积优先，约 25%）。
- 实现：PDFium 逐页渲染 → JPEG 重编码（质量 85/65/45）→ 重建 PDF；玻璃进度环显示百分比，可取消。

#### F3-07　导出图片　`P1`

- 单页/多页/全部导出 PNG 或 JPG，150/200/300 DPI 可选，输出到用户选择的目录。

#### F3-08　图片转 PDF　`P0`

- 从相册/文件选择 1~50 张图片（**经 SAF 或相册选择器，不申请读取相册权限**），可排序、裁剪边框，生成 A4 或原尺寸 PDF。

#### F3-09　添加水印　`P2`

- 文字水印：内容、字号、透明度（10%~50%）、角度、平铺开关。

#### F3-10　元数据与安全　`P2`

- 修改标题/作者/主题/关键词；设置打开密码（AES-128，仅对导出的新文件生效）。

> **明确不做**：OCR、PDF/A 转换、数字证书签名（CA）、AI 摘要、云协作、在线模板库。

---

### 3.4 F4　设置页（P3）

> 入口：P1 标题右侧玻璃齿轮按钮。整页为玻璃分组列表（iOS 设置风格），分组卡片为液态玻璃，开关为 iOS 风格玻璃拨杆（Kyant 组件）。

#### 4.1 阅读

| 设置项 | 控件 | 默认 | 说明 |
|---|---|---|---|
| 默认打开方式 | 单选 | 连续滚动 | 连续滚动 / 单页翻页 |
| 缩放上限 | 单选 | 5x | 2x / 3x / 5x |
| 双指缩放时自动旋转 | 开关 | 开 | 横屏内容自动适配 |
| 阅读时保持屏幕常亮 | 开关 | 关 | — |
| 自动记忆阅读位置 | 开关 | 开 | 关闭后每次从第 1 页开始 |
| 自动隐藏工具栏 | 开关 | 开 | Dock 4s 自动隐藏 |
| 翻页音效/震动 | 开关 | 关 | 使用系统触感反馈 |

#### 4.2 外观

| 设置项 | 控件 | 默认 |
|---|---|---|
| 主题 | 三段玻璃分段控件 | 跟随系统 / 浅色 / 深色 |
| 液体玻璃强度 | 玻璃滑块（模糊 0~20dp） | 10dp（锁定为基准值） |
| 玻璃折射强度 | 玻璃滑块（0~0.20） | 0.12 |
| 动效等级 | 单选 | 完整 / 减弱 / 关闭 |
| 强调色 | 色环选择 | 跟随内容采样（默认） |
| 列表密度 | 单选 | 标准 / 紧凑 |

> 外观参数仅影响本机渲染，不改变规范基准值；恢复默认按钮置于分组末尾。

#### 4.3 编辑

| 设置项 | 控件 | 默认 |
|---|---|---|
| 默认保存方式 | 单选 | 另存新文件 / 覆盖原文件（需二次确认） |
| 保存位置 | 目录选择 | 首次另存时询问 |
| 自动保存标注 | 开关 | 开（每 30s 或退出时） |
| 默认高亮颜色 | 4 色圆点 | 黄 |
| 手绘笔默认粗细 | 分段控件 | 2dp |
| 签名模板管理 | 跳转玻璃浮层 | 最多 3 个 |
| 撤销步数上限 | 滑块 | 50 |

#### 4.4 存储与缓存

| 设置项 | 说明 |
|---|---|
| 缩略图缓存 | 显示占用体积，可"清理" |
| 页面位图缓存策略 | 自动（默认）/ 小 / 大 |
| 临时文件 | 显示占用，自动清理超过 7 天的临时导出文件 |
| 缓存位置 | 应用私有目录（不可改） |
| 一键清理 | 玻璃确认卡片 |

#### 4.5 隐私与网络（V1.1 核心新增）

| 设置项 | 控件 | 默认 | 说明 |
|---|---|---|---|
| **完全离线模式** | 开关 | 关 | 开启后所有网络请求直接失败，UI 显示"离线模式"玻璃角标 |
| 仅 Wi-Fi 下联网 | 开关 | 开 | 移动网络下不发起任何请求 |
| 允许在线预览链接 | 开关 | 开 | 输入 http(s) 链接抓取 PDF 到本地（见 5.1） |
| 清除本地数据 | 玻璃确认卡片 | — | 清空阅读记录、标注、缓存（不删用户 PDF） |
| 隐私说明 | 静态文本页（玻璃卡片） | — | 明确写明：不上传文件、不收集日志、无统计 SDK |

#### 4.6 通用

| 设置项 | 说明 |
|---|---|
| 语言 | 跟随系统 / 简体中文 / English |
| 触感反馈 | 开关，默认开 |
| 无障碍 | 大字体适配提示、减少动效联动说明 |
| 关于 LPdf | 版本号、开源许可（PDFium / Kyant0 许可证）、隐私政策 |
| 检查更新 | **仅提示**，不自动下载；采用"手动检查 + 跳转应用市场"方式（V1.1 可不实现商店逻辑） |

---

### 3.5 F5　液态玻璃交互组件（全部基于 Kyant0/AndroidLiquidGlass）

> 全部组件使用 `RuntimeShader`（AGSL）GPU 实时渲染：采样背景 → 透镜位移 → 边缘色散 → 高光叠加。**非静态磨砂。**

#### C1　阅读页液态玻璃悬浮 Dock　`P0`

| 字段 | 说明 |
|---|---|
| 位置 | 底部居中，距安全区 24dp，最大宽 380dp |
| 形态 | 超椭圆胶囊，圆角 28dp，内边距 8dp |
| 主按钮 | ① 上一页 ② 下一页 ③ 页码胶囊 ④ 目录 ⑤ 编辑 ⑥ 关闭 |
| 显示逻辑 | 4s 无操作自动隐藏（淡出 + 下移 24dp）；点击画布任意位置唤起 |
| 边界 | 首页/末页按钮置灰；加密文档未解锁时禁用编辑 |

#### C2　列表选中 PDF 弹出液态玻璃悬浮框　`P0`

- 全屏 35% 黑蒙版 + 从条目位置生长的玻璃框；实时拾取底层画面，渲染**高亮色（取自缩略图主色）+ 透镜扭曲**。
- 内容：缩略图、文件名（2 行）、大小/页数/时间、玻璃主按钮"打开"、次要按钮"更多"（展开 F1-06 动作菜单）。

#### C3　悬浮玻璃框拖拽 + 弹簧回弹　`P0`

- 长按 200ms 进入拖拽，1:1 跟随；边缘 squash & stretch（1.06/0.94）；越界 rubberBand 48dp；松手欠阻尼回弹（320 / 0.72）。

#### C4　列表长按生成液态玻璃拖拽预览卡片　`P0`

- 长按 300ms + 触感 → 卡片升起跟随手指，拖拽中实时扭曲折射（0.12→0.20），松手弹簧归位。

#### C5　玻璃分段控件 / 拨杆 / 滑块　`P0`（设置页核心）

| 控件 | 规格 |
|---|---|
| 分段控件 | 玻璃底槽 + 滑动高光指示块，切换时指示块弹簧移动（400/0.8） |
| 拨杆 Switch | 48×28dp 玻璃轨道 + 24dp 高光球，开=采样高亮色，关=灰玻璃 |
| 滑块 Slider | 4dp 玻璃轨道 + 24dp 玻璃圆钮，拖动时折射增强 |
| 选择器倒计时/复选 | 玻璃圆形描边 + 高光对勾 |

#### C6　编辑工具栏（玻璃浮岛）　`P0`

- 阅读页右上角展开为两行玻璃工具岛（标注工具 8 个 + 颜色/粗细二级浮层）。
- 选中工具时按钮以采样高亮色填充 12%。

#### C7　玻璃输入浮层（页码/重命名/搜索/表单）　`P1`

- 统一组件：玻璃卡片 + 输入框（玻璃内凹 2% 黑）+ 玻璃键盘（自绘 0-9 与符号），避免系统键盘破坏视觉一致性；英文/中文输入回退系统键盘。

---

### 3.6 F6　异常状态（玻璃样式，禁止原生弹窗 / Toast）

| 场景 | 表现 | 文案 |
|---|---|---|
| 加载 / 解析 / 压缩 | 玻璃圆环 + 百分比 | `正在处理…` |
| 文件损坏 | 玻璃卡片 + "从列表隐藏" | `无法打开此 PDF` / `文件可能已损坏或格式不受支持` |
| 加密 PDF | 玻璃输入浮层 | `该 PDF 已加密，请输入密码` |
| 密码错误 | 输入框玻璃红微光 + 轻微横向抖动 | `密码不正确` |
| 权限不足 | 玻璃卡片 + 盾牌 | `需要文档访问权限` / `LPdf 只读取你选择的文件` |
| 网络失败（在线预览） | 顶部玻璃提示条 | `网络不可用，已切回本地模式` |
| 离线模式拦截 | 顶部玻璃提示条 | `已开启完全离线模式，本次请求被阻止` |
| 存储空间不足 | 玻璃卡片 | `存储空间不足` / `请清理后重试` |
| 导出成功 | 顶部玻璃提示条 + "查看"按钮 | `已保存到「下载」` |

**硬性要求**：禁止 `Toast`、`AlertDialog`、Material `Snackbar`；唯一例外是系统权限请求弹窗与 MediaStore 写/删授权弹窗。

---

### 3.7 F7　主题与适配

- 跟随系统浅/深色，可在设置页覆盖；玻璃自动跟随（深色下高光 ×0.7）。
- 全面屏 / 挖孔屏安全区避让；横屏支持；平板双列 / 阅读居中最大宽 900dp。
- 大字体（200%）自适应；列表项高度随内容增长。

---

## 4. 联网能力（V1.1 新增）

> 允许联网 ≠ 允许收集数据。联网只做两件事：**取回用户明确指定的 PDF**、**检查更新提示**。

### 4.1 在线 PDF 链接

| 项 | 规则 |
|---|---|
| 入口 | P1 标题区"链接"玻璃按钮（链环图标） |
| 流程 | 输入 http(s) URL → 玻璃确认卡片 → 显示域名与预计大小 → 下载到应用私有缓存 → 校验通过后导入列表 |
| 协议 | 仅 HTTPS（HTTP 直接拒绝并提示"仅支持 HTTPS 链接"） |
| 限制 | 单文件 ≤ 100MB；超时 30s；支持断点续传 |
| 隐私 | 不携带任何设备标识；请求头仅含 UA 与 Range；**不上传任何本地数据** |
| 关闭 | 设置页"允许在线预览链接"关闭后入口隐藏且所有请求被拦截 |

### 4.2 更新检查

- 手动触发（设置页"检查更新"）；请求仅包含当前版本号。
- 不自动下载、不静默安装；跳转浏览器或应用市场由用户决定。

### 4.3 网络层实现约束

- 仅允许一个网络客户端（OkHttp），集中在 `core/network` 包，其他包禁止直接创建客户端。
- 统一拦截器：离线模式 → 直接抛 `OfflineModeException`；仅 Wi-Fi → 非 Wi-Fi 网络抛 `MeteredBlockedException`。
- 禁止任何埋点、上报、心跳、遥测 endpoint。

---

## 5. 非功能需求

| 编号 | 类别 | 要求 | 指标 |
|---|---|---|---|
| NFR-01 | 性能 | 冷启动 | 到列表可见 ≤ 1.5s（中端机） |
| NFR-02 | 性能 | 大 PDF | 100MB+/1000+ 页可打开；峰值内存 ≤ 堆上限 60% |
| NFR-03 | 性能 | 玻璃渲染 | 稳定 60fps，掉帧率 < 2% |
| NFR-04 | 性能 | 省电 | 页面不可见/后台 → 暂停 AGSL 渲染 |
| NFR-05 | 性能 | 编辑操作响应 | 单次标注操作 UI 响应 < 50ms；导出 100 页 < 20s（中端机） |
| NFR-06 | 稳定性 | 崩溃率 | < 0.1%，无 ANR |
| NFR-07 | 隐私 | 数据 | 不上传 PDF、不上传日志、无统计 SDK |
| NFR-08 | 隐私 | 网络可控 | 完全离线模式一键断网；默认仅 Wi-Fi |
| NFR-09 | 权限 | 最小化 | 见第 6 节 |
| NFR-10 | 兼容 | 版本 | minSdk 33；AGSL 不支持时静态模糊降级 |

---

## 6. 权限说明

| 权限 | 用途 | 必需 |
|---|---|---|
| `android.permission.READ_MEDIA_DOCUMENTS` | 扫描本地 PDF | 是 |
| `android.permission.INTERNET` | 在线链接下载 PDF、检查更新 | 是（V1.1） |
| `android.permission.ACCESS_NETWORK_STATE` | 判断 Wi-Fi/移动网络，执行"仅 Wi-Fi"策略 | 是（V1.1） |
| MediaStore `createWriteRequest` / `createDeleteRequest` | 重命名/删除需系统授权 | 系统弹窗，非持久权限 |
| SAF `ACTION_OPEN_DOCUMENT_TREE` | 目录授权、另存位置 | 系统选择器 |

**仍然禁止申请**：`CAMERA`、`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`、`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`、`POST_NOTIFICATIONS`、`RECORD_AUDIO`、`READ_MEDIA_IMAGES`（图片导入走系统照片选择器）。

---

## 7. 禁止功能清单（V1.1 修订）

- ❌ 广告 SDK、开屏广告、插屏、信息流推荐
- ❌ 推送 SDK、通知栏营销
- ❌ 统计/埋点 SDK、崩溃上报云端、任何遥测
- ❌ 上传用户 PDF 文件到服务器（含"云转换""AI 处理"）
- ❌ 登录、账号、会员、付费弹窗、评分弹窗
- ❌ 开屏页、强制引导页
- ❌ OCR、AI 摘要、云端翻译
- ❌ 云同步、云书架、协作批注
- ❌ 原生 Material 块状 UI、Material 组件默认视觉
- ❌ Toast / 原生 AlertDialog / Material Snackbar
- ❌ 兼容 Android 12 及以下

> 变化：原"禁止 PDF 编辑/批注"已解除；"禁止联网"改为"允许联网但禁止上传与统计"。

---

## 8. 数据结构（本地）

```kotlin
data class PdfItem(
    val id: String,              // documentId 或 uri
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val source: Source,          // MEDIA_STORE / SAF_DIR / LINK_IMPORT
    val health: Health,          // OK / UNKNOWN / BROKEN / ENCRYPTED
    val pageCount: Int?,
    val highlightColor: Long?    // 缩略图采样主色，用于玻璃高亮色
)

// 标注层（与 PDF 分离存储，本地 JSON）
data class AnnotationDoc(
    val docKey: String,          // hash(uri + lastModified)
    val pageAnnotations: Map<Int, List<Annotation>>
)

sealed class Annotation {
    data class Highlight(val rects: List<RectF>, val color: Long) : Annotation()
    data class Ink(val path: List<Offset>, val width: Float, val color: Long) : Annotation()
    data class Note(val point: Offset, val text: String) : Annotation()
    data class Shape(val type: ShapeType, val bounds: RectF, val stroke: Float) : Annotation()
    data class TextBox(val bounds: RectF, val text: String, val size: Float) : Annotation()
}
```

存储位置：`/data/data/com.lpdf.reader/files/annotations/*.json`、`datastore/prefs`；不落外部存储。

---

## 9. 数据分析

**无第三方统计 SDK。** 仅保留本地匿名计数（如"打开次数"，不出设备），用于"关于"页展示与后续人工优化；用户可在设置页一键清除。**不联网上报。**

---

## 10. 验收清单（DoD）

| # | 验收项 | 通过标准 |
|---|---|---|
| 1 | 页面架构 | 3 主页面 + 1 编辑子页，无多余页面 |
| 2 | 列表 | 扫描/排序/筛选/搜索/文件操作全可用 |
| 3 | 阅读 | 滚动、缩放、页码、目录、文字复制 |
| 4 | 编辑 | 页面管理、标注、表单、签名、合并拆分、压缩、导出图片、图片转 PDF |
| 5 | 撤销/重做 | 50 步内任意回退，无错乱 |
| 6 | 保存 | 默认另存新文件；原文件 md5 不变 |
| 7 | 设置页 | 6 组设置全部可生效并持久化 |
| 8 | 网络 | 离线模式下所有请求被拦截；仅 Wi-Fi 生效 |
| 9 | 玻璃 | Dock/浮框/卡片/控件全部为 AGSL 实时渲染 |
| 10 | 异常 | 全部玻璃样式，无原生弹窗/Toast |
| 11 | 性能 | 千页 PDF 流畅；玻璃 60fps；后台停渲染 |
| 12 | 隐私 | APK 无定位/相机/通知权限；无统计 SDK 依赖 |

---

# 第二部分　开发约束规范文档

## 1. 技术栈锁定

| 层次 | 选型 | 可替换 |
|---|---|---|
| 语言 | **Kotlin** + 协程 | ❌ |
| UI | **Jetpack Compose**（M3 仅取 Typography/Icon，不用其视觉） | ❌ |
| PDF 渲染 | **PDFium（barteksc android-pdf-viewer）** | ❌ |
| PDF 编辑写入 | **PDFBox-Android**（`com.tom-roush:pdfbox-android`）用于合并/拆分/加密/表单字典写入 | ✅ 可用 PDFium + 自研增量写入替代 |
| 液态玻璃 | **Kyant0/AndroidLiquidGlass** | ❌ |
| 网络 | OkHttp（仅在 `core/network`） | ⚠️ 限库切换 |
| 本地存储 | DataStore Preferences + 文件 JSON | ✅ |
| 导航 | Compose Navigation | ✅ |
| 图片加载 | Coil（**仅本地/内存**，禁用网络 Fetcher） | ✅ |

### 1.1 绝对禁止

- ❌ Flutter / RN / uni-app
- ❌ XML View 体系与 `Fragment` + XML
- ❌ 广告/统计/推送 SDK（Firebase、UMeng、GDT、穿山甲等）
- ❌ 任何上传 PDF 到服务端的接口
- ❌ 将 OkHttp 客户端散落在业务包（只允许 `core/network`）
- ❌ Material3 的 `Card`、`FilledTonalButton`、`NavigationBar`、`Snackbar`、`Switch`（用自绘玻璃控件）

## 2. 工程配置约束

### 2.1 build.gradle.kts

```kotlin
android {
    namespace = "com.lpdf.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lpdf.reader"
        minSdk = 33                       // 硬约束
        targetSdk = 35
        versionCode = 110
        versionName = "1.1.0"
    }

    buildFeatures { compose = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 不启用 NDK 多 ABI 之外的额外能力；PDFium so 仅保留 arm64-v8a / armeabi-v7a
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = true } }
}
```

### 2.2 AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 文档读取 -->
    <uses-permission android:name="android.permission.READ_MEDIA_DOCUMENTS" />
    <!-- 联网（仅下载用户指定链接 / 检查更新） -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- 禁止：CAMERA / LOCATION / WRITE_EXTERNAL_STORAGE / POST_NOTIFICATIONS / READ_MEDIA_IMAGES -->

    <application
        android:name=".LpdfApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:usesCleartextTraffic="false"
        android:networkSecurityConfig="@xml/network_security_config"
        android:enableOnBackInvokedCallback="true"
        android:largeHeap="false"
        android:theme="@style/Theme.LPdf">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|uiMode"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- 支持从其他应用"用 LPdf 打开" -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:mimeType="application/pdf" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`network_security_config.xml`：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

### 2.3 依赖白名单

```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:..."))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")      // 仅 Typography/Icon
    implementation("androidx.activity:activity-compose")
    implementation("androidx.navigation:navigation-compose")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.datastore:datastore-preferences")

    // 液态玻璃（固定）
    implementation("com.github.Kyant0:AndroidLiquidGlass:<latest>")

    // PDF 渲染（固定）
    implementation("com.github.barteksc:android-pdf-viewer:3.2.0-beta.1")

    // PDF 写入 / 编辑
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // 网络（仅 core/network）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 图片（仅本地）
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

> 依赖新增需走评审；任何包含广告/统计能力的传递依赖必须 `exclude` 或换库。

## 3. 架构规范

### 3.1 分层

```
ui/
  page/list/      PdfListPage.kt / ListViewModel.kt
  page/reader/    PdfReaderPage.kt / ReaderViewModel.kt
  page/editor/    EditorSheet.kt / PageGrid.kt / AnnotToolbar.kt / SignPad.kt
  page/settings/  SettingsPage.kt / SettingsViewModel.kt
  glass/          LiquidDock / LiquidPopup / LiquidPreviewCard / GlassSwitch / GlassSlider
                  GlassSegmented / GlassTextField / GlassSnackbar / GlassProgress
  theme/          Color / Type / GlassTokens / MotionSpec
domain/
  scan/    ScanPdfUseCase
  read/    OpenPdfUseCase / ExtractTextUseCase / OutlineUseCase
  edit/    PageOpsUseCase / AnnotateUseCase / FormUseCase / SignUseCase
           MergeSplitUseCase / CompressUseCase / ExportImageUseCase / ImageToPdfUseCase
  settings/ ObserveSettingsUseCase
data/
  local/   MediaStoreSource / SafSource / AnnotationStore / PrefsRepository
  pdf/     PdfRenderer (PDFium) / PdfWriter (PDFBox)
  network/ LinkImporter / UpdateChecker
core/
  network/ OkHttpProvider / OfflineModeInterceptor / WifiOnlyInterceptor
  dispatcher/ AppDispatchers
  result/  PdfResult / PdfError
```

### 3.2 单向数据流

`UiState(StateFlow) → Compose → Event → ViewModel → UseCase → Repository`。ViewModel 不持有 `Context`。

### 3.3 线程与协程

| 任务 | 线程 | 约束 |
|---|---|---|
| MediaStore 查询 | IO | 分批 30，超时 10s |
| 文件头校验 | IO | 3s 超时 |
| PDFium 打开/渲染 | Default，**单线程 Mutex** | PDFium 非线程安全，禁止并发 |
| PDFBox 写入（合并/压缩/加密） | Default，串行队列 | 单文件操作互斥 |
| 标注持久化 | IO | 防抖 500ms 合并写 |
| 网络下载 | IO | 30s 超时，≤100MB，流式落盘 |

**红线**：主线程禁止文件 IO、PDFium、PDFBox 调用。

## 4. PDF 渲染与内存管控

| 措施 | 规格 |
|---|---|
| 惰性渲染 | 可见页 ±1；其余占位 |
| 位图规格 | 宽度 = 屏宽 × 缩放 × density，上限 2048px |
| 缓存 | LruCache `maxMemory/8`；`onTrimMemory` 清空 |
| 大文件 | 不整文件入内存；PDFium 变体模式（`PdfRenderer` 加载 `ParcelFileDescriptor`） |
| 编辑时 | 标注层绘制于独立图层，不触发页面重渲染 |
| 压缩 | 逐页渲染→编码→立即释放，峰值只保留单页位图 |
| 加密 PDF | 密码提示 → PDFium 变体 `openPage` 前校验；密码仅内存保存，不落盘 |

## 5. 编辑层实现约束

1. **坐标系**：标注以 **PDF 页面坐标（0~1 归一化）** 存储，与设备分辨率、缩放无关；渲染时再映射。
2. **叠加绘制**：标注绘制在 Compose `Canvas` 的独立图层（L2.5），与 PDF 位图同变换矩阵。
3. **撤销/重做**：命令模式（`EditCommand`），栈上限 50；每步记录增量 diff，不做全量快照。
4. **写入策略**：
   - 默认另存：`contentResolver.openOutputStream(destUri)`，先写临时文件再原子移动。
   - 覆盖原文件：仅当用户手动选择，且使用 `MediaStore.createWriteRequest` 授权后，写入前先备份 `.bak` 到私有目录。
5. **表单**：PDFBox 读写 AcroForm；不支持的域类型降级为只读并在玻璃提示条提示。
6. **签名**：Canvas 笔迹 → 平滑（Catmull-Rom）→ 转 PNG（透明底）→ 作为图片对象插入页面。
7. **加密**：仅对导出文件生效（PDFBox `StandardProtectionPolicy`，AES-128）。

## 6. 玻璃渲染性能规范

| 规则 | 说明 |
|---|---|
| 采样帧率 | 最高 60fps；无交互时静止 |
| 暂停 | `Lifecycle.STOP` / 页面不可见 / Dock 隐藏且无拖拽 → 停止帧循环 |
| 采样分辨率 | 背景 RTT 降采样 0.5x |
| 叠加层数 | 同屏 ≤ 2 层（编辑模式下允许 3 层：Dock + 工具岛 + 浮层，需实测保 60fps） |
| 采样区域 | 仅组件覆盖区 + 32dp 边距 |
| 拖拽期 | blur 降至 8dp，采样 0.4x |
| 设置页 | 开关/滑块拖动时仅重绘局部，不重采样整页 |
| 降级 | AGSL 不支持 或 <45fps 持续 2s → 静态模糊 + 无折射 |

## 7. 网络层规范

1. 单例 `OkHttpProvider`，唯一出口。
2. 拦截器链顺序：`OfflineModeInterceptor` → `WifiOnlyInterceptor` → `UaHeaderInterceptor` → 日志（仅 Debug，本地）。
3. 请求头禁止携带：设备 ID、IMEI、OAID、安装 ID、任何用户标识。
4. 只允许 GET；禁止 POST 上传。
5. 下载内容必须校验：`application/pdf` MIME 或 `%PDF-` 魔数，≤100MB。

## 8. 代码规范

- Kotlin 官方风格；4 空格；文件 ≤ 500 行，函数 ≤ 60 行。
- 命名：玻璃组件 `Liquid*` / `Glass*` 前缀。
- 动效统一 `spring()`；禁止 tween 位移/缩放（透明度、旋转除外）。
- 异常统一 `PdfResult<T>`；禁止空 catch。
- 公共 API 必须 KDoc；光学参数改动必须同步更新本文档参数表。
- Lint `warningsAsErrors = true`。

## 9. 层级与采样实现

```kotlin
Box(Modifier.fillMaxSize()) {
    BackgroundLayer()                       // L1
    val contentLayer = rememberGraphicsLayer()
    Box(Modifier.drawWithContent {          // L2 录制（含 PDF 画布 / 列表 / 标注层）
        contentLayer.record { this@drawWithContent.drawContent() }
        drawLayer(contentLayer)
    }) { Content() }

    if (mask) Scrim(0.35f)                  // L3

    LiquidDock(sample = contentLayer::toImageBitmap)  // L4
    EditorToolbar(...)                                 // L5
}
```

- 禁止 `PixelCopy` 全屏截屏采样。
- 采样 Bitmap 每帧复用同一 `ImageBitmap` 缓冲，避免 GC 抖动。

## 10. 测试与验收

| 类型 | 范围 |
|---|---|
| 单元 | 扫描合并去重、页码/范围解析（`1-3,7`）、标注坐标系换算、撤销栈 |
| UI | 列表弹框、长按拖拽、Dock 唤起、设置项持久化、玻璃控件可交互 |
| 性能 | 千页 PDF 滚动、压缩 100 页耗时与内存、玻璃帧率 |
| 网络 | 离线模式拦截、仅 Wi-Fi 拦截、非 PDF 响应拒绝 |
| 权限 | `aapt dump permissions` 校验权限清单 |
| 兼容 | Pixel 6a / 小米 13 / OPPO Find X6 / 折叠屏 / 平板 |

## 11. Code Review 红线

- [ ] 无主线程 IO / PDFium / PDFBox
- [ ] 无 XML View
- [ ] 无广告/统计/推送依赖
- [ ] 无任何上传接口、无设备标识请求头
- [ ] 无 Toast / AlertDialog / Material Snackbar
- [ ] 玻璃组件全部来自 Kyant 库
- [ ] AGSL 帧循环随生命周期暂停
- [ ] Bitmap 及时回收、无泄漏
- [ ] 动效全部 spring
- [ ] minSdk = 33
- [ ] 编辑保存默认不覆盖原文件

---

# 第三部分　iOS26 液态玻璃 UI 视觉规范文档

## 1. 设计理念

> **Glass is not a texture. Glass is an optical event.**
> 玻璃不是贴图，而是一次真实发生的光学事件：它折射身后的世界，并因你的触碰而形变。

1. **Lensing over blur**：折射为主，模糊为辅。
2. **Light follows motion**：高光随移动方向流动。
3. **Physics over timeline**：一切由弹簧物理驱动。

V1.1 增补：**玻璃承载功能**。编辑工具、设置控件同样是玻璃，不因"功能多"而退化成 Material 控件。

## 2. 材质规格

### 2.1 渲染管线

```
[背景/内容绘制] ─▶ GraphicsLayer 录制 ─▶ 降采样 RTT(0.5x)
                                        │
                                        ▼
                              ┌───────────────────┐
                              │  AGSL: LiquidGlass │
                              │ 1. 透镜位移 Field  │
                              │ 2. 边缘色散 0.04  │
                              │ 3. 磨砂混合 10dp  │
                              │ 4. 底部采样高亮色 │
                              │ 5. 顶部高光带     │
                              │ 6. squircle 裁剪  │
                              └───────────────────┘
                                        ▼
                                 玻璃组件输出（L4/L5）
```

### 2.2 基础参数表（**锁定值**）

| 参数 | 值 | 说明 |
|---|---|---|
| 圆角半径 | **28dp**（G2 连续曲率 squircle） | 小控件可用 20dp（见 2.6） |
| 模糊半径 | **10dp** | 降采样 σ≈5px |
| 折射强度 | **0.12** | 拖拽时可升至 0.20 |
| 色散强度 | **0.04** | R/B 通道边缘偏移 |
| 高光强度 | **0.15** | 顶部渐变白 |
| 悬浮蒙版透明度 | **0.35** | 深色主题 0.45 |
| 玻璃本底 | 白 6%（浅）/ 白 4%（深） | |
| 边缘描边 | 白 22%（浅）/ 白 14%（深），1dp | |
| 高光带高度 | 组件高度 40% | |
| 按压折射增强 | +20%（0.12→0.144） | |
| 按压缩放 | 0.96 | |

### 2.3 高亮色采样算法

```
1. 采样区 8×8 网格均值 → 64 个区域色
2. HSL 化，剔除 L<15% 与 L>92%
3. 平均 H，S=max(S,0.35)，L→0.55~0.65（浅）/0.35~0.45（深）
4. 以 12% 透明度叠加玻璃底部
5. 色相变化用 400ms 弹簧平滑
```

### 2.4 透镜位移模型

```
d = normalize(p - center) * pow(len(p - center)/R, 2) * thickness * 0.12
uv' = uv - d / resolution
```

### 2.5 形变规则

| 状态 | 变化 |
|---|---|
| 按压 | scale 0.96；折射 0.144；高光 0.22 |
| 拖拽 | 运动轴 1.06 / 垂直轴 0.94；速度 >800dp/s 折射 0.20 |
| 松手 | 欠阻尼回弹，overshoot ≤ 3% |

### 2.6 圆角体系（V1.1 分级）

| 层级 | 圆角 | 用途 |
|---|---|---|
| XL | 28dp | Dock、悬浮框、卡片、工具岛、设置分组 |
| L | 20dp | 按钮、页码胶囊、分段控件指示块、缩略图 |
| M | 14dp | 列表缩略图、小图标按钮背景 |
| Pill | 高度/2 | 胶囊按钮、拨杆轨道 |

> 所有圆角均为**连续曲率**，禁止普通圆弧 + 直角混合。

## 3. 颜色规范

### 3.1 文字

| 用途 | 浅色 | 深色 |
|---|---|---|
| 主文字 | `#1C1C1E` | `#F5F5F7` |
| 次要文字 | `#6E6E73` | `#8A8A8E` |
| 玻璃上主文字 | `#1C1C1E` @95% | `#F5F5F7` @95% |
| 禁用 | `#6E6E73` @40% | `#8A8A8E` @40% |
| 危险（删除） | `#FF453A` | `#FF6961` |
| 成功（已保存） | `#30D158` | `#32D74B` |

### 3.2 背景

| 用途 | 浅色 | 深色 |
|---|---|---|
| 页面底色 | `#F2F2F7` | `#000000` |
| 设置页分组底（玻璃下） | 同页面底色 | 同页面底色 |
| PDF 纸张 | `#FFFFFF` | `#FFFFFF`（不反色） |
| 蒙版 | `#000000` @35% | `#000000` @45% |

### 3.3 标注色板

| 名称 | 值 | 用途 |
|---|---|---|
| 高亮黄 | `#FFD60A` | 默认高亮 |
| 高亮绿 | `#30D158` | |
| 高亮蓝 | `#0A84FF` | |
| 高亮粉 | `#FF375F` | |
| 手绘墨黑 | `#1C1C1E` | |
| 手绘红 | `#FF453A` | |

> 标注色在深色主题下不降亮度，保证纸上可见。

## 4. 字体与排版

| 层级 | 字号 | 字重 | 行高 | 用途 |
|---|---|---|---|---|
| Title-L | 28sp | 600 | 34sp | 页面大标题 |
| Title-M | 20sp | 600 | 26sp | 浮框标题、设置分组标题 |
| Body | 16sp | 400 | 22sp | 列表项、设置项 |
| Body-Strong | 16sp | 500 | 22sp | 设置值 |
| Caption | 13sp | 400 | 18sp | 辅助信息 |
| Micro | 11sp | 500 | 14sp | 页码、角标 |

- 系统无衬线；数字等宽（`tnum`）。
- 字距：Title -0.3sp；正文 0；Micro +0.1sp。

## 5. 间距与网格

| Token | 值 |
|---|---|
| 基础间距 | 4 / 8 / 12 / 16 / 20 / 24 / 32 / 48 dp |
| 页面水平边距 | 20dp |
| 列表项间距 | 10dp |
| 设置分组间距 | 28dp |
| 设置项高度 | 56dp（玻璃内 16dp 水平内边距） |
| Dock 距底 | 24dp + navigationBars |
| 安全区顶部 | statusBars + 8dp |

## 6. 组件规范

### 6.1 列表条目

```
┌────────────────────────────────────────┐
│ ┌──────┐ 合同-2026-终版.pdf      [•••] │
│ │ 缩略 │ 2.4 MB · 356 页 · 今天 14:32  │
│ └──────┘                               │
└────────────────────────────────────────┘
```

- 透明底，点击玻璃高亮涟漪；右侧 `•••` 为 32dp 玻璃圆按钮。
- 已加密条目右上角玻璃小锁角标（16dp）。

### 6.2 液态玻璃 Dock（阅读页）

```
┌──────────────────────────────────────────┐
│  ⌃   12/356   ⌄   │  ☰   ✎   │   ✕      │
└──────────────────────────────────────────┘
 上一页 页码 下一页   目录 编辑    关闭
```

| 参数 | 值 |
|---|---|
| 高 | 56dp（内边距 8dp） |
| 最大宽 | 380dp |
| 按钮 | 40×40dp，图标 18dp，线宽 1.8dp |
| 页码胶囊 | 84×40dp，圆角 20dp |
| 唤起 | scale 0.92→1.0 + Y 16→0，spring(320, 0.72) |
| 隐藏 | 4s 后 opacity→0 + Y +24dp |

### 6.3 选中悬浮框 / 拖拽卡片

同 V1.0（宽 min(320, 屏宽-48)、28dp 圆角；卡片 180×240dp），新增"更多"次要玻璃按钮（高 40dp，文字 + `ellipsis` 图标）。

### 6.4 编辑工具岛（新增）

```
┌────────────────────────────────────────────┐
│ ✎  ▭  ⌒  T  ▣  ✍  ◻  ⌫                    │   ← 8 个工具，40dp
└────────────────────────────────────────────┘
        ┌───────────────────────┐
        │  ● ● ● ●   ──●── 粗细 │   ← 二级浮层：颜色 4 点 + 粗细滑块
        └───────────────────────┘
```

| 参数 | 值 |
|---|---|
| 位置 | 顶部安全区下方 12dp，水平居中 |
| 尺寸 | 高 56dp，宽自适应（最大屏宽-40dp），28dp 圆角 |
| 第二行 | 二级浮层从工具岛下方"生长"（spring 280/0.70） |
| 选中态 | 按钮底填充采样高亮色 12% + 图标加深 |
| 退出 | 点击"完成"玻璃胶囊 / 系统返回 |

### 6.5 玻璃控件（设置页）

| 控件 | 规格 |
|---|---|
| 分组卡 | 玻璃容器，28dp 圆角，内含 1~6 行，行间 1px 分隔（白 8%） |
| 行 | 左标题（Body），右控件；整行可点时有 chevron 玻璃小箭头 |
| Switch | 48×28 轨道（Pill），24dp 高光球；开=采样高亮色，关=`#787880` @30% |
| Slider | 4dp 轨道，24dp 玻璃钮，填充部分为采样高亮色；拖动时钮折射增强 |
| Segmented | 玻璃底槽（Pill）+ 指示块（20dp 圆角），弹簧移动 400/0.8 |
| 单选圆点 | 20dp 玻璃圆描边 + 内 8dp 高亮实心点 |
| 数字键盘 | 玻璃键帽 44×52dp，圆角 14dp，按压 0.96 |

### 6.6 玻璃提示 / 弹层

| 类型 | 位置 | 尺寸 | 生命周期 |
|---|---|---|---|
| Glass Snackbar | 顶部（statusBars+12dp） | 48dp 高，屏宽-40dp | 4s，可下滑 |
| Glass Dialog Card | 居中 | 宽 300dp | 需操作 |
| Glass Progress | 居中 | 环 56dp + 百分比 | 任务期间，可取消 |
| Glass Empty | 居中偏上 | 宽 280dp | 常驻 |
| 玻璃键盘浮层 | 底部升起 | 自适应 | 输入期间 |

## 7. 动效规范

### 7.1 弹簧参数（全部欠阻尼）

| 用途 | stiffness | dampingRatio |
|---|---|---|
| 通用状态切换 | 400 | 0.80 |
| Dock 唤起 | 320 | 0.72 |
| 拖拽回弹 | 320 | 0.72 |
| 浮框弹出 | 280 | 0.70 |
| 浮框收起 | 420 | 0.90 |
| 形变恢复 | 500 | 0.60 |
| 高亮色过渡 | 120 | 0.90 |
| 工具岛展开 | 340 | 0.74 |
| 开关拨杆 | 380 | 0.68（带轻微过冲） |
| 分段指示块 | 400 | 0.78 |
| 面板/抽屉 | 300 | 0.80 |

### 7.2 规则

1. 禁止线性位移/缩放；线性仅允许旋转与透明度渐隐（>200ms）。
2. 禁止闪烁：动画结束不得有 1 帧跳变。
3. 拖拽 1:1 跟手；越界 rubberBand（48dp 上限）。
4. 惯性：速度 >800dp/s 时先滑 12dp 再回弹。
5. 动画期间只用 `graphicsLayer` 变换，禁止重新布局。
6. 设置页"动效等级=关闭"时：改用 120ms 淡入淡出，保留玻璃静态折射。

## 8. 图标规范

- SF Symbols 线性风，1.8dp 线宽，端点圆角；尺寸 18dp（Dock）/ 20dp（列表/设置）/ 16dp（角标）。
- 编辑工具图标：笔、矩形、圆形箭头、文字 T、便签、签名、形状、橡皮。
- 不使用 Material 填充图标。

## 9. 新增页面布局（设置页）

```
┌────────────────────────────────┐
│  ‹        设置                 │  ← 顶部：返回玻璃圆钮 + 居中标题
│                                │
│  ┌──────────────────────────┐  │
│  │ 阅读                      │  │  ← 玻璃分组卡，Title-M 标题
│  │ 默认打开方式          连续 ›│  │
│  │ 缩放上限              5x  ›│  │
│  │ 保持屏幕常亮        ( ●  ) │  │
│  └──────────────────────────┘  │
│  ┌──────────────────────────┐  │
│  │ 外观                      │  │
│  │ 主题    [跟随|浅|深]       │  │
│  │ 动效等级          完整  › │  │
│  └──────────────────────────┘  │
│  …（编辑 / 存储 / 隐私与网络 / 关于）│
└────────────────────────────────┘
```

- 分组卡之间 28dp；卡片内行高 56dp；卡片自身为液态玻璃（可轻微透过看到底层页面光晕）。
- 滚动时顶部标题栏变为玻璃条（44dp 高），标题缩小至 17sp。

## 10. 无障碍

| 项 | 要求 |
|---|---|
| 触控目标 | ≥ 44×44dp |
| 对比度 | ≥ 4.5:1，不足时玻璃本底加深至 10% |
| 字体缩放 | 支持 200%，行高自适应 |
| 屏幕阅读 | 所有玻璃控件有 `contentDescription` 与 `stateDescription`（如"完全离线模式，已关闭"） |
| 减少动效 | 系统"移除动画"时降级为 120ms 过渡 |
| 色弱 | 标注色提供形状区分（高亮/下划线形态不同） |

## 11. 视觉自查

- [ ] 圆角分级正确（28/20/14/Pill），全部连续曲率
- [ ] 参数与 2.2 表完全一致
- [ ] 玻璃能透出并扭曲背景
- [ ] 拖拽时拉伸与高光流动
- [ ] 设置页无任何 Material 默认控件
- [ ] 动效无线性、无闪烁
- [ ] 浅/深色均有清晰层次

---

# 第四部分　UI 绘图提示词

> 画幅 **9:19.5 竖屏**，分辨率 ≥ 1024×2224；建议使用 Nano Banana Pro / GPT-Image / Midjourney v7。以下两段为核心页面确认用。

---

## 提示词 1：LPdf PDF 文件列表首页

```
Apple iPhone 17 Pro 风格的安卓应用界面效果图，9:19.5 竖屏，超高精度 UI 设计稿，iOS 26 液态玻璃（Liquid Glass）设计语言，真实玻璃光学材质渲染。

【页面】PDF 阅读与编辑应用「LPdf」的文件列表首页。

【背景】极浅冷灰白底色 #F2F2F7，底部有一层极淡的暖灰与浅蓝柔和光晕（像被打湿的纸面），整体干净、明亮、高级。

【顶部区域】状态栏仅显示时间 9:41 与信号电池（纯线性图标，颜色 #1C1C1E）。状态栏下方 8dp 是大标题「LPdf」，系统无衬线字体，28pt 半粗体，字距略紧，颜色 #1C1C1E。标题下方 12dp 是一行玻璃胶囊筛选条：三个小胶囊，分别显示「最近修改」「全部」「已标注」，第一个为选中态（玻璃内高亮），其余为普通玻璃。标题右侧 20dp 处是两枚直径 44 的液态玻璃圆形按钮：一枚线性文件夹图标，一枚线性齿轮图标，玻璃边缘有轻微折射与 1dp 白色高光描边。

【列表区域】六个 PDF 文件条目，纵向排列，条目间距 10dp，左右边距 20dp。每个条目左侧是 44×56 的 PDF 首页缩略图（圆角 14dp，白色纸张带细微阴影，上面有灰色文字排版块），右侧两行文字：第一行文件名（16pt，#1C1C1E，如「2026 年度产品合同.pdf」「机器学习导论.pdf」「设计规范 v3.pdf」「用户调研报告.pdf」「租房协议.pdf」「会议纪要.pdf」），第二行 13pt 灰色 #6E6E73 显示「2.4 MB · 356 页 · 今天 14:32」「18.7 MB · 214 页 · 10月12日」等信息；条目最右侧有一个 32dp 的玻璃小圆按钮，内含三个点 ellipsis 图标。条目背景完全透明，只有极淡的层次感，像悬浮在浅灰纸面上。

【核心交互状态】列表中的第 2 个条目被选中，整个屏幕覆盖一层 35% 不透明度的黑色柔和蒙版（暗化但不死黑，四周有轻微暗角）。在被选中条目位置上方悬浮一块巨大的液态玻璃浮框：宽度 320，超大连续曲率圆角 28，真实玻璃质感——背景内容透过它被放大并轻微扭曲（透镜折射，边缘可见背景文字被拉伸），玻璃顶部有一条柔和的流动高光带，玻璃底部透出从缩略图采样来的暖琥珀色高亮（12% 透明度），边缘有 1dp 白色高光描边和极轻微的彩虹色散。浮框内从上到下：PDF 首页缩略图（圆角 20，内边距 12）、文件名「机器学习导论.pdf」（20pt 半粗体 #1C1C1E）、「18.7 MB · 214 页 · 10月12日」（13pt #6E6E73）、一枚宽 276 高 44 的玻璃主按钮（文字「打开」，16pt 中等字重，按钮内为更亮的玻璃高光）、下方一行两个小玻璃文字按钮「更多」与「分享」。

【光影与材质要求】玻璃不是磨砂贴图，而是真实光学透镜：能看清背后列表被折射变形，边缘有极细的彩虹边（色散强度 0.04），顶部高光强度约 0.15，玻璃本底只有 6% 白色。整体光感柔和、通透，像真实的亚克力与玻璃叠层。

【风格关键词】iOS 26 Liquid Glass、Apple Human Interface Guidelines、超椭圆连续圆角、极简、留白充足、无广告、无推荐内容、无 Material Design 块状卡片、真实光影、高级质感、8K 细节、UI/UX 设计稿、Figma 导出级精度。

【禁止】不要 Material Design、不要彩色渐变大色块、不要厚重阴影、不要多余装饰元素与文字、不要底部导航栏。
```

---

## 提示词 2：LPdf PDF 阅读页面

```
Apple iPhone 17 Pro 风格的安卓 PDF 阅读器界面效果图，9:19.5 竖屏，超高精度 UI 设计稿，iOS 26 液态玻璃（Liquid Glass）设计语言，真实玻璃光学渲染。

【页面】PDF 阅读与编辑应用「LPdf」的阅读页，处于编辑模式。

【背景与内容层】最底层是浅灰 #F2F2F7 的阅读底色。屏幕中间垂直排列一页 A4 比例的 PDF 纸张（白色 #FFFFFF，圆角 4，极轻阴影），纸张上是一篇真实的英文论文排版：顶部大标题「Retrieval-Augmented Generation for Large Language Models」，下方两栏小号正文与一个简单柱状图，文字清晰可读。纸张上有一处黄色高亮（#FFD60A，20% 透明度，混合模式柔和）覆盖某一行文字，一处蓝色手绘下划线，右下一角有一个玻璃小图钉便签图标。下方露出上一页纸张底部四分之一，形成连续垂直滚动感，页间距 16dp。

【顶部编辑工具岛】状态栏下方 12dp，水平居中，一块液态玻璃工具岛：高 56，宽约 356，连续曲率圆角 28，内部从左到右排列八个 40×40 的线性图标：记号笔（当前选中，按钮底色为从 pdf 内容采样的暖黄色 12%）、矩形、圆形、文字 T、便签、签名笔、形状、橡皮擦；最右侧一条 1dp 竖直分隔线后是一个玻璃胶囊文字按钮「完成」。玻璃工具岛能透过它看到下方纸张文字被折射拉伸。

【右上角】一枚小型液态玻璃胶囊，宽 84 高 32，圆角 20，内部等宽数字「12 / 214」（11pt 中等字重，#1C1C1E）。

【底部悬浮 Dock】屏幕底部居中（距安全区 24dp）悬浮一条液态玻璃 Dock：宽 356，高 56，连续曲率圆角 28，真实玻璃材质。内部从左到右：① 40×40 玻璃圆按钮内嵌线性向上 chevron（上一页）② 中间 84×40 玻璃胶囊「12 / 214」（11pt 等宽数字）③ 40×40 玻璃圆按钮内嵌线性向下 chevron（下一页）④ 1dp 竖直分隔线 ⑤ 40×40 玻璃圆按钮内嵌三横条目录图标 ⑥ 40×40 玻璃圆按钮内嵌钢笔图标（编辑，图标为高亮选中色）⑦ 1dp 分隔线 ⑧ 40×40 玻璃圆按钮内嵌线性叉号 xmark（关闭）。全部图标为 1.8dp 线宽线性风格，颜色 #1C1C1E。

【玻璃材质的核心要求】工具岛与 Dock 都是真实液态玻璃：能透过它清晰看到下方 PDF 纸张文字，文字在玻璃边缘被透镜放大并向外拉伸（折射强度 0.12），边缘有极细彩虹色散（0.04），玻璃顶部有一条从左到右流动的柔和高光带（强度 0.15），玻璃本底只有 6% 白色，边缘 1dp 白色高光描边。玻璃与纸张之间有柔和真实的投影，证明它悬浮着。

【光影氛围】光线从上左方来，纸张极轻微明暗渐变；玻璃高光集中在左上边缘。整体明亮、干净、通透、高级。

【风格关键词】iOS 26 Liquid Glass、Apple HIG、超椭圆连续圆角、极简阅读界面、真实光学折射、专业 PDF 编辑工具、8K 细节、UI/UX 设计稿、Figma 导出级精度。

【禁止】不要 Material Design 底部导航、不要彩色图标、不要厚重阴影、不要翻书 3D 效果、不要系统工具栏与菜单栏、不要广告、不要多余按钮。
```

---

## 附：效果图确认要点

| # | 确认项 | 列表页 | 阅读页 |
|---|---|---|---|
| 1 | 玻璃"透"且"扭曲"而非灰白磨砂 | 浮框 | 工具岛 + Dock |
| 2 | 边缘色散可见但不脏 | ✔ | ✔ |
| 3 | 顶部高光带柔和存在 | ✔ | ✔ |
| 4 | 圆角 28dp 连续曲率 | ✔ | ✔ |
| 5 | 蒙版 35% 且不死黑 | ✔ | — |
| 6 | 高亮色来自底层内容 | 琥珀色 | 选中工具的暖黄 |
| 7 | 字体系统无衬线、颜色 #1C1C1E / #6E6E73 | ✔ | ✔ |
| 8 | 无任何 Material 块状元素 | ✔ | ✔ |
| 9 | 编辑工具岛与 Dock 层级清晰（工具岛在上） | — | ✔ |
| 10 | 标注不遮挡正文可读性 | — | ✔ |

---

*— 文档结束 —*
