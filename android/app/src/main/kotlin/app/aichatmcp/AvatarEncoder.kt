package app.aichatmcp

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import app.aichatmcp.core.EmbeddedAvatar
import java.io.ByteArrayOutputStream

/** Macht aus einem gewählten Bild das eingebettete Avatar-Bild: quadratisch zugeschnitten, 256 x 256, JPEG, Base64. */
object AvatarEncoder {
    fun encode(resolver: ContentResolver, uri: Uri, size: Int = EmbeddedAvatar.SIZE, quality: Int = 85): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size * 2 && bounds.outHeight / (sample * 2) >= size * 2) sample *= 2 // große Bilder schon beim Laden verkleinern
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val flat = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) // Transparenz auf Weiß, JPEG kennt sie nicht
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return EmbeddedAvatar.encode(out.toByteArray())
    }
}
