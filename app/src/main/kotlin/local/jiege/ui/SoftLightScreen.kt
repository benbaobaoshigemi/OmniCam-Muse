package local.jiege.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import local.jiege.settings.ModuleSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private val LEVELS = listOf(
    Triple(ModuleSettings.SOFT_LIGHT_WEAK_HDF, "弱", "HDF"),
    Triple(ModuleSettings.SOFT_LIGHT_MEDIUM_BLACK_MIST, "中", "1/4 黑柔"),
    Triple(ModuleSettings.SOFT_LIGHT_STRONG_WHITE_MIST, "强", "白柔"),
)

/** 拍摄 tab section: the entry to the physical soft light page. */
private val XPAN_CLEAN = listOf(
    ModuleSettings.XPAN_CLEAN_SMALL_MASK to "关闭小取景框的遮罩",
    ModuleSettings.XPAN_CLEAN_LARGE_MASK to "关闭大取景框的遮罩",
    ModuleSettings.XPAN_CLEAN_ANIMATION to "关闭显影动画",
)

internal fun LazyListScope.softLightItems(store: SettingsStore, cardModifier: Modifier, onOpen: () -> Unit, onOpenXpanClean: () -> Unit) {
    item(key = "experimental") {
        val replaced = LEVELS.filter { store.get(it.first) }.joinToString("，") { "${it.second}挡为${it.third}" }
        SmallTitle(text = "实验性功能")
        Card(modifier = cardModifier) {
            BasicComponent(
                title = "物理柔光",
                summary = replaced.ifEmpty { "未启用" },
                endActions = { Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                onClick = onOpen,
            )
            SettingSwitch(store = store, key = ModuleSettings.MASTER_PORTRA_PHYSICAL, title = "和光物理胶片",
                summary = "按柯达实测锐度模拟 Portra 400 的散射、光晕和边缘，仅成片")
            SettingSwitch(store = store, key = ModuleSettings.XPAN_DEVELOPING_LIVE, title = "XPAN显影水印",
                summary = "XPAN 照片存为实况，播放负片显影")
            val cleaned = XPAN_CLEAN.filter { store.get(it.first) }.joinToString("，") { it.second.removePrefix("关闭") }
            BasicComponent(
                title = "XPan 取景净化",
                summary = if (cleaned.isEmpty()) "未启用" else "已关闭$cleaned",
                endActions = { Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                onClick = onOpenXpanClean,
            )
        }
    }
}

/** The physical soft light page: each stock soft light level can be replaced on its own. */
@Composable
internal fun SoftLightScreen(padding: PaddingValues, nestedScroll: NestedScrollConnection, store: SettingsStore) {
    val cardModifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
    // The camera process cannot read the module's preferences, so the page also keeps the mask
    // and the strength in properties it can read; write them once on open so an edit made
    // elsewhere is picked up.
    LaunchedEffect(Unit) {
        pushLevels(store)
        pushStrength(store)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(nestedScroll),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        item(key = "levels") {
            Card(modifier = cardModifier) {
                for ((key, level, filter) in LEVELS) {
                    SettingSwitch(store = store, key = key, title = "${level}挡换成${filter}模拟",
                        onChange = { pushLevels(store) })
                }
            }
        }
        item(key = "strength") {
            Card(modifier = cardModifier) {
                var value by remember { mutableStateOf(store.getInt(ModuleSettings.SOFT_LIGHT_STRENGTH)) }
                BasicComponent(
                    title = "替换强度",
                    summary = "$value%，100% 为模拟的原型滤镜，越低晕越薄",
                    bottomAction = {
                        Slider(
                            value = value / 100f,
                            onValueChange = { value = Math.round(it * 100) },
                            onValueChangeFinished = {
                                // The render reads the property every time, so the strength needs
                                // no camera restart: the next shot already uses it.
                                store.setIntQuiet(ModuleSettings.SOFT_LIGHT_STRENGTH, value)
                                pushStrength(store)
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    },
                )
            }
        }
    }
}

/** Mirrors the three switches into persist.omnicam.aura.softlight as a bit mask. */
private fun pushLevels(store: SettingsStore) {
    val mask = (if (store.get(ModuleSettings.SOFT_LIGHT_WEAK_HDF)) 1 shl 1 else 0) or
        (if (store.get(ModuleSettings.SOFT_LIGHT_MEDIUM_BLACK_MIST)) 1 shl 0 else 0) or
        (if (store.get(ModuleSettings.SOFT_LIGHT_STRONG_WHITE_MIST)) 1 shl 2 else 0)
    rootCommand("setprop persist.omnicam.aura.softlight $mask")
}

/** Mirrors the strength slider into persist.omnicam.aura.slgain, which the capture reads. */
private fun pushStrength(store: SettingsStore) {
    rootCommand("setprop persist.omnicam.aura.slgain ${store.getInt(ModuleSettings.SOFT_LIGHT_STRENGTH)}")
}

/** XPan 取景净化: the small (framed) and large (full screen) viewfinder masks and the developing animation. */
@Composable
internal fun XpanCleanScreen(padding: PaddingValues, nestedScroll: NestedScrollConnection, store: SettingsStore) {
    val cardModifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(nestedScroll),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        item(key = "parts") {
            Card(modifier = cardModifier) {
                for ((key, title) in XPAN_CLEAN) {
                    SettingSwitch(store = store, key = key, title = title)
                }
            }
        }
    }
}
