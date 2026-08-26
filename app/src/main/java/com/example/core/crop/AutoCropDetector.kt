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
 * [Audit — Refactor v2] Root cause of crooked/wavy crops vs. CamScanner (reported by user via
 * side-by-side video comparison): the previous single-tier detector searched each of the 4 sides
 * COMPLETELY INDEPENDENTLY as a near-straight gradient line. On a real form with internal ruled
 * table lines (ink-on-white contrast can rival or exceed the true paper-vs-background contrast,
 * especially with a dim/textured background like a desk), a side's independent search regularly
 * locked onto an internal table border instead of the true paper edge -- confirmed directly from
 * the user's screen recording: the detector fell back to the plain centered default box (no real
 * quadrilateral was found at all) on a photo where the paper was visibly rotated, so the final
 * crop kept the tilt entirely uncorrected.
 *
 * This rewrite adds a PRIMARY detector that looks at the document as one connected shape instead
 * of four unrelated lines, which is the same fundamental strategy CamScanner-class scanners use:
 *
 *   1. Otsu-threshold the (blurred) luminance into two populations, and use whichever population
 *      contains the frame's CENTER pixel as the "document" class -- this works whether the paper
 *      is brighter or darker than its background, and does not care whether the document itself
 *      contains printed lines (those become small interior holes, not boundary evidence).
 *   2. Morphologically close the resulting mask (dilate then erode) to bridge thin dark ruled
 *      lines/text inside the paper without growing the true outer silhouette.
 *   3. Flood-fill to the largest connected component -- this is the document's silhouette as one
 *      global shape, immune to any single internal line "winning" a local contest.
 *   4. Take that shape's boundary pixels, compute their convex hull, and extract the 4 extreme
 *      corners (min/max of x+y and x-y). This finds the true 4-point quadrilateral -- including
 *      full keystone/perspective skew, not just axis-aligned rotation -- at any rotation angle.
 *
 * The previous four-independent-lines search is KEPT as a secondary fallback (unchanged, see
 * [detectByEdgeLines]) for the rare case where the primary region method can't find a confident
 * single connected shape (e.g. the document and background have near-identical brightness).
 */
object AutoCropDetector {

    private const val ANALYSIS_LONG_EDGE = 640
    private const val MIN_DOCUMENT_AREA = 0.18f
    private const val MIN_EDGE_FRACTION = 0.18f

    /** Minimum/maximum plausible fraction of the frame the document silhouette may occupy. */
    private const val MIN_REGION_COVERAGE = 0.10f
    private const val MAX_REGION_COVERAGE = 0.97f

    /** Radius (in analysis-resolution pixels) used to close small gaps/holes from ruled lines. */
    private const val MORPH_CLOSE_RADIUS = 3

    private data class LineModel(
        val slope: Float,
        val intercept: Float,
        val score: Float,
        val coverage: Float
    )

    private data class IntPoint(val x: Int, val y: Int)

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

