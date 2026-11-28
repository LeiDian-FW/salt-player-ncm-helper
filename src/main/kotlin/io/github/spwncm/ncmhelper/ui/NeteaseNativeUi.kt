@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class)

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
    private var sidebarHandle: HookHandle? = null
    private var overlayHandle: HookHandle? = null
    private val hookHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val dialogShown = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sidebarTargetLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    private val seenNewPlaylist = java.util.concurrent.atomic.AtomicBoolean(false)
    private val overlayClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sidebarHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val overlayHits = java.util.concurrent.atomic.AtomicInteger(0)
    private var candidateIndex = 0

    /** 侧边栏入口 + 内嵌页面是否可用（不再用独立弹窗） */
    internal var embedded by mutableStateOf(false)
    internal var pageVisible by mutableStateOf(false)
    internal var dialogFallback by mutableStateOf(false)
    private val embeddedRenderCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val embeddedRenderSeen = java.util.concurrent.atomic.AtomicLong(0)

    internal var visible by mutableStateOf(false)
    internal var status by mutableStateOf("")
    internal var message by mutableStateOf("")
    internal var tab by mutableStateOf("playlist")
    internal var cards by mutableStateOf<List<NeteaseClient.Card>>(emptyList())
    internal val covers = androidx.compose.runtime.mutableStateMapOf<String, ImageBitmap>()
    internal var loading by mutableStateOf(false)
    internal var searchText by mutableStateOf("")
    internal var avatar by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    internal var accountMenu by mutableStateOf(false)
    internal var myPlaylistCount by mutableStateOf(-1)
    internal var avatarToken by mutableStateOf(0)
    internal var loadToken by mutableStateOf(0)

    internal var dlActive by mutableStateOf(false)
    internal var dlVisible by mutableStateOf(false)

    // 歌单详情（浏览曲目 + 勾选下载）
    internal var viewMode by mutableStateOf("discover") // discover / detail
    internal var detailId by mutableStateOf(0L)
    internal var detailTitle by mutableStateOf("")
    internal var detailCoverUrl by mutableStateOf("")
    internal var detailTracks by mutableStateOf<List<NeteaseClient.Song>>(emptyList())
    internal var selectedTracks by mutableStateOf<Set<Int>>(emptySet())
    internal var loadingDetail by mutableStateOf(false)
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
        pageVisible = value
        dialogFallback = false
        dialogShown.set(false)
        PageSlot.resetKey()
        if (value) {
            refreshStatus()
            if (cards.isEmpty()) loadCards()
        }
    }

    /** 侧边栏点击：显示内嵌页面；若内嵌未真正渲染则自动用弹窗兜底 */
    internal fun openEmbedded() {
        Log.info("侧边栏: 打开网易云音乐页面")
        // 每次打开都重置：弹窗一次性标记、页面 key、内嵌渲染计数
        dialogShown.set(false)
        dialogFallback = false
        PageSlot.resetKey()
        pageVisible = true
        visible = true
        refreshStatus()
        if (cards.isEmpty()) loadCards()
        val before = embeddedRenderCount.get()
        Thread {
            try {
                Thread.sleep(900)
            } catch (_: InterruptedException) {
            }
            if (embeddedRenderCount.get() == before) {
                Log.warn("内嵌页面未渲染，使用弹窗兜底显示")
                dialogFallback = true
            }
        }.start()
    }

    /** 路由页面正在显示（由页面槽包装器调用） */
    internal fun onRoutePageShown() {
        if (!pageVisible) {
            pageVisible = true
            refreshStatus()
            if (cards.isEmpty()) loadCards()
        }
    }

    /** 路由离开本插件页面 */
    internal fun onRoutePageHidden() {
        if (pageVisible) {
            pageVisible = false
            dialogFallback = false
            dialogShown.set(false)
        }
    }

    internal fun onEmbeddedRendered() {
        embeddedRenderCount.incrementAndGet()
        Log.info("内嵌页面已组合渲染")
        // 内嵌已渲染：若兜底弹窗已弹出，自动收起，避免双份界面
        if (dialogFallback) {
            dialogFallback = false
            Log.info("内嵌页面已渲染，自动收起兜底弹窗")
        }
    }

    internal fun closePage() {
        pageVisible = false
        visible = false
        dialogFallback = false
        dialogShown.set(false)
        PageSlot.resetKey()
        releaseOverlay()
        if (embedded) {
            val ok = NeteaseRoute.back()
            Log.info("关闭页面: 路由返回 = " + ok)
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
                installEmbedded()
                refreshStatus()
                return true
            } catch (t: Throwable) {
                Log.warn("原生界面候选 ${index + 1} 注入失败（${target.owner}#${target.name}）: $t")
            }
        }
        Log.warn("原生界面所有候选注入点均失败，将回退 Swing 面板")
        return false
    }

    /** 捕获宿主 Navigator 实例（hook navigate/back 的 this 对象） */
    private fun installNavigatorCapture() {
        try {
            val hook = object : com.xuncorp.spw.workshop.api.hook.MethodHook() {
                override fun before(param: com.xuncorp.spw.workshop.api.hook.MethodHookParam) {
                    NeteaseRoute.remember(param.thisObject)
                }
            }
            WorkshopApi.hookRegistrar.hookMethod(
                "com.xuncorp.voxzen.ui.navigation.Navigator", "navigate",
                listOf("com.xuncorp.voxzen.ui.navigation.ScreenRoute"), hook
            )
            Log.info("Navigator 捕获 Hook 已安装")
        } catch (t: Throwable) {
            Log.warn("Navigator 捕获 Hook 安装失败: $t")
        }
    }

    /** 安装侧边栏入口，并尝试接管宿主页面内容槽实现主界面内嵌显示 */
    private fun installEmbedded() {
        installNavigatorCapture()
        if (sidebarHandle == null) {
            try {
                sidebarHandle = WorkshopApi.hookRegistrar.afterComposable(
                    "androidx.compose.ui.\u0b1a", "\u037f",
                    "androidx.compose.ui.\u0b1a$\u052e",
                    listOf("androidx.compose.ui.\u063a$\u052a"), 60, SidebarHook())
                sidebarTargetLogged.set(false)
                Log.info("侧边栏入口 Hook 已安装")
            } catch (t: Throwable) {
                Log.warn("侧边栏入口 Hook 安装失败: $t")
            }
        }
        if (overlayHandle == null) {
            val ok = PageSlot.install()
            embedded = ok
            if (ok) {
                overlayHandle = null
                Log.info("主界面页面内容槽已接管（内嵌显示，非独立窗口）")
            } else {
                Log.warn("页面内容槽接管失败（${PageSlot.failure()}），回退独立弹窗")
            }
        }
    }

    internal fun onSidebarHit() {
        sidebarHits.incrementAndGet()
    }

    internal fun onOverlayHit() {
        overlayHits.incrementAndGet()
    }

    internal fun markSidebarTarget(): Boolean {
        seenNewPlaylist.set(true)
        return sidebarTargetLogged.compareAndSet(false, true)
    }

    internal fun newPlaylistSeen(): Boolean = seenNewPlaylist.get()

    internal fun claimOverlay(): Boolean = overlayClaimed.compareAndSet(false, true)

    internal fun releaseOverlay() {
        overlayClaimed.set(false)
    }

    @JvmStatic
    fun sidebarHitCount(): Int = sidebarHits.get()

    @JvmStatic
    fun overlayHitCount(): Int = overlayHits.get()

    @JvmStatic
    fun isEmbedded(): Boolean = embedded

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
        for (h in listOf(hookHandle, sidebarHandle, overlayHandle)) {
            try {
                h?.unhook()
            } catch (_: Throwable) {
            }
        }
        hookHandle = null
        sidebarHandle = null
        overlayHandle = null
        embedded = false
        visible = false
        pageVisible = false
    }

    internal fun onHookHit() {
        hookHits.incrementAndGet()
    }

    internal fun claimDialog(): Boolean = dialogShown.compareAndSet(false, true)

    internal fun refreshStatus() {
        status = NeteaseUiBridge.statusText()
        avatarToken++
    }

    /** 退出登录并刷新界面 */
    internal fun openAccountMenu() {
        accountMenu = !accountMenu
        if (accountMenu && NeteaseUiBridge.loggedIn()) {
            myPlaylistCount = -1
            Thread {
                myPlaylistCount = NeteaseUiBridge.myPlaylistCount()
            }.start()
        }
    }

    internal fun logoutAccount() {
        Log.info("账号菜单: 退出登录")
        NeteaseUiBridge.logout()
        accountMenu = false
        refreshStatus()
        loadAvatar()
    }

    /** 加载账号头像（后台线程，内存解码） */
    internal fun loadAvatar() {
        // 启动后首次打开页面时补拉一次账号资料（昵称/头像/uid）
        if (NeteaseUiBridge.loggedIn() && NeteaseUiBridge.uid() == 0L) {
            NeteaseUiBridge.refreshProfile()
        }
        val url = NeteaseUiBridge.avatarUrl()
        if (url.isEmpty()) {
            avatar = null
            return
        }
        Thread {
            try {
                val bytes = NeteaseUiBridge.avatarImage(url) ?: return@Thread
                javax.imageio.ImageIO.setUseCache(false)
                val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return@Thread
                avatar = img.toComposeImageBitmap()
                Log.info("账号头像已加载")
            } catch (_: Throwable) {
            }
        }.start()
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
                loadCovers()
            } catch (t: Throwable) {
                message = "加载失败: " + t.message
                Log.warn("原生面板: 加载 " + tab + " 失败: " + t)
            } finally {
                loading = false
            }
        }.start()
    }

    private val coversLoading = java.util.concurrent.atomic.AtomicBoolean(false)

    internal fun loadCovers() {
        val list = cards
        if (list.isEmpty()) return
        if (!coversLoading.compareAndSet(false, true)) return
        Thread {
            try {
                val cacheDir = NeteaseUiBridge.cacheDir()
                val pending = list.filter { it.coverUrl.isNotEmpty() && !covers.containsKey(it.coverUrl) }
                if (pending.isEmpty()) {
                    coversLoading.set(false)
                    return@Thread
                }
                val map = HashMap<String, ImageBitmap>()
                for (card in pending) {
                    val img = decodeCover(card.coverUrl, cacheDir) ?: continue
                    map[card.coverUrl] = img
                }
                map.forEach { (k, v) -> covers[k] = v }
                Log.info("原生面板: 封面加载 " + map.size + "/" + pending.size + " 张")
            } catch (t: Throwable) {
                Log.warn("封面加载异常: " + t)
            } finally {
                coversLoading.set(false)
            }
        }.start()
    }

    /** 下载并解码一张封面（带磁盘缓存与损坏校验） */
    private fun decodeCover(url: String, cacheDir: String): ImageBitmap? {
        return try {
            val bytes = loadCoverBytes(url, cacheDir) ?: return null
            // 使用 v1.7.6 已验证可用的解码路径
            javax.imageio.ImageIO.setUseCache(false)
            val img = javax.imageio.ImageIO.read(ByteArrayInputStream(bytes))
            if (img == null) {
                Log.warn("封面解码失败: " + url.take(80) + " 字节=" + bytes.size)
                return null
            }
            // 缩放到最大 320px：内存占用从约 4MB/张 降到约 0.4MB/张，避免 GC 抖动
            val scaled = scaleDown(img, 320)
            scaled.toComposeImageBitmap()
        } catch (t: Throwable) {
            Log.warn("封面解码异常: " + t)
            null
        }
    }

    /** 曲目封面按需加载：由列表可见行触发，不再一次性预取 */
    internal fun loadTrackCovers(tracks: List<NeteaseClient.Song>) {
        // 预取前 12 张（首屏可见），其余滚动到可见时由 requestCover 加载
        tracks.take(12).forEach { if (it.coverUrl.isNotEmpty()) requestCover(it.coverUrl) }
    }

    private fun loadCoverBytes(url: String, cacheDir: String): ByteArray? {
        try {
            if (cacheDir.isNotEmpty()) {
                val name = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".img"
                val f = java.io.File(cacheDir, name)
                if (f.isFile && f.length() > 0) {
                    val cached = f.readBytes()
                    if (isImageBytes(cached)) return cached
                    // 缓存损坏（历史中断写入）：删除后重新下载
                    runCatching { f.delete() }
                }
                val bytes = NeteaseUiBridge.cover(url)
                if (bytes != null && bytes.isNotEmpty() && isImageBytes(bytes)) {
                    runCatching { f.writeBytes(bytes) }
                    return bytes
                }
                Log.warn("封面下载失败: " + url.take(80))
                return null
            }
            return NeteaseUiBridge.cover(url)
        } catch (t: Throwable) {
            Log.warn("封面读取异常: " + t)
            return null
        }
    }

    /** 等比缩小图片到最长边不超过 maxSize */
    private fun scaleDown(src: java.awt.image.BufferedImage, maxSize: Int): java.awt.image.BufferedImage {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxSize) return src
        val scale = maxSize.toDouble() / longest
        val nw = maxOf(1, (w * scale).toInt())
        val nh = maxOf(1, (h * scale).toInt())
        val out = java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, nw, nh, null)
        g.dispose()
        return out
    }

    /** 按需加载单张封面（可见行懒加载，单线程队列 + 去重 + 上限） */
    internal fun requestCover(url: String) {
        if (url.isEmpty() || covers.containsKey(url)) return
        if (!coverPending.add(url)) return
        coverQueue.execute {
            try {
                val img = decodeCover(url, NeteaseUiBridge.cacheDir())
                if (img != null) {
                    covers[url] = img
                    if (covers.size > 120) {
                        // 只保留最近 80 张，防止内存无限增长
                        val keep = covers.keys.toList().takeLast(80)
                        val drop = covers.keys.filter { it !in keep }
                        drop.forEach { covers.remove(it) }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                coverPending.remove(url)
            }
        }
    }

    private val coverQueue = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "netease-cover").also { it.isDaemon = true }
    }
    private val coverPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 粗略校验图片字节（JPEG/PNG/WEBP 头） */
    private fun isImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xD8) return true // JPEG
        if (b0 == 0x89 && b1 == 0x50) return true // PNG
        if (b0 == 0x52 && b1 == 0x49) return true // WEBP(RIFF)
        return false
    }

    internal fun openCard(card: NeteaseClient.Card) {
        Log.info("原生面板: 点击卡片 " + card.kind + " " + card.title)
        if (card.kind == "playlist") {
            openPlaylistDetail(card.id, card.title, card.coverUrl)
            return
        }
        if (!NeteaseUiBridge.loggedIn()) {
            message = "请先登录网易云（从剪贴板登录）"
            return
        }
        dlActive = true
        dlVisible = true
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
                    hideProgressSoon()
                }
                override fun error(message: String) {
                    dlText = if (message == "已取消") "已取消下载" else "下载失败: " + message
                    dlActive = false
                    hideProgressSoon()
                }
            })
    }

    /** 完成后/取消后 1.5 秒自动关闭进度条 */
    internal fun hideProgressSoon() {
        Thread {
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
            }
            dlVisible = false
            dlText = ""
            dlStats = ""
            dlFraction = 0f
        }.start()
    }

    internal fun cancelDownload() {
        Log.info("原生面板: 取消下载")
        NeteaseUiBridge.downloadCancel()
        dlText = "正在取消…"
    }

    /** 打开歌单详情并加载曲目 */
    internal fun openPlaylistDetail(playlistId: Long, title: String, coverUrl: String = "") {
        detailId = playlistId
        detailTitle = title
        detailCoverUrl = coverUrl
        detailTracks = emptyList()
        selectedTracks = emptySet()
        loadingDetail = true
        viewMode = "detail"
        message = ""
        Thread {
            try {
                detailTracks = NeteaseUiBridge.playlistTracks(playlistId)
                Log.info("歌单详情: " + title + " 共 " + detailTracks.size + " 首")
                loadTrackCovers(detailTracks)
            } catch (t: Throwable) {
                message = "加载歌单失败: " + t.message
            } finally {
                loadingDetail = false
            }
        }.start()
    }

    internal fun closeDetail() {
        viewMode = "discover"
        detailTracks = emptyList()
        selectedTracks = emptySet()
    }

    internal fun toggleTrack(index: Int) {
        selectedTracks = if (selectedTracks.contains(index)) selectedTracks - index else selectedTracks + index
    }

    internal fun selectAllTracks() {
        selectedTracks = detailTracks.indices.toSet()
    }

    internal fun clearSelectedTracks() {
        selectedTracks = emptySet()
    }

    /** 下载勾选的曲目（不限数量） */
    internal fun downloadSelected() {
        if (selectedTracks.isEmpty()) {
            message = "请先勾选曲目"
            return
        }
        if (!NeteaseUiBridge.loggedIn()) {
            message = "请先登录网易云（从剪贴板登录）"
            return
        }
        dlActive = true
        dlVisible = true
        dlFraction = 0f
        dlText = "准备下载 " + selectedTracks.size + " 首…"
        dlStats = ""
        message = ""
        val indices = selectedTracks.sorted().toIntArray()
        NeteaseUiBridge.downloadPicked(detailId, indices,
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
                    hideProgressSoon()
                }
                override fun error(message: String) {
                    dlText = if (message == "已取消") "已取消下载" else "下载失败: " + message
                    dlActive = false
                    hideProgressSoon()
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
                pageVisible = true
                embedded = true
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

    private var demoCoverSeq = 0

    private fun demoCard(title: String, artist: String, duration: Long, kind: String): NeteaseClient.Card {
        val c = NeteaseClient.Card()
        demoCoverSeq++
        c.coverUrl = "demo" + demoCoverSeq
        c.id = title.hashCode().toLong()
        c.title = title
        c.subtitle = artist
        c.duration = duration
        c.kind = kind
        return c
    }

    /** 离屏渲染测试用：注入一张本地封面图 */
    @JvmStatic
    fun injectCoverForTest(url: String, filePath: String): Boolean {
        return try {
            ImageIO.setUseCache(false)
            val img = ImageIO.read(java.io.File(filePath)) ?: return false
            covers[url] = img.toComposeImageBitmap()
            true
        } catch (_: Throwable) {
            false
        }
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

/** 侧边栏入口：在宿主「新建歌单」下方渲染「网易云音乐」一行（跟随宿主主题） */
private class SidebarHook : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        NeteaseNativeUi.onSidebarHit()
        captureNavigator()
        val item = runCatching { call.getArgument(0) }.getOrNull()
        val routeName = routeClassName(item)
        // 宿主侧边栏的「新建歌单」路由是 ScreenRoute$NewPlaylist：渲染在其下方
        if (routeName == "NewPlaylist") {
            if (NeteaseNativeUi.markSidebarTarget()) {
                Log.info("侧边栏: 命中 NewPlaylist（新建歌单），在其下方渲染网易云入口")
            }
            NeteaseSidebarRow {
            val ok = NeteaseRoute.navigateTo()
            Log.info("侧边栏: 导航到网易云页面 = " + ok)
            if (!ok) NeteaseNativeUi.openEmbedded()
        }
            return
        }
        // 仅在新歌单下方渲染，避免重复入口
    }

    /** 从宿主 RouteItem 上读取显示文本（反射 String 字段） */
    /** 在组合上下文中读取宿主 Navigator（currentComposer 只能在 @Composable 中读取） */
    @Composable
    private fun captureNavigator() {
        if (NeteaseRoute.hasNavigator()) return
        val composer = androidx.compose.runtime.currentComposer
        readNavigatorWithComposer(composer)
    }

    /** 反射读取 CompositionLocal<Navigator>.current（非组合代码，异常安全） */
    private fun readNavigatorWithComposer(composer: Any) {
        try {
            val host = PageSlot::class.java.classLoader
            val localClass = Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt", true, host)
            val local = localClass.getMethod("getLocalNavigator").invoke(null)
            val current = local.javaClass.getMethod(
                "getCurrent", androidx.compose.runtime.Composer::class.java, Int::class.javaPrimitiveType
            ).invoke(local, composer, 0)
            if (current != null) {
                NeteaseRoute.remember(current)
                Log.info("已从宿主 CompositionLocal 读取 Navigator: " + current.javaClass.name)
            }
        } catch (t: Throwable) {
            Log.warn("读取 Navigator 失败: " + t)
        }
    }

    /** 读取宿主 RouteItem 里的 ScreenRoute 具体类型名（如 NewPlaylist / Settings） */
    private fun routeClassName(item: Any?): String {
        if (item == null) return ""
        return try {
            for (field in item.javaClass.declaredFields) {
                if (field.type.name.contains("ScreenRoute")) {
                    field.isAccessible = true
                    val route = field.get(item) ?: continue
                    return route.javaClass.simpleName
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }
}

/** 主界面内嵌覆盖层：覆盖在宿主导航内容之上显示网易云页面 */
private class OverlayHook : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        NeteaseNativeUi.onOverlayHit()
        if (!NeteaseNativeUi.pageVisible) return
        if (!NeteaseNativeUi.claimOverlay()) return
        androidx.compose.runtime.DisposableEffect(Unit) {
            Log.info("原生面板已显示（内嵌主界面）")
            onDispose {
                NeteaseNativeUi.releaseOverlay()
                Log.info("原生面板已关闭（内嵌主界面）")
            }
        }
        Box(Modifier.fillMaxSize().background(PAGE_BG)) {
            NeteasePageContent()
        }
    }
}

private class PanelHook : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        NeteaseNativeUi.onHookHit()
        Box(Modifier.size(0.dp))
        if (NeteaseNativeUi.visible && NeteaseNativeUi.dialogFallback && NeteaseNativeUi.claimDialog()) {
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
        PageButton("关闭") { NeteaseNativeUi.closePage() }
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

