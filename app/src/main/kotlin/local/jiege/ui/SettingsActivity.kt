package local.jiege.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import local.jiege.settings.MasterLooks
import local.jiege.settings.ModuleSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Sidebar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SettingsScreen(store)
            }
        }
    }
}

/** A switch bound to one module setting. */
@Composable
internal fun SettingSwitch(
    store: SettingsStore,
    key: String,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit = {},
) {
    var checked by remember { mutableStateOf(store.get(key)) }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        onCheckedChange = {
            checked = it
            store.set(key, it)
            onChange(it)
        },
    )
}

/** A 0-100 slider bound to one integer module setting; saved when the drag ends. */
@Composable
internal fun SettingPercent(
    store: SettingsStore,
    key: String,
    title: String,
    enabled: Boolean = true,
    summary: String? = null,
) {
    var value by remember { mutableStateOf(store.getInt(key)) }
    BasicComponent(
        title = title,
        summary = if (summary == null) "$value%" else "$value%，$summary",
        bottomAction = {
            Slider(
                value = value / 100f,
                onValueChange = { value = Math.round(it * 100) },
                onValueChangeFinished = { store.setInt(key, value) },
                enabled = enabled,
                modifier = Modifier.padding(top = 8.dp),
            )
        },
    )
}

/** A slider bound to one look parameter setting (MasterLooks.Param); saved when the drag ends. */
@Composable
internal fun SettingNumber(store: SettingsStore, param: MasterLooks.Param, revision: Int = 0) {
    var value by remember(param.key, revision) { mutableStateOf(store.getInt(param.key).coerceIn(param.min, param.max)) }
    val shown = if (param.scale == 1) "$value${param.unit}"
    else String.format(java.util.Locale.ROOT, "%.2f", value / param.scale.toFloat()) + param.unit
    BasicComponent(
        title = param.title,
        summary = shown,
        bottomAction = {
            Slider(
                value = value.toFloat(),
                onValueChange = { value = Math.round(it).coerceIn(param.min, param.max) },
                onValueChangeFinished = { store.setInt(param.key, value) },
                valueRange = param.min.toFloat()..param.max.toFloat(),
                modifier = Modifier.padding(top = 8.dp),
            )
        },
    )
}

private val TAB_TITLES = arrayOf("主页", "拍摄", "大师色彩", "大师滤镜", "杂项")
private const val TAB_HOME = 0
private const val TAB_SHOOT = 1
private const val TAB_MASTER_COLOUR = 2
private const val TAB_MASTER_FILTER = 3
private const val TAB_MISC = 4
// Second pages, opened from an entry on a tab; the back arrow returns to the tab.
private const val PAGE_NONE = 0
private const val PAGE_PRESETS = 1
private const val PAGE_KEY_MODE = 2
private const val PAGE_SOFT_LIGHT = 3
private const val PAGE_LOG = 4
private const val PAGE_XPAN_CLEAN = 5
private val PAGE_TITLES = mapOf(PAGE_PRESETS to "预设管理", PAGE_KEY_MODE to "启动模式", PAGE_SOFT_LIGHT to "物理柔光", PAGE_LOG to "日志", PAGE_XPAN_CLEAN to "XPan 取景净化")

@Composable
private fun SettingsScreen(store: SettingsStore) {
    val scrollBehavior = MiuixScrollBehavior()
    val cardModifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
    var tab by remember { mutableStateOf(TAB_HOME) }
    var page by remember { mutableStateOf(PAGE_NONE) }
    fun select(index: Int) {
        tab = index
        page = PAGE_NONE
    }
    val onSubPage = page != PAGE_NONE
    BackHandler(enabled = onSubPage) { page = PAGE_NONE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = PAGE_TITLES[page] ?: TAB_TITLES[tab],
                navigationIcon = {
                    if (onSubPage) {
                        IconButton(onClick = { page = PAGE_NONE }) {
                            Icon(imageVector = BackIcon, contentDescription = "返回", tint = MiuixTheme.colorScheme.onSurface)
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == TAB_HOME, onClick = { select(TAB_HOME) }, icon = HomeIcon, label = "主页")
                NavigationBarItem(selected = tab == TAB_SHOOT, onClick = { select(TAB_SHOOT) }, icon = CameraIcon, label = "拍摄")
                NavigationBarItem(selected = tab == TAB_MASTER_COLOUR, onClick = { select(TAB_MASTER_COLOUR) }, icon = MasterColorIcon, label = "大师色彩")
                NavigationBarItem(selected = tab == TAB_MASTER_FILTER, onClick = { select(TAB_MASTER_FILTER) }, icon = MiuixIcons.Basic.ArrowUpDown, label = "大师滤镜")
                NavigationBarItem(selected = tab == TAB_MISC, onClick = { select(TAB_MISC) }, icon = MiuixIcons.Basic.Sidebar, label = "杂项")
            }
        },
    ) { padding ->
        val scroll = scrollBehavior.nestedScrollConnection
        when {
            page == PAGE_PRESETS -> ModelPresetScreen(padding, scroll, store)
            page == PAGE_KEY_MODE -> KeyLaunchModeScreen(padding, scroll, store)
            page == PAGE_SOFT_LIGHT -> SoftLightScreen(padding, scroll, store)
            page == PAGE_LOG -> LogScreen(padding, scroll)
            page == PAGE_XPAN_CLEAN -> XpanCleanScreen(padding, scroll, store)
            tab == TAB_MASTER_COLOUR -> MasterLookScreen(padding, scroll, store)
            tab == TAB_MASTER_FILTER -> MasterFilterScreen(padding, scroll, store)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(scroll),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            ) {
                when (tab) {
                    TAB_HOME -> homeItems(cardModifier, onOpenLog = { page = PAGE_LOG })
                    TAB_SHOOT -> shootItems(store, cardModifier, onOpenSoftLight = { page = PAGE_SOFT_LIGHT }, onOpenXpanClean = { page = PAGE_XPAN_CLEAN })
                    else -> miscItems(store, cardModifier, onOpenPresets = { page = PAGE_PRESETS }, onOpenKeyModes = { page = PAGE_KEY_MODE })
                }
            }
        }
    }
}

