package com.seeandsay.chronologer.engine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Drops unchanged frames on device, crops the Ray-Ban center region, and emits WebP.
 * The 32x32 thumbnail is only the delta reference. The returned bytes are scaled for the model.
 */
class FrameProcessor {
    private var previousThumbnail: Bitmap? = null

    @Synchronized
    fun processFrameIfChanged(rawBitmap: Bitmap, cropRoi: Boolean = true): ByteArray? {
        if (rawBitmap.isRecycled || rawBitmap.width <= 0 || rawBitmap.height <= 0) {
            return null
        }

        val thumbnail = try {
            Bitmap.createScaledBitmap(rawBitmap, THUMB_SIZE, THUMB_SIZE, true)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val ownsThumbnail = thumbnail !== rawBitmap
        var retainThumbnail = false
        try {
            val prior = previousThumbnail
            if (prior != null && !prior.isRecycled && changedFraction(thumbnail, prior) < CHANGE_FRACTION) {
                return null
            }

            val cropped = if (cropRoi) centerCrop(rawBitmap) else rawBitmap
            val ownsCrop = cropped !== rawBitmap
            try {
                val encoded = compressWebp(cropped) ?: return null
                retainThumbnail = true
                return encoded
            } finally {
                if (ownsCrop && !cropped.isRecycled) {
                    cropped.recycle()
                }
            }
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            if (retainThumbnail) {
                adoptThumbnail(thumbnail, ownsThumbnail)
            } else if (ownsThumbnail && !thumbnail.isRecycled) {
                thumbnail.recycle()
            }
        }
    }

    @Synchronized
    fun reset() {
        previousThumbnail?.takeIf { !it.isRecycled }?.recycle()
        previousThumbnail = null
    }

    private fun adoptThumbnail(thumbnail: Bitmap, ownsThumbnail: Boolean) {
        val retained = if (ownsThumbnail) {
            thumbnail
        } else {
            thumbnail.copy(Bitmap.Config.ARGB_8888, false)
        }
        val previous = previousThumbnail
        previousThumbnail = retained
        if (previous != null && previous !== retained && !previous.isRecycled) {
            previous.recycle()
        }
    }

    private fun changedFraction(current: Bitmap, previous: Bitmap): Float {
        val count = THUMB_SIZE * THUMB_SIZE
        val now = IntArray(count)
        val before = IntArray(count)
        current.getPixels(now, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
        previous.getPixels(before, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
        var changed = 0
        for (index in 0 until count) {
            if (abs(luma(now[index]) - luma(before[index])) > LUMA_THRESHOLD) {
                changed++
            }
        }
        return changed.toFloat() / count.toFloat()
    }

    private fun luma(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private fun centerCrop(source: Bitmap): Bitmap {
        val cropWidth = (source.width * ROI_FRACTION).toInt().coerceIn(1, source.width)
        val cropHeight = (source.height * ROI_FRACTION).toInt().coerceIn(1, source.height)
        val left = (source.width - cropWidth) / 2
        val top = (source.height - cropHeight) / 2
        if (left == 0 && top == 0 && cropWidth == source.width && cropHeight == source.height) {
            return source
        }
        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
    }

    private fun compressWebp(bitmap: Bitmap): ByteArray? {
        val scaled = scaleForModel(bitmap)
        val ownsScaled = scaled !== bitmap
        try {
            val stream = ByteArrayOutputStream()
            val wrote = scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, stream)
            if (!wrote || stream.size() == 0) {
                return null
            }
            return stream.toByteArray()
        } finally {
            if (ownsScaled && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    private fun scaleForModel(bitmap: Bitmap): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= MODEL_LONG_EDGE) {
            return bitmap
        }
        val scale = MODEL_LONG_EDGE.toFloat() / longEdge.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private companion object {
        const val THUMB_SIZE = 32
        const val LUMA_THRESHOLD = 45
        const val CHANGE_FRACTION = 0.035f
        const val ROI_FRACTION = 0.70f
        const val MODEL_LONG_EDGE = 1024
        const val WEBP_QUALITY = 80
    }
}
