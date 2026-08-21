package com.example.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-performance PDF reader & page renderer using Android native PdfRenderer
 */
class PdfRendererEngine(@Suppress("UNUSED_PARAMETER") context: Context) {

    data class PageDimensions(val width: Int, val height: Int)

    suspend fun renderPdfPages(
        file: File,
        scale: Float = 2.0f
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        validateScale(scale)
        PdfFileUtils.requirePdf(file)
        val bitmaps = mutableListOf<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val openedRenderer = PdfRenderer(requireNotNull(pfd))
            renderer = openedRenderer
            val pageCount = openedRenderer.pageCount

            for (i in 0 until pageCount) {
                val page = openedRenderer.openPage(i)
                var bitmap: Bitmap? = null
                try {
                    val (width, height) = boundedPageSize(page.width, page.height, scale)
                    val allocated = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap = allocated
                    val canvas = Canvas(allocated)
                    canvas.drawColor(Color.WHITE)
                    page.render(allocated, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(allocated)
                    bitmap = null
                } finally {
                    bitmap?.let { if (!it.isRecycled) it.recycle() }
                    page.close()
                }
            }
        } catch (oom: OutOfMemoryError) {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            throw IllegalStateException(
                "Memori tidak cukup untuk merender seluruh PDF sekaligus; gunakan pemrosesan per halaman",
                oom
            )
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
        validateScale(scale)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var bitmap: Bitmap? = null
        try {
            PdfFileUtils.requirePdf(file)
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val openedRenderer = PdfRenderer(requireNotNull(pfd))
            renderer = openedRenderer
            if (pageIndex < 0 || pageIndex >= openedRenderer.pageCount) return@withContext null

            val page = openedRenderer.openPage(pageIndex)
            try {
                val (width, height) = boundedPageSize(page.width, page.height, scale)
                val allocated = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap = allocated
                val canvas = Canvas(allocated)
                canvas.drawColor(Color.WHITE)
                page.render(allocated, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                allocated.also { bitmap = null }
            } finally {
                page.close()
            }
        } catch (oom: OutOfMemoryError) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            null
        } catch (cancellation: CancellationException) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            throw cancellation
        } catch (e: Exception) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            Log.w("DokuPdfRenderer", "Halaman PDF gagal dirender", e)
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
            PdfFileUtils.requirePdf(file)
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val openedRenderer = PdfRenderer(requireNotNull(pfd))
            renderer = openedRenderer
            openedRenderer.pageCount
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            0
        } finally {
            try { renderer?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }
    }

    suspend fun getPageDimensions(file: File): List<PageDimensions> = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            PdfFileUtils.requirePdf(file)
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val openedRenderer = PdfRenderer(requireNotNull(pfd))
            renderer = openedRenderer
            buildList(openedRenderer.pageCount) {
                for (index in 0 until openedRenderer.pageCount) {
                    val page = openedRenderer.openPage(index)
                    try {
                        add(
                            PageDimensions(
                                page.width.coerceIn(1, 20_000),
                                page.height.coerceIn(1, 20_000)
                            )
                        )
                    } finally {
                        page.close()
                    }
                }
            }
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Renders and releases one page at a time. [action] must not retain [Bitmap] after it returns.
     */
    suspend fun forEachRenderedPage(
        file: File,
        scale: Float = 2.0f,
        action: suspend (pageIndex: Int, bitmap: Bitmap) -> Unit
    ) = withContext(Dispatchers.IO) {
        validateScale(scale)
        PdfFileUtils.requirePdf(file)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val openedRenderer = PdfRenderer(requireNotNull(pfd))
            renderer = openedRenderer
            for (index in 0 until openedRenderer.pageCount) {
                val page = openedRenderer.openPage(index)
                var bitmap: Bitmap? = null
                try {
                    val (width, height) = boundedPageSize(page.width, page.height, scale)
                    val allocated = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap = allocated
                    Canvas(allocated).drawColor(Color.WHITE)
                    page.render(allocated, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    action(index, allocated)
                } catch (oom: OutOfMemoryError) {
                    throw IllegalStateException("Memori tidak cukup saat merender halaman ${index + 1}", oom)
                } finally {
                    bitmap?.let { if (!it.isRecycled) it.recycle() }
                    page.close()
                }
            }
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun boundedPageSize(width: Int, height: Int, scale: Float): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Ukuran halaman PDF tidak valid" }
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

    private fun validateScale(scale: Float) {
        require(scale.isFinite() && scale in 0.02f..4f) { "Skala render PDF tidak valid" }
    }
}
