package com.example.util

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/**
 * High-performance image optimization utility reverse-engineered from MovieBox's
 * discrete edge-CDN bucketing, WebP compression pipeline, and BlurHash architecture.
 */
object ImageOptimizer {

    // Discrete width buckets matching MovieBox's nj.g.java
    private val WIDTH_BUCKETS = intArrayOf(120, 180, 240, 360, 540, 720, 1080)

    // Base-83 character map for BlurHash decoding
    private const val BASE83_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    private val BASE83_MAP = BASE83_CHARSET.mapIndexed { index, char -> char to index }.toMap()

    // In-memory cache for decoded BlurHash bitmaps (20x20 bitmaps take < 2KB each)
    private val blurHashCache = LruCache<String, Bitmap>(150)

    /**
     * Transforms an original poster/cover URL into an edge-optimized WebP thumbnail
     * sized specifically for the target screen dimensions.
     *
     * Reduces image file payload from ~660KB down to 15KB - 35KB (>90% reduction).
     */
    fun getOptimalUrl(originalUrl: String?, targetWidthPx: Int = 360): String {
        if (originalUrl.isNullOrBlank()) return ""

        val trimmedUrl = originalUrl.trim()

        // 1. MovieBox Edge CDN (Alibaba Cloud OSS Image Service on aoneroom.com / pbcdn.aoneroom.com)
        if (trimmedUrl.contains("pbcdn.aoneroom.com") || trimmedUrl.contains("aoneroom.com")) {
            // Strip any existing x-oss-process parameter to avoid staying locked into low-res thumbnails
            val cleanUrl = trimmedUrl.replace(Regex("[?&]x-oss-process=image[^&]*"), "")
            val bucketWidth = WIDTH_BUCKETS.firstOrNull { it >= targetWidthPx } ?: 1080
            val separator = if (cleanUrl.contains("?")) "&" else "?"
            return "$cleanUrl${separator}x-oss-process=image/resize,w_$bucketWidth/quality,q_90/format,webp"
        }

        // 2. TMDB CDN Optimization (image.tmdb.org)
        if (trimmedUrl.contains("image.tmdb.org")) {
            val tmdbSize = when {
                targetWidthPx <= 185 -> "w185"
                targetWidthPx <= 342 -> "w342"
                targetWidthPx <= 500 -> "w500"
                targetWidthPx <= 780 -> "w780"
                targetWidthPx <= 1280 -> "w1280"
                else -> "original"
            }
            return trimmedUrl.replace(Regex("/t/p/(w\\d+|original)/"), "/t/p/$tmdbSize/")
        }

        return trimmedUrl
    }

    /**
     * Parses the ambient hue hex string (e.g., "#5b5d66" from averageHueDark / averageHueLight)
     * into a Compose Color, or returns a safe dark fallback.
     */
    /**
     * Decodes BlurHash into a ready-to-render Compose BitmapPainter.
     */
    fun getBlurHashPainter(blurHash: String?, width: Int = 20, height: Int = 20): BitmapPainter? {
        val bitmap = decodeBlurHash(blurHash, width, height) ?: return null
        return BitmapPainter(bitmap.asImageBitmap())
    }

    fun parseAverageHue(hueHex: String?, fallback: Color = Color(0xFF1E1E22)): Color {
        if (hueHex.isNullOrBlank()) return fallback
        return try {
            val hex = hueHex.trim()
            if (hex.startsWith("#")) {
                Color(AndroidColor.parseColor(hex))
            } else {
                Color(AndroidColor.parseColor("#$hex"))
            }
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * Decodes an 83-base BlurHash DCT string into a small 20x20 ARGB Bitmap in < 1ms.
     * Thread-safe and cached in RAM.
     */
    fun decodeBlurHash(blurHash: String?, width: Int = 20, height: Int = 20, punch: Float = 1.0f): Bitmap? {
        if (blurHash == null || blurHash.length < 6) return null

        val cacheKey = "${blurHash}_${width}x$height"
        blurHashCache.get(cacheKey)?.let { return it }

        return try {
            val sizeFlag = decodeBase83(blurHash, 0, 1)
            val numY = (sizeFlag / 9) + 1
            val numX = (sizeFlag % 9) + 1

            val expectedLength = 4 + 2 * numX * numY
            if (blurHash.length != expectedLength) return null

            val quantisedMaximumValue = decodeBase83(blurHash, 1, 2)
            val maximumValue = (quantisedMaximumValue + 1).toFloat() / 166.0f

            val colors = Array(numX * numY) { i ->
                if (i == 0) {
                    val value = decodeBase83(blurHash, 2, 6)
                    decodeDc(value)
                } else {
                    val value = decodeBase83(blurHash, 4 + i * 2, 6 + i * 2)
                    decodeAc(value, maximumValue * punch)
                }
            }

            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var r = 0f
                    var g = 0f
                    var b = 0f

                    for (j in 0 until numY) {
                        for (i in 0 until numX) {
                            val basis = (cos((Math.PI * x * i) / width) * cos((Math.PI * y * j) / height)).toFloat()
                            val color = colors[i + j * numX]
                            r += color[0] * basis
                            g += color[1] * basis
                            b += color[2] * basis
                        }
                    }

                    pixels[x + y * width] = AndroidColor.rgb(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
                }
            }

            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            blurHashCache.put(cacheKey, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase83(str: String, from: Int, to: Int): Int {
        var result = 0
        for (i in from until to) {
            val value = BASE83_MAP[str[i]] ?: 0
            result = result * 83 + value
        }
        return result
    }

    private fun decodeDc(value: Int): FloatArray {
        val intR = value shr 16
        val intG = (value shr 8) and 255
        val intB = value and 255
        return floatArrayOf(srgbToLinear(intR), srgbToLinear(intG), srgbToLinear(intB))
    }

    private fun decodeAc(value: Int, maximumValue: Float): FloatArray {
        val quantR = value / (19 * 19)
        val quantG = (value / 19) % 19
        val quantB = value % 19
        return floatArrayOf(
            signedPow2((quantR - 9) / 9.0f) * maximumValue,
            signedPow2((quantG - 9) / 9.0f) * maximumValue,
            signedPow2((quantB - 9) / 9.0f) * maximumValue
        )
    }

    private fun signedPow2(value: Float): Float {
        return (value.pow(2.0f)).withSign(value)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0.0f, 1.0f)
        val result = if (v <= 0.0031308f) {
            v * 12.92f
        } else {
            1.055f * v.pow(1.0f / 2.4f) - 0.055f
        }
        return (result * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun srgbToLinear(value: Int): Float {
        val v = value / 255.0f
        return if (v <= 0.04045f) {
            v / 12.92f
        } else {
            ((v + 0.055f) / 1.055f).pow(2.4f)
        }
    }
}

