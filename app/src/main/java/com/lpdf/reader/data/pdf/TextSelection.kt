package com.lpdf.reader.data.pdf

/**
 * 一页的文字层（v1.7.0）。
 *
 * 坐标是归一化的 0~1，与 [PageRender] 的宽高无关，因此缩放、
 * 分块降级、横竖屏切换都不会让选区错位 —— 与标注用同一套坐标约定。
 */
data class TextLayer(
    val page: Int,
    /** 按字符序号排列。注意：不含被跳过的空字符，序号与 PDF 原生序号有偏差 */
    val chars: List<CharBox>,
    /** 行数（0 表示这一页没有文字，比如扫描件） */
    val lineCount: Int
) {
    val isEmpty: Boolean get() = chars.isEmpty()
}

/** 单个字符的位置与其所属行 */
data class CharBox(
    val ch: Char,
    val line: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * 一个选区（v1.7.0）。
 *
 * ## 为什么用「起点字符 + 终点字符」而不是矩形
 *
 * 拖动到换行处时，选区应该自然地延续到下一行行首，而不是变成一个大方块
 * 把两行之间的空白也框进去。字符范围天然表达了这种「按阅读顺序」的语义。
 *
 * ## 拖选如何转换成字符范围
 *
 * 用户手指按下与抬起的两个点，各自找到最近的字符，
 * 然后取二者在**阅读顺序**上的区间：
 * - 同行：直接取列序之间的字符
 * - 跨行：起始行取 [起点列, 行尾]，中间行整行，结束行取 [行首, 终点列]
 *
 * 这正是「拖过一整段」应该选中的内容。
 */
data class TextSelection(
    val page: Int,
    /** 选区覆盖的字符，按阅读顺序 */
    val chars: List<CharBox>
) {
    /** 选中的纯文字；行末补换行，行中不加分隔 */
    val text: String by lazy {
        if (chars.isEmpty()) return@lazy ""
        val sb = StringBuilder()
        var lastLine = chars.first().line
        chars.forEach { c ->
            if (c.line != lastLine) {
                sb.append('\n')
                lastLine = c.line
            }
            sb.append(c.ch)
        }
        sb.toString().trim()
    }

    /**
     * 选区高亮要画的矩形：把同一行的字符合并成一个横向长条。
     *
     * 不逐字画的原因：字符框之间有字距，逐字画会看到一格一格的白缝，
     * 像打了一排马赛克。合并成行内的连续区间后才是正常的「选中一段」的样子。
     */
    val rects: List<PdfiumEngine.AnnotRectLike> by lazy {
        if (chars.isEmpty()) return@lazy emptyList()
        val out = ArrayList<PdfiumEngine.AnnotRectLike>()
        var start = 0
        while (start < chars.size) {
            val line = chars[start].line
            var end = start
            var left = chars[start].left
            var right = chars[start].right
            var top = chars[start].top
            var bottom = chars[start].bottom
            var i = start + 1
            while (i < chars.size && chars[i].line == line) {
                left = minOf(left, chars[i].left)
                right = maxOf(right, chars[i].right)
                top = minOf(top, chars[i].top)
                bottom = maxOf(bottom, chars[i].bottom)
                end = i
                i++
            }
            out += PdfiumEngine.AnnotRectLike(left, top, right, bottom)
            start = end + 1
        }
        out
    }

    val isEmpty: Boolean get() = chars.isEmpty()
}

/**
 * 把「按下点 → 抬起点」两个归一化坐标转换成字符范围。
 *
 * 抽成纯函数是为了能脱离 PDFium 直接单测 —— 这段逻辑有大量边界
 * （反向拖、跨行、跨页外沿、扫描件无文字），靠真机试很难覆盖全。
 *
 * @param layer 该页文字层，`chars` 必须按阅读顺序排列
 */
fun selectByDrag(
    layer: TextLayer,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float
): TextSelection {
    if (layer.chars.isEmpty()) return TextSelection(layer.page, emptyList())

    val a = nearestChar(layer, startX, startY) ?: return TextSelection(layer.page, emptyList())
    val b = nearestChar(layer, endX, endY) ?: return TextSelection(layer.page, emptyList())

    // 反向拖：交换，保证 a 是阅读顺序上的前一点
    val (from, to) = if (a.first <= b.first) a to b else b to a
    val fromIdx = from.first
    val toIdx = to.first

    val fromChar = layer.chars[fromIdx]
    val toChar = layer.chars[toIdx]

    val picked = when {
        // 同一行：只取这两列之间
        fromChar.line == toChar.line -> layer.chars.subList(fromIdx, toIdx + 1).toList()
        // 跨行时不能直接用索引区间。
        //
        // 索引区间会得到「起点行从 fromIdx 到行尾 + 中间整行 + 终点行行首到 toIdx」，
        // 但真实的拖选语义是：起点行**从起点字到行尾**，终点行**从行首到终点字**。
        // 两者的差别在起点/终点落在行中间时非常明显 ——
        // 从第 1 行的 C 拖到第 2 行的 H，用户期望 "CDE\nFGH"，
        // 索引区间却给出 "DE\nFGHIJ"（起点行只从 C 之后开始，终点行越过了 H）。
        else -> {
            val out = ArrayList<CharBox>()
            // ① 起点行：fromIdx 到该行最后一个字
            var i = fromIdx
            while (i < layer.chars.size && layer.chars[i].line == fromChar.line) {
                out += layer.chars[i]
                i++
            }
            // ② 中间行：整行
            while (i < layer.chars.size && layer.chars[i].line < toChar.line) {
                out += layer.chars[i]
                i++
            }
            // ③ 终点行：行首到 toIdx。
            // 循环条件必须同时守住「不超过 toIdx」——只判行号会一路吃到行尾
            while (i <= toIdx && i < layer.chars.size) {
                out += layer.chars[i]
                i++
            }
            out
        }
    }.toList()

    return TextSelection(layer.page, picked)
}

/**
 * 找离 (x, y) 最近的字符下标。
 *
 * 判定用**字框距离**而不是中心点距离：手指落在行间空白处时，
 * 中心点距离会把离得远但同一行的高字判得更近，导致选到上一行末尾。
 * 距离先算纵向再算横向，纵向优先 —— 人拖选时对「哪一行」的判断更敏感。
 */
internal fun nearestChar(layer: TextLayer, x: Float, y: Float): Pair<Int, Float>? {
    var bestIdx = -1
    var bestDist = Float.MAX_VALUE
    layer.chars.forEachIndexed { i, c ->
        val dx = when {
            x < c.left -> c.left - x
            x > c.right -> x - c.right
            else -> 0f
        }
        val dy = when {
            y < c.top -> c.top - y
            y > c.bottom -> y - c.bottom
            else -> 0f
        }
        // 纵向权重更高：宁可同行的字，也不要上一行更近的字
        val d = dy * 3f + dx
        if (d < bestDist) {
            bestDist = d
            bestIdx = i
        }
    }
    return if (bestIdx < 0) null else bestIdx to bestDist
}
