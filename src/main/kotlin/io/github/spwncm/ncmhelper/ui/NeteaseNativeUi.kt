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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import io.github.spwncm.ncmhelper.Log
import io.github.spwncm.ncmhelper.NeteaseClient
import io.github.spwncm.ncmhelper.NeteaseUiBridge
import io.github.spwncm.ncmhelper.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val LOGIN_PREFIX = "https://music.163.com/login?codekey="

private val BG = Color(0xFF141B26)
private val CARD = Color(0xFF171E29)
private val TRACK = Color(0xFF232C3A)
private val ACCENT = Color(0xFF2D6CDF)
private val TEXT = Color(0xFFEAF1FA)
private val DIM = Color(0xFF8FA3B8)
private val CHIP = Color(0xFF232F40)

/**
 * 原生 Compose 网易云界面：Hook 宿主导航根组件后以原生 Dialog 渲染。
 * 页签：登录 / 搜索 / 歌单；下载进度为原生进度条。
 */
object NeteaseNativeUi {

    private var hookHandle: HookHandle? = null
    private val hookHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val dialogShown = java.util.concurrent.atomic.AtomicBoolean(false)
    private var candidateIndex = 0

    internal var visible by mutableStateOf(false)
    internal var status by mutableStateOf("")
    internal var message by mutableStateOf("")
    internal var qrRows by mutableStateOf<Array<BooleanArray>?>(null)
    internal var scanToken by mutableStateOf(0)

    internal var tab by mutableStateOf(0) // 0 登录 / 1 搜索 / 2 歌单
    internal var keyword by mutableStateOf("")
    internal var playlistId by mutableStateOf("")
    internal var listTitle by mutableStateOf("")
    internal var songs by mutableStateOf<List<NeteaseClient.Song>>(emptyList())
    internal var busy by mutableStateOf(false)

    internal var dlActive by mutableStateOf(false)
    internal var dlFraction by mutableStateOf(0f)
    internal var dlText by mutableStateOf("")
    internal var dlStats by mutableStateOf("")

    @JvmStatic
    fun isInstalled(): Boolean = hookHandle != null

    /** Hook 实际被调用的次数（用于判断注入点是否真的生效） */
    @JvmStatic
    fun hookHits(): Int = hookHits.get()

    @JvmStatic
    fun isVisible(): Boolean = visible

    @JvmStatic
    fun setVisible(value: Boolean) {
        visible = value
        dialogShown.set(false)
        if (value) refreshStatus()
    }

    /** 候选注入点：标题栏（参考实现同版本验证过）→ 导航内容根 → 侧栏项 */
    private data class HookTarget(
        val owner: String,
        val name: String,
        val caller: String,
        val params: List<String>
    )

    private val hookTargets = listOf(
        HookTarget(
            "androidx.compose.ui.\u0b61", "\u037f", "androidx.compose.ui.\u0537",
            listOf(
                "androidx.compose.ui.\u04d7", "androidx.compose.ui.\u0aa0",
                "androidx.compose.ui.\u091a", "androidx.compose.ui.Modifier"
            )
        ),
        HookTarget(
            "com.xuncorp.voxzen.ui.navigation.AppNavigationKt", "Content",
            "com.xuncorp.voxzen.ui.navigation.AppNavigationKt", emptyList()
        ),
        HookTarget(
            "androidx.compose.ui.\u0b1a", "\u037f", "androidx.compose.ui.\u0b1a$\u052e",
            listOf("androidx.compose.ui.\u063a$\u052a")
        )
    )

    @JvmStatic
    fun install(): Boolean {
        if (hookHandle != null) return true
        val order = (0 until hookTargets.size).map { (candidateIndex + it) % hookTargets.size }
        for (index in order) {
            val target = hookTargets[index]
            try {
                hookHandle = WorkshopApi.hookRegistrar.afterComposable(
                    target.owner, target.name, target.caller, target.params, 50, PanelHook()
                )
                hookHits.set(0)
                candidateIndex = index
                Log.info("原生界面已安装（候选 ${index + 1}/${hookTargets.size}：${target.owner}#${target.name}）")
                refreshStatus()
                return true
            } catch (t: Throwable) {
                Log.warn("原生界面候选 ${index + 1} 注入失败（${target.owner}#${target.name}）: $t")
            }
        }
        Log.warn("原生界面所有候选注入点均失败，将回退 Swing 面板")
        return false
    }

