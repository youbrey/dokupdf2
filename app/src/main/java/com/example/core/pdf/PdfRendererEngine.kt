package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-performance PDF reader & page renderer using Android native PdfRenderer
 */
class PdfRendererEngine(@Suppress("UNUSED_PARAMETER") context: Context) {

    suspend fun renderPdfPages(
        file: File,
        scale: Float = 2.0f
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        require(scale.isFinite() && scale in 0.1f..4f) { "Skala render PDF tidak valid" }
        val bitmaps = mutableListOf<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext emptyList()
            }

            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                try {
                    val (width, height) = boundedPageSize(page.width, page.height, scale)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(bitmap)
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            throw e
        } finally {
            try { renderer?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }

        bitmaps
    }

    suspend fun renderSinglePage(
        file: File,
        pageIndex: Int,
        scale: Float = 2.0f
    ): Bitmap? = withContext(Dispatchers.IO) {
        require(scale.isFinite() && scale in 0.1f..4f) { "Skala render PDF tidak valid" }
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            if (!file.exists() || file.length() == 0L) return@withContext null
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            val page = renderer.openPage(pageIndex)
            try {
                val (width, height) = boundedPageSize(page.width, page.height, scale)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try { renderer?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }
    }

    suspend fun getPageCount(file: File): Int = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            if (!file.exists() || file.length() == 0L) return@withContext 0
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (e: Exception) {
            0
        } finally {
            try { renderer?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }
    }

    private fun boundedPageSize(width: Int, height: Int, scale: Float): Pair<Int, Int> {
        var targetWidth = (width * scale).toInt().coerceAtLeast(1)
        var targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val longest = maxOf(targetWidth, targetHeight)
        if (longest > 4096) {
            val downscale = 4096f / longest
            targetWidth = (targetWidth * downscale).toInt().coerceAtLeast(1)
            targetHeight = (targetHeight * downscale).toInt().coerceAtLeast(1)
        }
        return targetWidth to targetHeight
    }
}
