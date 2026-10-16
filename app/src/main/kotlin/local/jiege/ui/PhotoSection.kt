package local.jiege.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import local.jiege.settings.ModuleSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * The 照片 card: natural photo mode (a master switch that opens the two features it turns off, both on
 * by default, and the extra softening slider, 0 % = stock) and the 25MP switch.
 */
@Composable
internal fun PhotoSection(store: SettingsStore, cardModifier: Modifier) {
    var natural by remember { mutableStateOf(store.get(ModuleSettings.NATURAL_MODE)) }
    SmallTitle(text = "照片")
    Card(modifier = cardModifier) {
        SettingSwitch(
            store = store,
            key = ModuleSettings.NATURAL_MODE,
            title = "拍照模式自然化",
            onChange = { natural = it },
        )
        AnimatedVisibility(visible = natural) {
            Column {
                SettingSwitch(
                    store = store,
                    key = ModuleSettings.NATURAL_NO_AI_DETAIL,
                    title = "关闭 AI 细节增强",
                    summary = "文字和纹理不再被网络重绘，同时关闭头发锐化",
                )
                SettingSwitch(
                    store = store,
                    key = ModuleSettings.NATURAL_NO_LCE,
                    title = "关闭局部对比度增强（LCE）",
                )
                SettingPercent(
                    store = store,
                    key = ModuleSettings.NATURAL_SOFTEN,
                    title = "进一步柔化",
                    summary = "不作用于大师模式和理光模式",
                )
            }
        }
        SettingSwitch(
            store = store,
            key = ModuleSettings.UNLOCK_25MP,
            title = "放宽 25MP 自动切换条件",
            summary = "放宽照度、ISO、温度和运动限制。需在相机设置里选“超清优先”",
        )
    }
}