/** The 拍摄 tab: settings grouped by camera mode. */
private fun LazyListScope.shootItems(store: SettingsStore, cardModifier: Modifier, onOpenSoftLight: () -> Unit, onOpenXpanClean: () -> Unit) {
    item(key = "photo") {
        PhotoSection(store, cardModifier)
    }
    item(key = "highpixel") {
        SmallTitle(text = "哈苏超清")
        Card(modifier = cardModifier) {
            SettingSwitch(
                store = store,
                key = ModuleSettings.HIGH_PIXEL_EFFECTS,
                title = "哈苏超清滤镜与调色盘",
                summary = "使用拍照模式的滤镜、调色盘和柔光，入口在取景框角落",
            )
            SettingSwitch(
                store = store,
                key = ModuleSettings.HIGH_PIXEL_200M_NO_FACE,
                title = "哈苏超清200MP解除人脸识别门禁",
                summary = "画面里没有大人脸也走真 200M 读出。仍需 1.0×、光线充足、非 HDR",
            )
        }
    }
    item(key = "pop") {
        SmallTitle(text = "POP")
        Card(modifier = cardModifier) {
            SettingSwitch(
                store = store,
                key = ModuleSettings.POP_SDR_STYLE,
                title = "POP 使用 SDR 风格",
                summary = "成片为 SDR，偏亮，是 CCD 风味。关闭则为 Ultra HDR",
            )
            SettingSwitch(
                store = store,
                key = ModuleSettings.POP_HIGH_PIXEL,
                title = "POP 高像素",
                summary = "光线充足时输出中像素，画面中有人脸时保持原尺寸",
            )
        }
    }
    item(key = "video") {
        SmallTitle(text = "视频")
        Card(modifier = cardModifier) {
            SettingSwitch(
                store = store,
                key = ModuleSettings.VIDEO_FILTER_HIGH_SPEC,
                title = "4K / 60fps 视频滤镜",
                summary = "普通视频在 4K、60fps 下保留滤镜，不回退到 1080p30",
            )
        }
    }
    softLightItems(store, cardModifier, onOpenSoftLight, onOpenXpanClean)
}

/** The 杂项 tab: shutter sound, per-mode memory, quick launch key and the watermark items. */
private fun LazyListScope.miscItems(store: SettingsStore, cardModifier: Modifier, onOpenPresets: () -> Unit, onOpenKeyModes: () -> Unit) {
    item(key = "shutter") {
        var always by remember { mutableStateOf(store.get(ModuleSettings.SHUTTER_ALWAYS)) }
        var customVolume by remember { mutableStateOf(store.get(ModuleSettings.SHUTTER_VOLUME_CUSTOM)) }
        SmallTitle(text = "快门声")
        Card(modifier = cardModifier) {
            SettingSwitch(
                store = store,
                key = ModuleSettings.SHUTTER_ALWAYS,
                title = "静音和震动时播放快门声",
                summary = "需在 LSPosed 中对“系统框架”启用本模块",
                onChange = { always = it },
            )
            AnimatedVisibility(visible = always) {
                Column {
                    SettingSwitch(store = store, key = ModuleSettings.SHUTTER_IN_SILENT, title = "静音模式下播放")
                    SettingSwitch(store = store, key = ModuleSettings.SHUTTER_IN_VIBRATE, title = "震动模式下播放")
                }
            }
            SettingSwitch(
                store = store,
                key = ModuleSettings.SHUTTER_VOLUME_CUSTOM,
                title = "快门声独立音量",
                onChange = { customVolume = it },
            )
            AnimatedVisibility(visible = customVolume) {
                SettingPercent(store = store, key = ModuleSettings.SHUTTER_VOLUME, title = "快门声音量")
            }
        }
    }
    item(key = "modestate") {
        SmallTitle(text = "按模式记忆")
        Card(modifier = cardModifier) {
            SettingSwitch(
                store = store,
                key = ModuleSettings.FLASH_PER_MODE,
                title = "闪光灯按模式独立",
                summary = "每个模式记住自己的闪光灯设置",
            )
            SettingSwitch(
                store = store,
                key = ModuleSettings.LIVE_PHOTO_PER_MODE,
                title = "实况按模式独立",
                summary = "每个模式记住自己的实况开关",
            )
        }
    }
    keyLaunchItems(store, cardModifier, onOpenKeyModes)
    watermarkItems(store, cardModifier, onOpenPresets)
}
