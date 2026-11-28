@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.spwncm.ncmhelper.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spwncm.ncmhelper.Log
import io.github.spwncm.ncmhelper.NeteaseClient
import io.github.spwncm.ncmhelper.NeteaseUiBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 页面内容槽：宿主用 ComposableSingletons$AppNavigation_desktopKt 里的一个
 * ComposableLambdaImpl 渲染主界面页面内容。我们包装它的 _block：
 * 打开网易云页面时渲染我们的页面，否则调用原内容——因此页面显示在主界面内容区，
 * 和宿主的其他页面一致（不是独立窗口）。
 */
object PageSlot {

    private const val SINGLETON = "com.xuncorp.voxzen.ui.navigation.ComposableSingletons\$AppNavigation_desktopKt"
    private const val GETTER = "getLambda\$1605156558\$Voxzen_composeApp"
    private const val LAMBDA = "androidx.compose.runtime.internal.ComposableLambdaImpl"

    private var slot: Any? = null
    private var blockField: Field? = null
    private var update: Method? = null
    private var original: Any? = null
    private var wrapper: Any? = null
    private var failure: String = "not-installed"

    /** 宿主当前页面 key；用于检测用户切换了其他侧边栏项时自动关闭本页面 */
    private val pageKey = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    private var observedKey: Any? = null

    fun isInstalled(): Boolean = wrapper != null


    fun failure(): String = failure

    fun install(): Boolean {
        if (wrapper != null) return true
        return try {
            val host = PageSlot::class.java.classLoader
            val owner = Class.forName(SINGLETON, true, host)
            val instance = owner.getField("INSTANCE").get(null)
            val target = owner.getMethod(GETTER).invoke(instance)
            val lambdaClass = Class.forName(LAMBDA, false, host)
            if (!lambdaClass.isInstance(target)) {
                failure = "slot-not-lambda"
                return false
            }
            val field = lambdaClass.getDeclaredField("_block").apply { isAccessible = true }
            val upd = lambdaClass.getMethod("update", Any::class.java)
            var current = field.get(target)
            // 上一次版本可能把 Compose 容器写进了槽位（历史残留）：先解包，
            // 若解出的不是 Function3 则放弃接管（绝不写入非法对象）。
            if (lambdaClass.isInstance(current)) {
                // 检测到历史残留容器：无法安全还原原始内容，明确放弃接管并提示重启播放器。
                failure = "slot-leftover-container-please-restart-host"
                Log.warn("页面槽存在上次版本的残留对象，已放弃接管；请完整退出并重启 Salt Player 以清除残留")
                return false
            }
            if (current !is kotlin.jvm.functions.Function3<*, *, *, *>) {
                failure = "slot-not-function3:" + current.javaClass.name
                return false
            }
            val fallback = current as? @Composable (Any) -> Unit
            if (fallback == null) {
                failure = "slot-arity-unsupported"
                return false
            }

            val wrapped: @Composable (Any) -> Unit = { key ->
                observedKey = key
                if (NeteaseNativeUi.pageVisible) {
                    val expected = pageKey.get()
                    if (expected == null) {
                        pageKey.set(key)
                    } else if (expected !== key) {
                        // 用户切换了其他侧边栏页面：关闭网易云页面
                        pageKey.set(null)
                        NeteaseNativeUi.pageVisible = false
                    }
                } else if (pageKey.get() != null) {
                    pageKey.set(null)
                }
                if (NeteaseNativeUi.pageVisible) {
                    NeteaseEmbeddedPage()
                } else {
                    fallback(key)
                }
            }

            // _block 必须是原始 arity-3 函数体（Function3），不能是 Compose 的容器对象，
            // 否则宿主调用时会 ComposableLambdaImpl -> Function3 类型转换崩溃。
            var rawBody: Any = wrapped
            if (lambdaClass.isInstance(wrapped)) {
                rawBody = field.get(wrapped)
            }
            if (rawBody !is kotlin.jvm.functions.Function3<*, *, *, *>) {
                failure = "block-body-not-function3:" + rawBody.javaClass.name
                return false
            }
            upd.invoke(target, rawBody)
            slot = target
            blockField = field
            update = upd
            original = current
            wrapper = rawBody
            failure = "none"
            Log.info("页面内容槽已接管（主界面内嵌显示）")
            true
        } catch (t: Throwable) {
            failure = t.toString()
            Log.warn("页面内容槽接管失败: $t")
            false
        }
    }

