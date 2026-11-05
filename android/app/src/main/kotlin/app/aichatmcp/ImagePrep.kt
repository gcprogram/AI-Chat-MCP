package app.aichatmcp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.aichatmcp.core.DocumentException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bereitet ein Foto für das Bildmodell vor: verkleinern (große Fotos sprengen Zeit und Speicher), drehen, als JPEG. */
object ImagePrep {
    const val MAX_SIDE = 768

    fun jpeg(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): ByteArray =
        jpeg({ context.contentResolver.openInputStream(uri) }, maxSide)

    /** Wie [jpeg] für ein Bild, das schon als Bytes vorliegt (z. B. aus einer EPUB-Datei). */
    fun jpegFromBytes(bytes: ByteArray, maxSide: Int = MAX_SIDE): ByteArray = jpeg({ ByteArrayInputStream(bytes) }, maxSide)

    private fun jpeg(open: () -> InputStream?, maxSide: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw DocumentException("Das Bild lässt sich nicht lesen.")

        var sample = 1 // grob verkleinern beim Laden, damit ein 12-Megapixel-Foto nicht ganz in den Speicher kommt
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bitmap = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw DocumentException("Das Bild lässt sich nicht lesen.")

        val rotation = try {
            open()?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (rotation != 0f || scale < 1f) {
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation)
            }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
