package local.jiege.ui

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.jiege.settings.DiagnosticsContract
import local.jiege.settings.ModuleSettings
import local.jiege.settings.MasterFilterContract
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val CAMERA_PACKAGE = "com.oplus.camera"

/** The 主页 tab: injection state with restarts, the log page, development verification, device/version facts. */
internal fun LazyListScope.homeItems(cardModifier: Modifier, onOpenLog: () -> Unit) {
    item(key = "status") {
        val version = rememberVersionName(LocalContext.current.packageName)
        val state = rememberInjectionState()
        StatusCard(version = version, status = state.value?.summary ?: "正在检查", health = state.value?.health,
            modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 12.dp))
    }
    item(key = "injection") {
        InjectionCard(rememberInjectionState(), cardModifier)
    }
    item(key = "log") {
        Card(modifier = cardModifier) {
            BasicComponent(
                title = "日志",
                summary = "记录与导出，反馈问题时附上",
                endActions = { Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                onClick = onOpenLog,
            )
        }
    }
    item(key = "verification") {
        SmallTitle(text = "校验")
        Card(modifier = cardModifier) {
            BasicComponent(title = "验证完成", summary = "开发模式 · 全部功能可用")
        }
    }
    item(key = "info") {
        val moduleVersion = rememberVersionName(LocalContext.current.packageName)
        val cameraVersion = rememberVersionName(CAMERA_PACKAGE)
        Card(modifier = cardModifier) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 4.dp)) {
                InfoRow(icon = InfoModuleIcon, title = "模块版本", content = moduleVersion)
                InfoRow(icon = CameraIcon, title = "相机版本", content = cameraVersion)
                InfoRow(icon = PhoneIcon, title = "设备", content = "${Build.MANUFACTURER} ${Build.MODEL}")
            }
        }
    }
}

@Composable
private fun rememberVersionName(packageName: String): String {
    val context = LocalContext.current
    return remember(packageName) {
        try {
            context.packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
        } catch (_: Exception) {
            "未安装"
        }
    }
}

@Composable
private fun StatusCard(version: String, status: String, health: Health?, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    val (accent, background) = when (health) {
        Health.OK -> if (dark) Color(0xFF4CAF68) to Color(0xFF16301E) else Color(0xFF34A853) to Color(0xFFDDF3E2)
        Health.FAILED -> if (dark) Color(0xFFE0605A) to Color(0xFF3A1817) else Color(0xFFE5483F) to Color(0xFFFDE2E0)
        else -> if (dark) Color(0xFFE08A3C) to Color(0xFF3A2614) else Color(0xFFF28C28) to Color(0xFFFFEBD9)
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = background),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(124.dp)) {
            Box(modifier = Modifier.fillMaxSize().offset(24.dp, 28.dp), contentAlignment = Alignment.BottomEnd) {
                Icon(imageVector = CameraIcon, contentDescription = null, tint = accent.copy(alpha = 0.55f), modifier = Modifier.size(116.dp))
            }
            Column(modifier = Modifier.padding(16.dp, 14.dp)) {
                Text(text = "OmniCam Muse", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(text = version, fontSize = 15.sp, modifier = Modifier.padding(top = 1.dp))
            }
            Box(modifier = Modifier.fillMaxSize().padding(16.dp, 12.dp), contentAlignment = Alignment.BottomStart) {
                Text(text = status, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, content: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 12.dp).size(24.dp))
        Column {
            Text(text = title, fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
            Text(text = content, fontSize = MiuixTheme.textStyles.body2.fontSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The injection state, read again whenever the app comes back to the front and after a restart. */
internal class InjectionHolder {
    var value by mutableStateOf<InjectionState?>(null)
    var revision by mutableIntStateOf(0)
}

private val injectionHolder = InjectionHolder()

@Composable
private fun rememberInjectionState(): InjectionHolder {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(resumed, injectionHolder.revision) {
        if (resumed) injectionHolder.value = withContext(Dispatchers.IO) { InjectionState.read(context.applicationContext) }
    }
    return injectionHolder
}

@Composable
private fun InjectionCard(state: InjectionHolder, cardModifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    SmallTitle(text = "注入状态")
    Card(modifier = cardModifier) {
        val checks = state.value?.checks.orEmpty()
        if (checks.isEmpty()) BasicComponent(title = "正在检查")
        for (check in checks) {
            BasicComponent(
                title = check.title,
                summary = check.detail,
                summaryColor = if (check.ok) BasicComponentDefaults.summaryColor() else BasicComponentDefaults.summaryColor(color = Color(0xFFE5483F)),
                endActions = {
                Text(
                    text = if (check.ok) "正常" else "异常",
                    color = if (check.ok) MiuixTheme.colorScheme.onSurfaceVariantSummary else Color(0xFFE5483F),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                )
            })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((label, packageName) in listOf("相机" to MasterFilterContract.CAMERA_PACKAGE, "相册" to DiagnosticsContract.GALLERY_PACKAGE)) {
                TextButton(
                    text = "重启$label",
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val problem = withContext(Dispatchers.IO) { AppRestart.restart(context.applicationContext, packageName, label) }
                            busy = false
                            if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                            // The camera reports a few seconds after it starts.
                            delay(6000)
                            state.revision++
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The log page: a root logcat recording (the system buffers hold about two minutes) and the export. */
@Composable
internal fun LogScreen(padding: PaddingValues, nestedScroll: NestedScrollConnection) {
    val context = LocalContext.current
    val store = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val cardModifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
    var recording by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { recording = withContext(Dispatchers.IO) { LogCapture.running(context.applicationContext) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { LogExport.write(context.applicationContext, it) }
                }
            }
            busy = false
            Toast.makeText(context, if (result.isSuccess) "日志已导出" else "导出失败：" + result.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(nestedScroll),
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding()),
    ) {
        item(key = "capture_dumps") {
            SmallTitle(text = "成片转储")
            Card(modifier = cardModifier) {
                SettingSwitch(store = store, key = ModuleSettings.DUMP_RAW, title = "RAW 转储",
                    summary = "HybridRaw 输入的逐帧 RAW，保留原始打包格式")
                SettingSwitch(store = store, key = ModuleSettings.DUMP_CCM, title = "ccmVec 转储",
                    summary = "原厂白平衡与 CCM 之后、gamma 之前的线性 RGB")
                SettingSwitch(store = store, key = ModuleSettings.DUMP_HDRT, title = "HDRTransform 转储",
                    summary = "大师主图和快图的 HDR 输入、SDR 输出")
            }
        }
        item(key = "log") {
            Card(modifier = cardModifier) {
                SwitchPreference(
                    title = "开启日志",
                    summary = "持续记录系统日志，复现问题后再导出",
                    checked = recording == true,
                    enabled = recording != null && !busy,
                    onCheckedChange = { on ->
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                if (on) LogCapture.start(context.applicationContext) else { LogCapture.stop(context.applicationContext); true }
                            }
                            recording = withContext(Dispatchers.IO) { LogCapture.running(context.applicationContext) }
                            busy = false
                            if (!ok) Toast.makeText(context, "没有 Root 授权，无法记录", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                BasicComponent(
                    title = if (busy) "处理中" else "导出日志",
                    onClick = { if (!busy) export.launch(LogExport.fileName()) },
                )
            }
        }
    }
}
