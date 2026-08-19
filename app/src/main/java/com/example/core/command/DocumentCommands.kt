package com.example.core.command

import android.graphics.Bitmap
import com.example.core.model.*

/**
 * Command to add a new page to the document
 */
class AddPageCommand(
    private val newPage: PageModel,
    private val targetIndex: Int = -1
) : Command {
    override val description: String = "Tambah Halaman"

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        val currentPages = currentDoc.pages.toMutableList()
        if (targetIndex in 0..currentPages.size) {
            currentPages.add(targetIndex, newPage)
        } else {
            currentPages.add(newPage)
        }
        val reindexed = currentPages.mapIndexed { idx, p -> p.copy(pageIndex = idx) }
        return currentDoc.copy(pages = reindexed)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val filtered = currentDoc.pages.filter { it.id != newPage.id }
        val reindexed = filtered.mapIndexed { idx, p -> p.copy(pageIndex = idx) }
        return currentDoc.copy(pages = reindexed)
    }
}

/**
 * Command to delete a page
 */
class DeletePageCommand(private val pageId: String) : Command {
    override val description: String = "Hapus Halaman"
    private var removedPage: PageModel? = null
    private var removedIndex: Int = -1

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        removedIndex = currentDoc.pages.indexOfFirst { it.id == pageId }
        if (removedIndex != -1) {
            removedPage = currentDoc.pages[removedIndex]
        }
        val remaining = currentDoc.pages.filter { it.id != pageId }
        val reindexed = remaining.mapIndexed { idx, p -> p.copy(pageIndex = idx) }
        return currentDoc.copy(pages = reindexed)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val page = removedPage ?: return currentDoc
        val mutable = currentDoc.pages.toMutableList()
        val insertAt = removedIndex.coerceIn(0, mutable.size)
        mutable.add(insertAt, page)
        val reindexed = mutable.mapIndexed { idx, p -> p.copy(pageIndex = idx) }
        return currentDoc.copy(pages = reindexed)
    }
}

/**
 * Command to reorder pages
 */
class ReorderPagesCommand(private val newPages: List<PageModel>) : Command {
    override val description: String = "Urutkan Halaman"
    private var previousPages: List<PageModel> = emptyList()

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        previousPages = currentDoc.pages
        val reindexed = newPages.mapIndexed { idx, p -> p.copy(pageIndex = idx) }
        return currentDoc.copy(pages = reindexed)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        return currentDoc.copy(pages = previousPages)
    }
}

/**
 * Command to rotate a page
 */
class RotatePageCommand(
    private val pageId: String,
    private val deltaDegrees: Int = 90
) : Command {
    override val description: String = "Putar Halaman"

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                val newRot = (page.rotationDegrees + deltaDegrees) % 360
                page.copy(rotationDegrees = if (newRot < 0) newRot + 360 else newRot)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                val prevRot = (page.rotationDegrees - deltaDegrees) % 360
                page.copy(rotationDegrees = if (prevRot < 0) prevRot + 360 else prevRot)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }
}

/**
 * Command to apply a filter preset to a page
 */
class ApplyFilterCommand(
    private val pageId: String,
    private val newFilter: FilterType,
    private val processedBitmap: Bitmap? = null
) : Command {
    override val description: String = "Terapkan Filter: ${newFilter.displayName}"
    private var oldFilter: FilterType = FilterType.ORIGINAL
    private var oldBitmap: Bitmap? = null

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                oldFilter = page.filterType
                oldBitmap = page.processedBitmap
                page.copy(filterType = newFilter, processedBitmap = processedBitmap ?: page.processedBitmap)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                page.copy(filterType = oldFilter, processedBitmap = oldBitmap)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }
}

/**
 * Command to apply crop geometry to a page
 */
class SetCropCommand(
    private val pageId: String,
    private val newCrop: CropGeometry
) : Command {
    override val description: String = "Potong Dokumen"
    private var oldCrop: CropGeometry = CropGeometry()

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                oldCrop = page.cropGeometry
                page.copy(cropGeometry = newCrop)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                page.copy(cropGeometry = oldCrop)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }
}

/**
 * Command to add a signature to a page
 */
class AddSignatureCommand(
    private val pageId: String,
    private val signature: SignatureAnnotation
) : Command {
    override val description: String = "Tambah Tanda Tangan"

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                page.copy(signatures = page.signatures + signature)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                page.copy(signatures = page.signatures.filter { it.id != signature.id })
            } else page
        }
        return currentDoc.copy(pages = updated)
    }
}

/**
 * Command to add or update watermark on all or specific pages
 */
class AddWatermarkCommand(
    private val watermark: WatermarkAnnotation,
    private val targetPageId: String? = null // null means all pages
) : Command {
    override val description: String = "Tambah Tanda Air"
    private var previousWatermarks: Map<String, List<WatermarkAnnotation>> = emptyMap()

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        previousWatermarks = currentDoc.pages.associate { it.id to it.watermarks }
        val updated = currentDoc.pages.map { page ->
            if (targetPageId == null || page.id == targetPageId) {
                page.copy(watermarks = listOf(watermark))
            } else page
        }
        return currentDoc.copy(pages = updated)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            page.copy(watermarks = previousWatermarks[page.id] ?: emptyList())
        }
        return currentDoc.copy(pages = updated)
    }
}

/**
 * Command to add pen draw paths
 */
class AddDrawPathCommand(
    private val pageId: String,
    private val drawPath: DrawPath
) : Command {
    override val description: String = "Gambar / Anotasi"

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                page.copy(drawPaths = page.drawPaths + drawPath)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                page.copy(drawPaths = page.drawPaths.filter { it.id != drawPath.id })
            } else page
        }
        return currentDoc.copy(pages = updated)
    }
}

/**
 * Command for smart whiteout eraser stroke
 */
class AddEraserStrokeCommand(
    private val pageId: String,
    private val stroke: EraserStroke
) : Command {
    override val description: String = "Hapus Cerdas"

    override fun execute(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                page.copy(eraserStrokes = page.eraserStrokes + stroke)
            } else page
        }
        return currentDoc.copy(pages = updated)
    }

    override fun undo(currentDoc: DocumentModel): DocumentModel {
        val updated = currentDoc.pages.map { page ->
            if (page.id == pageId) {
                page.copy(eraserStrokes = page.eraserStrokes.filter { it.id != stroke.id })
            } else page
        }
        return currentDoc.copy(pages = updated)
    }
}