            detectByRegion(luma, magnitudes, analysisWidth, analysisHeight)
                ?: detectByEdgeLines(luma, gradientX, gradientY, magnitudes, analysisWidth, analysisHeight)
        } catch (_: OutOfMemoryError) {
            fallbackResult("analysis_out_of_memory")
        } catch (error: Exception) {
            fallbackResult("analysis_${error.javaClass.simpleName}")
        } finally {
            if (analysisBitmap !== bitmap && !analysisBitmap.isRecycled) analysisBitmap.recycle()
        }
    }

    // ------------------------------------------------------------------------------------
    // PRIMARY: whole-shape region segmentation + convex hull corner extraction.
    // ------------------------------------------------------------------------------------

    private fun detectByRegion(luma: FloatArray, magnitudes: FloatArray, width: Int, height: Int): AutoCropResult? {
        if (width < 16 || height < 16) return null

        val threshold = otsuThreshold(luma)
        val centerIndex = (height / 2) * width + (width / 2)
        val centerIsDocumentClass = luma[centerIndex] > threshold

        var mask = BooleanArray(luma.size) { index -> (luma[index] > threshold) == centerIsDocumentClass }
        mask = dilateHorizontal(mask, width, height, MORPH_CLOSE_RADIUS)
        mask = dilateVertical(mask, width, height, MORPH_CLOSE_RADIUS)
        mask = erodeHorizontal(mask, width, height, MORPH_CLOSE_RADIUS)
        mask = erodeVertical(mask, width, height, MORPH_CLOSE_RADIUS)

        val totalPixels = width * height
        val (boundary, componentSize) = largestComponentBoundary(mask, width, height) ?: return null

        val coverageFraction = componentSize.toFloat() / totalPixels
        if (coverageFraction < MIN_REGION_COVERAGE || coverageFraction > MAX_REGION_COVERAGE) return null
        if (boundary.size < 4) return null

        val hull = convexHull(boundary)
        if (hull.size < 4) return null

        val tl = hull.minByOrNull { it.x + it.y } ?: return null
        val br = hull.maxByOrNull { it.x + it.y } ?: return null
        val tr = hull.maxByOrNull { it.x - it.y } ?: return null
        val bl = hull.minByOrNull { it.x - it.y } ?: return null

        // [Audit — Refactor v2] Region segmentation alone can be fooled when a bright, unrelated
        // background object (e.g. a light reflection on the desk) touches or bridges into the
        // paper's mask, pulling one hull corner far outside the true document -- the mask is
        // technically one connected shape, so area/fill-ratio checks alone don't catch it (the
        // spurious area is still "real" mask, just the wrong object). Cross-check every candidate
        // edge against the independent Sobel gradient map: a genuine paper edge is a sustained,
        // visible intensity transition along its *entire* length, while a corner dragged toward
        // an unrelated blob produces an edge that only partially follows a real transition.
        val edgeThreshold = percentileThreshold(magnitudes, 0.70f).coerceAtLeast(10f)
        val edgesSupported =
            edgeIsSupported(tl, tr, magnitudes, width, height, edgeThreshold) &&
                edgeIsSupported(tr, br, magnitudes, width, height, edgeThreshold) &&
                edgeIsSupported(br, bl, magnitudes, width, height, edgeThreshold) &&
                edgeIsSupported(bl, tl, magnitudes, width, height, edgeThreshold)
        if (!edgesSupported) return null

        val geometry = CropGeometry(
            topLeft = Offset(
                (tl.x.toFloat() / width).coerceIn(0.002f, 0.998f),
                (tl.y.toFloat() / height).coerceIn(0.002f, 0.998f)
            ),
            topRight = Offset(
                (tr.x.toFloat() / width).coerceIn(0.002f, 0.998f),
                (tr.y.toFloat() / height).coerceIn(0.002f, 0.998f)
            ),
            bottomRight = Offset(
                (br.x.toFloat() / width).coerceIn(0.002f, 0.998f),
                (br.y.toFloat() / height).coerceIn(0.002f, 0.998f)
            ),
            bottomLeft = Offset(
                (bl.x.toFloat() / width).coerceIn(0.002f, 0.998f),
                (bl.y.toFloat() / height).coerceIn(0.002f, 0.998f)
            )
        )
        if (!isValidGeometry(geometry)) return null

        val quadAreaFraction = polygonArea(geometry)
        val quadAreaPixels = quadAreaFraction * totalPixels
        val fillRatio = if (quadAreaPixels > 1f) componentSize / quadAreaPixels else 0f
        val tightness = (1f - abs(1f - fillRatio)).coerceIn(0f, 1f)
        val confidence = (tightness * 0.65f + coverageFraction.coerceAtMost(0.6f) * 0.55f).coerceIn(0f, 1f)

        return AutoCropResult(geometry, confidence, usedFallback = false)
    }

    private fun otsuThreshold(luma: FloatArray): Int {
        val histogram = IntArray(256)
        for (value in luma) {
            histogram[value.roundToInt().coerceIn(0, 255)]++
        }
        val total = luma.size
        if (total == 0) return 127

        var sumAll = 0.0
        for (level in 0 until 256) sumAll += level.toDouble() * histogram[level]

        var sumBackground = 0.0
        var weightBackground = 0
        var bestVariance = -1.0
        var bestThreshold = 127
        for (level in 0 until 256) {
            weightBackground += histogram[level]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += level.toDouble() * histogram[level]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val diff = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground.toDouble() * diff * diff
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = level
            }
        }
        return bestThreshold
    }

    private fun dilateHorizontal(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        val prefix = IntArray(width + 1)
        for (y in 0 until height) {
            val rowStart = y * width
            prefix[0] = 0
            for (x in 0 until width) prefix[x + 1] = prefix[x] + if (mask[rowStart + x]) 1 else 0
            for (x in 0 until width) {
                val lo = max(0, x - radius)
                val hi = min(width - 1, x + radius)
                out[rowStart + x] = (prefix[hi + 1] - prefix[lo]) > 0
            }
        }
        return out
    }

    private fun dilateVertical(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        val prefix = IntArray(height + 1)
        for (x in 0 until width) {
            prefix[0] = 0
            for (y in 0 until height) prefix[y + 1] = prefix[y] + if (mask[y * width + x]) 1 else 0
            for (y in 0 until height) {
                val lo = max(0, y - radius)
                val hi = min(height - 1, y + radius)
                out[y * width + x] = (prefix[hi + 1] - prefix[lo]) > 0
            }
        }
        return out
    }

    private fun erodeHorizontal(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        val prefix = IntArray(width + 1)
        for (y in 0 until height) {
            val rowStart = y * width
            prefix[0] = 0
            for (x in 0 until width) prefix[x + 1] = prefix[x] + if (mask[rowStart + x]) 1 else 0
            for (x in 0 until width) {
                val lo = max(0, x - radius)
                val hi = min(width - 1, x + radius)
                val windowSize = hi - lo + 1
                out[rowStart + x] = (prefix[hi + 1] - prefix[lo]) == windowSize
            }
        }
        return out
    }

    private fun erodeVertical(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        val prefix = IntArray(height + 1)
        for (x in 0 until width) {
            prefix[0] = 0
            for (y in 0 until height) prefix[y + 1] = prefix[y] + if (mask[y * width + x]) 1 else 0
            for (y in 0 until height) {
                val lo = max(0, y - radius)
                val hi = min(height - 1, y + radius)
                val windowSize = hi - lo + 1
                out[y * width + x] = (prefix[hi + 1] - prefix[lo]) == windowSize
            }
        }
        return out
    }

    /**
     * Flood-fills the mask to find its largest 4-connected component, then returns that
     * component's boundary pixels (any member pixel touching a non-member pixel or the frame
     * edge) together with the component's total pixel count.
     */
    private fun largestComponentBoundary(
        mask: BooleanArray,
        width: Int,
        height: Int
    ): Pair<List<IntPoint>, Int>? {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var bestMembers: IntArray? = null
        var bestSize = 0

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            val members = ArrayList<Int>()
            while (head < tail) {
                val index = queue[head++]
                members.add(index)
                val x = index % width
                val y = index / width
                if (x > 0) {
                    val neighbor = index - 1
                    if (mask[neighbor] && !visited[neighbor]) { visited[neighbor] = true; queue[tail++] = neighbor }
                }
                if (x < width - 1) {
                    val neighbor = index + 1
                    if (mask[neighbor] && !visited[neighbor]) { visited[neighbor] = true; queue[tail++] = neighbor }
                }
                if (y > 0) {
                    val neighbor = index - width
                    if (mask[neighbor] && !visited[neighbor]) { visited[neighbor] = true; queue[tail++] = neighbor }
                }
                if (y < height - 1) {
                    val neighbor = index + width
                    if (mask[neighbor] && !visited[neighbor]) { visited[neighbor] = true; queue[tail++] = neighbor }
                }
            }
            if (members.size > bestSize) {
                bestSize = members.size
                bestMembers = members.toIntArray()
            }
        }

        val members = bestMembers ?: return null
        val boundary = ArrayList<IntPoint>()
        for (index in members) {
            val x = index % width
            val y = index / width
            val isBoundary = x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                !mask[index - 1] || !mask[index + 1] || !mask[index - width] || !mask[index + width]
            if (isBoundary) boundary.add(IntPoint(x, y))
        }
        return boundary to bestSize
    }

    /** Standard Andrew's monotone chain convex hull, O(n log n). */
    private fun convexHull(points: List<IntPoint>): List<IntPoint> {
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted

        fun cross(o: IntPoint, a: IntPoint, b: IntPoint): Long =
            (a.x - o.x).toLong() * (b.y - o.y) - (a.y - o.y).toLong() * (b.x - o.x)

        val lower = ArrayList<IntPoint>()
        for (point in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(point)
        }
        val upper = ArrayList<IntPoint>()
        for (point in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(point)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /**
     * Samples points along the segment a->b and checks whether a real Sobel edge exists near
     * each sample (a small neighborhood is checked to tolerate the +/-1px discretization of hull
     * corners). Requires most of the segment to be backed by a real intensity transition.
     */
    private fun edgeIsSupported(
        a: IntPoint,
        b: IntPoint,
        magnitudes: FloatArray,
        width: Int,
        height: Int,
        edgeThreshold: Float,
        neighborhoodRadius: Int = 2,
        minSupportedFraction: Float = 0.75f
    ): Boolean {
        val steps = 24
        var supported = 0
        var sampled = 0
        for (step in 1 until steps) {
            val t = step / steps.toFloat()
            val x = (a.x + (b.x - a.x) * t).roundToInt()
            val y = (a.y + (b.y - a.y) * t).roundToInt()
            var localMax = 0f
            for (dy in -neighborhoodRadius..neighborhoodRadius) {
                val ny = y + dy
                if (ny !in 0 until height) continue
                for (dx in -neighborhoodRadius..neighborhoodRadius) {
                    val nx = x + dx
                    if (nx !in 0 until width) continue
                    val value = magnitudes[ny * width + nx]
                    if (value > localMax) localMax = value
                }
            }
            sampled++
            if (localMax >= edgeThreshold) supported++
        }
        return sampled == 0 || (supported.toFloat() / sampled) >= minSupportedFraction
    }

    // ------------------------------------------------------------------------------------
    // SECONDARY (fallback): legacy four-independent-boundary-lines search. Unchanged logic --
    // kept only for frames where the document and its background are too close in brightness
    // for [detectByRegion] to isolate a single confident connected shape.
    // ------------------------------------------------------------------------------------

    private fun detectByEdgeLines(
        luma: FloatArray,
        gradientX: FloatArray,
        gradientY: FloatArray,
        magnitudes: FloatArray,
        width: Int,
        height: Int
    ): AutoCropResult {
        val edgeThreshold = percentileThreshold(magnitudes, 0.78f).coerceAtLeast(14f)

        val left = findVerticalBoundary(
            luma, gradientX, gradientY, width, height,
            minBaseFraction = 0.01f, maxBaseFraction = 0.46f,
            preferOuter = true, threshold = edgeThreshold
        )
        val right = findVerticalBoundary(
            luma, gradientX, gradientY, width, height,
            minBaseFraction = 0.54f, maxBaseFraction = 0.99f,
            preferOuter = false, threshold = edgeThreshold
        )
        val top = findHorizontalBoundary(
            luma, gradientX, gradientY, width, height,
            minBaseFraction = 0.01f, maxBaseFraction = 0.46f,
            preferOuter = true, threshold = edgeThreshold
        )
        val bottom = findHorizontalBoundary(
            luma, gradientX, gradientY, width, height,
            minBaseFraction = 0.54f, maxBaseFraction = 0.99f,
            preferOuter = false, threshold = edgeThreshold
        )

        val leftLine = left ?: return fallbackResult("left_boundary_not_found")
        val rightLine = right ?: return fallbackResult("right_boundary_not_found")
        val topLine = top ?: return fallbackResult("top_boundary_not_found")
        val bottomLine = bottom ?: return fallbackResult("bottom_boundary_not_found")

        val tl = intersect(leftLine, topLine, width, height)
        val tr = intersect(rightLine, topLine, width, height)
        val br = intersect(rightLine, bottomLine, width, height)
        val bl = intersect(leftLine, bottomLine, width, height)
        val geometry = CropGeometry(tl, tr, br, bl)

        val minimumCoverage = min(
            min(leftLine.coverage, rightLine.coverage),
            min(topLine.coverage, bottomLine.coverage)
        )
        return if (minimumCoverage < 0.055f) {
            fallbackResult("insufficient_edge_coverage")
        } else if (!isValidGeometry(geometry)) {
            fallbackResult("invalid_detected_geometry")
        } else {
            val area = polygonArea(geometry)
            val confidence = (minimumCoverage * 1.8f + area * 0.45f).coerceIn(0f, 1f)
            AutoCropResult(geometry, confidence, usedFallback = false)
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
                if (best == null || score > best!!.score) best = LineModel(slope, base.toFloat(), score, coverage)
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
                if (best == null || score > best!!.score) best = LineModel(slope, base.toFloat(), score, coverage)
            }
            slope += 0.05f
        }
        return best
    }

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
