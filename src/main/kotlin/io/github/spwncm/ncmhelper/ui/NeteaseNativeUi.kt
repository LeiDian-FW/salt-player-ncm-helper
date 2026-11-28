@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.spwncm.ncmhelper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

private const val LOGIN_PREFIX = "https://music.163.com/login?codekey="

// 浅色发现页配色（贴近截图）
private val PAGE_BG = Color(0xFFF3F4F6)
private val HEADER_TEXT = Color(0xFF1D2129)
private val SUB_TEXT = Color(0xFF86909C)
private val ACCENT = Color(0xFF2D6CDF)
private val CARD_BG = Color(0xFFFFFFFF)
private val TRACK = Color(0xFFE5E6EB)
private val BADGE_BG = Color(0x99000000)

private val TABS = listOf(
    "推荐歌单" to "playlist",
    "每日推荐" to "daily",
    "音乐漫游" to "roam",
    "私人歌单" to "mine",
    "推荐新歌" to "newsong"
)

/**
 * 原生 Compose 网易云发现页：浅色页面 + 标题栏 + 页签 + 封面卡片网格 + 下载进度。
 */
object NeteaseNativeUi {

    private var hookHandle: HookHandle? = null
    private val hookHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val dialogShown = java.util.concurrent.atomic.AtomicBoolean(false)
    private var candidateIndex = 0

    internal var visible by mutableStateOf(false)
    internal var status by mutableStateOf("")
    internal var message by mutableStateOf("")
    internal var tab by mutableStateOf("playlist")
    internal var cards by mutableStateOf<List<NeteaseClient.Card>>(emptyList())
    internal var covers by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
    internal var loading by mutableStateOf(false)
    internal var searchText by mutableStateOf("")
    internal var loadToken by mutableStateOf(0)

    internal var dlActive by mutableStateOf(false)
    internal var dlFraction by mutableStateOf(0f)
    internal var dlText by mutableStateOf("")
    internal var dlStats by mutableStateOf("")

    // 扫码登录（保留）
    internal var qrRows by mutableStateOf<Array<BooleanArray>?>(null)
    internal var scanToken by mutableStateOf(0)
    internal var showQr by mutableStateOf(false)

    @JvmStatic
    fun isInstalled(): Boolean = hookHandle != null

    @JvmStatic
    fun isVisible(): Boolean = visible

    @JvmStatic
    fun setVisible(value: Boolean) {
        visible = value
        dialogShown.set(false)
        if (value) {
            refreshStatus()
            if (cards.isEmpty()) loadCards()
        }
    }

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

    @JvmStatic
    fun retryNextCandidate(): Boolean {
        try {
            hookHandle?.unhook()
        } catch (_: Throwable) {
        }
        hookHandle = null
        candidateIndex = (candidateIndex + 1) % hookTargets.size
        return install()
    }

    @JvmStatic
    fun currentTarget(): String {
        val t = hookTargets.getOrNull(candidateIndex) ?: return "未安装"
        return "候选${candidateIndex + 1}/${hookTargets.size}: ${t.owner}#${t.name}"
    }

    @JvmStatic
    fun hookHits(): Int = hookHits.get()

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

    internal fun switchTab(key: String) {
        tab = key
        Log.info("原生面板: 切换到页签 " + key)
        loadCards()
    }

    internal fun search() {
        val kw = searchText.trim()
        if (kw.isEmpty()) {
            message = "请输入搜索关键词"
            return
        }
        NeteaseUiBridge.setSearchKeyword(kw)
        Log.info("原生面板: 搜索 " + kw)
        tab = "search"
        loadCards()
    }

    internal fun loadCards() {
        loading = true
        loadToken++
        message = ""
        cards = emptyList()
        Thread {
            try {
                cards = NeteaseUiBridge.discovery(tab)
                Log.info("原生面板: 加载 " + tab + " 成功，共 " + cards.size + " 项")
            } catch (t: Throwable) {
                message = "加载失败: " + t.message
                Log.warn("原生面板: 加载 " + tab + " 失败: " + t)
            } finally {
                loading = false
            }
        }.start()
    }

