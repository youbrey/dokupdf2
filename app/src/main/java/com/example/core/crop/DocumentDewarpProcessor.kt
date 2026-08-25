package com.example.core.crop

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Result of the optional non-planar page correction pass.
 *
 * [bitmap] is the caller-owned source when [applied] is false. When [applied] is true it is a
 * newly allocated bitmap and the caller remains responsible for the source bitmap as well.
 */
data class DocumentDewarpResult(
    val bitmap: Bitmap,
    val applied: Boolean,
    val confidence: Float,
    val controlLineCount: Int
)

/**
 * Lightweight, on-device document dewarper.
 *
 * A four-corner homography can remove camera perspective, but it cannot straighten a sheet that
 * is physically bowed or wavy. This processor detects long horizontal structures (table borders,
 * ruled lines and text baselines), tracks their vertical position across the page and constructs a
 * smooth piecewise-linear mesh. Pixels are then resampled vertically so every supported control
 * line becomes horizontal. The pass is deliberately confidence-gated: photographs and documents
 * without enough reliable horizontal evidence are returned untouched.
 *
 * The implementation is dependency-free and keeps analysis below [ANALYSIS_LONG_EDGE] pixels so
 * it remains practical on devices where retaining multiple full-resolution ARGB pages is costly.
 */
object DocumentDewarpProcessor {

    private const val ANALYSIS_LONG_EDGE = 720
    private const val NODE_COUNT = 29
    private const val MAX_CANDIDATE_ROWS = 22
    private const val MAX_CONTROL_LINES = 12
    private const val MIN_CONTROL_LINES = 3

    private data class TrackedLine(
        val targetY: Float,
        val positions: FloatArray,
        val support: Float,
        val quality: Float,
        val roughness: Float
    )

