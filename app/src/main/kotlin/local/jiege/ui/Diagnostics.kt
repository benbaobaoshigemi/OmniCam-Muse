package local.jiege.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import local.jiege.settings.DiagnosticsContract
import local.jiege.settings.MasterFilterContract
import local.jiege.settings.ModuleSettings

/** The camera's injection state (DiagnosticsContract); null when the camera is not hooked or not reachable. */
internal object CameraReport {
    /**
     * Also stores the camera's master list when it came along. Blocks, and starts the camera
     * process when it is not running; call off the main thread.
     */
    fun fetch(context: Context): Bundle? = fetchState(context, DiagnosticsContract.CAMERA_AUTHORITY)
        ?.also { state -> state.getString(DiagnosticsContract.KEY_MASTER_STOCK)?.let { MasterFilters.store(context).saveCameraList(it) } }
}

/** The gallery's injection state; null when the gallery is not hooked. Blocks and starts the gallery process. */
internal object GalleryReport {
    fun fetch(context: Context): Bundle? = fetchState(context, DiagnosticsContract.GALLERY_AUTHORITY)
}

private fun fetchState(context: Context, authority: String): Bundle? = try {
    context.contentResolver.call(Uri.parse("content://$authority"), DiagnosticsContract.METHOD_STATE, null, null)
        ?.takeIf { it.containsKey(DiagnosticsContract.KEY_PID) }
} catch (_: Exception) {
    null
}

/** One line of the injection check: passed, or the problem and what to do. */
internal data class Check(val title: String, val ok: Boolean, val detail: String)

internal enum class Health { OK, PENDING, FAILED }

internal class InjectionState(val checks: List<Check>, val health: Health) {
    val summary: String
        get() = when (health) {
            Health.OK -> "注入正常"
            Health.PENDING -> checks.first { !it.ok }.detail
            Health.FAILED -> checks.first { !it.ok }.title + "异常"
        }

    companion object {
        /** Reads the state; does file and package manager I/O, call off the main thread. */
        fun read(context: Context): InjectionState {
            val checks = ArrayList<Check>()
            val lsposed = MasterFilters.visibleToCamera(context)
            checks += Check("LSPosed", lsposed, if (lsposed) "模块已激活" else "模块未激活，在 LSPosed 中启用并勾选相机、相册、系统框架")
            val mounted = moduleMounted()
            checks += Check("KSU 模块", mounted, if (mounted) "相机库已挂载" else "相机库未挂载，确认 KSU 模块已启用并重启手机")
            val updated = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            // A process still running hooks from the previous module APK only needs a restart.
            var stale = 0
            for ((label, report) in listOf("相机" to CameraReport.fetch(context), "相册" to GalleryReport.fetch(context))) {
                when {
                    report == null ->
                        checks += Check("${label}注入", false, if (lsposed) "${label}未被注入，在 LSPosed 中勾选${label}后点“重启$label”" else "${label}未被注入")
                    report.getString(DiagnosticsContract.KEY_MODULE_APK) != context.applicationInfo.sourceDir ||
                        report.getLong(DiagnosticsContract.KEY_START) < updated -> {
                        stale++
                        checks += Check("${label}注入", false, "${label}仍在使用旧版本，点“重启$label”")
                    }
                    !report.getBoolean(DiagnosticsContract.KEY_FILES_MOUNTED, true) ->
                        checks += Check("${label}注入", false, "${label}里的模块文件被卸载，功能已停用。打开 KernelSU 管理器 → 超级用户 → 右上角菜单勾选“显示系统应用” → $label → 选“自定义” → 关闭“卸载模块”，再点“重启$label”。常见原因：10007 去广告等模块开启了 Zygisk Next 排除列表")
                    else -> {
                        val missing = report.getInt(DiagnosticsContract.KEY_SYMBOLS_MISSING)
                        val errors = report.getInt(DiagnosticsContract.KEY_ERRORS)
                        checks += when {
                            missing > 0 -> Check("${label}注入", false, "$missing 个符号未匹配，${label}版本可能不受支持")
                            errors > 0 -> Check("${label}注入", false, "$errors 条错误，请导出日志发给作者")
                            else -> Check("${label}注入", true, "已注入，" + report.getInt(DiagnosticsContract.KEY_SYMBOLS_RESOLVED) + " 个符号")
                        }
                    }
                }
            }
            val problems = checks.count { !it.ok }
            val health = when {
                problems == 0 -> Health.OK
                problems == stale -> Health.PENDING
                else -> Health.FAILED
            }
            return InjectionState(checks, health)
        }

        /**
         * The module's camera files are bind-mounted over /odm, so a mounted file sits on another
         * device than the directory holding it. The feature config is the file to probe: both device
         * profiles mount it, and it is the same one UnmountedGuard reads in the camera process, so
         * the settings app and the camera agree. Probing a camera library instead reports the port as
         * unmounted: PKJ110 keeps X8U's stock libAlgoInterface.so / libmsnativefilter.so on purpose
         * (the X10 backend supplies the basic-tone one), so those libraries are never bind-mounted
         * there - which is exactly the state this check used to mistake for a missing module.
         */
        private fun moduleMounted(): Boolean = try {
            val config = Os.stat("/odm/etc/camera/config/camera_unit_feature_config.protobuf")
            config.st_dev != Os.stat("/odm/etc/camera/config").st_dev
        } catch (_: Exception) {
            false
        }
    }
}

