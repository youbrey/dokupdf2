package com.example.core.filter

import android.graphics.*
import androidx.compose.ui.geometry.Offset
import com.example.core.crop.AutoCropDetector
import com.example.core.model.CropGeometry
import com.example.core.model.FilterSettings
import com.example.core.model.FilterType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-performance, professional document scanning filter engine.
 * Implements adaptive background illumination normalization, shadow elimination,
 * high-pass text sharpening, and color stamp/signature preservation (matching CamScanner Ajaib Pro).
 */
object FilterProcessor {

    fun applyFilter(
        source: Bitmap,
        filterType: FilterType,
        brightness: Float = 1.0f,
        contrast: Float = 1.0f
    ): Bitmap = applyFilter(
        source,
        filterType,
        FilterSettings(brightness = brightness, contrast = contrast)
    )

    fun applyFilter(source: Bitmap, filterType: FilterType, settings: FilterSettings): Bitmap {
        require(!source.isRecycled) { "Bitmap sumber sudah di-recycle" }
        val preset = when (filterType) {
            FilterType.AUTO -> applyAutoEnhance(source)
            FilterType.ORIGINAL -> source
            FilterType.LIGHTEN -> applyLighten(source, 1f, 1f)
            FilterType.SHARPEN -> applySuperSharpen(source, 1f, 1f)
            FilterType.MAGIC_COLOR -> applyMagicColor(source, 1f, 1f)
            FilterType.NO_SHADOW -> applyNoShadow(source, 1f, 1f)
            FilterType.MAGIC_BW_HP -> applyHighContrastBW(source, 1f, 1f)
            FilterType.GRAYSCALE -> applyGrayscale(source, 1f, 1f)
            FilterType.PHOTO_ENHANCE -> applyPhotoEnhance(source)
            FilterType.INVERT -> applyInvert(source)
        }

        val normalized = settings.normalized()
        if (normalized.isNeutral()) return preset

        var adjusted: Bitmap? = null
        return try {
            applyProfessionalAdjustments(preset, normalized).also { adjusted = it }
        } finally {
            // A preset can itself be a full-resolution allocation. Release it both after a
            // successful adjustment pass and when the second pass throws/OOMs.
            if (preset !== source && preset !== adjusted && !preset.isRecycled) preset.recycle()
        }
    }

    private fun applyAutoEnhance(source: Bitmap): Bitmap {
        // PERF FIX: this used to call Bitmap.getPixel(x, y) once per sample -- each call is a
        // separate JNI round-trip into the native bitmap, so at a 320px sampling grid on a
        // ~3000px-wide scan that was several thousand individual native calls on every single
        // default-mode capture. A single bulk getPixels() read of the sampled rows is the same
        // data with one native call per row instead of one per pixel.
        val sampleStep = max(1, max(source.width, source.height) / 320)
        var lumaSum = 0.0
        var lumaSquareSum = 0.0
        var chromaSum = 0.0
        var samples = 0
        // One bulk row read per sampled y (native call), then sample every sampleStep-th column
        // out of that row in plain CPU-side array indexing -- keeps the exact same sparse grid
        // of sample points as the original per-pixel version, just without a native call per point.
        val rowBuffer = IntArray(source.width)
        for (y in 0 until source.height step sampleStep) {
            source.getPixels(rowBuffer, 0, source.width, 0, y, source.width, 1)
            for (x in 0 until source.width step sampleStep) {
                val color = rowBuffer[x]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val luma = (299 * red + 587 * green + 114 * blue) / 1000.0
                lumaSum += luma
                lumaSquareSum += luma * luma
                chromaSum += max(red, max(green, blue)) - min(red, min(green, blue))
                samples++
            }
        }
        if (samples == 0) return source.copy(Bitmap.Config.ARGB_8888, false)

        val mean = lumaSum / samples
        val standardDeviation = kotlin.math.sqrt((lumaSquareSum / samples - mean * mean).coerceAtLeast(0.0))
        val averageChroma = chromaSum / samples
        return when {
            averageChroma >= 16.0 -> applyMagicColor(source, 1f, 1f)
            standardDeviation < 38.0 || mean < 145.0 -> applyNoShadow(source, 1.05f, 1.08f)
            else -> applySuperSharpen(source, 1f, 0.92f)
        }
    }

