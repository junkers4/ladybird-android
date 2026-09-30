package io.github.junkers4.ladybird.browser

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.junkers4.ladybird.R
import io.github.junkers4.ladybird.core.engine.FinishedDownload
import io.github.junkers4.ladybird.core.security.DownloadPolicy
import java.io.File

/** Moves finished engine downloads into the shared Downloads collection (UI-014, SEC-018). */
object DownloadPublisher {
    fun publish(context: Context, download: FinishedDownload) {
        val source = File(download.path)
        if (!source.isFile) return
        val name = DownloadPolicy.sanitizeFileName(download.name.ifBlank { source.name })
        if (DownloadPolicy.isDangerous(name)) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dangerous_download_title)
                .setMessage(context.getString(R.string.dangerous_download_message, name))
                .setPositiveButton(R.string.keep) { _, _ -> copy(context, source, name) }
                .setNegativeButton(R.string.discard) { _, _ -> source.delete() }
                .show()
        } else {
            copy(context, source, name)
        }
    }

    private fun copy(context: Context, source: File, name: String) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        runCatching {
            resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            source.delete()
            Toast.makeText(context, context.getString(R.string.download_finished, name), Toast.LENGTH_LONG).show()
        }.onFailure {
            resolver.delete(uri, null, null)
            Toast.makeText(context, context.getString(R.string.download_failed, name), Toast.LENGTH_LONG).show()
        }
    }
}
