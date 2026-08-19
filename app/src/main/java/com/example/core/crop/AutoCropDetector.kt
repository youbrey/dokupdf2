package com.example.core.crop

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import com.example.core.model.CropGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Intelligent on-device edge & corner detection for document scanning.
 * Automatically locates the 4 corners of a document on a table/background.
 */
object AutoCropDetector {

    /**
     * Detects document corners in normalized 0.0f..1.0f coordinates.
     */
    fun detectDocumentCorners(bitmap: Bitmap): CropGeometry {
        val sampleSize = 300
        val scaleX = bitmap.width.toFloat() / sampleSize
        val scaleY = bitmap.height.toFloat() / sampleSize

        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        } catch (e: Exception) {
            return defaultGeometry()
        }

        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        // Step 1: Compute grayscale luminance and local variance/gradients
        val luminance = FloatArray(width * height)
        var sumLuma = 0.0
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = (0.299f * r + 0.587f * g + 0.114f * b)
            luminance[i] = luma
            sumLuma += luma
        }
        val avgLuma = (sumLuma / luminance.size).toFloat()

        // Step 2: Compute Sobel edge gradients
        val edges = FloatArray(width * height)
        var maxEdge = 1f
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val gx = (luminance[idx + 1] - luminance[idx - 1]) +
                        0.5f * (luminance[idx - width + 1] - luminance[idx - width - 1]) +
                        0.5f * (luminance[idx + width + 1] - luminance[idx + width - 1])
                val gy = (luminance[idx + width] - luminance[idx - width]) +
                        0.5f * (luminance[idx + width + 1] - luminance[idx - width + 1]) +
                        0.5f * (luminance[idx + width - 1] - luminance[idx - width - 1])
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                edges[idx] = mag
                if (mag > maxEdge) maxEdge = mag
            }
        }

        // Step 3: Scan along rays from center to 4 corners to find highest gradient transitions
        val centerX = width / 2f
        val centerY = height / 2f

        // Search rays targeting each corner region
        val tl = findCornerAlongRay(edges, luminance, width, height, centerX, centerY, 0.05f * width, 0.05f * height, avgLuma)
        val tr = findCornerAlongRay(edges, luminance, width, height, centerX, centerY, 0.95f * width, 0.05f * height, avgLuma)
        val br = findCornerAlongRay(edges, luminance, width, height, centerX, centerY, 0.95f * width, 0.95f * height, avgLuma)
        val bl = findCornerAlongRay(edges, luminance, width, height, centerX, centerY, 0.05f * width, 0.95f * height, avgLuma)

        // Normalize points to 0.0f..1.0f range
        val normTL = Offset((tl.x / width).coerceIn(0.02f, 0.35f), (tl.y / height).coerceIn(0.02f, 0.35f))
        val normTR = Offset((tr.x / width).coerceIn(0.65f, 0.98f), (tr.y / height).coerceIn(0.02f, 0.35f))
        val normBR = Offset((br.x / width).coerceIn(0.65f, 0.98f), (br.y / height).coerceIn(0.65f, 0.98f))
        val normBL = Offset((bl.x / width).coerceIn(0.02f, 0.35f), (bl.y / height).coerceIn(0.65f, 0.98f))

        // Validate quadrilateral area & sanity
        if (isValidQuad(normTL, normTR, normBR, normBL)) {
            return CropGeometry(
                topLeft = normTL,
                topRight = normTR,
                bottomRight = normBR,
                bottomLeft = normBL
            )
        }

        return defaultGeometry()
    }

    private fun findCornerAlongRay(
        edges: FloatArray,
        luminance: FloatArray,
        width: Int,
        height: Int,
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        avgLuma: Float
    ): PointF {
        val steps = 60
        var bestX = targetX
        var bestY = targetY
        var maxScore = -1f

        val dx = (targetX - startX) / steps
        val dy = (targetY - startY) / steps

        // Look in outer 60% of ray
        val startStep = (steps * 0.35f).toInt()

        for (s in startStep..steps) {
            val curX = (startX + dx * s).toInt().coerceIn(1, width - 2)
            val curY = (startY + dy * s).toInt().coerceIn(1, height - 2)
            val idx = curY * width + curX

            val edgeStrength = edges[idx]
            val lumaDiff = abs(luminance[idx] - avgLuma)
            val score = edgeStrength * 1.5f + lumaDiff * 0.5f

            if (score > maxScore) {
                maxScore = score
                bestX = curX.toFloat()
                bestY = curY.toFloat()
            }
        }

        return PointF(bestX, bestY)
    }

    private fun isValidQuad(tl: Offset, tr: Offset, br: Offset, bl: Offset): Boolean {
        // Check top width, bottom width, left height, right height
        val topW = tr.x - tl.x
        val botW = br.x - bl.x
        val leftH = bl.y - tl.y
        val rightH = br.y - tr.y

        if (topW < 0.25f || botW < 0.25f || leftH < 0.25f || rightH < 0.25f) {
            return false
        }

        // Polygon must not self-intersect
        if (tl.x >= tr.x || bl.x >= br.x || tl.y >= bl.y || tr.y >= br.y) {
            return false
        }

        return true
    }

    fun defaultGeometry(): CropGeometry {
        return CropGeometry(
            topLeft = Offset(0.06f, 0.06f),
            topRight = Offset(0.94f, 0.06f),
            bottomRight = Offset(0.94f, 0.94f),
            bottomLeft = Offset(0.06f, 0.94f)
        )
    }

    fun fullGeometry(): CropGeometry {
        return CropGeometry(
            topLeft = Offset(0f, 0f),
            topRight = Offset(1f, 0f),
            bottomRight = Offset(1f, 1f),
            bottomLeft = Offset(0f, 1f)
        )
    }
}