    /**
     * CamScanner Ajaib Pro (Magic Color):
     * 1. Estimates 2D non-uniform paper background illumination.
     * 2. Whitens paper background to pure 100% white (#FFFFFF), removing all shadow gradients.
     * 3. Deepens black printed text with high-contrast tone mapping.
     * 4. Preserves and boosts vibrant blue stamps, ink signatures, and colored seals.
     * 5. Applies high-pass unsharp masking on text edges for razor-sharp typography.
     */
    private fun applyMagicColor(source: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Build smooth 2D Background Illumination Map
        val bgGrid = buildBackgroundGrid(pixels, width, height)

        val outPixels = IntArray(width * height)
        val gridCols = bgGrid[0].size
        val gridRows = bgGrid.size
        val cellW = width.toFloat() / (gridCols - 1).coerceAtLeast(1)
        val cellH = height.toFloat() / (gridRows - 1).coerceAtLeast(1)

        val contrastMultiplier = contrast.coerceIn(0.6f, 2.0f)
        val brightnessBias = (brightness - 1.0f) * 40f

        for (y in 0 until height) {
            val gy = (y / cellH).toInt().coerceIn(0, gridRows - 2)
            val ty = (y - gy * cellH) / cellH

            val rowOffset = y * width

            for (x in 0 until width) {
                val gx = (x / cellW).toInt().coerceIn(0, gridCols - 2)
                val tx = (x - gx * cellW) / cellW

                // Bilinear interpolation for smooth continuous paper background luminance
                val topBg = (1f - tx) * bgGrid[gy][gx] + tx * bgGrid[gy][gx + 1]
                val bottomBg = (1f - tx) * bgGrid[gy + 1][gx] + tx * bgGrid[gy + 1][gx + 1]
                val bg = ((1f - ty) * topBg + ty * bottomBg).coerceIn(45f, 255f)

                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val lum = (299 * r + 587 * g + 114 * b + 500) / 1000

                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val chroma = maxC - minC

                // Normalized luminance ratio against local paper background
                val ratio = (lum.toFloat() / bg)

                // White paper background detection threshold
                val paperThreshold = 0.86f - (brightnessBias / 300f)

                if (ratio >= paperThreshold && chroma < 22) {
                    // Pure paper white - completely cleans up shadows, folds, and yellowing
                    outPixels[rowOffset + x] = 0xFFFFFFFF.toInt()
                } else if (ratio in (paperThreshold - 0.14f)..paperThreshold && chroma < 22) {
                    // Smooth antialiased blend near paper boundary
                    val t = (ratio - (paperThreshold - 0.14f)) / 0.14f
                    val darkVal = ((ratio / paperThreshold) * 200f * (2.0f - contrastMultiplier)).toInt().coerceIn(100, 255)
                    val smoothVal = (darkVal + (255 - darkVal) * t).toInt().coerceIn(0, 255)
                    outPixels[rowOffset + x] = (0xFF shl 24) or (smoothVal shl 16) or (smoothVal shl 8) or smoothVal
                } else if ((chroma >= 22 && !isWarmShadowCast(r, g, b)) || (b > r + 15 && b > g + 10)) {
                    // Color Element: Official blue stamps, ink signatures, red seals, colored headers
                    val normFactor = (255f / bg) * 1.05f * contrastMultiplier
                    var newR = (r * normFactor).toInt()
                    var newG = (g * normFactor).toInt()
                    var newB = (b * normFactor).toInt()

                    // Boost color saturation for crisp vivid stamps
                    val newLum = (299 * newR + 587 * newG + 114 * newB + 500) / 1000
                    val satBoost = 1.35f
                    newR = (newLum + (newR - newLum) * satBoost).toInt().coerceIn(0, 255)
                    newG = (newLum + (newG - newLum) * satBoost).toInt().coerceIn(0, 255)
                    newB = (newLum + (newB - newLum) * satBoost).toInt().coerceIn(0, 255)

                    outPixels[rowOffset + x] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
                } else {
                    // Dark printed typography, lines, and logos: deepen black contrast
                    val norm = (ratio / paperThreshold).coerceIn(0f, 1f)
                    // High-contrast power curve to make text solid dark black
                    val textVal = (Math.pow(norm.toDouble(), 1.7 * contrastMultiplier) * 175.0).toInt().coerceIn(0, 255)
                    outPixels[rowOffset + x] = (0xFF shl 24) or (textVal shl 16) or (textVal shl 8) or textVal
                }
            }
        }

        // Apply high-pass sharpening on text pixels to enhance small character legibility
        applyUnsharpSharpen(outPixels, width, height, strength = 0.5f)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Mempertajam (Super Sharp):
     * Adaptive illumination normalization + aggressive edge sharpening kernel for dense or blurry text.
     */
    private fun applySuperSharpen(source: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val bgGrid = buildBackgroundGrid(pixels, width, height)

        val outPixels = IntArray(width * height)
        val gridCols = bgGrid[0].size
        val gridRows = bgGrid.size
        val cellW = width.toFloat() / (gridCols - 1).coerceAtLeast(1)
        val cellH = height.toFloat() / (gridRows - 1).coerceAtLeast(1)

        val contrastMultiplier = (contrast * 1.2f).coerceIn(0.7f, 2.2f)

        for (y in 0 until height) {
            val gy = (y / cellH).toInt().coerceIn(0, gridRows - 2)
            val ty = (y - gy * cellH) / cellH
            val rowOffset = y * width

            for (x in 0 until width) {
                val gx = (x / cellW).toInt().coerceIn(0, gridCols - 2)
                val tx = (x - gx * cellW) / cellW

                val topBg = (1f - tx) * bgGrid[gy][gx] + tx * bgGrid[gy][gx + 1]
                val bottomBg = (1f - tx) * bgGrid[gy + 1][gx] + tx * bgGrid[gy + 1][gx + 1]
                val bg = ((1f - ty) * topBg + ty * bottomBg).coerceIn(45f, 255f)

                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val lum = (299 * r + 587 * g + 114 * b + 500) / 1000
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                val ratio = lum.toFloat() / bg

                val paperThreshold = 0.84f

                if (ratio >= paperThreshold && chroma < 20) {
                    outPixels[rowOffset + x] = 0xFFFFFFFF.toInt()
                } else if ((chroma >= 20 && !isWarmShadowCast(r, g, b)) || (b > r + 12 && b > g + 8)) {
                    val normFactor = (255f / bg) * 1.1f * contrastMultiplier
                    var newR = (r * normFactor).toInt()
                    var newG = (g * normFactor).toInt()
                    var newB = (b * normFactor).toInt()

                    val newLum = (299 * newR + 587 * newG + 114 * newB + 500) / 1000
                    val satBoost = 1.4f
                    newR = (newLum + (newR - newLum) * satBoost).toInt().coerceIn(0, 255)
                    newG = (newLum + (newG - newLum) * satBoost).toInt().coerceIn(0, 255)
                    newB = (newLum + (newB - newLum) * satBoost).toInt().coerceIn(0, 255)
                    outPixels[rowOffset + x] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
                } else {
                    val norm = (ratio / paperThreshold).coerceIn(0f, 1f)
                    val textVal = (Math.pow(norm.toDouble(), 2.0 * contrastMultiplier) * 150.0).toInt().coerceIn(0, 255)
                    outPixels[rowOffset + x] = (0xFF shl 24) or (textVal shl 16) or (textVal shl 8) or textVal
                }
            }
        }

        // [Audit] Dua pass unsharp mask skala berbeda (radius 1px lalu 3px) — bukan satu pass
        // radius=1 seperti sebelumnya. Radius kecil menajamkan detail halus/tepi huruf tipis,
        // radius lebih besar menajamkan kontras goresan yang lebih lebar. Kombinasi ini yang
        // sebelumnya hilang dan bikin hasil "Mempertajam" tetap terasa lembek dibanding
        // CamScanner meski sudah lewat normalisasi latar & pemetaan kontras di atas.
        applyUnsharpSharpen(outPixels, width, height, strength = 0.55f, radius = 1)
        applyUnsharpSharpen(outPixels, width, height, strength = 0.5f, radius = 3)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Tanpa Bayangan (No Shadow):
     * Normalizes illumination across the page to flatten lighting gradients while preserving subtle tones.
     */
    private fun applyNoShadow(source: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val bgGrid = buildBackgroundGrid(pixels, width, height)
        val outPixels = IntArray(width * height)
        val gridCols = bgGrid[0].size
        val gridRows = bgGrid.size
        val cellW = width.toFloat() / (gridCols - 1).coerceAtLeast(1)
        val cellH = height.toFloat() / (gridRows - 1).coerceAtLeast(1)

        for (y in 0 until height) {
            val gy = (y / cellH).toInt().coerceIn(0, gridRows - 2)
            val ty = (y - gy * cellH) / cellH
            val rowOffset = y * width

            for (x in 0 until width) {
                val gx = (x / cellW).toInt().coerceIn(0, gridCols - 2)
                val tx = (x - gx * cellW) / cellW

                val topBg = (1f - tx) * bgGrid[gy][gx] + tx * bgGrid[gy][gx + 1]
                val bottomBg = (1f - tx) * bgGrid[gy + 1][gx] + tx * bgGrid[gy + 1][gx + 1]
                val bg = ((1f - ty) * topBg + ty * bottomBg).coerceIn(40f, 255f)

                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val normFactor = (245f / bg) * contrast * brightness
                val nr = (r * normFactor).toInt().coerceIn(0, 255)
                val ng = (g * normFactor).toInt().coerceIn(0, 255)
                val nb = (b * normFactor).toInt().coerceIn(0, 255)

                outPixels[rowOffset + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Hitam & Putih (High-Contrast B&W):
     * Adaptive Sauvola-inspired local thresholding for crisp scanned document look.
     */
    private fun applyHighContrastBW(source: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val thresholdGrid = buildAdaptiveThresholdGrid(pixels, width, height, brightness, contrast)
        val outPixels = IntArray(width * height)
        val gridCols = thresholdGrid[0].size
        val gridRows = thresholdGrid.size
        val cellW = width.toFloat() / (gridCols - 1).coerceAtLeast(1)
        val cellH = height.toFloat() / (gridRows - 1).coerceAtLeast(1)

        for (y in 0 until height) {
            val gy = (y / cellH).toInt().coerceIn(0, gridRows - 2)
            val ty = (y - gy * cellH) / cellH
            val rowOffset = y * width

            for (x in 0 until width) {
                val gx = (x / cellW).toInt().coerceIn(0, gridCols - 2)
                val tx = (x - gx * cellW) / cellW

                val topThreshold = (1f - tx) * thresholdGrid[gy][gx] + tx * thresholdGrid[gy][gx + 1]
                val bottomThreshold = (1f - tx) * thresholdGrid[gy + 1][gx] + tx * thresholdGrid[gy + 1][gx + 1]
                val localThreshold = ((1f - ty) * topThreshold + ty * bottomThreshold).coerceIn(25f, 245f)

                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = (299 * r + 587 * g + 114 * b + 500) / 1000

                if (lum >= localThreshold) {
                    outPixels[rowOffset + x] = 0xFFFFFFFF.toInt()
                } else {
                    // Preserve a narrow antialiasing band instead of producing jagged glyphs.
                    val diff = localThreshold - lum
                    val edgeVal = if (diff < 18f) ((1f - diff / 18f) * 132f).toInt().coerceIn(0, 255) else 0
                    outPixels[rowOffset + x] = (0xFF shl 24) or (edgeVal shl 16) or (edgeVal shl 8) or edgeVal
                }
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Low-memory Sauvola threshold surface. Statistics are calculated per local block and
     * interpolated, avoiding the 100MB+ integral-image allocations caused by full-resolution
     * camera photos while still adapting to shadows and paper folds.
     */
    private fun buildAdaptiveThresholdGrid(
        pixels: IntArray,
        width: Int,
        height: Int,
        brightness: Float,
        contrast: Float
    ): Array<FloatArray> {
        val cols = (width / 48).coerceIn(10, 42)
        val rows = (height / 48).coerceIn(10, 42)
        val grid = Array(rows) { FloatArray(cols) }
        val blockWidth = max(1, width / cols)
        val blockHeight = max(1, height / rows)
        val sauvolaK = (0.22f * contrast.coerceIn(0.7f, 1.6f)).coerceIn(0.14f, 0.34f)
        val brightnessShift = (brightness.coerceIn(0.7f, 1.3f) - 1f) * 38f

        for (row in 0 until rows) {
            val centerY = ((row + 0.5f) * height / rows).toInt().coerceIn(0, height - 1)
            val startY = (centerY - blockHeight).coerceAtLeast(0)
            val endY = (centerY + blockHeight).coerceAtMost(height)
            for (column in 0 until cols) {
                val centerX = ((column + 0.5f) * width / cols).toInt().coerceIn(0, width - 1)
                val startX = (centerX - blockWidth).coerceAtLeast(0)
                val endX = (centerX + blockWidth).coerceAtMost(width)
                val stepX = max(1, (endX - startX) / 18)
                val stepY = max(1, (endY - startY) / 18)
                var sum = 0.0
                var squareSum = 0.0
                var count = 0

                for (y in startY until endY step stepY) {
                    val rowOffset = y * width
                    for (x in startX until endX step stepX) {
                        val pixel = pixels[rowOffset + x]
                        val red = (pixel shr 16) and 0xFF
                        val green = (pixel shr 8) and 0xFF
                        val blue = pixel and 0xFF
                        val luma = (299 * red + 587 * green + 114 * blue) / 1000.0
                        sum += luma
                        squareSum += luma * luma
                        count++
                    }
                }

                val mean = if (count > 0) sum / count else 200.0
                val deviation = kotlin.math.sqrt((squareSum / max(1, count) - mean * mean).coerceAtLeast(0.0))
                grid[row][column] = (
                    mean * (1.0 + sauvolaK * (deviation / 128.0 - 1.0)) - brightnessShift
                    ).toFloat().coerceIn(25f, 245f)
            }
        }
        return blurGrid(grid)
    }

    /**
     * Cerahkan (Lighten): Auto-levels brightness with highlight protection
     */
    private fun applyLighten(source: Bitmap, brightness: Float, contrast: Float): Bitmap {
        return applyColorMatrix(source, brightness * 1.25f, contrast * 1.15f, 1.1f)
    }

    /**
     * Standard Grayscale with balanced contrast
     */
    private fun applyGrayscale(source: Bitmap, brightness: Float, contrast: Float): Bitmap {
        return applyColorMatrix(source, brightness * 1.05f, contrast * 1.35f, 0.0f)
    }

    /** Photo-safe enhancement that does not force paper pixels to white. */
    private fun applyPhotoEnhance(source: Bitmap): Bitmap {
        val output = applyColorMatrix(source, brightness = 1.03f, contrast = 1.10f, saturation = 1.12f)
        return try {
            val pixels = IntArray(output.width * output.height)
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            applyUnsharpSharpen(pixels, output.width, output.height, strength = 0.28f)
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            output
        } catch (error: Throwable) {
            if (!output.isRecycled) output.recycle()
            throw error
        }
    }

    /**
     * Invert (Negatif)
     */
    private fun applyInvert(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cm = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun applyColorMatrix(
        source: Bitmap,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        warmth: Float = 0f
    ): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scale = contrast
        val translate = (-0.5f * scale + 0.5f + (brightness - 1.0f)) * 255f

        val warmthShift = warmth.coerceIn(-1f, 1f) * 22f
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate + warmthShift,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate - warmthShift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        if (saturation != 1.0f) {
            val sat = ColorMatrix()
            sat.setSaturation(saturation)
            cm.postConcat(sat)
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)

        return output
    }

    private fun applyProfessionalAdjustments(source: Bitmap, settings: FilterSettings): Bitmap {
        val output = applyColorMatrix(
            source = source,
            brightness = settings.brightness,
            contrast = settings.contrast,
            saturation = settings.saturation,
            warmth = settings.warmth
        )
        if (settings.sharpness > 0.001f) {
            val pixels = IntArray(output.width * output.height)
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            applyUnsharpSharpen(
                pixels,
                output.width,
                output.height,
                strength = settings.sharpness.coerceIn(0f, 1.5f) * 0.65f
            )
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
        }
        return output
    }

    /**
     * Builds a 2D grid estimating the ambient background paper illumination across the document.
     * Samples local highlights to ignore dark text/ink and capture lighting variations.
     */
    private fun buildBackgroundGrid(pixels: IntArray, width: Int, height: Int): Array<FloatArray> {
        val cols = (width / 32).coerceIn(12, 48)
        val rows = (height / 32).coerceIn(12, 48)

        val rawGrid = Array(rows) { FloatArray(cols) }
        val blockW = width / cols
        val blockH = height / rows

        for (r in 0 until rows) {
            val startY = r * blockH
            val endY = if (r == rows - 1) height else (r + 1) * blockH

            for (c in 0 until cols) {
                val startX = c * blockW
                val endX = if (c == cols - 1) width else (c + 1) * blockW

                // Sample top 15% brightest pixels in this block to find paper background level
                var maxLum1 = 0
                var maxLum2 = 0
                var maxLum3 = 0
                var maxLum4 = 0

                val stepY = max(1, (endY - startY) / 8)
                val stepX = max(1, (endX - startX) / 8)

                for (y in startY until endY step stepY) {
                    val rowOffset = y * width
                    for (x in startX until endX step stepX) {
                        val p = pixels[rowOffset + x]
                        val pr = (p shr 16) and 0xFF
                        val pg = (p shr 8) and 0xFF
                        val pb = p and 0xFF
                        val lum = (299 * pr + 587 * pg + 114 * pb + 500) / 1000

                        if (lum > maxLum1) {
                            maxLum4 = maxLum3
                            maxLum3 = maxLum2
                            maxLum2 = maxLum1
                            maxLum1 = lum
                        } else if (lum > maxLum2) {
                            maxLum4 = maxLum3
                            maxLum3 = maxLum2
                            maxLum2 = lum
                        } else if (lum > maxLum3) {
                            maxLum4 = maxLum3
                            maxLum3 = lum
                        } else if (lum > maxLum4) {
                            maxLum4 = lum
                        }
                    }
                }

                val avgPaperLum = if (maxLum1 > 0) {
                    ((maxLum1 + maxLum2 + maxLum3 + maxLum4) / 4f).coerceIn(40f, 255f)
                } else 200f

                rawGrid[r][c] = avgPaperLum
            }
        }

        // Apply 2 passes of 3x3 box blur for silky smooth background transition
        return blurGrid(blurGrid(rawGrid))
    }

    private fun blurGrid(grid: Array<FloatArray>): Array<FloatArray> {
        val rows = grid.size
        val cols = grid[0].size
        val blurred = Array(rows) { FloatArray(cols) }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0f
                var count = 0
                for (dr in -1..1) {
                    val nr = r + dr
                    if (nr in 0 until rows) {
                        for (dc in -1..1) {
                            val nc = c + dc
                            if (nc in 0 until cols) {
                                sum += grid[nr][nc]
                                count++
                            }
                        }
                    }
                }
                blurred[r][c] = sum / count
            }
        }
        return blurred
    }

    /**
     * Fast 3x3 unsharp text sharpener kernel applied selectively to non-white pixels
     */
    /**
     * [Audit — Tahap Refactor] Fix "hasil Mempertajam masih terasa blur dibanding CamScanner".
     * Sebelumnya fungsi ini SELALU memakai radius tetangga = 1 piksel (hanya kiri/kanan/atas/
     * bawah langsung) sebagai estimasi "versi blur" dari gambar untuk unsharp mask. Radius
     * 1px terlalu sempit untuk resolusi scan dokumen (biasanya ~2000px lebar) — bedanya
     * dengan piksel tetangga langsung sangat kecil, jadi efek "pertajam" yang dihasilkan nyaris
     * tidak terlihat, hasil akhir tetap terasa lembek/blur meski strength dinaikkan.
     * Parameter `radius` (px, di skala gambar sebenarnya, BUKAN dinormalisasi) sekarang bisa
     * diatur oleh pemanggil. Filter "Mempertajam" (applySuperSharpen) memanggil fungsi ini DUA
     * kali dengan radius berbeda (1px lalu 3px) — meniru unsharp mask multi-skala: pass
     * pertama menajamkan detail halus, pass kedua menajamkan kontras goresan huruf yang lebih
     * lebar, mendekati hasil "Ajaib Pro" CamScanner. Pemanggil lain (AUTO/adjustments slider)
     * sengaja TETAP di radius=1 (default) agar tampilannya tidak berubah dari sebelumnya.
     */
    private fun applyUnsharpSharpen(pixels: IntArray, width: Int, height: Int, strength: Float, radius: Int = 1) {
        val temp = pixels.clone()
        val w = width
        val h = height
        val r = radius.coerceAtLeast(1)

        for (y in r until h - r) {
            val rowOffset = y * w
            val rowAbove = (y - r) * w
            val rowBelow = (y + r) * w

            for (x in r until w - r) {
                val center = temp[rowOffset + x]
                // If pixel is already pure white, keep it pure white (avoid noise on clean paper)
                if (center == 0xFFFFFFFF.toInt()) continue

                val cr = (center shr 16) and 0xFF
                val cg = (center shr 8) and 0xFF
                val cb = center and 0xFF

                val left = temp[rowOffset + x - r]
                val right = temp[rowOffset + x + r]
                val up = temp[rowAbove + x]
                val down = temp[rowBelow + x]

                val surroundR = (((left shr 16) and 0xFF) + ((right shr 16) and 0xFF) + ((up shr 16) and 0xFF) + ((down shr 16) and 0xFF)) / 4
                val surroundG = (((left shr 8) and 0xFF) + ((right shr 8) and 0xFF) + ((up shr 8) and 0xFF) + ((down shr 8) and 0xFF)) / 4
                val surroundB = ((left and 0xFF) + (right and 0xFF) + (up and 0xFF) + (down and 0xFF)) / 4

                val sharpR = (cr + (cr - surroundR) * strength).toInt().coerceIn(0, 255)
                val sharpG = (cg + (cg - surroundG) * strength).toInt().coerceIn(0, 255)
                val sharpB = (cb + (cb - surroundB) * strength).toInt().coerceIn(0, 255)

                pixels[rowOffset + x] = (0xFF shl 24) or (sharpR shl 16) or (sharpG shl 8) or sharpB
            }
        }
    }

    /**
     * Perspective crop / Warp quadrilateral to rectangular bitmap using normalized CropGeometry.
     */
    fun cropPerspective(
        source: Bitmap,
        cropGeometry: CropGeometry
    ): Bitmap {
        val safeGeometry = if (AutoCropDetector.isValidGeometry(cropGeometry, minimumArea = 0.005f, minimumEdge = 0.02f)) {
            cropGeometry
        } else {
            AutoCropDetector.fullGeometry()
        }
        val w = source.width.toFloat()
        val h = source.height.toFloat()

        val tl = PointF(safeGeometry.topLeft.x * w, safeGeometry.topLeft.y * h)
        val tr = PointF(safeGeometry.topRight.x * w, safeGeometry.topRight.y * h)
        val br = PointF(safeGeometry.bottomRight.x * w, safeGeometry.bottomRight.y * h)
        val bl = PointF(safeGeometry.bottomLeft.x * w, safeGeometry.bottomLeft.y * h)

        return cropPerspective(source, tl, tr, br, bl)
    }

    /**
     * Perspective crop / Warp quadrilateral to rectangular bitmap with exact pixel PointF coordinates.
     */
    fun cropPerspective(
        source: Bitmap,
        topLeft: PointF,
        topRight: PointF,
        bottomRight: PointF,
        bottomLeft: PointF
    ): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            throw IllegalArgumentException("Bitmap sumber crop tidak valid")
        }

        val normalizedGeometry = CropGeometry(
            topLeft = Offset(topLeft.x / source.width, topLeft.y / source.height),
            topRight = Offset(topRight.x / source.width, topRight.y / source.height),
            bottomRight = Offset(bottomRight.x / source.width, bottomRight.y / source.height),
            bottomLeft = Offset(bottomLeft.x / source.width, bottomLeft.y / source.height)
        )
        if (!AutoCropDetector.isValidGeometry(normalizedGeometry, minimumArea = 0.005f, minimumEdge = 0.02f)) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }

        val maximumDimension = min(8192, max(source.width, source.height) * 2).coerceAtLeast(1)
        val targetWidth = max(
            hypot(topRight.x - topLeft.x, topRight.y - topLeft.y),
            hypot(bottomRight.x - bottomLeft.x, bottomRight.y - bottomLeft.y)
        ).roundToInt().coerceIn(1, maximumDimension)

        val targetHeight = max(
            hypot(bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y),
            hypot(bottomRight.x - topRight.x, bottomRight.y - topRight.y)
        ).roundToInt().coerceIn(1, maximumDimension)

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val srcPoints = floatArrayOf(
            topLeft.x, topLeft.y,
            topRight.x, topRight.y,
            bottomRight.x, bottomRight.y,
            bottomLeft.x, bottomLeft.y
        )

        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) {
            result.recycle()
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        return result
    }

    /**
     * [Audit] Root-cause fix untuk hasil filter "Mempertajam"/"Magic Color"/"Otomatis" yang
     * buruk pada foto ber-bayangan hangat (lighting indoor/kuning khas kamera HP tanpa
     * flash — lihat perbandingan screenshot pengguna).
     *
     * Cabang "Color Element" di atas dimaksudkan untuk MEMPERTAHANKAN warna asli stempel
     * biru/tanda tangan/materai merah (chroma tinggi = sengaja tidak diputihkan/dinormalisasi,
     * malah saturasinya DINAIKKAN 1.4x). Masalahnya threshold `chroma >= 20/22` juga kena oleh
     * bayangan kuning/hangat biasa — bayangan itu punya R dan G tinggi tapi B rendah, jadi
     * chroma-nya gampang tembus 20-40 padahal itu SAMA SEKALI bukan stempel/tinta berwarna.
     * Akibatnya: alih-alih diratakan ke putih seperti bayangan lain, bayangan kuning itu malah
     * ikut dinaikkan saturasinya -> jadi noda kuning pekat yang justru lebih buruk dari aslinya.
     *
     * Fungsi ini mendeteksi pola "warm cast" itu secara spesifik: R DAN G sama-sama jauh di
     * atas B (ciri khas bayangan kuning/oranye). Stempel MERAH sungguhan tidak match kondisi
     * ini karena G-nya tetap rendah (hanya R yang tinggi, bukan R dan G berdua) — jadi tetap
     * lolos sebagai elemen warna yang harus dipertahankan. Stempel/tinta BIRU sudah punya jalur
     * deteksi terpisah (`b > r + ... && b > g + ...`) yang tidak disentuh fungsi ini sama sekali.
     */
    private fun isWarmShadowCast(r: Int, g: Int, b: Int): Boolean = r > b + 25 && g > b + 15

    private fun hypot(dx: Float, dy: Float): Float {
        return Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }
}
