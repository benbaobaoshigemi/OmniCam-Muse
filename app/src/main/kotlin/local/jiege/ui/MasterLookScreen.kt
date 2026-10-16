package local.jiege.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import local.jiege.settings.MasterAlign
import local.jiege.settings.MasterLooks
import local.jiege.settings.ModuleSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Bottom bar icon for 大师色彩: a palette (outline with three paint wells and a thumb hole). */
val MasterColorIcon: ImageVector = ImageVector.Builder(
    name = "MasterColor", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        // Palette body: circle of radius 10 with a bite for the thumb at the lower right.
        moveTo(12f, 2f)
        curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
        reflectiveCurveTo(6.48f, 22f, 12f, 22f)
        curveTo(13.1f, 22f, 13.8f, 21.2f, 13.8f, 20.2f)
        curveTo(13.8f, 19.7f, 13.6f, 19.3f, 13.3f, 18.9f)
        curveTo(13f, 18.6f, 12.8f, 18.2f, 12.8f, 17.7f)
        curveTo(12.8f, 16.7f, 13.6f, 15.9f, 14.6f, 15.9f)
        horizontalLineTo(16.7f)
        curveTo(19.6f, 15.9f, 22f, 13.5f, 22f, 10.6f)
        curveTo(22f, 5.85f, 17.52f, 2f, 12f, 2f)
        close()
        // Paint wells (holes).
        moveTo(6.5f, 12f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
        close()
        moveTo(9.5f, 8f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
        close()
        moveTo(14.5f, 8f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
        close()
    }
}.build()

/** One family's sub-choices: title, labels, optional summaries, and the integer setting holding the index. */
private class SubChoice(val family: Int, val title: String, val labels: Array<String>, val summaries: Array<String>?, val key: String)

private val SUB_CHOICES = listOf(
    SubChoice(MasterLooks.FAMILY_BASIC, "传输曲线", MasterLooks.BASIC_LABELS, MasterLooks.BASIC_SUMMARIES, ModuleSettings.MASTER_LOOK_BASIC),
    SubChoice(MasterLooks.FAMILY_HNCS, "色彩映射", MasterLooks.HNCS_INTENT_LABELS, MasterLooks.HNCS_INTENT_SUMMARIES, ModuleSettings.MASTER_HNCS_INTENT),
    SubChoice(MasterLooks.FAMILY_OPPO, "机型", MasterLooks.OPPO_LABELS, null, ModuleSettings.MASTER_LOOK_OPPO),
    SubChoice(MasterLooks.FAMILY_LUMIX, "照片风格", MasterLooks.LUMIX_LABELS, null, ModuleSettings.MASTER_LOOK_LUMIX),
    SubChoice(MasterLooks.FAMILY_FILM, "胶片", MasterLooks.FILM_LABELS, MasterLooks.FILM_SUMMARIES, ModuleSettings.MASTER_LOOK_FILM),
    SubChoice(MasterLooks.FAMILY_CANON, "照片风格", MasterLooks.CANON_LABELS, null, ModuleSettings.MASTER_LOOK_CANON),
    SubChoice(MasterLooks.FAMILY_DARKTABLE, "色调映射", MasterLooks.DARKTABLE_LABELS, null, ModuleSettings.MASTER_LOOK_DARKTABLE),
)

/**
 * 大师色彩: the look the master mode LMT is baked with (MasterLooks). The camera picks the change
 * up within a second; preview and the next capture use it, no camera restart needed.
 *
 * 模式选择: 替换模式 (the chosen look replaces the stock LMT) or 混合模式 (it is blended with the stock
 * LMT by two weights, 影调 and 色彩; 100 % / 100 % is the same as replacing). The stock look (OPPO) is
 * the other side of the blend: choosing it switches to replace mode and hides the mode choice.
 */
@Composable
fun MasterLookScreen(padding: PaddingValues, nestedScrollConnection: NestedScrollConnection, store: SettingsStore) {
    var family by remember { mutableStateOf(store.getInt(ModuleSettings.MASTER_LOOK)) }
    LaunchedEffect(family) {
        if (family == MasterLooks.FAMILY_AGX) {
            store.setInt(ModuleSettings.MASTER_LOOK_DARKTABLE, 2)
            family = MasterLooks.FAMILY_DARKTABLE
            store.setInt(ModuleSettings.MASTER_LOOK, family)
        } else if (family == MasterLooks.FAMILY_FUJI) {
            family = MasterLooks.FAMILY_HNCS
            store.setInt(ModuleSettings.MASTER_LOOK, family)
        }
    }
    var mode by remember { mutableStateOf(store.getInt(ModuleSettings.MASTER_LOOK_MODE)) }
    var film by remember { mutableStateOf(store.getInt(ModuleSettings.MASTER_LOOK_FILM)) }
    // Changes with every choice, so the parameter card follows the chosen look; the sub-choices keep their own state.
    var choiceRevision by remember { mutableStateOf(0) }
    var resetRevision by remember { mutableStateOf(0) }
    val blend = mode == MasterLooks.MODE_BLEND
    val cardModifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .nestedScroll(nestedScrollConnection),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        item(key = "family") {
            SmallTitle(text = "大师模式色彩")
            Card(modifier = cardModifier) {
                MasterLooks.FAMILY_LABELS.forEachIndexed { index, label ->
                    val value = MasterLooks.VISIBLE_FAMILIES[index]
                    RadioButtonPreference(
                        title = label,
                        summary = MasterLooks.FAMILY_SUMMARIES[index],
                        selected = family == value,
                        onClick = {
                            family = value
                            store.setInt(ModuleSettings.MASTER_LOOK, value)
                            // OPPO is the stock look itself: nothing to blend it with.
                            if (value == MasterLooks.FAMILY_OPPO && blend) {
                                mode = MasterLooks.MODE_REPLACE
                                store.setInt(ModuleSettings.MASTER_LOOK_MODE, mode)
                            }
                            choiceRevision += 1
                        },
                    )
                }
            }
        }
        SUB_CHOICES.firstOrNull { it.family == family }?.let { choice ->
            item(key = "sub_${choice.family}") {
                var selected by remember(choice.key) { mutableStateOf(store.getInt(choice.key)) }
                SmallTitle(text = choice.title)
                Card(modifier = cardModifier) {
                    choice.labels.forEachIndexed { index, label ->
                        RadioButtonPreference(
                            title = label,
                            summary = choice.summaries?.get(index),
                            selected = selected == index,
                            onClick = {
                                selected = index
                                if (choice.family == MasterLooks.FAMILY_FILM) film = index
                                store.setInt(choice.key, index)
                                choiceRevision += 1
                            },
                        )
                    }
                }
            }
        }
        if (family == MasterLooks.FAMILY_HNCS) {
            item(key = "hncs_curve") {
                var curve by remember { mutableStateOf(store.getInt(ModuleSettings.MASTER_HNCS_CURVE)) }
                SmallTitle(text = "色调曲线")
                Card(modifier = cardModifier) {
                    MasterLooks.HNCS_CURVE_LABELS.forEachIndexed { index, label ->
                        RadioButtonPreference(title = label, selected = curve == index, onClick = {
                            curve = index
                            store.setInt(ModuleSettings.MASTER_HNCS_CURVE, index)
                        })
                    }
                }
            }
        }
        if (family == MasterLooks.FAMILY_FILM && !MasterLooks.isPositiveFilm(film)) {
            item(key = "print") {
                var paper by remember { mutableStateOf(store.getInt(ModuleSettings.MASTER_LOOK_PRINT)) }
                SmallTitle(text = "相纸／电影拷贝")
                Card(modifier = cardModifier) {
                    MasterLooks.PRINT_LABELS.forEachIndexed { index, label ->
                        RadioButtonPreference(
                            title = label,
                            selected = paper == index,
                            onClick = {
                                paper = index
                                store.setInt(ModuleSettings.MASTER_LOOK_PRINT, index)
                            },
                        )
                    }
                }
            }
        }
        // Parameters of the chosen look (the last card): the look's own terms, nothing else.
        val parameters = run {
            choiceRevision
            if (family == MasterLooks.FAMILY_FILM) {
                if (MasterLooks.isPositiveFilm(film)) emptyArray() else MasterLooks.FILM_DENSITY_PARAMS
            } else {
                MasterLooks.paramsFor(MasterLooks.lookId(family, store.getInt(ModuleSettings.MASTER_LOOK_OPPO),
                    store.getInt(ModuleSettings.MASTER_LOOK_LUMIX), film, store.getInt(ModuleSettings.MASTER_LOOK_CANON),
                    store.getInt(ModuleSettings.MASTER_LOOK_DARKTABLE), store.getInt(ModuleSettings.MASTER_LOOK_BASIC),
                    store.getInt(ModuleSettings.MASTER_LOOK_PRINT), store.getInt(ModuleSettings.MASTER_HNCS_INTENT),
                    store.getInt(ModuleSettings.MASTER_HNCS_CURVE)))
            }
        }
        if (parameters.isNotEmpty()) {
            item(key = "params") {
                SmallTitle(text = "参数")
                Card(modifier = cardModifier) {
                    parameters.forEach { SettingNumber(store, it, resetRevision) }
                    BasicComponent(
                        title = "恢复默认",
                        onClick = {
                            parameters.forEach { store.setInt(it.key, it.def) }
                            resetRevision += 1
                        },
                    )
                }
            }
        }
        if (family != MasterLooks.FAMILY_OPPO) item(key = "mode") {
            SmallTitle(text = "模式选择")
            Card(modifier = cardModifier) {
                RadioButtonPreference(
                    title = "混合模式",
                    summary = "把所选色彩按权重混入原厂大师色彩",
                    selected = blend,
                    onClick = {
                        mode = MasterLooks.MODE_BLEND
                        store.setInt(ModuleSettings.MASTER_LOOK_MODE, mode)
                    },
                )
                RadioButtonPreference(
                    title = "替换模式",
                    summary = "所选色彩完全替换原厂大师色彩",
                    selected = !blend,
                    onClick = {
                        mode = MasterLooks.MODE_REPLACE
                        store.setInt(ModuleSettings.MASTER_LOOK_MODE, mode)
                    },
                )
            }
            AnimatedVisibility(visible = blend) {
                Column {
                    SmallTitle(text = "混合权重")
                    Card(modifier = cardModifier) {
                        SettingPercent(
                            store = store,
                            key = ModuleSettings.MASTER_BLEND_TONE,
                            title = "影调",
                            summary = "0% 为原厂影调，100% 完全用所选色彩的影调",
                        )
                        SettingPercent(
                            store = store,
                            key = ModuleSettings.MASTER_BLEND_COLOUR,
                            title = "色彩",
                            summary = "0% 为原厂色彩，100% 完全用所选色彩的色彩",
                        )
                    }
                }
            }
        }
        // Exposure alignment to the stock look; only the alignments the chosen look can reach are listed.
        val lookId = run {
            choiceRevision
            MasterLooks.lookId(family, store.getInt(ModuleSettings.MASTER_LOOK_OPPO),
                store.getInt(ModuleSettings.MASTER_LOOK_LUMIX), film, store.getInt(ModuleSettings.MASTER_LOOK_CANON),
                store.getInt(ModuleSettings.MASTER_LOOK_DARKTABLE), store.getInt(ModuleSettings.MASTER_LOOK_BASIC),
                store.getInt(ModuleSettings.MASTER_LOOK_PRINT), store.getInt(ModuleSettings.MASTER_HNCS_INTENT),
                store.getInt(ModuleSettings.MASTER_HNCS_CURVE))
        }
        if (MasterAlign.covered(lookId)) {
            item(key = "align_$lookId") {
                var align by remember(lookId) {
                    val stored = store.getInt(ModuleSettings.MASTER_ALIGN)
                    mutableStateOf(if (MasterAlign.available(lookId, stored)) stored else MasterAlign.NONE)
                }
                SmallTitle(text = "曝光对齐")
                Card(modifier = cardModifier) {
                    MasterAlign.LABELS.forEachIndexed { index, label ->
                        if (!MasterAlign.available(lookId, index)) return@forEachIndexed
                        RadioButtonPreference(
                            title = label,
                            summary = MasterAlign.SUMMARIES[index],
                            selected = align == index,
                            onClick = {
                                align = index
                                store.setInt(ModuleSettings.MASTER_ALIGN, index)
                            },
                        )
                    }
                }
            }
        }
        item(key = "credits") {
            val uriHandler = LocalUriHandler.current
            SmallTitle(text = "致谢")
            Card(modifier = cardModifier) {
                BasicComponent(
                    title = "光子相机 PhotonCamera",
                    summary = "Lumix、胶片、Canon、Darktable 等色彩引擎基于该开源项目\ngithub.com/bjzhou/PhotonCamera",
                    endActions = { Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                    onClick = { uriHandler.openUri("https://github.com/bjzhou/PhotonCamera") },
                )
            }
        }
    }
}
