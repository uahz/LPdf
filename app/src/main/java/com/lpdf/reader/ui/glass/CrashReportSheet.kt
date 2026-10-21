package com.lpdf.reader.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lpdf.reader.core.util.CrashReporter
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors

/**
 * 崩溃报告面板（v1.3.1）。
 *
 * 闪退后下次启动在首页弹出：标题给人看，堆栈给排查用，
 * 并提供「复制全文」——用户不必装 adb 也能把关键信息发出来。
 */
@Composable
fun CrashReportSheet(
    report: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalColors.current
    val headline = remember(report) { CrashReporter.headline(report) }
    val where = remember(report) { CrashReporter.summarize(report) }
    var expanded by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .padding(horizontal = LSpace.pageHorizontal),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlass(Modifier.fillMaxWidth(), shape = Shapes2.XL) {
            Column(Modifier.padding(24.dp)) {
                Text("上次打开时闪退了", style = LType.TitleM, color = colors.text)
                Spacer(Modifier.height(8.dp))
                Text(
                    headline ?: "未能读取异常类型",
                    style = LType.Caption,
                    color = colors.danger
                )
                if (where != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("位置：$where", style = LType.Micro, color = colors.secondary)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "把下面的信息复制发出来，就能定位到具体代码行。日志只存在手机里，不会上传。",
                    style = LType.Micro,
                    color = colors.secondary
                )
                Spacer(Modifier.height(12.dp))

                if (expanded) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(colors.text.copy(alpha = 0.06f))
                            .padding(10.dp)
                    ) {
                        Text(
                            report,
                            style = LType.Micro.copy(fontFamily = FontFamily.Monospace),
                            color = colors.text,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassPillButton(
                        if (expanded) "收起堆栈" else "查看完整堆栈",
                        { expanded = !expanded },
                        Modifier.weight(1f),
                        height = 36.dp
                    )
                    GlassPillButton("复制全文", onCopy, Modifier.weight(1f), height = 36.dp)
                }
                Spacer(Modifier.height(10.dp))
                GlassPillButton("知道了", onDismiss, Modifier.fillMaxWidth(), height = 44.dp)
            }
        }
    }
}

/**
 * 根级兜底界面：Compose 组合期抛出且未被捕获时显示，而不是让用户面对一片空白。
 */
@Composable
fun FatalErrorScreen(error: Throwable, onRetry: () -> Unit) {
    val colors = LocalColors.current
    val clipboard = LocalClipboardManager.current
    val report = remember(error) { CrashReporter.formatUiError(error) }
    var expanded by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.pageBg)
            .padding(LSpace.pageHorizontal),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlass(Modifier.fillMaxWidth(), shape = Shapes2.XL) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LIconView(LIcon.Broken, size = 30.dp, color = colors.danger)
                Spacer(Modifier.height(14.dp))
                Text("界面出错了", style = LType.TitleM, color = colors.text)
                Spacer(Modifier.height(8.dp))
                Text(
                    error.message?.take(160) ?: error::class.java.simpleName,
                    style = LType.Micro,
                    color = colors.secondary
                )
                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(colors.text.copy(alpha = 0.06f))
                            .padding(10.dp)
                    ) {
                        Text(
                            report,
                            style = LType.Micro.copy(fontFamily = FontFamily.Monospace),
                            color = colors.text,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPillButton(
                        if (expanded) "收起" else "查看堆栈",
                        { expanded = !expanded },
                        Modifier.weight(1f),
                        height = 40.dp
                    )
                    GlassPillButton(
                        "复制",
                        { clipboard.setText(AnnotatedString(report)) },
                        Modifier.weight(1f),
                        height = 40.dp
                    )
                }
                Spacer(Modifier.height(8.dp))
                GlassPillButton("重试", onRetry, Modifier.fillMaxWidth(), height = 40.dp)
            }
        }
    }
}
