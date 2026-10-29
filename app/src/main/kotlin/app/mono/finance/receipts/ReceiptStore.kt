package app.mono.finance.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Receipt photos live as files in the app's private storage; the Receipt record keeps the file name. */
class ReceiptStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "receipts").also { it.mkdirs() }
    private val maxPixels = 2000

    /** Opens a picked or captured image upright and no larger than needed. */
    fun decode(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPixels * 2) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        val turn = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        } ?: 0f
        if (raw != null && turn != 0f) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn) }, true) else raw
    } catch (e: Exception) { null }

    /** Caps the size so the library stays small and encodes as JPEG. */
    fun prepared(image: Bitmap): ByteArray {
        val longest = maxOf(image.width, image.height)
        val scaled = if (longest > maxPixels) {
            val f = maxPixels.toFloat() / longest
            Bitmap.createScaledBitmap(image, (image.width * f).toInt().coerceAtLeast(1), (image.height * f).toInt().coerceAtLeast(1), true)
        } else image
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
    }

    fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun store(data: ByteArray, name: String = UUID.randomUUID().toString().uppercase() + ".jpg"): String {
        val safe = File(name).name // never a path
        val tmp = File(dir, "$safe.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(File(dir, safe))) { tmp.delete(); throw java.io.IOException("The photo couldn’t be saved.") }
        return safe
    }

    fun data(file: String): ByteArray? = if (file.isEmpty()) null else File(dir, File(file).name).takeIf { it.exists() }?.readBytes()
    fun exists(file: String) = file.isNotEmpty() && File(dir, File(file).name).exists()
    fun delete(file: String) { if (file.isNotEmpty()) File(dir, File(file).name).delete() }

    fun bitmap(file: String, maxPixel: Int = 1600): Bitmap? = data(file)?.let { decodeBytes(it, maxPixel) }

    private val thumbs = object : android.util.LruCache<String, Bitmap>(48) {}
    fun thumbnail(file: String, maxPixel: Int = 160): Bitmap? {
        if (file.isEmpty()) return null
        val key = "$file|$maxPixel"
        thumbs.get(key)?.let { return it }
        return bitmap(file, maxPixel)?.also { thumbs.put(key, it) }
    }

    private fun decodeBytes(bytes: ByteArray, maxPixel: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixel) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Removes photo files no receipt refers to (after a restore). */
    fun pruneOrphans(keep: Set<String>) { dir.listFiles()?.forEach { if (it.name !in keep) it.delete() } }

    fun cameraTarget(): Pair<File, Uri> {
        val folder = File(context.cacheDir, "camera").also { it.mkdirs() }
        val f = File(folder, "shot-${System.currentTimeMillis()}.jpg")
        return f to androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }
}