    fun flatten(source: Bitmap): DocumentDewarpResult {
        require(!source.isRecycled && source.width > 0 && source.height > 0) {
            "Bitmap sumber dewarp tidak valid"
        }
        if (source.width < 96 || source.height < 128) return unchanged(source)

        val longest = max(source.width, source.height).toFloat()
        val scale = min(1f, ANALYSIS_LONG_EDGE / longest)
        val analysisWidth = max(64, (source.width * scale).roundToInt())
        val analysisHeight = max(64, (source.height * scale).roundToInt())
        val analysisBitmap = try {
            if (analysisWidth == source.width && analysisHeight == source.height) source
            else Bitmap.createScaledBitmap(source, analysisWidth, analysisHeight, true)
        } catch (_: OutOfMemoryError) {
            return unchanged(source)
        }

        return try {
            val pixels = IntArray(analysisWidth * analysisHeight)
            analysisBitmap.getPixels(pixels, 0, analysisWidth, 0, 0, analysisWidth, analysisHeight)
            val luma = IntArray(pixels.size)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                luma[index] = (299 * red + 587 * green + 114 * blue + 500) / 1000
            }

            val verticalEdges = buildVerticalEdgeMap(luma, analysisWidth, analysisHeight)
            val rowScores = buildRowScores(verticalEdges, analysisWidth, analysisHeight)
            val noiseFloor = percentile(rowScores, 0.55f).coerceAtLeast(2f)
            val candidateRows = selectCandidateRows(rowScores, analysisHeight, noiseFloor)
            if (candidateRows.size < MIN_CONTROL_LINES) return unchanged(source)

            val tracked = candidateRows.mapNotNull { row ->
                trackHorizontalStructure(
                    edges = verticalEdges,
                    width = analysisWidth,
                    height = analysisHeight,
                    nominalY = row,
                    rowScore = rowScores[row],
                    noiseFloor = noiseFloor
                )
            }
            val controlLines = selectNonCrossingLines(tracked, analysisHeight)
            if (controlLines.size < MIN_CONTROL_LINES) return unchanged(source)

            val verticalSpan = controlLines.last().targetY - controlLines.first().targetY
            if (verticalSpan < analysisHeight * 0.24f) return unchanged(source)

            val displacementSamples = ArrayList<Float>(controlLines.size * NODE_COUNT)
            var maximumDisplacement = 0f
            for (line in controlLines) {
                for (position in line.positions) {
                    val displacement = abs(position - line.targetY)
                    displacementSamples += displacement
                    maximumDisplacement = max(maximumDisplacement, displacement)
                }
            }
            val typicalDisplacement = percentile(displacementSamples.toFloatArray(), 0.75f)
            // Do not resample a page whose structures are already effectively straight. Avoiding
            // that extra interpolation preserves the source's native text sharpness.
            if (typicalDisplacement < 0.65f && maximumDisplacement < 1.6f) {
                return unchanged(source, confidenceFor(controlLines))
            }
            // A track that asks for extreme movement is almost certainly following different text
            // rows in adjacent columns instead of the same physical baseline.
            if (maximumDisplacement > analysisHeight * 0.075f) return unchanged(source)

            val confidence = confidenceFor(controlLines)
            if (confidence < 0.48f) return unchanged(source, confidence)

            val flattened = renderMesh(source, controlLines, analysisWidth, analysisHeight)
            DocumentDewarpResult(
                bitmap = flattened,
                applied = flattened !== source,
                confidence = confidence,
                controlLineCount = controlLines.size
            )
        } catch (_: OutOfMemoryError) {
            unchanged(source)
        } catch (_: Exception) {
            // Dewarp is an enhancement pass, never a reason to lose an otherwise valid crop.
            unchanged(source)
        } finally {
            if (analysisBitmap !== source && !analysisBitmap.isRecycled) analysisBitmap.recycle()
        }
    }

    private fun buildVerticalEdgeMap(luma: IntArray, width: Int, height: Int): IntArray {
        val edges = IntArray(luma.size)
        for (y in 1 until height - 1) {
            val row = y * width
            val previous = (y - 1) * width
            val next = (y + 1) * width
            for (x in 1 until width - 1) {
                val firstDerivative = abs(luma[next + x] - luma[previous + x])
                val secondDerivative = abs(luma[previous + x] - 2 * luma[row + x] + luma[next + x])
                edges[row + x] = min(255, firstDerivative + secondDerivative / 2)
            }
        }
        return edges
    }

    private fun buildRowScores(edges: IntArray, width: Int, height: Int): FloatArray {
        val raw = FloatArray(height)
        val startX = (width * 0.035f).roundToInt().coerceIn(1, width - 2)
        val endX = (width * 0.965f).roundToInt().coerceIn(startX + 1, width - 1)
        val sampleCount = (endX - startX).coerceAtLeast(1)

        for (y in 2 until height - 2) {
            val row = y * width
            var energy = 0f
            var strong = 0
            for (x in startX until endX) {
                val value = edges[row + x]
                energy += min(value, 96)
                if (value >= 18) strong++
            }
            raw[y] = energy / sampleCount + strong.toFloat() / sampleCount * 52f
        }

        val smoothed = FloatArray(height)
        for (y in 2 until height - 2) {
            smoothed[y] = (raw[y - 2] + raw[y - 1] * 2f + raw[y] * 3f + raw[y + 1] * 2f + raw[y + 2]) / 9f
        }
        return smoothed
    }

    private fun selectCandidateRows(rowScores: FloatArray, height: Int, noiseFloor: Float): List<Int> {
        val margin = (height * 0.045f).roundToInt().coerceAtLeast(3)
        val minimumSpacing = (height / 52).coerceIn(7, 18)
        val threshold = max(7.5f, noiseFloor * 1.12f)
        val localMaxima = ArrayList<Int>()

        for (y in margin until height - margin) {
            val score = rowScores[y]
            if (score >= threshold && score >= rowScores[y - 1] && score >= rowScores[y + 1]) {
                localMaxima += y
            }
        }

        val selected = ArrayList<Int>(MAX_CANDIDATE_ROWS)
        for (row in localMaxima.sortedByDescending { rowScores[it] }) {
            if (selected.none { abs(it - row) < minimumSpacing }) {
                selected += row
                if (selected.size == MAX_CANDIDATE_ROWS) break
            }
        }
        return selected
    }

    private fun trackHorizontalStructure(
        edges: IntArray,
        width: Int,
        height: Int,
        nominalY: Int,
        rowScore: Float,
        noiseFloor: Float
    ): TrackedLine? {
        val xMargin = (width * 0.035f).roundToInt().coerceAtLeast(2)
        val xSpan = (width - 1 - xMargin * 2).coerceAtLeast(1)
        val nodesX = IntArray(NODE_COUNT) { index ->
            xMargin + (xSpan * index.toFloat() / (NODE_COUNT - 1)).roundToInt()
        }
        val blockHalfWidth = (width / (NODE_COUNT * 2)).coerceIn(3, 14)
        val maximumOffset = (height * 0.042f).roundToInt().coerceIn(8, 32)
        val maximumStep = (height / 180).coerceIn(3, 6)
        val positions = FloatArray(NODE_COUNT)
        val scores = FloatArray(NODE_COUNT)
        val centerIndex = NODE_COUNT / 2

        fun bestAt(nodeIndex: Int, predicted: Int, radius: Int): Pair<Int, Float> {
            var bestY = predicted.coerceIn(2, height - 3)
            var bestScore = Float.NEGATIVE_INFINITY
            val minimumY = max(2, max(nominalY - maximumOffset, predicted - radius))
            val maximumY = min(height - 3, min(nominalY + maximumOffset, predicted + radius))
            for (candidateY in minimumY..maximumY) {
                val energy = localHorizontalEnergy(
                    edges,
                    width,
                    height,
                    nodesX[nodeIndex],
                    candidateY,
                    blockHalfWidth
                )
                val continuityPenalty = abs(candidateY - predicted) * 0.85f
                val candidateScore = energy - continuityPenalty
                if (candidateScore > bestScore) {
                    bestScore = candidateScore
                    bestY = candidateY
                }
            }
            return bestY to max(0f, bestScore)
        }

        val center = bestAt(centerIndex, nominalY, maximumOffset)
        positions[centerIndex] = center.first.toFloat()
        scores[centerIndex] = center.second

        for (index in centerIndex + 1 until NODE_COUNT) {
            val previous = positions[index - 1]
            val previousPrevious = positions.getOrNull(index - 2) ?: previous
            val predicted = (previous + (previous - previousPrevious).coerceIn(-2f, 2f)).roundToInt()
            val best = bestAt(index, predicted, maximumStep)
            positions[index] = best.first.toFloat()
            scores[index] = best.second
        }
        for (index in centerIndex - 1 downTo 0) {
            val previous = positions[index + 1]
            val previousPrevious = positions.getOrNull(index + 2) ?: previous
            val predicted = (previous + (previous - previousPrevious).coerceIn(-2f, 2f)).roundToInt()
            val best = bestAt(index, predicted, maximumStep)
            positions[index] = best.first.toFloat()
            scores[index] = best.second
        }

        val supportThreshold = max(7f, max(noiseFloor * 0.78f, rowScore * 0.24f))
        val support = scores.count { it >= supportThreshold }.toFloat() / NODE_COUNT
        if (support < 0.56f) return null

        var roughness = 0f
        for (index in 1 until NODE_COUNT - 1) {
            roughness += abs(positions[index - 1] - 2f * positions[index] + positions[index + 1])
        }
        roughness /= (NODE_COUNT - 2)
        if (roughness > 2.35f) return null

        val targetY = percentile(positions, 0.5f)
        val medianScore = percentile(scores, 0.5f)
        val quality = (medianScore / (noiseFloor + 1f)) * support / (1f + roughness * 0.45f)
        if (quality < 0.72f) return null

        return TrackedLine(
            targetY = targetY,
            positions = smoothPositions(positions),
            support = support,
            quality = quality,
            roughness = roughness
        )
    }

    private fun localHorizontalEnergy(
        edges: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        halfWidth: Int
    ): Float {
        val startX = max(1, centerX - halfWidth)
        val endX = min(width - 2, centerX + halfWidth)
        val startY = max(1, centerY - 1)
        val endY = min(height - 2, centerY + 1)
        var sum = 0f
        var strong = 0
        var count = 0
        for (y in startY..endY) {
            val row = y * width
            for (x in startX..endX) {
                val value = edges[row + x]
                sum += min(value, 96)
                if (value >= 18) strong++
                count++
            }
        }
        if (count == 0) return 0f
        return sum / count + strong.toFloat() / count * 46f
    }

    private fun smoothPositions(source: FloatArray): FloatArray {
        var current = source.copyOf()
        repeat(2) {
            val next = current.copyOf()
            for (index in 1 until current.lastIndex) {
                next[index] = (current[index - 1] + current[index] * 2f + current[index + 1]) / 4f
            }
            current = next
        }
        return current
    }

    private fun selectNonCrossingLines(lines: List<TrackedLine>, height: Int): List<TrackedLine> {
        val minimumSpacing = (height / 58).coerceIn(7, 16)
        val distinct = ArrayList<TrackedLine>()
        for (line in lines.sortedByDescending { it.quality }) {
            if (distinct.none { abs(it.targetY - line.targetY) < minimumSpacing }) {
                distinct += line
                if (distinct.size == MAX_CONTROL_LINES) break
            }
        }

        val ordered = distinct.sortedBy { it.targetY }
        val accepted = ArrayList<TrackedLine>(ordered.size)
        for (line in ordered) {
            val previous = accepted.lastOrNull()
            if (previous == null) {
                accepted += line
                continue
            }
            val nominalGap = line.targetY - previous.targetY
            val minimumTrackedGap = max(2.5f, nominalGap * 0.32f)
            val doesNotCross = line.positions.indices.all { index ->
                line.positions[index] - previous.positions[index] >= minimumTrackedGap
            }
            if (doesNotCross) accepted += line
        }
        return accepted
    }

    private fun confidenceFor(lines: List<TrackedLine>): Float {
        if (lines.isEmpty()) return 0f
        val support = lines.map { it.support }.average().toFloat()
        val quality = lines.map { it.quality.coerceAtMost(2.2f) / 2.2f }.average().toFloat()
        val count = (lines.size / 7f).coerceIn(0f, 1f)
        return (support * 0.45f + quality * 0.30f + count * 0.25f).coerceIn(0f, 1f)
    }

    private fun renderMesh(
        source: Bitmap,
        lines: List<TrackedLine>,
        analysisWidth: Int,
        analysisHeight: Int
    ): Bitmap {
        val width = source.width
        val height = source.height
        val targetAnchors = FloatArray(lines.size + 2)
        targetAnchors[0] = 0f
        for (index in lines.indices) {
            targetAnchors[index + 1] = lines[index].targetY / (analysisHeight - 1f) * (height - 1f)
        }
        targetAnchors[targetAnchors.lastIndex] = height - 1f

        for (index in 1 until targetAnchors.size) {
            if (targetAnchors[index] <= targetAnchors[index - 1] + 1f) return source
        }

        val sourceAnchors = Array(lines.size + 2) { FloatArray(width) }
        for (x in 0 until width) {
            sourceAnchors[0][x] = 0f
            sourceAnchors[sourceAnchors.lastIndex][x] = height - 1f
            val analysisX = x.toFloat() / (width - 1).coerceAtLeast(1) * (analysisWidth - 1f)
            val nodeCoordinate = (
                (analysisX - analysisWidth * 0.035f) /
                    (analysisWidth * 0.93f).coerceAtLeast(1f) * (NODE_COUNT - 1)
                ).coerceIn(0f, (NODE_COUNT - 1).toFloat())
            val leftNode = nodeCoordinate.toInt().coerceIn(0, NODE_COUNT - 1)
            val rightNode = min(NODE_COUNT - 1, leftNode + 1)
            val nodeFraction = nodeCoordinate - leftNode
            for (lineIndex in lines.indices) {
                val line = lines[lineIndex]
                val analysisY = line.positions[leftNode] * (1f - nodeFraction) +
                    line.positions[rightNode] * nodeFraction
                sourceAnchors[lineIndex + 1][x] =
                    (analysisY / (analysisHeight - 1f) * (height - 1f)).coerceIn(0f, height - 1f)
            }
        }

        // Every source column must remain monotonic. Non-monotonic anchors indicate a bad track;
        // returning the original crop is safer than folding document content over itself.
        for (x in 0 until width) {
            for (anchor in 1 until sourceAnchors.size) {
                if (sourceAnchors[anchor][x] <= sourceAnchors[anchor - 1][x] + 0.5f) return source
            }
        }

        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val outputPixels = IntArray(input.size)
        var interval = 0
        for (y in 0 until height) {
            while (interval < targetAnchors.lastIndex - 1 && y > targetAnchors[interval + 1]) interval++
            val targetStart = targetAnchors[interval]
            val targetEnd = targetAnchors[interval + 1]
            val fraction = ((y - targetStart) / (targetEnd - targetStart).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val outputRow = y * width
            for (x in 0 until width) {
                val sourceY = (
                    sourceAnchors[interval][x] * (1f - fraction) +
                        sourceAnchors[interval + 1][x] * fraction
                    ).coerceIn(0f, height - 1f)
                val topY = sourceY.toInt().coerceIn(0, height - 1)
                val bottomY = min(height - 1, topY + 1)
                val verticalFraction = sourceY - topY
                val topPixel = input[topY * width + x]
                val bottomPixel = input[bottomY * width + x]
                outputPixels[outputRow + x] = interpolateColor(topPixel, bottomPixel, verticalFraction)
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)
        }
    }

    private fun interpolateColor(first: Int, second: Int, amount: Float): Int {
        if (amount <= 0.001f || first == second) return first
        val inverse = 1f - amount
        val alpha = (((first ushr 24) and 0xFF) * inverse + ((second ushr 24) and 0xFF) * amount).roundToInt()
        val red = (((first shr 16) and 0xFF) * inverse + ((second shr 16) and 0xFF) * amount).roundToInt()
        val green = (((first shr 8) and 0xFF) * inverse + ((second shr 8) and 0xFF) * amount).roundToInt()
        val blue = ((first and 0xFF) * inverse + (second and 0xFF) * amount).roundToInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun percentile(values: FloatArray, percentile: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().apply { sort() }
        val position = (sorted.lastIndex * percentile.coerceIn(0f, 1f)).roundToInt()
        return sorted[position]
    }

    private fun unchanged(source: Bitmap, confidence: Float = 0f) = DocumentDewarpResult(
        bitmap = source,
        applied = false,
        confidence = confidence,
        controlLineCount = 0
    )
}