    /** 离屏渲染测试：把内嵌页面渲染成 PNG */
    @JvmStatic
    fun renderEmbeddedForTest(path: String, width: Int, height: Int): Boolean {
        return try {
            val scene = androidx.compose.ui.ImageComposeScene(width, height) {
                NeteaseEmbeddedPage()
            }
            val image = scene.render()
            val bytes = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            scene.close()
            if (bytes == null || bytes.isEmpty()) return false
            java.io.File(path).writeBytes(bytes)
            true
        } catch (t: Throwable) {
            Log.error("内嵌页面离屏渲染失败: $t")
            false
        }
    }

    fun uninstall() {
        try {
            if (wrapper != null) update?.invoke(slot, original)
        } catch (_: Throwable) {
        }
        wrapper = null
        slot = null
        blockField = null
        update = null
        original = null
        pageKey.set(null)
    }

    /** 让宿主重新渲染当前页面（例如从外部打开/关闭页面时） */
    @JvmStatic
    fun refresh() {
        pageKey.set(null)
    }
}

/** 侧边栏「网易云音乐」行：与宿主侧边栏项同样的主题色/间距 */
@Composable
internal fun NeteaseSidebarRow() {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val selected = NeteaseNativeUi.pageVisible
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { NeteaseNativeUi.openEmbedded() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            "♪",
            style = typography.bodyMedium.merge(TextStyle(
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant))
        )
        Spacer(Modifier.width(10.dp))
        BasicText(
            "网易云音乐",
            style = typography.bodyMedium.merge(TextStyle(
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal))
        )
    }
}

/** 内嵌在主界面内容区的网易云页面：配色使用宿主 Material 主题 */
@Composable
internal fun NeteaseEmbeddedPage() {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            EmbeddedHeader()
            Spacer(Modifier.height(12.dp))
            EmbeddedTabs()
            Spacer(Modifier.height(14.dp))
            EmbeddedGrid()
            Spacer(Modifier.height(10.dp))
            EmbeddedFooter()
        }
    }
}

internal val APP_TABS = listOf(
    "推荐歌单" to "playlist",
    "每日推荐" to "daily",
    "音乐漫游" to "roam",
    "私人歌单" to "mine",
    "推荐新歌" to "newsong"
)

private val GRID_COLUMNS = 5
private val CARD_WIDTH = 186.dp
private val COVER_HEIGHT = 186.dp

@Composable
private fun EmbeddedHeader() {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BasicText(
            "网易云音乐",
            style = typography.titleLarge.merge(TextStyle(color = colors.onSurface, fontWeight = FontWeight.Bold))
        )
        Spacer(Modifier.width(14.dp))
        BasicText(
            NeteaseNativeUi.status.ifEmpty { "未登录" },
            style = typography.bodySmall.merge(TextStyle(color = colors.onSurfaceVariant))
        )
        Spacer(Modifier.weight(1f))
        // 搜索框
        Box(
            Modifier
                .width(220.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            if (NeteaseNativeUi.searchText.isEmpty()) {
                BasicText("搜索歌曲 / 歌手", style = typography.bodySmall.merge(
                    TextStyle(color = colors.onSurfaceVariant)))
            }
            androidx.compose.foundation.text.BasicTextField(
                value = NeteaseNativeUi.searchText,
                onValueChange = { NeteaseNativeUi.searchText = it },
                textStyle = typography.bodySmall.merge(TextStyle(color = colors.onSurface)),
                singleLine = true
            )
        }
        Spacer(Modifier.width(10.dp))
        ThemeAction("搜索") { NeteaseNativeUi.search() }
        Spacer(Modifier.width(4.dp))
        ThemeAction("登录") { NeteaseNativeUi.clipboardLogin() }
        Spacer(Modifier.width(4.dp))
        ThemeAction("刷新") { NeteaseNativeUi.loadCards() }
        Spacer(Modifier.width(4.dp))
        ThemeAction("关闭") { NeteaseNativeUi.closePage() }
    }
}