    /** 离屏渲染指定场景（自测/截图用） */
    @JvmStatic
    fun renderScenario(scenario: String, path: String): Boolean {
        when (scenario) {
            "search" -> {
                tab = 1
                keyword = "unravel"
                listTitle = "搜索「unravel」：30 首"
                songs = listOf(
                    songOf("unravel", "TK from 凛として時雨"),
                    songOf("unravel (acoustic version)", "TK from 凛として時雨"),
                    songOf("unravel (TV Edit)", "TK from 凛として時雨")
                )
                dlActive = true
                dlFraction = 0.42f
                dlText = "(2/10) unravel - TK from 凛として時雨"
                dlStats = "成功 1 · 跳过 0 · 失败 0"
                message = ""
            }
            "scan" -> {
                tab = 0
                qrRows = runCatching { QrCode.matrix(LOGIN_PREFIX + "TEST-KEY", 300) }.getOrNull()
                message = "请用网易云 App 扫码"
                status = "未登录"
            }
            "playlist" -> {
                tab = 2
                playlistId = "3778678"
                listTitle = "歌单：200 首"
                songs = listOf(
                    songOf("海屿你", "马也_Crabbit"),
                    songOf("夜に駆ける", "YOASOBI"),
                    songOf("群青", "YOASOBI")
                )
                message = ""
            }
            else -> {
                tab = 0
                status = "未登录"
            }
        }
        return renderPanelForTest(path, 720, 860)
    }

    private fun songOf(name: String, artist: String): NeteaseClient.Song {
        val s = NeteaseClient.Song()
        s.name = name
        s.artists.add(artist)
        return s
    }

    /** 离屏渲染自测：把原生面板渲染成 PNG，用于无主机环境验证 UI 可组合 */
    @JvmStatic
    fun renderPanelForTest(path: String, width: Int, height: Int): Boolean {
        return try {
            val scene = androidx.compose.ui.ImageComposeScene(width, height) {
                NeteasePanelContent()
            }
            val image = scene.render()
            val bytes = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            scene.close()
            if (bytes == null || bytes.isEmpty()) return false
            java.io.File(path).writeBytes(bytes)
            true
        } catch (t: Throwable) {
            Log.error("原生面板离屏渲染失败: $t")
            false
        }
    }

    /** 当前注入点未生效时，卸载并切换到下一个候选注入点 */
    @JvmStatic
    fun retryNextCandidate(): Boolean {
        try {
            hookHandle?.unhook()
        } catch (_: Throwable) {
        }
        hookHandle = null
        candidateIndex = (candidateIndex + 1) % hookTargets.size
        Log.info("原生界面：切换到候选注入点 ${candidateIndex + 1}/${hookTargets.size}")
        return install()
    }

    /** 当前生效的注入点描述（诊断用） */
    @JvmStatic
    fun currentTarget(): String {
        val t = hookTargets.getOrNull(candidateIndex) ?: return "未安装"
        return "候选${candidateIndex + 1}/${hookTargets.size}: ${t.owner}#${t.name}"
    }

    @JvmStatic
    fun uninstall() {
        try {
            hookHandle?.unhook()
        } catch (_: Throwable) {
        }
        hookHandle = null
        visible = false
    }

    internal fun onHookHit() {
        hookHits.incrementAndGet()
    }

    internal fun claimDialog(): Boolean = dialogShown.compareAndSet(false, true)

    internal fun refreshStatus() {
        status = NeteaseUiBridge.statusText()
    }

    internal fun startScan() {
        Log.info("原生面板: 点击扫码登录")
        message = "正在申请二维码…"
        qrRows = null
        scanToken++
    }

    internal fun clipboardLogin() {
        Log.info("原生面板: 点击从剪贴板登录")
        Thread {
            message = try {
                NeteaseUiBridge.clipboardLogin()
            } catch (t: Throwable) {
                "登录失败: $t"
            }
            refreshStatus()
        }.start()
    }

