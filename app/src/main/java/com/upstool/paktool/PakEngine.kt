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

    // Dedicated Lua directory structure
    val dirLuaOriginal   = File(baseDir, "Lua/Original").apply { mkdirs() }
    val dirLuaDecompiled = File(baseDir, "Lua/Decompiled").apply { mkdirs() }
    val dirLuaCompiled   = File(baseDir, "Lua/Compiled").apply { mkdirs() }

    private lateinit var context: Context

    fun initPython(ctx: Context) {
        context = ctx.applicationContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    suspend fun unpackArchive(file: File, callback: TerminalCallback): Boolean = withContext(Dispatchers.IO) {
        val targetFolder = File(dirUnpack, file.nameWithoutExtension).apply { mkdirs() }
        try {
            val py = Python.getInstance()
            val module = py.getModule("pak_engine")
            val pyResult = module.callAttr("unpack_pak", file.absolutePath, targetFolder.absolutePath, callback).toBoolean()
            if (pyResult) {
                // Auto copy extracted Lua to Lua/Original
                targetFolder.walkTopDown().filter { it.extension.lowercase() == "lua" }.forEach { luaF ->
                    luaF.copyTo(File(dirLuaOriginal, luaF.name), overwrite = true)
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
            callback.onLog("[ERROR] Unpack directory missing: $folderName")
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

    suspend fun decompileLua(file: File): String = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val module = py.getModule("pak_engine")
            val source = module.callAttr("decompile_lua_file", file.absolutePath).toString()
            val savePath = File(dirLuaDecompiled, file.name)
            savePath.writeText(source)
            return@withContext source
        } catch (e: Exception) {
            return@withContext "-- Error: ${e.message}"
        }
    }

    suspend fun saveCompiledLua(fileName: String, code: String): File = withContext(Dispatchers.IO) {
        val destCompiled = File(dirLuaCompiled, fileName)
        destCompiled.writeText(code)
        val destEditor = File(dirEditor, fileName)
        destCompiled.copyTo(destEditor, overwrite = true)
        return@withContext destCompiled
    }

    fun getAllLuaFiles(): List<File> {
        val set = mutableSetOf<File>()
        dirLuaOriginal.listFiles()?.filter { it.extension.lowercase() in listOf("lua", "luac") }?.let { set.addAll(it) }
        dirUnpack.walkTopDown().filter { it.extension.lowercase() in listOf("lua", "luac") }.let { set.addAll(it) }
        return set.toList()
    }
}
