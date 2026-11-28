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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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

internal val APP_TABS = listOf(
    "推荐歌单" to "playlist",
    "每日推荐" to "daily",
    "音乐漫游" to "roam",
    "私人歌单" to "mine",
    "推荐新歌" to "newsong"
)

private val GRID_COLUMNS = 5
private val CARD_WIDTH = 186.dp
private val COVER_HEIGHT = 183.dp

/** 本插件在宿主导航中的页面路由，全部通过反射访问宿主类 */
object NeteaseRoute {
    internal const val PLUGIN_ID = "io.github.spwncm.ncmhelper"
    private const val ROUTE_PATH = PLUGIN_ID + "/netease"
    private const val MOD_ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute" + "$" + "ModConfigContent"

    @Volatile
    private var navigator: Any? = null

    fun remember(nav: Any?) {
        if (nav != null) navigator = nav
    }

    fun hasNavigator(): Boolean = navigator != null

    private fun host(): ClassLoader = PageSlot::class.java.classLoader

    fun create(): Any? = try {
        val cls = Class.forName(MOD_ROUTE_CLASS, true, host())
        cls.getConstructor(String::class.java).newInstance(ROUTE_PATH)
    } catch (t: Throwable) {
        Log.warn("创建宿主路由失败: " + t)
        null
    }

    fun isOurs(key: Any?): Boolean {
        if (key == null) return false
        return try {
            val cls = key.javaClass
            if (cls.simpleName.contains("ModConfigContent")) {
                for (name in listOf("getPluginId", "getRoute", "pluginId")) {
                    runCatching {
                        val m = cls.getMethod(name)
                        val v = m.invoke(key) ?: return@runCatching
                        if (v.toString().contains(ROUTE_PATH)) return true
                    }
                }
                for (f in cls.declaredFields) {
                    if (f.name.contains("pluginId", ignoreCase = true) || f.name.contains("route", ignoreCase = true)) {
                        f.isAccessible = true
                        val v = f.get(key) ?: continue
                        if (v.toString().contains(ROUTE_PATH)) return true
                    }
                }
                return key.toString().contains(ROUTE_PATH)
            }
            for (m in cls.methods) {
                if (m.parameterCount == 0 && m.returnType.simpleName.contains("ScreenRoute")) {
                    val route = runCatching { m.invoke(key) }.getOrNull()
                    if (route != null && isOurs(route)) return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun navigateTo(): Boolean {
        val nav = navigator ?: run {
            Log.warn("尚未读取到宿主 Navigator，无法路由导航")
            return false
        }
        val route = create() ?: return false
        return try {
            val method = nav.javaClass.methods.firstOrNull { it.name == "navigate" && it.parameterCount == 1 }
                ?: return false
            method.invoke(nav, route)
            true
        } catch (t: Throwable) {
            Log.warn("路由导航失败: " + t)
            false
        }
    }

    fun back(): Boolean {
        val nav = navigator ?: return false
        return try {
            nav.javaClass.getMethod("back").invoke(nav)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * 页面内容槽：包装宿主页面内容 lambda，路由命中本插件页面时渲染网易云页面。
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
    private var lastLoggedOurs: Boolean? = null

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
            if (lambdaClass.isInstance(current)) {
                failure = "slot-leftover-container-please-restart-host"
                Log.warn("页面槽存在上次版本的残留对象，已放弃接管；请完整重启 Salt Player")
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
                val ours = NeteaseRoute.isOurs(key)
                if (lastLoggedOurs != ours) {
                    lastLoggedOurs = ours
                    Log.info("页面内容槽调用: ours=" + ours + ", key=" + key?.javaClass?.simpleName)
                }
                if (ours) {
                    NeteaseNativeUi.onRoutePageShown()
                    NeteaseEmbeddedPage()
                } else {
                    NeteaseNativeUi.onRoutePageHidden()
                    fallback(key)
                }
            }

            var rawBody: Any = wrapped
            if (lambdaClass.isInstance(wrapped)) rawBody = field.get(wrapped)
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
    }

    @JvmStatic
    fun refresh() {
        lastLoggedOurs = null
    }

    fun resetKey() {
        // 路由方案下无需页面 key 记录
    }

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
}

// ---------------- 主题一致的界面组件 ----------------
// 全部使用宿主主题的 CompositionLocal（LocalContentColor / LocalTextStyle）与透明背景，
// 不写死颜色，从而与软件当前主题（含深色模式）保持一致。

@Composable
internal fun NeteaseSidebarRow(onClick: () -> Unit = { NeteaseNativeUi.openEmbedded() }) {
    val content = LocalContentColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (NeteaseNativeUi.pageVisible) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("♪", style = LocalTextStyle.current.copy(fontSize = 15.sp), color = Color.White)
        Spacer(Modifier.width(10.dp))
        Text("网易云音乐", style = LocalTextStyle.current.copy(fontSize = 14.sp), color = Color.White)
    }
}

@Composable
internal fun NeteaseEmbeddedPage() {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        NeteaseNativeUi.onEmbeddedRendered()
    }
    androidx.compose.runtime.LaunchedEffect(NeteaseNativeUi.avatarToken) {
        NeteaseNativeUi.loadAvatar()
    }
    // 不绘制自己的背景：直接使用宿主页面背景
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        EmbeddedHeader()
        Spacer(Modifier.height(10.dp))
        EmbeddedTabs()
        Spacer(Modifier.height(14.dp))
        EmbeddedGrid()
        Spacer(Modifier.height(10.dp))
        EmbeddedFooter()
    }
}

@Composable
private fun EmbeddedHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("网易云音乐", style = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            color = Color.White)
        Spacer(Modifier.width(12.dp))
        Text(
            NeteaseNativeUi.status.ifEmpty { "未登录" },
            style = LocalTextStyle.current.copy(fontSize = 12.sp),
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .width(200.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (NeteaseNativeUi.searchText.isEmpty()) {
                Text("搜索歌曲 / 歌手", style = LocalTextStyle.current.copy(fontSize = 12.sp),
                    color = Color.White.copy(alpha = 0.6f))
            }
            BasicTextField(
                value = NeteaseNativeUi.searchText,
                onValueChange = { NeteaseNativeUi.searchText = it },
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color.White),
                singleLine = true
            )
        }
        Spacer(Modifier.width(8.dp))
        ThemeAction("搜索") { NeteaseNativeUi.search() }
        ThemeAction("登录") { NeteaseNativeUi.clipboardLogin() }
        ThemeAction("刷新") { NeteaseNativeUi.loadCards() }
        ThemeAction("关闭") { NeteaseNativeUi.closePage() }
        Spacer(Modifier.width(10.dp))
        AccountAvatar()
    }
}

