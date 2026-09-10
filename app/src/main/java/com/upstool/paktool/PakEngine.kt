package com.upstool.paktool

import android.content.Context
import android.os.Environment
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface TerminalCallback {
    fun onLog(line: String)
}

object PakEngine {
    init {
        System.loadLibrary("upstool_native")
    }

    private external fun nativeUnpackDeep(pakPath: String, outputDir: String, callback: TerminalCallback, assetManager: Any): Boolean

    private val baseDir = File(Environment.getExternalStorageDirectory(), "Upstool")
    val dirOriginal = File(baseDir, "Original").apply { mkdirs() }
    val dirEditor   = File(baseDir, "Editor").apply { mkdirs() }
    val dirUnpack   = File(baseDir, "Unpack").apply { mkdirs() }
    val dirRepack   = File(baseDir, "Repack").apply { mkdirs() }

    private lateinit var context: Context

    fun initPython(ctx: Context) {
        context = ctx.applicationContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    suspend fun unpackArchive(file: File, callback: TerminalCallback): Boolean = withContext(Dispatchers.IO) {
        val targetFolder = File(dirUnpack, file.nameWithoutExtension).apply { mkdirs() }

        val manifestFile = File(context.cacheDir, "bgmi.csv")
        try {
            context.assets.open("bgmi.csv").use { input ->
                manifestFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            callback.onLog("[WARN] Manifest load error: ${e.message}")
        }

        try {
            val py = Python.getInstance()
            val module = py.getModule("pak_engine")
            val pyResult = module.callAttr("unpack_pak",
                file.absolutePath,
                targetFolder.absolutePath,
                callback,
                manifestFile.absolutePath
            ).toBoolean()
            if (pyResult) return@withContext true
        } catch (e: Exception) {
            callback.onLog("[PY] Switching to native deep engine: ${e.message}")
        }

        nativeUnpackDeep(file.absolutePath, targetFolder.absolutePath, callback, context.assets)
    }

    suspend fun replaceAndRepack(folderName: String, callback: TerminalCallback): Boolean = withContext(Dispatchers.IO) {
        val unpackedFolder = File(dirUnpack, folderName)
        if (!unpackedFolder.exists()) {
            callback.onLog("[ERROR] Unpack directory missing: $folderName")
            return@withContext false
        }

        callback.onLog("[REPLACE] Checking Editor folder for modified assets...")
        var replaced = 0
        dirEditor.listFiles()?.forEach { editorFile ->
            unpackedFolder.walkTopDown().forEach { fileInTree ->
                if (fileInTree.name.equals(editorFile.name, ignoreCase = true)) {
                    editorFile.copyTo(fileInTree, overwrite = true)
                    replaced++
                    callback.onLog("🔄 [REPLACED] ${editorFile.name} (${editorFile.length() / 1024} KB)")
                }
            }
        }
        callback.onLog("[INFO] $replaced files replaced with actual edited versions.")

        val outputPak = File(dirRepack, "$folderName.pak")
        try {
            val py = Python.getInstance()
            val module = py.getModule("pak_engine")
            return@withContext module.callAttr("repack_pak", unpackedFolder.absolutePath, outputPak.absolutePath, callback).toBoolean()
        } catch (e: Exception) {
            callback.onLog("[REPACK ERROR] ${e.message}")
            return@withContext false
        }
    }
}
