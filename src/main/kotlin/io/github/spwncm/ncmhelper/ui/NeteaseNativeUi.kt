@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.spwncm.ncmhelper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import io.github.spwncm.ncmhelper.NeteaseUiBridge
import io.github.spwncm.ncmhelper.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 登录二维码的登录页前缀 */
private const val LOGIN_PREFIX = "https://music.163.com/login?codekey="

/**
 * 原生 Compose 网易云界面：Hook 到宿主导航根组件后，以原生 Dialog 方式渲染面板。
 * 配置页「打开网易云原生面板」按钮会调用 setVisible(true)。
 */
object NeteaseNativeUi {

    private var hookHandle: HookHandle? = null
    private var hookClass: ComposableHook? = null

    internal var visible by mutableStateOf(false)
    internal var status by mutableStateOf("")
    internal var message by mutableStateOf("")
    internal var qrRows by mutableStateOf<Array<BooleanArray>?>(null)

    @JvmStatic
    fun isInstalled(): Boolean = hookHandle != null

    @JvmStatic
    fun isVisible(): Boolean = visible

    @JvmStatic
    fun setVisible(value: Boolean) {
        visible = value
        if (value) refreshStatus()
    }

    /** 安装 Hook（需要 CLASS_TRANSFORM 权限，由 Java 侧检查后调用） */
    @JvmStatic
    fun install(): Boolean {
        if (hookHandle != null) return true
        return try {
            val hook = PanelHook()
            hookClass = hook
            hookHandle = WorkshopApi.hookRegistrar.afterComposable(
                "com.xuncorp.voxzen.ui.navigation.AppNavigationKt",
                "Content",
                "com.xuncorp.voxzen.ui.navigation.AppNavigationKt",
                emptyList<String>(),
                50,
                hook
            )
            refreshStatus()
            true
        } catch (t: Throwable) {
            println("[NCM Helper] 原生界面 Hook 安装失败: $t")
            hookHandle = null
            hookClass = null
            false
        }
    }

    @JvmStatic
    fun uninstall() {
        try {
            hookHandle?.unhook()
        } catch (_: Throwable) {
        }
        hookHandle = null
        hookClass = null
        visible = false
    }

    internal fun refreshStatus() {
        status = NeteaseUiBridge.statusText()
    }

    internal fun startScan() {
        message = "正在申请二维码…"
        qrRows = null
        scanToken++
    }

    internal var scanToken by mutableStateOf(0)

    internal fun clipboardLogin() {
        Thread {
            val text = try {
                NeteaseUiBridge.clipboardLogin()
            } catch (t: Throwable) {
                "登录失败: $t"
            }
            message = text
            refreshStatus()
        }.start()
    }
}

private class PanelHook : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        // 零尺寸锚点：不参与宿主布局，只借这个调用点挂载原生 Dialog
        Box(Modifier.size(0.dp))
        if (NeteaseNativeUi.visible) {
            NeteasePanel()
        }
    }
}

@Composable
private fun NeteasePanel() {
    Dialog(onDismissRequest = { NeteaseNativeUi.visible = false }) {
        Box(
            Modifier
                .width(520.dp)
                .background(Color(0xFF171E29), RoundedCornerShape(16.dp))
                .padding(22.dp)
        ) {
            Column {
                BasicText(
                    "网易云音乐",
                    style = TextStyle(color = Color(0xFFEAF1FA), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    NeteaseNativeUi.status.ifEmpty { NeteaseUiBridge.statusText() },
                    style = TextStyle(color = Color(0xFF8FA3B8), fontSize = 13.sp)
                )
                if (NeteaseNativeUi.message.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        NeteaseNativeUi.message,
                        style = TextStyle(color = Color(0xFFB9CDE3), fontSize = 12.sp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelButton("从剪贴板登录") { NeteaseNativeUi.clipboardLogin() }
                    Spacer(Modifier.width(10.dp))
                    PanelButton("扫码登录") { NeteaseNativeUi.startScan() }
                    Spacer(Modifier.width(10.dp))
                    PanelButton("关闭") { NeteaseNativeUi.visible = false }
                }
                val rows = NeteaseNativeUi.qrRows
                if (rows != null) {
                    Spacer(Modifier.height(16.dp))
                    QrCanvas(rows)
                }
            }
        }
    }

    // 扫码登录副作用：申请 key → 绘制二维码 → 每 2 秒轮询
    LaunchedEffect(NeteaseNativeUi.scanToken) {
        if (NeteaseNativeUi.scanToken == 0) return@LaunchedEffect
        val key = withContext(Dispatchers.IO) {
            runCatching { NeteaseUiBridge.requestQrKey() }.getOrNull()
        }
        if (key == null) {
            NeteaseNativeUi.message = "申请二维码失败（可能被风控，请稍后再试）"
            return@LaunchedEffect
        }
        NeteaseNativeUi.qrRows = withContext(Dispatchers.IO) {
            runCatching { QrCode.matrix(LOGIN_PREFIX + key, 300) }.getOrNull()
        }
        NeteaseNativeUi.message = "请用网易云 App 扫码"
        while (true) {
            delay(2000)
            val code = withContext(Dispatchers.IO) { NeteaseUiBridge.pollQr(key) }
            when (code) {
                803 -> {
                    NeteaseNativeUi.message = "登录成功"
                    NeteaseNativeUi.qrRows = null
                    NeteaseNativeUi.refreshStatus()
                    break
                }
                802 -> NeteaseNativeUi.message = "已扫码，请在手机上确认"
                800 -> {
                    NeteaseNativeUi.message = "二维码已过期，请重新点击扫码登录"
                    NeteaseNativeUi.qrRows = null
                    break
                }
                406, 8821 -> NeteaseNativeUi.message = "请求过于频繁，请稍后再试（或改用剪贴板登录）"
            }
        }
    }
}

@Composable
private fun PanelButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color(0xFF2D6CDF), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 13.sp))
    }
}

@Composable
private fun QrCanvas(rows: Array<BooleanArray>) {
    val n = rows.size
    Box(Modifier.size(296.dp).background(Color.White, RoundedCornerShape(12.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (rows[y][x]) {
                        drawRect(
                            Color.Black,
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell, cell)
                        )
                    }
                }
            }
        }
    }
}