    internal fun loadCovers() {
        val list = cards
        if (list.isEmpty()) return
        Thread {
            val map = HashMap<String, ImageBitmap>()
            for (card in list) {
                if (card.coverUrl.isEmpty() || covers.containsKey(card.coverUrl)) continue
                try {
                    val bytes = NeteaseUiBridge.cover(card.coverUrl) ?: continue
                    val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: continue
                    map[card.coverUrl] = img.toComposeImageBitmap()
                } catch (_: Throwable) {
                }
            }
            if (map.isNotEmpty()) covers = covers + map
        }.start()
    }

    internal fun openCard(card: NeteaseClient.Card) {
        Log.info("原生面板: 点击卡片 " + card.kind + " " + card.title)
        if (!NeteaseUiBridge.loggedIn()) {
            message = "请先登录网易云（从剪贴板登录）"
            return
        }
        dlActive = true
        dlFraction = 0f
        dlText = "准备下载: " + card.title
        dlStats = ""
        NeteaseUiBridge.downloadCard(card.id, card.title, card.subtitle, card.kind,
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
                }
                override fun error(message: String) {
                    dlText = "下载失败: " + message
                    dlActive = false
                }
            })
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
            if (NeteaseUiBridge.loggedIn()) loadCards()
        }.start()
    }

    internal fun startScan() {
        Log.info("原生面板: 点击扫码登录")
        showQr = true
        message = "正在申请二维码…"
        qrRows = null
        scanToken++
    }

    // ---------------- 离屏渲染自测/截图 ----------------
    @JvmStatic
    fun renderScenario(scenario: String, path: String): Boolean {
        when (scenario) {
            "discovery" -> {
                tab = "playlist"
                status = "已登录：源谷绘"
                cards = demoCards()
            }
            "search" -> {
                tab = "newsong"
                status = "已登录：源谷绘"
                cards = demoSongs()
                dlActive = true
                dlFraction = 0.38f
                dlText = "(2/10) イイコと妖狐 - きくお / 绮萱"
                dlStats = "成功 1 · 跳过 0 · 失败 0"
            }
            "scan" -> {
                showQr = true
                qrRows = runCatching { QrCode.matrix(LOGIN_PREFIX + "TEST-KEY", 300) }.getOrNull()
                message = "请用网易云 App 扫码"
            }
            else -> {
                status = "未登录"
                cards = emptyList()
            }
        }
        return renderPanelForTest(path, 1240, 860)
    }

    private fun demoCards(): List<NeteaseClient.Card> = listOf(
        demoPlaylist("イイコと妖狐", "きくお / 绮萱", 233000, 1289000),
        demoPlaylist("再生", "ナナツカゼ / PIKASONIC", 161000, 560000),
        demoPlaylist("下一站泊风港", "蛋仔派对", 90000, 320000),
        demoPlaylist("素颜", "许嵩 / 何曼婷", 238000, 2100000),
        demoPlaylist("山楂树之恋", "程佳佳", 184000, 870000),
        demoPlaylist("hello&bye, days", "洛天依 / 乐正绫", 252000, 430000),
        demoPlaylist("还是分开", "张叶蕾", 210000, 150000),
        demoPlaylist("花之舞", "大依", 200000, 98000),
        demoPlaylist("黄泉之舞", "洛天依", 240000, 760000),
        demoPlaylist("心似烟火", "陈壹千", 271000, 1200000)
    )

    private fun demoPlaylist(title: String, artist: String, duration: Long, playCount: Long): NeteaseClient.Card {
        val c = demoCard(title, artist, duration, "playlist")
        c.playCount = playCount
        return c
    }

    private fun demoSongs(): List<NeteaseClient.Card> = demoCards()

    private fun demoCard(title: String, artist: String, duration: Long, kind: String): NeteaseClient.Card {
        val c = NeteaseClient.Card()
        c.id = title.hashCode().toLong()
        c.title = title
        c.subtitle = artist
        c.duration = duration
        c.kind = kind
        return c
    }

    /** 离屏渲染：把发现页渲染成 PNG（无主机环境验证 UI） */
    @JvmStatic
    fun renderPanelForTest(path: String, width: Int, height: Int): Boolean {
        return try {
            val scene = androidx.compose.ui.ImageComposeScene(width, height) {
                NeteasePageContent()
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

    // ---------------- 候选注入点 ----------------
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
}

private class PanelHook : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        NeteaseNativeUi.onHookHit()
        Box(Modifier.size(0.dp))
        if (NeteaseNativeUi.visible && NeteaseNativeUi.claimDialog()) {
            NeteasePage()
        }
    }
}

