package dev.turbodash.app.ui.logs

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Several logs packed in one zip, so they go in a single WhatsApp message instead of one by one. */
object LogBundle {
    private const val PREFS = "logs_sent"
    private const val KEY = "names"

    fun zip(files: List<File>, outDir: File): File {
        outDir.mkdirs()
        // Only the latest bundle is kept: the previous one was already shared.
        outDir.listFiles { f -> f.name.startsWith("TurboDash-logs_") && f.name.endsWith(".zip") }?.forEach { it.delete() }
        val day = SimpleDateFormat("yyyy-MM-dd_HH.mm", Locale.US).format(Date())
        val out = File(outDir, "TurboDash-logs_${day}_${files.size}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    fun share(context: Context, zip: File, count: Int) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Enviar $count logs"))
    }

    fun sent(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()

    /** Marked when the share sheet opens: there is no way to know if the other app finished sending. */
    fun markSent(context: Context, names: Collection<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY, sent(context) + names).apply()
    }
}
