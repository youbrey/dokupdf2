package com.example.core.crop

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.example.core.model.CropGeometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AutoCropResult(
    val geometry: CropGeometry,
    val confidence: Float,
    val usedFallback: Boolean,
    /** Machine-readable reason when [usedFallback] is true; null for a successful detection. */
    val failureReason: String? = null
)

/**
 * On-device document boundary detector that does not require OpenCV.
 *
 * The previous implementation stretched every image to a 300x300 square and sampled only
 * four diagonal rays. That distorted portrait documents and frequently locked onto text or
 * table lines instead of the page boundary. This detector preserves aspect ratio, builds
 * directional Sobel gradients, searches four continuous boundary lines, intersects those
 * lines, and rejects non-convex or implausibly small quadrilaterals.
 */
object AutoCropDetector {

    private const val ANALYSIS_LONG_EDGE = 420
    private const val MIN_DOCUMENT_AREA = 0.18f
    private const val MIN_EDGE_FRACTION = 0.18f

    private data class LineModel(
        val slope: Float,
        val intercept: Float,
        val score: Float,
        val coverage: Float
    )

    fun detectDocumentCorners(bitmap: Bitmap): CropGeometry = detect(bitmap).geometry

    fun detect(bitmap: Bitmap): AutoCropResult {
        if (bitmap.isRecycled || bitmap.width < 16 || bitmap.height < 16) {
            return fallbackResult("invalid_bitmap")
        }

        val longestEdge = max(bitmap.width, bitmap.height).toFloat()
        val analysisScale = min(1f, ANALYSIS_LONG_EDGE / longestEdge)
        val analysisWidth = max(16, (bitmap.width * analysisScale).roundToInt())
        val analysisHeight = max(16, (bitmap.height * analysisScale).roundToInt())

        val analysisBitmap = try {
            if (analysisWidth == bitmap.width && analysisHeight == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, analysisWidth, analysisHeight, true)
        } catch (_: OutOfMemoryError) {
            return fallbackResult("analysis_bitmap_out_of_memory")
        } catch (error: Exception) {
            return fallbackResult("analysis_bitmap_${error.javaClass.simpleName}")
        }

        return try {
            val pixels = IntArray(analysisWidth * analysisHeight)
            analysisBitmap.getPixels(pixels, 0, analysisWidth, 0, 0, analysisWidth, analysisHeight)
            val luma = blurLuminance(pixels, analysisWidth, analysisHeight)
            val gradientX = FloatArray(luma.size)
            val gradientY = FloatArray(luma.size)
            val magnitudes = FloatArray(luma.size)

            computeSobel(luma, analysisWidth, analysisHeight, gradientX, gradientY, magnitudes)
            val edgeThreshold = percentileThreshold(magnitudes, 0.78f).coerceAtLeast(14f)

            val left = findVerticalBoundary(
                luma, gradientX, gradientY, analysisWidth, analysisHeight,
                minBaseFraction = 0.01f, maxBaseFraction = 0.46f,
                preferOuter = true, threshold = edgeThreshold
            )
            val right = findVerticalBoundary(
                luma, gradientX, gradientY, analysisWidth, analysisHeight,
                minBaseFraction = 0.54f, maxBaseFraction = 0.99f,
                preferOuter = false, threshold = edgeThreshold
            )
            val top = findHorizontalBoundary(
                luma, gradientX, gradientY, analysisWidth, analysisHeight,
                minBaseFraction = 0.01f, maxBaseFraction = 0.46f,
                preferOuter = true, threshold = edgeThreshold
            )
            val bottom = findHorizontalBoundary(
                luma, gradientX, gradientY, analysisWidth, analysisHeight,
                minBaseFraction = 0.54f, maxBaseFraction = 0.99f,
                preferOuter = false, threshold = edgeThreshold
            )

            val leftLine = left ?: return fallbackResult("left_boundary_not_found")
            val rightLine = right ?: return fallbackResult("right_boundary_not_found")
            val topLine = top ?: return fallbackResult("top_boundary_not_found")
            val bottomLine = bottom ?: return fallbackResult("bottom_boundary_not_found")

            val tl = intersect(leftLine, topLine, analysisWidth, analysisHeight)
            val tr = intersect(rightLine, topLine, analysisWidth, analysisHeight)
            val br = intersect(rightLine, bottomLine, analysisWidth, analysisHeight)
            val bl = intersect(leftLine, bottomLine, analysisWidth, analysisHeight)
            val geometry = CropGeometry(tl, tr, br, bl)

            val minimumCoverage = min(
                min(leftLine.coverage, rightLine.coverage),
                min(topLine.coverage, bottomLine.coverage)
            )
            if (minimumCoverage < 0.055f) {
                fallbackResult("insufficient_edge_coverage")
            } else if (!isValidGeometry(geometry)) {
                fallbackResult("invalid_detected_geometry")
            } else {
                val area = polygonArea(geometry)
                val confidence = (minimumCoverage * 1.8f + area * 0.45f).coerceIn(0f, 1f)
                AutoCropResult(geometry, confidence, usedFallback = false)
            }
        } catch (_: OutOfMemoryError) {
            fallbackResult("analysis_out_of_memory")
        } catch (error: Exception) {
            fallbackResult("analysis_${error.javaClass.simpleName}")
        } finally {
            if (analysisBitmap !== bitmap && !analysisBitmap.isRecycled) analysisBitmap.recycle()
        }
    }

