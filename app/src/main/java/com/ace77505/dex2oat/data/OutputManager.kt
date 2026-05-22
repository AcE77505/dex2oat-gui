package com.ace77505.dex2oat.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ace77505.dex2oat.model.OutputLocation
import java.io.File

class OutputManager(private val context: Context) {
    fun createLogFile(): File = createInternalFile("logs", "last_compile.log")

    fun createDumpFile(): File = createInternalFile("dumps", "oatdump.txt")

    fun copyToLocation(file: File, location: OutputLocation, displayName: String) {
        if (location !is OutputLocation.Saf) return
        val tree = DocumentFile.fromTreeUri(context, location.uri) ?: return
        val target = tree.findFile(displayName)
            ?: tree.createFile("text/plain", displayName)
            ?: return
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun createInternalFile(folder: String, fileName: String): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, fileName)
    }
}