    /** 加载搜索或歌单列表（后台线程） */
    internal fun loadSongs(kind: String) {
        Log.info("原生面板: 加载列表 " + kind + " keyword=" + keyword + " playlist=" + playlistId)
        if (busy) return
        busy = true
        message = "正在加载…"
        Thread {
            try {
                val result = when (kind) {
                    "playlist" -> NeteaseUiBridge.playlist(playlistId.trim().toLong())
                    "likes" -> NeteaseUiBridge.likes()
                    else -> NeteaseUiBridge.search(keyword.trim(), 30)
                }
                songs = result
                listTitle = when (kind) {
                    "playlist" -> "歌单：" + result.size + " 首"
                    "likes" -> "我喜欢的音乐：" + result.size + " 首"
                    else -> "搜索「" + keyword.trim() + "」：" + result.size + " 首"
                }
                message = ""
            } catch (t: Throwable) {
                message = "加载失败: " + t.message
            } finally {
                busy = false
            }
        }.start()
    }

    /** 原生面板内开始下载（kind: search / playlist / likes） */
    internal fun startDownload(kind: String) {
        Log.info("原生面板: 开始下载 " + kind + " arg=" + (if (kind == "playlist") playlistId else keyword))
        if (dlActive) return
        if (!NeteaseUiBridge.loggedIn()) {
            message = "请先登录网易云"
            return
        }
        if (!NeteaseUiBridge.downloadLimitValid()) {
            message = "「每次最多下载数」需填写 1-50 的整数"
            return
        }
        dlActive = true
        dlFraction = 0f
        dlStats = ""
        dlText = "准备下载…"
        val arg = when (kind) {
            "playlist" -> playlistId.trim()
            "likes" -> ""
            else -> keyword.trim()
        }
        NeteaseUiBridge.downloadAsync(kind, arg, NeteaseUiBridge.downloadLimit(),
            object : NeteaseUiBridge.ProgressSink {
                override fun status(text: String) { dlText = text }

                override fun song(index: Int, total: Int, name: String, artist: String) {
                    dlText = "(" + index + "/" + total + ") " + name +
                            (if (artist.isEmpty()) "" else " - " + artist)
                }

                override fun progress(fraction: Float) { dlFraction = fraction }

                override fun stats(text: String) { dlStats = text }

                override fun done(summary: String) {
                    dlFraction = 1f
                    dlText = summary
                    dlActive = false
                    refreshStatus()
                }

                override fun error(message: String) {
                    dlText = "下载失败: " + message
                    dlActive = false
                }
            })
    }
}

/** 零尺寸锚点 + 原生 Dialog */
private class PanelHook : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        NeteaseNativeUi.onHookHit()
        Box(Modifier.size(0.dp))
        // 某些注入点会按列表项多次调用，这里保证同一时刻只显示一个面板
        if (NeteaseNativeUi.visible && NeteaseNativeUi.claimDialog()) NeteasePanel()
    }
}

@Composable
private fun NeteasePanel() {
    androidx.compose.runtime.DisposableEffect(Unit) {
        Log.info("原生面板已显示")
        onDispose { Log.info("原生面板已关闭") }
    }
    Dialog(onDismissRequest = {
        NeteaseNativeUi.visible = false
    }) {
        NeteasePanelContent()
    }
}

@Composable
internal fun NeteasePanelContent() {
    Box(
            Modifier
                .width(620.dp)
                .background(BG, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                // 标题栏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("网易云音乐",
                        style = TextStyle(color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(12.dp))
                    BasicText(NeteaseNativeUi.status,
                        style = TextStyle(color = DIM, fontSize = 12.sp))
                }
                Spacer(Modifier.height(12.dp))

                // 页签
                Row {
                    Tab("登录", 0)
                    Spacer(Modifier.width(8.dp))
                    Tab("搜索", 1)
                    Spacer(Modifier.width(8.dp))
                    Tab("歌单", 2)
                    Spacer(Modifier.width(8.dp))
                    Tab("我喜欢", 3)
                }
                Spacer(Modifier.height(14.dp))

                when (NeteaseNativeUi.tab) {
                    0 -> LoginTab()
                    1 -> SearchTab()
                    2 -> PlaylistTab()
                    else -> LikesTab()
                }

                // 下载进度
                if (NeteaseNativeUi.dlActive || NeteaseNativeUi.dlText.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    ProgressSection()
                }

                if (NeteaseNativeUi.message.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    BasicText(NeteaseNativeUi.message, style = TextStyle(color = Color(0xFFB9CDE3), fontSize = 12.sp))
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (NeteaseNativeUi.songs.isNotEmpty()) {
                        BasicText(NeteaseNativeUi.listTitle, style = TextStyle(color = DIM, fontSize = 12.sp))
                    }
                    Spacer(Modifier.width(10.dp))
                    PanelButton("关闭") { NeteaseNativeUi.visible = false }
                }

                if (NeteaseNativeUi.songs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SongList()
                }
            }
    }

    // 扫码登录副作用
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
            when (withContext(Dispatchers.IO) { NeteaseUiBridge.pollQr(key) }) {
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
private fun LoginTab() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelButton("从剪贴板登录") { NeteaseNativeUi.clipboardLogin() }
        Spacer(Modifier.width(10.dp))
        PanelButton("扫码登录") { NeteaseNativeUi.startScan() }
        Spacer(Modifier.width(10.dp))
        PanelButton("刷新状态") { NeteaseNativeUi.refreshStatus() }
    }
    val rows = NeteaseNativeUi.qrRows
    if (rows != null) {
        Spacer(Modifier.height(14.dp))
        QrCanvas(rows)
    }
}

