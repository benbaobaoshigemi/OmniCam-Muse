package local.jiege.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import local.jiege.settings.LaunchModes
import local.jiege.settings.ModuleSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 杂项 tab section: the quick launch key opens a chosen mode. */
internal fun LazyListScope.keyLaunchItems(store: SettingsStore, cardModifier: Modifier, onOpenModes: () -> Unit) {
    item(key = "keylaunch") {
        var on by remember { mutableStateOf(store.get(ModuleSettings.KEY_LAUNCH_MODE_ON)) }
        SmallTitle(text = "快启键")
        Card(modifier = cardModifier) {
            SettingSwitch(
                store = store,
                key = ModuleSettings.KEY_LAUNCH_MODE_ON,
                title = "快启键指定模式",
                summary = "用快启键打开相机时进入指定模式，不跟随上次使用的模式",
                onChange = { on = it },
            )
            AnimatedVisibility(visible = on) {
                BasicComponent(
                    title = "启动模式",
                    summary = LaunchModes.label(store.getString(ModuleSettings.KEY_LAUNCH_MODE)),
                    endActions = { Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                    onClick = onOpenModes,
                )
            }
        }
    }
}

/** The mode page: which mode the quick launch key opens. */
@Composable
internal fun KeyLaunchModeScreen(padding: PaddingValues, nestedScroll: NestedScrollConnection, store: SettingsStore) {
    val cardModifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
    var selected by remember { mutableStateOf(store.getString(ModuleSettings.KEY_LAUNCH_MODE)) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(nestedScroll),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        item(key = "modes") {
            SmallTitle(text = "快启键打开的模式")
            Card(modifier = cardModifier) {
                LaunchModes.NAMES.forEachIndexed { index, name ->
                    RadioButtonPreference(
                        title = LaunchModes.LABELS[index],
                        selected = name == selected,
                        onClick = {
                            selected = name
                            store.setString(ModuleSettings.KEY_LAUNCH_MODE, name)
                        },
                    )
                }
            }
        }
    }
}
