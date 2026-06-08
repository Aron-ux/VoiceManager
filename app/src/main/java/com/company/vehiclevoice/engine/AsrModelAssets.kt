package com.company.vehiclevoice.engine

import android.content.Context
import java.io.File

object AsrModelAssets {
    private const val ASSET_DIR = "models/asr"
    private const val TARGET_DIR = "models/asr"
    private const val VERSION_FILE = "model-version.txt"

    private val requiredFiles = listOf(
        "model.int8.onnx",
        "tokens.txt",
        VERSION_FILE,
    )

    fun ensureCopied(context: Context): File {
        val targetDir = File(context.filesDir, TARGET_DIR)
        val assetVersion = context.assets.open("$ASSET_DIR/$VERSION_FILE").use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText().trim()
        }
        val targetVersion = File(targetDir, VERSION_FILE)
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.trim()

        if (targetVersion == assetVersion && requiredFiles.all { File(targetDir, it).isFile }) {
            return targetDir
        }

        targetDir.mkdirs()
        for (fileName in requiredFiles) {
            copyAssetFile(context, "$ASSET_DIR/$fileName", File(targetDir, fileName))
        }

        return targetDir
    }

    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
