package com.upstool.paktool

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

interface TerminalCallback {
    fun onLog(line: String)
}

object PakEngine {
    init {
        System.loadLibrary("upstool_native")
    }

    private external fun nativeUnpackPak(pakPath: String, outputDir: String, callback: TerminalCallback): Boolean
    private external fun nativeRepackPak(sourceDir: String, outputPak: String, callback: TerminalCallback): Boolean

    private val baseDir = File(Environment.getExternalStorageDirectory(), "Upstool")
    val dirOriginal = File(baseDir, "Original").apply { mkdirs() }
    val dirEditor   = File(baseDir, "Editor").apply { mkdirs() }
    val dirUnpack   = File(baseDir, "Unpack").apply { mkdirs() }
    val dirRepack   = File(baseDir, "Repack").apply { mkdirs() }

    suspend fun unpackArchive(file: File, callback: TerminalCallback): Boolean = withContext(Dispatchers.IO) {
        val targetFolder = File(dirUnpack, file.nameWithoutExtension).apply { mkdirs() }

        // If file is an OBB or ZIP format (Magic: PK\x03\x04)
        val isZip = try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                fis.read(header)
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            }
        } catch (e: Exception) {
            false
        }

        if (isZip) {
            callback.onLog("[OBB/ZIP] Detected ZIP format. Extracting...")
            return@withContext unpackZip(file, targetFolder, callback)
        } else {
            return@withContext nativeUnpackPak(file.absolutePath, targetFolder.absolutePath, callback)
        }
    }

    private fun unpackZip(zipFile: File, targetDir: File, callback: TerminalCallback): Boolean {
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                var count = 0
                val buffer = ByteArray(65536)
                while (entry != null) {
                    val newFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                        count++
                        if (count % 5 == 0 || newFile.name.contains("uasset")) {
                            callback.onLog("📁 [OBB] Extracted: ${entry.name}")
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                callback.onLog("[SUCCESS] OBB unpacked total $count files.")
                return true
            }
        } catch (e: Exception) {
            callback.onLog("[ERROR] OBB extract error: ${e.message}")
            return false
        }
    }

    suspend fun replaceAndRepack(folderName: String, callback: TerminalCallback): Boolean = withContext(Dispatchers.IO) {
        val unpackedFolder = File(dirUnpack, folderName)
        if (!unpackedFolder.exists()) {
            callback.onLog("[ERROR] Unpacked directory not found: $folderName")
            return@withContext false
        }

        callback.onLog("[REPLACE] Checking Editor folder for modifications...")
        var replacedCount = 0
        dirEditor.listFiles()?.forEach { editorFile ->
            unpackedFolder.walkTopDown().forEach { fileInTree ->
                if (fileInTree.name.equals(editorFile.name, ignoreCase = true)) {
                    editorFile.copyTo(fileInTree, overwrite = true)
                    replacedCount++
                    callback.onLog("🔄 [REPLACED] ${editorFile.name} -> ${fileInTree.relativeTo(unpackedFolder).path}")
                }
            }
        }

        callback.onLog("[INFO] Total replaced files: $replacedCount")
        val outputPak = File(dirRepack, "$folderName.pak")
        nativeRepackPak(unpackedFolder.absolutePath, outputPak.absolutePath, callback)
    }
}