    private fun blurLuminance(pixels: IntArray, width: Int, height: Int): FloatArray {
        val source = FloatArray(pixels.size)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            source[index] = (299 * red + 587 * green + 114 * blue) / 1000f
        }

        val blurred = source.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                blurred[index] = (
                    source[index - width - 1] + source[index - width] * 2f + source[index - width + 1] +
                        source[index - 1] * 2f + source[index] * 4f + source[index + 1] * 2f +
                        source[index + width - 1] + source[index + width] * 2f + source[index + width + 1]
                    ) / 16f
            }
        }
        return blurred
    }

    private fun computeSobel(
        luminance: FloatArray,
        width: Int,
        height: Int,
        gradientX: FloatArray,
        gradientY: FloatArray,
        magnitudes: FloatArray
    ) {
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val gx =
                    -luminance[index - width - 1] + luminance[index - width + 1] -
                        2f * luminance[index - 1] + 2f * luminance[index + 1] -
                        luminance[index + width - 1] + luminance[index + width + 1]
                val gy =
                    -luminance[index - width - 1] - 2f * luminance[index - width] - luminance[index - width + 1] +
                        luminance[index + width - 1] + 2f * luminance[index + width] + luminance[index + width + 1]
                gradientX[index] = gx
                gradientY[index] = gy
                magnitudes[index] = hypot(gx.toDouble(), gy.toDouble()).toFloat()
            }
        }
    }

    private fun percentileThreshold(values: FloatArray, percentile: Float): Float {
        val histogram = IntArray(512)
        var count = 0
        for (index in values.indices step 2) {
            val value = values[index]
            if (value <= 0f || !value.isFinite()) continue
            histogram[(value / 3f).toInt().coerceIn(0, histogram.lastIndex)]++
            count++
        }
        if (count == 0) return 14f

        val target = (count * percentile.coerceIn(0f, 1f)).roundToInt()
        var accumulated = 0
        for (index in histogram.indices) {
            accumulated += histogram[index]
            if (accumulated >= target) return index * 3f
        }
        return histogram.lastIndex * 3f
    }

    private fun findVerticalBoundary(
        luma: FloatArray,
        gradientX: FloatArray,
        gradientY: FloatArray,
        width: Int,
        height: Int,
        minBaseFraction: Float,
        maxBaseFraction: Float,
        preferOuter: Boolean,
        threshold: Float
    ): LineModel? {
        val minBase = (width * minBaseFraction).roundToInt()
        val maxBase = (width * maxBaseFraction).roundToInt()
        val middleY = (height - 1) / 2f
        // [Audit] Jarak sampel sisi dalam/luar untuk cek asimetri kecerahan, lihat dokumentasi
        // di findHorizontalBoundary (logika identik, hanya sumbunya ditukar).
        val sideOffset = (width * 0.045f).roundToInt().coerceIn(3, 24)
        val innerSign = if (preferOuter) 1 else -1
        var best: LineModel? = null

        var slope = -0.70f
        while (slope <= 0.7001f) {
            val normalLength = sqrt(1f + slope * slope)
            for (base in minBase..maxBase step 2) {
                var sum = 0f
                var strong = 0
                var valid = 0
                var innerLumaSum = 0f
                var outerLumaSum = 0f
                var sideSamples = 0
                for (y in 2 until height - 2 step 2) {
                    val x = (base + slope * (y - middleY)).roundToInt()
                    if (x !in 2 until width - 2) continue
                    val index = y * width + x
                    val response = abs(gradientX[index] - slope * gradientY[index]) / normalLength
                    sum += min(response, threshold * 4f)
                    if (response >= threshold) strong++
                    valid++

                    val innerX = x + innerSign * sideOffset
                    val outerX = x - innerSign * sideOffset
                    if (innerX in 0 until width && outerX in 0 until width) {
                        innerLumaSum += luma[y * width + innerX]
                        outerLumaSum += luma[y * width + outerX]
                        sideSamples++
                    }
                }
                if (valid == 0) continue
                val coverage = strong.toFloat() / valid
                val position = base.toFloat() / width
                val positionPrior = if (preferOuter) 1.12f - position * 0.28f else 0.84f + position * 0.28f
                val brightnessFactor = brightnessBiasFactor(innerLumaSum, outerLumaSum, sideSamples)
                val score = (sum / valid) * (0.55f + coverage * 1.9f) * positionPrior * brightnessFactor
                if (best == null || score > best.score) best = LineModel(slope, base.toFloat(), score, coverage)
            }
            slope += 0.05f
        }
        return best
    }

    private fun findHorizontalBoundary(
        luma: FloatArray,
        gradientX: FloatArray,
        gradientY: FloatArray,
        width: Int,
        height: Int,
        minBaseFraction: Float,
        maxBaseFraction: Float,
        preferOuter: Boolean,
        threshold: Float
    ): LineModel? {
        val minBase = (height * minBaseFraction).roundToInt()
        val maxBase = (height * maxBaseFraction).roundToInt()
        val middleX = (width - 1) / 2f
        val sideOffset = (height * 0.045f).roundToInt().coerceIn(3, 24)
        val innerSign = if (preferOuter) 1 else -1
        var best: LineModel? = null

        var slope = -0.70f
        while (slope <= 0.7001f) {
            val normalLength = sqrt(1f + slope * slope)
            for (base in minBase..maxBase step 2) {
                var sum = 0f
                var strong = 0
                var valid = 0
                var innerLumaSum = 0f
                var outerLumaSum = 0f
                var sideSamples = 0
                for (x in 2 until width - 2 step 2) {
                    val y = (base + slope * (x - middleX)).roundToInt()
                    if (y !in 2 until height - 2) continue
                    val index = y * width + x
                    val response = abs(gradientY[index] - slope * gradientX[index]) / normalLength
                    sum += min(response, threshold * 4f)
                    if (response >= threshold) strong++
                    valid++

                    val innerY = y + innerSign * sideOffset
                    val outerY = y - innerSign * sideOffset
                    if (innerY in 0 until height && outerY in 0 until height) {
                        innerLumaSum += luma[innerY * width + x]
                        outerLumaSum += luma[outerY * width + x]
                        sideSamples++
                    }
                }
                if (valid == 0) continue
                val coverage = strong.toFloat() / valid
                val position = base.toFloat() / height
                val positionPrior = if (preferOuter) 1.12f - position * 0.28f else 0.84f + position * 0.28f
                val brightnessFactor = brightnessBiasFactor(innerLumaSum, outerLumaSum, sideSamples)
                val score = (sum / valid) * (0.55f + coverage * 1.9f) * positionPrior * brightnessFactor
                if (best == null || score > best.score) best = LineModel(slope, base.toFloat(), score, coverage)
            }
            slope += 0.05f
        }
        return best
    }

    /**
     * [Audit — Tahap Refactor] Root cause pemotongan halaman yang tidak akurat/miring
     * (dilaporkan pengguna lewat rekaman video, dibandingkan dengan CamScanner): 4 sisi
     * dicari SEPENUHNYA independen satu sama lain, dan skornya sebelumnya HANYA berbasis
     * kekuatan gradien + cakupan garis. Di dokumen dengan garis tabel/formulir internal yang
     * panjang dan lurus (kontras tinta-hitam-di-atas-kertas-putih bisa SAMA KUAT atau lebih
     * kuat dari kontras tepi-kertas-vs-latar, apalagi kalau latar belakang foto gelap/kurang
     * cahaya seperti pada video pengujian), garis tabel internal itu bisa MENGALAHKAN tepi
     * kertas asli dalam skor — persis yang terlihat: sudut kanan-bawah hasil deteksi berhenti
     * di garis pembatas tabel "ISI DISPOSISI", bukan di tepi kertas sesungguhnya.
     *
     * Pembeda kunci yang selama ini tidak dipakai: tepi kertas sungguhan punya lompatan
     * kecerahan SATU ARAH (sisi dalam = kertas putih terang, sisi luar = latar belakang
     * biasanya lebih gelap/bertekstur). Garis tabel internal TIDAK punya pola ini — kedua
     * sisinya sama-sama kertas putih (kecerahan hampir sama, selisih mendekati nol).
     *
     * Fungsi ini memberi bobot skor berdasarkan pola itu: dukungan penuh (x1.0) kalau sisi
     * dalam jelas lebih terang dari sisi luar, diturunkan bertahap sampai minimum x0.35 kalau
     * polanya terbalik/tidak ada — sengaja tidak menolak total (bukan hard filter) supaya
     * dokumen dengan latar belakang terang (mis. discan di atas meja putih) masih bisa
     * terdeteksi lewat sinyal gradien seperti sebelumnya, hanya kalah prioritas dibanding
     * kandidat lain yang polanya lebih meyakinkan.
     */
    private fun brightnessBiasFactor(innerLumaSum: Float, outerLumaSum: Float, sideSamples: Int): Float {
        if (sideSamples == 0) return 0.5f
        val diff = (innerLumaSum - outerLumaSum) / sideSamples
        return ((diff.coerceIn(-40f, 60f) + 40f) / 100f) * 0.65f + 0.35f
    }



    /** Vertical line: x = a*y+b. Horizontal line: y = c*x+d. */
    private fun intersect(vertical: LineModel, horizontal: LineModel, width: Int, height: Int): Offset {
        val verticalIntercept = vertical.intercept - vertical.slope * ((height - 1) / 2f)
        val horizontalIntercept = horizontal.intercept - horizontal.slope * ((width - 1) / 2f)
        val denominator = 1f - vertical.slope * horizontal.slope
        if (abs(denominator) < 0.05f) return Offset(0.5f, 0.5f)

        val x = (vertical.slope * horizontalIntercept + verticalIntercept) / denominator
        val y = horizontal.slope * x + horizontalIntercept
        return Offset(
            (x / width).coerceIn(0.005f, 0.995f),
            (y / height).coerceIn(0.005f, 0.995f)
        )
    }

    fun isValidGeometry(
        geometry: CropGeometry,
        minimumArea: Float = MIN_DOCUMENT_AREA,
        minimumEdge: Float = MIN_EDGE_FRACTION
    ): Boolean {
        val points = listOf(geometry.topLeft, geometry.topRight, geometry.bottomRight, geometry.bottomLeft)
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        if (polygonArea(geometry) < minimumArea.coerceAtLeast(0f)) return false

        val edges = listOf(
            distance(points[0], points[1]), distance(points[1], points[2]),
            distance(points[2], points[3]), distance(points[3], points[0])
        )
        if (edges.any { it < minimumEdge.coerceAtLeast(0f) }) return false

        var sign = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val c = points[(index + 2) % points.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 0.002f) return false
            if (sign == 0f) sign = cross else if (sign * cross < 0f) return false
        }
        return true
    }

    private fun polygonArea(geometry: CropGeometry): Float {
        val points = listOf(geometry.topLeft, geometry.topRight, geometry.bottomRight, geometry.bottomLeft)
        var sum = 0f
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            sum += current.x * next.y - next.x * current.y
        }
        return abs(sum) * 0.5f
    }

    private fun distance(first: Offset, second: Offset): Float =
        hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble()).toFloat()

    private fun fallbackResult(reason: String) = AutoCropResult(
        geometry = defaultGeometry(),
        confidence = 0f,
        usedFallback = true,
        failureReason = reason
    )

    fun defaultGeometry(): CropGeometry = CropGeometry(
        topLeft = Offset(0.04f, 0.04f),
        topRight = Offset(0.96f, 0.04f),
        bottomRight = Offset(0.96f, 0.96f),
        bottomLeft = Offset(0.04f, 0.96f)
    )

    fun fullGeometry(): CropGeometry = CropGeometry(
        topLeft = Offset.Zero,
        topRight = Offset(1f, 0f),
        bottomRight = Offset(1f, 1f),
        bottomLeft = Offset(0f, 1f)
    )
}
