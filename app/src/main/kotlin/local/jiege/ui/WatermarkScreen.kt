package local.jiege.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import local.jiege.settings.ModelPresets
import local.jiege.settings.ModuleSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Back arrow for the preset page. */
val BackIcon: ImageVector = ImageVector.Builder(
    name = "Back", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(15.7f, 5.3f)
        lineTo(14.3f, 3.9f)
        lineTo(6.2f, 12f)
        lineTo(14.3f, 20.1f)
        lineTo(15.7f, 18.7f)
        lineTo(9f, 12f)
        close()
    }
}.build()

/** The watermark items of the 杂项 tab: XPAN watermark isolation, and the model disguise card with its preset entry. */
internal fun LazyListScope.watermarkItems(store: SettingsStore, cardModifier: Modifier, onOpenPresets: () -> Unit) {
    item(key = "xpan") {
        SmallTitle(text = "XPAN 水印")
        Card(modifier = cardModifier) {
            SettingSwitch(
                store = store,
                key = ModuleSettings.XPAN_WATERMARK_ISOLATION,
                title = "XPAN 水印隔离",
                summary = "XPAN 水印只在 XPAN 模式出现，其他模式不受影响",
            )
        }
    }
    item(key = "model") {
        var spoofWatermark by remember { mutableStateOf(store.get(ModuleSettings.MODEL_SPOOF_WATERMARK)) }
        val presetName = ModelPresets.nameFor(store.getString(ModuleSettings.MODEL_PRESET_ID), store.getString(ModuleSettings.MODEL_CUSTOM_PRESETS))
        SmallTitle(text = "机型伪装")
        Card(modifier = cardModifier) {
            SettingSwitch(
                store = store,
                key = ModuleSettings.MODEL_SPOOF_WATERMARK,
                title = "水印机型伪装",
                summary = "水印上的机型名称换成所选预设",
                onChange = { spoofWatermark = it },
            )
            SettingSwitch(
                store = store,
                key = ModuleSettings.MODEL_SPOOF_EXIF,
                title = "EXIF 伪装",
                summary = "同时修改成片 EXIF 里的机型，需先开启水印机型伪装",
                enabled = spoofWatermark,
            )
            BasicComponent(
                title = "预设管理",
                summary = presetName,
                endActions = { Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                onClick = onOpenPresets,
            )
        }
    }
}

/** The preset page: pick a model preset, add one, delete the ones added. */
@Composable
internal fun ModelPresetScreen(padding: PaddingValues, nestedScroll: NestedScrollConnection, store: SettingsStore) {
    val cardModifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
    var custom by remember { mutableStateOf(store.getString(ModuleSettings.MODEL_CUSTOM_PRESETS)) }
    var selected by remember { mutableStateOf(store.getString(ModuleSettings.MODEL_PRESET_ID)) }
    var adding by remember { mutableStateOf(false) }
    val presets = ModelPresets.all(custom)
    val effective = presets.firstOrNull { it.id == selected }?.id ?: presets.first().id

    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(nestedScroll),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        item(key = "presets") {
            SmallTitle(text = "机型预设")
            Card(modifier = cardModifier) {
                presets.forEach { preset ->
                    RadioButtonPreference(
                        title = preset.name,
                        summary = if (preset.builtin) "内置" else "自定义",
                        selected = preset.id == effective,
                        onClick = {
                            selected = preset.id
                            store.setString(ModuleSettings.MODEL_PRESET_ID, preset.id)
                        },
                        endActions = {
                            if (!preset.builtin) {
                                IconButton(onClick = {
                                    custom = ModelPresets.remove(custom, preset.id)
                                    store.setString(ModuleSettings.MODEL_CUSTOM_PRESETS, custom)
                                    if (selected == preset.id) {
                                        selected = ModelPresets.DEFAULT_ID
                                        store.setString(ModuleSettings.MODEL_PRESET_ID, selected)
                                    }
                                }) {
                                    Icon(
                                        imageVector = MiuixIcons.Basic.Close,
                                        contentDescription = "删除",
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        item(key = "add") {
            Card(modifier = cardModifier) {
                BasicComponent(
                    title = "新增预设",
                    endActions = { Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                    onClick = { adding = true },
                )
            }
        }
    }

    if (adding) {
        AddPresetDialog(
            onSave = { name ->
                custom = ModelPresets.add(custom, name)
                store.setString(ModuleSettings.MODEL_CUSTOM_PRESETS, custom)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun AddPresetDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    OverlayDialog(show = true, title = "新增预设", onDismissRequest = onDismiss) {
        TextField(
            value = name,
            onValueChange = {
                name = it.take(60)
                error = false
            },
            label = "请输入机型名称",
            modifier = Modifier.fillMaxWidth(),
        )
        if (error) {
            Text(
                text = "机型名称不能为空",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        DialogButtons("取消", onDismiss, "保存预设") {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) error = true else onSave(trimmed)
        }
    }
}