@Composable
private fun SearchTab() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InputBox(NeteaseNativeUi.keyword, "输入歌曲/歌手关键词") {
            NeteaseNativeUi.keyword = it
        }
        Spacer(Modifier.width(8.dp))
        PanelButton("搜索") { NeteaseNativeUi.loadSongs("search") }
        Spacer(Modifier.width(8.dp))
        PanelButton("下载前 N 首") { NeteaseNativeUi.startDownload("search") }
    }
}

@Composable
private fun PlaylistTab() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InputBox(NeteaseNativeUi.playlistId, "歌单 ID（数字）") {
            NeteaseNativeUi.playlistId = it
        }
        Spacer(Modifier.width(8.dp))
        PanelButton("加载") { NeteaseNativeUi.loadSongs("playlist") }
        Spacer(Modifier.width(8.dp))
        PanelButton("下载歌单") { NeteaseNativeUi.startDownload("playlist") }
    }
}

@Composable
private fun LikesTab() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelButton("刷新列表") { NeteaseNativeUi.loadSongs("likes") }
        Spacer(Modifier.width(8.dp))
        PanelButton("下载我喜欢的音乐") { NeteaseNativeUi.startDownload("likes") }
    }
}

@Composable
private fun Tab(text: String, index: Int) {
    val selected = NeteaseNativeUi.tab == index
    Box(
        Modifier
            .background(if (selected) ACCENT else CHIP, RoundedCornerShape(8.dp))
            .clickable {
                NeteaseNativeUi.tab = index
                Log.info("原生面板: 切换到页签 " + text)
            }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        BasicText(text, style = TextStyle(color = if (selected) Color.White else DIM, fontSize = 13.sp))
    }
}

@Composable
private fun InputBox(value: String, hint: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .width(280.dp)
            .background(CHIP, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (value.isEmpty()) {
            BasicText(hint, style = TextStyle(color = Color(0xFF6E8298), fontSize = 13.sp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = TEXT, fontSize = 13.sp),
            singleLine = true
        )
    }
}

@Composable
private fun ProgressSection() {
    Column {
        BasicText(NeteaseNativeUi.dlText.ifEmpty { "下载中…" },
            style = TextStyle(color = TEXT, fontSize = 13.sp))
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(TRACK, RoundedCornerShape(6.dp))
        ) {
            val f = NeteaseNativeUi.dlFraction.coerceIn(0f, 1f)
            if (f > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(f)
                        .height(10.dp)
                        .background(ACCENT, RoundedCornerShape(6.dp))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(NeteaseNativeUi.dlStats,
                style = TextStyle(color = DIM, fontSize = 12.sp))
            Spacer(Modifier.width(12.dp))
            if (NeteaseNativeUi.dlActive) {
                PanelButton("取消下载") { NeteaseUiBridge.downloadCancel() }
            }
        }
    }
}

@Composable
private fun SongList() {
    Column {
        NeteaseNativeUi.songs.take(15).forEachIndexed { i, song ->
            BasicText(
                (i + 1).toString() + ". " + song.name +
                        (if (song.artistText().isEmpty()) "" else " - " + song.artistText()),
                style = TextStyle(color = Color(0xFFC9D8EA), fontSize = 12.sp)
            )
            Spacer(Modifier.height(3.dp))
        }
        if (NeteaseNativeUi.songs.size > 15) {
            BasicText("… 共 " + NeteaseNativeUi.songs.size + " 首",
                style = TextStyle(color = DIM, fontSize = 11.sp))
        }
    }
}

@Composable
private fun PanelButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(ACCENT, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
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
                        drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell, cell))
                    }
                }
            }
        }
    }
}
