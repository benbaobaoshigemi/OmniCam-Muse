package local.jiege.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.jiege.settings.MasterFilterStore
import local.jiege.settings.ModuleSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 大师滤镜: import of Rec.709 .cube LUTs (converted on the phone) as the main action; the master
 * filter list (name over category) in two grouped panels, shown and hidden, with long-press drag
 * to reorder within a panel, a switch per filter and deletion of imported ones; backup / restore
 * of the configuration with the imported LUTs at the bottom.
 */
@Composable
fun MasterFilterScreen(padding: PaddingValues, scroll: NestedScrollConnection, settings: SettingsStore) {
    val context = LocalContext.current
    val store = remember { MasterFilters.store(context) }
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<MasterFilterStore.Row>() }
    var hasCamera by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MasterFilterStore.Row?>(null) }

    fun reload() {
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { store.rows() to store.hasCameraList() }
            rows.clear()
            rows.addAll(fresh.first)
            hasCamera = fresh.second
        }
    }

    fun run(action: String, quiet: Boolean = false, work: suspend () -> String) {
        if (busy) return
        busy = true
        if (!quiet) status = "正在$action…"
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { work() }
            } catch (error: Throwable) {
                "$action 失败：${error.message ?: error.javaClass.simpleName}"
            }
            if (!quiet) status = result
            busy = false
            reload()
        }
    }

    LaunchedEffect(Unit) { reload() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        run("导入") {
            val header = MasterFilters.header(context)
            val failed = mutableListOf<String>()
            var done = 0
            for (uri in uris) {
                val name = displayName(context, uri)
                try {
                    context.contentResolver.openInputStream(uri)!!.use { store.importCube(name, it, header) }
                    done++
                } catch (error: Throwable) {
                    failed += "$name（${error.message ?: error.javaClass.simpleName}）"
                }
            }
            buildString {
                append("已导入 $done 个 LUT")
                if (failed.isNotEmpty()) append("；${failed.size} 个失败：${failed.joinToString("、")}")
            }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("备份") {
            context.contentResolver.openOutputStream(uri)!!.use { store.backup(it) }
            "已备份，包含导入的 LUT"
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("恢复") {
            context.contentResolver.openInputStream(uri)!!.use { store.restore(it) }
            "已从备份恢复"
        }
    }

    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val shown = rows.filter { it.visible }
    val hidden = rows.filter { !it.visible }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(scroll),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
    ) {
        item(key = "top") {
            if (!hasCamera) {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                    Text(
                        text = "还没有读到相机的滤镜列表，请先打开一次相机",
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            SmallTitle(text = "拍照模式")
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SettingSwitch(
                    store = settings,
                    key = ModuleSettings.NORMAL_FOLLOWS_MASTER,
                    title = "拍照模式同步大师滤镜列表",
                    summary = "拍照、人像和哈苏超清（开启调色盘时）使用下面的顺序和显示状态，并加入大师的扩展滤镜",
                )
            }
            SmallTitle(text = "特殊效果")
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SettingSwitch(
                    store = settings,
                    key = ModuleSettings.MASTER_EFFECT_NEON,
                    title = "霓虹",
                    summary = "套用普通模式 800T 的光晕与颗粒",
                )
                SettingSwitch(
                    store = settings,
                    key = ModuleSettings.MASTER_EFFECT_PORTRA,
                    title = "和光",
                    summary = "套用普通模式 Portra 的高光溢光，颗粒保持大师模式设置",
                )
            }
            SmallTitle(text = "显示中（${shown.size}）· 长按拖动排序")
        }
        items(shown, key = { it.id }) { row ->
            FilterRow(
                row = row,
                shape = groupShape(shown, row),
                dragging = draggingId == row.id,
                dragOffset = dragOffset,
                busy = busy,
                dragModifier = dragModifier(row, busy, listState, rows,
                    onStart = { draggingId = row.id; dragStart = rows.indexOfFirst { it.id == row.id }; dragOffset = 0f },
                    onDrag = { amount -> dragOffset += amount; dragOffset -= swapUnderDrag(listState, rows, row, dragOffset) },
                    onEnd = {
                        val from = dragStart
                        val to = rows.indexOfFirst { it.id == row.id }
                        draggingId = null
                        dragOffset = 0f
                        if (from > 0 && to > 0 && from != to) run("排序", quiet = true) { store.move(from, to); "" }
                    },
                    onCancel = { draggingId = null; dragOffset = 0f; reload() },
                ),
                onToggle = { checked -> run("设置", quiet = true) { store.setVisible(row.id, checked); "" } },
                onDelete = { pendingDelete = row },
            )
        }
        if (hidden.isNotEmpty()) {
            item(key = "hidden-title") {
                SmallTitle(text = "已隐藏（${hidden.size}）")
                Text(
                    text = "隐藏的滤镜不出现在滤镜条上，绑定它们的预设照常可用。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
            }
            items(hidden, key = { it.id }) { row ->
                FilterRow(
                    row = row,
                    shape = groupShape(hidden, row),
                    dragging = draggingId == row.id,
                    dragOffset = dragOffset,
                    busy = busy,
                    dragModifier = dragModifier(row, busy, listState, rows,
                        onStart = { draggingId = row.id; dragStart = rows.indexOfFirst { it.id == row.id }; dragOffset = 0f },
                        onDrag = { amount -> dragOffset += amount; dragOffset -= swapUnderDrag(listState, rows, row, dragOffset) },
                        onEnd = {
                            val from = dragStart
                            val to = rows.indexOfFirst { it.id == row.id }
                            draggingId = null
                            dragOffset = 0f
                            if (from > 0 && to > 0 && from != to) run("排序", quiet = true) { store.move(from, to); "" }
                        },
                        onCancel = { draggingId = null; dragOffset = 0f; reload() },
                    ),
                    onToggle = { checked -> run("设置", quiet = true) { store.setVisible(row.id, checked); "" } },
                    onDelete = { pendingDelete = row },
                )
            }
        }
        item(key = "manage") {
            SmallTitle(text = "导入与备份", modifier = Modifier.padding(top = 8.dp))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(
                    title = "导入 LUT",
                    summary = "可多选 Rec.709 的 .cube 文件",
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy,
                )
                BasicComponent(
                    title = "备份配置",
                    summary = "导出顺序、显示开关和导入的 LUT",
                    onClick = { backupLauncher.launch("大师滤镜备份.zip") },
                    enabled = !busy,
                )
                BasicComponent(
                    title = "从备份恢复",
                    summary = "覆盖当前配置和导入的 LUT",
                    onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = !busy,
                )
            }
            status?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(top = 10.dp),
                )
            }
        }
    }

    pendingDelete?.let { row ->
        ConfirmDialog(
            title = "删除导入的滤镜",
            message = "删除“${row.title}”？相机里绑定这个滤镜的预设也会一起删除。",
            onConfirm = {
                pendingDelete = null
                run("删除") { store.deleteImported(row.id); "已删除“${row.title}”" }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** One row of a grouped panel: name over category, delete (imported), switch or "固定". */
@Composable
private fun FilterRow(
    row: MasterFilterStore.Row,
    shape: RoundedCornerShape,
    dragging: Boolean,
    dragOffset: Float,
    busy: Boolean,
    dragModifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (dragging) colors.surfaceContainerHigh else colors.surfaceContainer)
            .then(dragModifier)
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(text = row.title, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = row.category,
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (row.imported) {
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(imageVector = MiuixIcons.Basic.Close, contentDescription = "删除", tint = colors.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        if (row.fixed) {
            Text(text = "固定", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(end = 4.dp))
        } else {
            Switch(checked = row.visible, enabled = !busy, onCheckedChange = onToggle)
        }
    }
}

private val Corner = 16.dp

/** Rounded top on the first row of a panel, rounded bottom on the last. */
private fun groupShape(group: List<MasterFilterStore.Row>, row: MasterFilterStore.Row): RoundedCornerShape {
    val first = group.firstOrNull()?.id == row.id
    val last = group.lastOrNull()?.id == row.id
    return RoundedCornerShape(
        topStart = if (first) Corner else 0.dp, topEnd = if (first) Corner else 0.dp,
        bottomStart = if (last) Corner else 0.dp, bottomEnd = if (last) Corner else 0.dp,
    )
}

private fun dragModifier(
    row: MasterFilterStore.Row,
    busy: Boolean,
    state: LazyListState,
    rows: MutableList<MasterFilterStore.Row>,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    if (row.fixed || busy) return Modifier
    return Modifier.pointerInput(row.id, row.visible) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onStart() },
            onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
            onDragEnd = { onEnd() },
            onDragCancel = { onCancel() },
        )
    }
}

/**
 * Moves the dragged row past the neighbour its centre has crossed, within its own panel (shown or
 * hidden; 标准 stays put); returns how far the row's resting place moved (taken off the offset).
 */
private fun swapUnderDrag(state: LazyListState, rows: MutableList<MasterFilterStore.Row>, dragged: MasterFilterStore.Row, offset: Float): Float {
    val items = state.layoutInfo.visibleItemsInfo
    val current = items.firstOrNull { it.key == dragged.id } ?: return 0f
    val centre = current.offset + current.size / 2f + offset
    val target = items.firstOrNull { item ->
        item.key != dragged.id && rows.any { it.id == item.key && !it.fixed && it.visible == dragged.visible } &&
            centre >= item.offset && centre <= item.offset + item.size
    } ?: return 0f
    val from = rows.indexOfFirst { it.id == dragged.id }
    val to = rows.indexOfFirst { it.id == target.key }
    if (from < 0 || to <= 0) return 0f
    rows.add(to, rows.removeAt(from))
    return (target.offset - current.offset).toFloat()
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
    }
    return uri.lastPathSegment ?: "LUT"
}

@Composable
private fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = title, style = MiuixTheme.textStyles.title4)
                Text(text = message, modifier = Modifier.padding(top = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    TextButton(text = "删除", onClick = onConfirm, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
