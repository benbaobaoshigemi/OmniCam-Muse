package local.jiege.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import local.jiege.settings.MasterFilterContract

/**
 * Serves the master filter configuration for diagnostics
 * (MasterFilterContract). Only the camera and this app may call it. The camera reads the
 * configuration and the imported LUTs from the LSPosed preferences directory, not from here.
 */
class MasterFilterProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        val store = MasterFilters.store(context!!)
        return when (method) {
            MasterFilterContract.METHOD_CONFIG -> Bundle().apply { putString(MasterFilterContract.KEY_TEXT, store.configText()) }
            else -> null
        }
    }

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val packages = context!!.packageManager.getPackagesForUid(uid).orEmpty()
        if (MasterFilterContract.CAMERA_PACKAGE !in packages) throw SecurityException("Not allowed: uid $uid")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * The app's single master filter store, in the module's LSPosed preferences directory (the one
 * xposedsharedprefs gives module_settings.xml), which the camera reads. Without an active LSPosed
 * it falls back to private files, where the camera cannot see it.
 */
object MasterFilters {
    @Volatile private var store: local.jiege.settings.MasterFilterStore? = null

    fun store(context: android.content.Context): local.jiege.settings.MasterFilterStore =
        store ?: synchronized(this) {
            store ?: local.jiege.settings.MasterFilterStore(directory(context.applicationContext)).also { store = it }
        }

    /** True when the camera can read the store (LSPosed preferences directory in use). */
    fun visibleToCamera(context: android.content.Context): Boolean = sharedDirectory(context.applicationContext) != null

    private fun directory(context: android.content.Context): java.io.File =
        sharedDirectory(context) ?: java.io.File(context.filesDir, "master")

    @Suppress("DEPRECATION")
    private fun sharedDirectory(context: android.content.Context): java.io.File? = try {
        val preferences = context.getSharedPreferences(local.jiege.settings.ModuleSettings.FILE, android.content.Context.MODE_WORLD_READABLE)
        val field = preferences.javaClass.getDeclaredField("mFile").apply { isAccessible = true }
        (field.get(preferences) as java.io.File).parentFile
    } catch (_: Throwable) {
        null
    }

    fun header(context: android.content.Context): ByteArray =
        context.assets.open("mslut_header.bin").use { it.readBytes() }
}
