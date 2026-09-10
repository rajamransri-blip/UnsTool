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

    // Dedicated Lua directories
    val dirLuaOriginal = File(baseDir, "Lua/Original").apply { mkdirs() }
    val dirLuaCompile  = File(baseDir, "Lua/Compile").apply { mkdirs() }

    private lateinit var context: Context

    fun initPython(ctx: Context) {
        context = ctx.applicationContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    suspend fun unpackArchive(file: File, callback: TerminalCallback): Boolean = withContext(Dispatchers.IO) {
        val targetFolder = File(dirUnpack, file.nameWithoutExtension).apply { mkdirs() }

        val manifestCache = File(context.cacheDir, "BGMI.csv")
        try {
            val assetName = if (context.assets.list("")?.contains("BGMI.csv") == true) "BGMI.csv" else "bgmi.csv"
            context.assets.open(assetName).use { input ->
                manifestCache.outputStream().use { output -> input.copyTo(output) }
            }
            callback.onLog("[MANIFEST] Connected: $assetName")
        } catch (e: Exception) {
            callback.onLog("[MANIFEST] Using built-in inspection")
        }

        try {
            val py = Python.getInstance()
            val module = py.getModule("pak_engine")
            val pyResult = module.callAttr("unpack_pak",
                file.absolutePath,
                targetFolder.absolutePath,
                callback,
                manifestCache.absolutePath
            ).toBoolean()

            if (pyResult) {
                // Copy cleanly extracted Lua directly to Lua/Original
                targetFolder.walkTopDown().filter { it.extension.lowercase() == "lua" }.forEach { luaF ->
                    val dest = File(dirLuaOriginal, luaF.name)
                    luaF.copyTo(dest, overwrite = true)
                    callback.onLog("📁 [LUA SAVED] Clean code ready in /Lua/Original/${luaF.name}")
                }
                return@withContext true
            }
        } catch (e: Exception) {
            callback.onLog("[PY ERROR] ${e.message}")
        }

        nativeUnpackDeep(file.absolutePath, targetFolder.absolutePath, callback, context.assets)
    }

    suspend fun replaceAndRepack(folderName: String, callback: TerminalCallback): Boolean = withContext(Dispatchers.IO) {
        val unpackedFolder = File(dirUnpack, folderName)
        if (!unpackedFolder.exists()) {
            callback.onLog("[ERROR] Unpack folder missing: $folderName")
            return@withContext false
        }

        callback.onLog("[REPLACE] Checking Editor folder for files...")
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
        callback.onLog("[INFO] $replaced modified files placed.")

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

    suspend fun compileAndSaveLua(fileName: String, code: String): File = withContext(Dispatchers.IO) {
        val py = Python.getInstance()
        val module = py.getModule("pak_engine")
        val pyBytes = module.callAttr("compile_lua_to_pak_ready", code).toJava(ByteArray::class.java)

        val destCompile = File(dirLuaCompile, fileName)
        destCompile.writeBytes(pyBytes)

        // Save ready file directly into Editor/ for repack
        val destEditor = File(dirEditor, fileName)
        destCompile.copyTo(destEditor, overwrite = true)
        return@withContext destEditor
    }

    fun getAllLuaFiles(): List<File> {
        val set = mutableSetOf<File>()
        dirLuaOriginal.listFiles()?.filter { it.extension.lowercase() == "lua" }?.let { set.addAll(it) }
        dirLuaCompile.listFiles()?.filter { it.extension.lowercase() == "lua" }?.let { set.addAll(it) }
        dirUnpack.walkTopDown().filter { it.extension.lowercase() == "lua" }.let { set.addAll(it) }
        return set.toList()
    }
}
