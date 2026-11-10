package com.seeandsay.chronologer.data

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes the chronologer history to a JSON file in cache and opens the system share sheet.
 */
object LogExporter {
    private const val EXPORT_DIRECTORY = "chronologer_exports"
    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    fun export(context: Context, logs: List<NarrationLog>): File {
        val file = writeJson(context, logs)
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            file,
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "See and Say chronologer history")
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(share, "Export chronologer history").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
        return file
    }

    private fun writeJson(context: Context, logs: List<NarrationLog>): File {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val cutoff = System.currentTimeMillis() - DAY_MILLIS
        directory.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.lastModified() < cutoff) {
                existing.delete()
            }
        }
        val stamp = fileStamp.format(Instant.now())
        val file = File(directory, "chronologer_history-$stamp.json")
        file.writeText(toJson(logs))
        return file
    }

    private fun toJson(logs: List<NarrationLog>): String {
        val array = JSONArray()
        logs.forEach { log ->
            array.put(
                JSONObject()
                    .put("id", log.id)
                    .put("timestamp", log.timestamp)
                    .put("formattedTimestamp", log.formattedTimestamp)
                    .put("activeDevice", log.activeDevice)
                    .put("primaryApp", log.primaryApp)
                    .put("primaryActivity", log.primaryActivity)
                    .put("visibleText", log.visibleText)
                    .put("microActions", log.microActions)
                    .put("physicalAction", log.physicalAction)
                    .put("ambientContext", log.ambientContext)
                    .put("rawPayload", log.rawPayload),
            )
        }
        return array.toString(2)
    }
}