@Composable
private fun EmbeddedTabs() {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically) {
        APP_TABS.forEachIndexed { index, (label, key) ->
            val selected = NeteaseNativeUi.tab == key
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { NeteaseNativeUi.switchTab(key) }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                BasicText(
                    label,
                    style = typography.bodyMedium.merge(TextStyle(
                        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    ))
                )
            }
            if (index < APP_TABS.size - 1) Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun EmbeddedGrid() {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val cards = NeteaseNativeUi.cards
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            BasicText(
                if (NeteaseNativeUi.loading) "正在加载…" else "暂无内容（点「刷新」或先点「登录」）",
                style = typography.bodyMedium.merge(TextStyle(color = colors.onSurfaceVariant))
            )
        }
        return
    }
    Column {
        cards.chunked(GRID_COLUMNS).take(2).forEach { rowCards ->
            Row {
                rowCards.forEach { card ->
                    EmbeddedCard(card)
                    Spacer(Modifier.width(12.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun EmbeddedCard(card: NeteaseClient.Card) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(
        Modifier
            .width(CARD_WIDTH)
            .clickable { NeteaseNativeUi.openCard(card) }
    ) {
        Box(
            Modifier
                .width(CARD_WIDTH)
                .height(COVER_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = NeteaseNativeUi.covers[card.coverUrl]
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                BasicText("♪", style = TextStyle(color = colors.onSurfaceVariant, fontSize = 26.sp))
            }
            if (card.kind == "song") {
                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(androidx.compose.ui.graphics.Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 1.dp)) {
                    BasicText(formatDuration(card.duration), style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp))
                }
            }
            if (card.kind == "playlist" && card.playCount > 0) {
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(androidx.compose.ui.graphics.Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 1.dp)) {
                    BasicText("▶ " + formatPlayCount(card.playCount),
                        style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(card.title, style = typography.bodySmall.merge(TextStyle(color = colors.onSurface)))
        Spacer(Modifier.height(2.dp))
        BasicText(card.subtitle, style = typography.labelSmall.merge(TextStyle(color = colors.onSurfaceVariant)))
    }
}

@Composable
private fun EmbeddedFooter() {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column {
        if (NeteaseNativeUi.dlActive || NeteaseNativeUi.dlText.isNotEmpty()) {
            BasicText(NeteaseNativeUi.dlText.ifEmpty { "下载中…" },
                style = typography.bodySmall.merge(TextStyle(color = colors.onSurface)))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceVariant)) {
                val f = NeteaseNativeUi.dlFraction.coerceIn(0f, 1f)
                if (f > 0f) {
                    Box(Modifier.fillMaxWidth(f).height(7.dp).clip(RoundedCornerShape(4.dp))
                        .background(colors.primary))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(NeteaseNativeUi.dlStats,
                    style = typography.labelSmall.merge(TextStyle(color = colors.onSurfaceVariant)))
                Spacer(Modifier.width(10.dp))
                if (NeteaseNativeUi.dlActive) ThemeAction("取消下载") { NeteaseUiBridge.downloadCancel() }
            }
        } else if (NeteaseNativeUi.message.isNotEmpty()) {
            BasicText(NeteaseNativeUi.message,
                style = typography.bodySmall.merge(TextStyle(color = colors.primary)))
        }
    }
}

@Composable
private fun ThemeAction(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        BasicText(text, style = TextStyle(color = colors.primary, fontSize = 13.sp))
    }
}

internal fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000 -> String.format("%.1f亿", count / 100_000_000.0)
    count >= 10_000 -> String.format("%.1f万", count / 10_000.0)
    else -> count.toString()
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSeconds = ms / 1000
    return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
