package com.upstool.paktool

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PakEngine {
    init {
        System.loadLibrary("upstool_native")
    }

    private external fun nativeUnpackFull(pakPath: String, outputDir: String): Boolean
    private external fun nativeRepack(sourceDir: String, outputPak: String): Boolean

    private val baseDir = File(Environment.getExternalStorageDirectory(), "Upstool")

    val dirOriginal = File(baseDir, "Original").apply { mkdirs() }
    val dirEditor   = File(baseDir, "Editor").apply { mkdirs() }
    val dirUnpack   = File(baseDir, "Unpack").apply { mkdirs() }
    val dirRepack   = File(baseDir, "Repack").apply { mkdirs() }

    suspend fun unpackArchive(archiveFile: File): Boolean = withContext(Dispatchers.IO) {
        val outFolder = File(dirUnpack, archiveFile.nameWithoutExtension)
        if (!outFolder.exists()) outFolder.mkdirs()
        nativeUnpackFull(archiveFile.absolutePath, outFolder.absolutePath)
    }

    suspend fun replaceAndRepack(unpackedFolderName: String): Boolean = withContext(Dispatchers.IO) {
        val targetUnpackDir = File(dirUnpack, unpackedFolderName)
        if (!targetUnpackDir.exists()) return@withContext false

        dirEditor.listFiles()?.forEach { editFile ->
            targetUnpackDir.walkTopDown().forEach { extractedFile ->
                if (extractedFile.name.equals(editFile.name, ignoreCase = true)) {
                    editFile.copyTo(extractedFile, overwrite = true)
                }
            }
        }

        val outRepackedPak = File(dirRepack, "$unpackedFolderName.pak")
        nativeRepack(targetUnpackDir.absolutePath, outRepackedPak.absolutePath)
    }
}
