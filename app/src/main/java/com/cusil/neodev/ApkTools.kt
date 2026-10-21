package com.cusil.neodev

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipInputStream

class DownloadedFile(val file: File, val savedTo: String?, val isApk: Boolean)

object ApkTools {

    private fun mimeFor(name: String): String = when {
        name.endsWith(".apk") -> "application/vnd.android.package-archive"
        name.endsWith(".jar") -> "application/java-archive"
        else -> "application/zip"
    }

    /**
     * Takes a downloaded GitHub artifact zip. Pulls out the first .apk (or .jar) and copies it to
     * Downloads/NeoDev. If there is none, the zip itself is saved.
     */
    fun deliver(ctx: Context, zip: File, prefix: String): DownloadedFile {
        val outDir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        var found: File? = null
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null && found == null) {
                val base = e.name.substringAfterLast('/')
                if (!e.isDirectory && (base.endsWith(".apk") || base.endsWith(".jar"))) {
                    val out = File(outDir, "$prefix-$base".replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    out.outputStream().use { zin.copyTo(it) }
                    found = out
                }
                e = zin.nextEntry
            }
        }
        val f = found
        if (f != null) {
            val saved = saveToDownloads(ctx, f)
            return DownloadedFile(f, saved, f.name.endsWith(".apk"))
        }
        val keep = File(outDir, "$prefix-artifact.zip".replace(Regex("[^A-Za-z0-9._-]"), "_"))
        zip.copyTo(keep, overwrite = true)
        return DownloadedFile(keep, saveToDownloads(ctx, keep), false)
    }

    /** Copies a file into the public Downloads/NeoDev folder. Returns a readable path or null. */
    fun saveToDownloads(ctx: Context, file: File): String? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(file.name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NeoDev")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            "Downloads/NeoDev/" + file.name
        } catch (e: Exception) {
            null
        }
    }

    private fun uriFor(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)

    fun install(ctx: Context, file: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return
        }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(ctx, file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun share(ctx: Context, file: File) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeFor(file.name))
            .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Share " + file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }
}