/** Stops an app's process and opens it again. */
internal object AppRestart {
    /** Stops the camera in the background (root) so that camera-side settings apply when it opens next. */
    fun stopCamera() {
        Thread { rootCommand("am force-stop com.oplus.camera") }.start()
    }

    /**
     * Returns null on success, else what the user has to do. Needs root (Android 14 lets an app
     * stop only its own processes); without it the app's info page opens for 强行停止.
     * Blocks; call off the main thread.
     */
    fun restart(context: Context, packageName: String, label: String): String? {
        if (rootCommand("am force-stop $packageName") == null) {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "没有 Root 授权，请在应用信息页点“强行停止”后重开$label；或在 KernelSU 中给本应用授权 Root"
        }
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return "${label}已结束，找不到${label}入口"
        Thread.sleep(300)
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }
}

/**
 * The system log buffers hold about two minutes on this phone, so 开启日志 runs a root logcat into
 * the app's cache (two rotating 8 MiB files) until it is switched off; the export takes it along.
 */
internal object LogCapture {
    private fun file(context: Context) = context.cacheDir.absolutePath + "/logcat-capture.txt"

    private fun pattern(context: Context) = "'[l]ogcat -b main,system,crash -f ${file(context)}'"

    /** Starts a fresh recording; false without root. */
    fun start(context: Context): Boolean {
        val path = file(context)
        rootCommand("pkill -f ${pattern(context)}; rm -f $path $path.1")
        return rootCommand("nohup logcat -b main,system,crash -f $path -r 8192 -n 1 </dev/null >/dev/null 2>&1 &") != null
    }

    fun running(context: Context): Boolean = rootCommand("pgrep -f ${pattern(context)}")?.isNotBlank() == true

    /** The recording, oldest lines first; null when nothing was recorded. */
    fun read(context: Context): String? {
        val path = file(context)
        return rootCommand("cat $path.1 $path 2>/dev/null", 20)?.takeIf { it.isNotBlank() }
    }

    /** Stops the recording and keeps it for the export. */
    fun stop(context: Context) {
        rootCommand("pkill -f ${pattern(context)}")
    }
}

/** Runs a command as root; the output, or null when root is unavailable or the command failed. */
internal fun rootCommand(command: String, timeoutSeconds: Long = 8): String? = try {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroy()
        null
    } else {
        reader.join(1000)
        if (process.exitValue() == 0) output.toString() else null
    }
} catch (_: Exception) {
    null
}

/** The exported log: a zip with the state, the camera state and log lines, settings and, with root, system logs. */
internal object LogExport {
    fun fileName(): String = "OmniCam-Muse-日志-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip"

    /** Writes the zip; blocks for up to ~20 s when root logs are collected. */
    fun write(context: Context, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("概要.txt", summary(context))
            val report = CameraReport.fetch(context)
            put("相机.txt", if (report == null) "相机未被注入或无法连接\n" else
                report.keySet().filter { it != DiagnosticsContract.KEY_LOG && it != DiagnosticsContract.KEY_MASTER_STOCK }.sorted().joinToString("\n") { "$it = ${report.get(it)}" } +
                    "\n\n" + report.getString(DiagnosticsContract.KEY_LOG).orEmpty() + "\n")
            put("设置.txt", settings(context))
            runCatching { MasterFilters.store(context).configText() }.getOrNull()?.let { put("大师滤镜配置.txt", it) }
            put("应用日志.txt", ownLogcat())
            val lspd = rootCommand("for f in \$(ls -t /data/adb/lspd/log/*.log 2>/dev/null | head -4); do echo \"== \$f\"; tail -n 3000 \"\$f\"; done", 15)
            if (lspd != null) put("LSPosed.txt", lspd)
            val logcat = rootCommand("logcat -d -b main,system,crash -t 20000", 15)
            if (logcat != null) put("logcat.txt", logcat)
            LogCapture.read(context)?.let { put("logcat-记录.txt", it) }
        }
    }

    private fun summary(context: Context): String = buildString {
        val state = InjectionState.read(context)
        appendLine("导出时间  " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
        appendLine("模块版本  " + version(context, context.packageName))
        appendLine("相机版本  " + version(context, MasterFilterContract.CAMERA_PACKAGE))
        appendLine("相册版本  " + version(context, "com.coloros.gallery3d"))
        appendLine("设备      ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("系统      Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("构建      ${Build.DISPLAY}")
        appendLine("指纹      ${Build.FINGERPRINT}")
        appendLine("Root 日志 " + if (rootCommand("id") != null) "已收集" else "无 Root 授权，未收集")
        appendLine()
        appendLine("注入状态  " + state.summary)
        for (check in state.checks) appendLine((if (check.ok) "  [正常] " else "  [异常] ") + check.title + "：" + check.detail)
    }

    private fun version(context: Context, packageName: String): String = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: Exception) {
        "未安装"
    }

    /** All module settings; the licence itself stays out. */
    @Suppress("DEPRECATION")
    private fun settings(context: Context): String {
        val preferences = try {
            context.getSharedPreferences(ModuleSettings.FILE, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(ModuleSettings.FILE, Context.MODE_PRIVATE)
        }
        return preferences.all.toSortedMap().entries.joinToString("\n") { (key, value) ->
            "$key = $value"
        } + "\n"
    }

    private fun ownLogcat(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-t", "3000", "--pid", Process.myPid().toString()).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.SECONDS)
        text
    } catch (error: Exception) {
        error.toString()
    }
}