/** 右上角账号头像：圆形，未登录时显示占位 */
@Composable
private fun AccountAvatar() {
    val bitmap = NeteaseNativeUi.avatar
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = "账号头像",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("♪", style = LocalTextStyle.current.copy(fontSize = 13.sp), color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun EmbeddedTabs() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        APP_TABS.forEachIndexed { index, (label, key) ->
            val selected = NeteaseNativeUi.tab == key
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { NeteaseNativeUi.switchTab(key) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    label,
                    style = LocalTextStyle.current.copy(
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.7f)
                )
            }
            if (index < APP_TABS.size - 1) Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun EmbeddedGrid() {
    val cards = NeteaseNativeUi.cards
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            Text(
                if (NeteaseNativeUi.loading) "正在加载…" else "暂无内容（点「刷新」或先点「登录」）",
                style = LocalTextStyle.current.copy(fontSize = 13.sp),
                color = Color.White.copy(alpha = 0.7f)
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
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmbeddedCard(card: NeteaseClient.Card) {
    Column(Modifier.width(CARD_WIDTH).clickable { NeteaseNativeUi.openCard(card) }) {
        Box(
            Modifier
                .width(CARD_WIDTH)
                .height(COVER_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = NeteaseNativeUi.covers[card.coverUrl]
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text("♪", style = LocalTextStyle.current.copy(fontSize = 24.sp),
                    color = Color.White.copy(alpha = 0.5f))
            }
            if (card.kind == "song") {
                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Text(formatDuration(card.duration),
                        style = LocalTextStyle.current.copy(fontSize = 10.sp), color = Color.White)
                }
            }
            if (card.kind == "playlist" && card.playCount > 0) {
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Text("▶ " + formatPlayCount(card.playCount),
                        style = LocalTextStyle.current.copy(fontSize = 10.sp), color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(card.title, style = LocalTextStyle.current.copy(fontSize = 13.sp),
            color = Color.White, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(card.subtitle, style = LocalTextStyle.current.copy(fontSize = 11.sp),
            color = Color.White.copy(alpha = 0.7f), maxLines = 1)
    }
}

@Composable
private fun EmbeddedFooter() {
    Column {
        if (NeteaseNativeUi.dlActive || NeteaseNativeUi.dlText.isNotEmpty()) {
            Text(NeteaseNativeUi.dlText.ifEmpty { "下载中…" },
                style = LocalTextStyle.current.copy(fontSize = 12.sp), color = Color.White)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.22f))) {
                val f = NeteaseNativeUi.dlFraction.coerceIn(0f, 1f)
                if (f > 0f) {
                    Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(Color.White))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(NeteaseNativeUi.dlStats, style = LocalTextStyle.current.copy(fontSize = 11.sp),
                    color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.width(10.dp))
                if (NeteaseNativeUi.dlActive) ThemeAction("取消下载") { NeteaseUiBridge.downloadCancel() }
            }
        } else if (NeteaseNativeUi.message.isNotEmpty()) {
            Text(NeteaseNativeUi.message, style = LocalTextStyle.current.copy(fontSize = 12.sp),
                color = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun ThemeAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, style = LocalTextStyle.current.copy(fontSize = 12.sp), color = Color.White)
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