@Composable
private fun NeteasePage() {
    androidx.compose.runtime.DisposableEffect(Unit) {
        Log.info("原生面板已显示")
        onDispose { Log.info("原生面板已关闭") }
    }
    Dialog(onDismissRequest = { NeteaseNativeUi.visible = false }) {
        NeteasePageContent()
    }
}

@Composable
internal fun NeteasePageContent() {
    Column(
        Modifier
            .width(1200.dp)
            .height(840.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PAGE_BG)
            .padding(horizontal = 26.dp, vertical = 18.dp)
    ) {
        Header()
        Spacer(Modifier.height(14.dp))
        TabRow()
        Spacer(Modifier.height(16.dp))
        CoverGrid()
        Spacer(Modifier.height(12.dp))
        if (NeteaseNativeUi.showQr) {
            QrArea()
            Spacer(Modifier.height(10.dp))
        }
        BottomBar()
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("网易云", style = TextStyle(color = HEADER_TEXT, fontSize = 26.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(16.dp))
        BasicText(NeteaseNativeUi.status.ifEmpty { "未登录" },
            style = TextStyle(color = SUB_TEXT, fontSize = 13.sp))
        Spacer(Modifier.weight(1f))
        if (NeteaseNativeUi.message.isNotEmpty()) {
            BasicText(NeteaseNativeUi.message, style = TextStyle(color = ACCENT, fontSize = 12.sp))
            Spacer(Modifier.width(12.dp))
        }
        InputBox(NeteaseNativeUi.searchText, "搜索歌曲/歌手") { NeteaseNativeUi.searchText = it }
        Spacer(Modifier.width(8.dp))
        PageButton("搜索") { NeteaseNativeUi.search() }
        Spacer(Modifier.width(8.dp))
        PageButton("从剪贴板登录") { NeteaseNativeUi.clipboardLogin() }
        Spacer(Modifier.width(8.dp))
        PageButton("扫码登录") { NeteaseNativeUi.startScan() }
        Spacer(Modifier.width(8.dp))
        PageButton("刷新") { NeteaseNativeUi.loadCards() }
        Spacer(Modifier.width(8.dp))
        PageButton("关闭") { NeteaseNativeUi.visible = false }
    }
}

@Composable
private fun TabRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TABS.forEachIndexed { index, (label, key) ->
            val selected = NeteaseNativeUi.tab == key
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) CARD_BG else Color.Transparent)
                    .clickable { NeteaseNativeUi.switchTab(key) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                BasicText(label, style = TextStyle(
                    color = if (selected) HEADER_TEXT else SUB_TEXT,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal))
            }
            if (index < TABS.size - 1) Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
private fun CoverGrid() {
    val cards = NeteaseNativeUi.cards
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(430.dp), contentAlignment = Alignment.Center) {
            BasicText(
                if (NeteaseNativeUi.loading) "正在加载…" else "暂无内容（点「刷新」或先登录）",
                style = TextStyle(color = SUB_TEXT, fontSize = 14.sp)
            )
        }
        return
    }
    val rows = cards.chunked(5).take(2)
    Column {
        rows.forEach { rowCards ->
            Row {
                rowCards.forEach { card ->
                    CardItem(card)
                    Spacer(Modifier.width(14.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CardItem(card: NeteaseClient.Card) {
    Column(
        Modifier
            .width(204.dp)
            .clickable { NeteaseNativeUi.openCard(card) }
    ) {
        Box(
            Modifier
                .width(204.dp)
                .height(204.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(TRACK),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = NeteaseNativeUi.covers[card.coverUrl]
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                BasicText("♫", style = TextStyle(color = SUB_TEXT, fontSize = 30.sp))
            }
            if (card.kind == "song") {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp)).background(BADGE_BG)
                    .padding(horizontal = 6.dp, vertical = 2.dp)) {
                    BasicText(formatDuration(card.duration),
                        style = TextStyle(color = Color.White, fontSize = 11.sp))
                }
            }
            if (card.kind == "playlist" && card.playCount > 0) {
                Box(Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp)).background(BADGE_BG)
                    .padding(horizontal = 6.dp, vertical = 2.dp)) {
                    BasicText("▶ " + formatPlayCount(card.playCount),
                        style = TextStyle(color = Color.White, fontSize = 11.sp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(card.title, style = TextStyle(color = HEADER_TEXT, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(3.dp))
        BasicText(card.subtitle, style = TextStyle(color = SUB_TEXT, fontSize = 12.sp))
    }
}

@Composable
private fun QrArea() {
    Row {
        val rows = NeteaseNativeUi.qrRows
        if (rows != null) QrCanvas(rows)
        Spacer(Modifier.width(14.dp))
        BasicText("请用网易云 App 扫码（若提示 8821 请改用「从剪贴板登录」）",
            style = TextStyle(color = SUB_TEXT, fontSize = 12.sp))
    }
}

@Composable
private fun BottomBar() {
    Column {
        if (NeteaseNativeUi.dlActive || NeteaseNativeUi.dlText.isNotEmpty()) {
            BasicText(NeteaseNativeUi.dlText.ifEmpty { "下载中…" },
                style = TextStyle(color = HEADER_TEXT, fontSize = 13.sp))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(8.dp)
                .clip(RoundedCornerShape(4.dp)).background(TRACK)) {
                val f = NeteaseNativeUi.dlFraction.coerceIn(0f, 1f)
                if (f > 0f) {
                    Box(Modifier.fillMaxWidth(f).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ACCENT))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(NeteaseNativeUi.dlStats, style = TextStyle(color = SUB_TEXT, fontSize = 12.sp))
                Spacer(Modifier.width(12.dp))
                if (NeteaseNativeUi.dlActive) {
                    PageButton("取消下载") { NeteaseUiBridge.downloadCancel() }
                }
            }
        } else {
            BasicText("点击卡片下载（歌单=下载整单，歌曲=下载单曲）；文件归档到音乐库",
                style = TextStyle(color = SUB_TEXT, fontSize = 12.sp))
        }
    }
}

@Composable
private fun PageButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CARD_BG)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        BasicText(text, style = TextStyle(color = HEADER_TEXT, fontSize = 13.sp))
    }
}

@Composable
private fun InputBox(value: String, hint: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CARD_BG)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (value.isEmpty()) {
            BasicText(hint, style = TextStyle(color = SUB_TEXT, fontSize = 13.sp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = HEADER_TEXT, fontSize = 13.sp),
            singleLine = true
        )
    }
}

@Composable
private fun QrCanvas(rows: Array<BooleanArray>) {
    val n = rows.size
    Box(Modifier.size(220.dp).background(Color.White, RoundedCornerShape(10.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (rows[y][x]) {
                        drawRect(Color(0xFF1D2129), topLeft = Offset(x * cell, y * cell),
                            size = Size(cell, cell))
                    }
                }
            }
        }
    }
}

private fun formatPlayCount(count: Long): String {
    return when {
        count >= 100_000_000 -> String.format("%.1f亿", count / 100_000_000.0)
        count >= 10_000 -> String.format("%.1f万", count / 10_000.0)
        else -> count.toString()
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format("%02d:%02d", m, s)
}
