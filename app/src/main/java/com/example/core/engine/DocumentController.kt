package com.example.core.engine

import android.graphics.Bitmap
import com.example.core.command.*
import com.example.core.model.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller mediating user intent / UI interactions to DocumentEngine via Command Pattern
 */
class DocumentController(private val engine: DocumentEngine) {

    val documentState: StateFlow<DocumentModel> = engine.documentState
    val canUndo: StateFlow<Boolean> = engine.commandManager.canUndo
    val canRedo: StateFlow<Boolean> = engine.commandManager.canRedo

    fun addPage(page: PageModel, targetIndex: Int = -1) {
        engine.commandManager.execute(AddPageCommand(page, targetIndex))
    }

    fun deletePage(pageId: String) {
        engine.commandManager.execute(DeletePageCommand(pageId))
    }

    fun reorderPages(newPages: List<PageModel>) {
        engine.commandManager.execute(ReorderPagesCommand(newPages))
    }

    fun rotatePage(pageId: String, deltaDegrees: Int = 90) {
        engine.commandManager.execute(RotatePageCommand(pageId, deltaDegrees))
    }

    fun applyFilter(pageId: String, filterType: FilterType, processedBitmap: Bitmap? = null) {
        engine.commandManager.execute(ApplyFilterCommand(pageId, filterType, processedBitmap))
    }

    fun setCrop(pageId: String, cropGeometry: CropGeometry) {
        engine.commandManager.execute(SetCropCommand(pageId, cropGeometry))
    }

    fun addSignature(pageId: String, signature: SignatureAnnotation) {
        engine.commandManager.execute(AddSignatureCommand(pageId, signature))
    }

    fun addWatermark(watermark: WatermarkAnnotation, targetPageId: String? = null) {
        engine.commandManager.execute(AddWatermarkCommand(watermark, targetPageId))
    }

    fun addDrawPath(pageId: String, drawPath: DrawPath) {
        engine.commandManager.execute(AddDrawPathCommand(pageId, drawPath))
    }

    fun addEraserStroke(pageId: String, stroke: EraserStroke) {
        engine.commandManager.execute(AddEraserStrokeCommand(pageId, stroke))
    }

    fun undo(): Boolean = engine.commandManager.undo()

    fun redo(): Boolean = engine.commandManager.redo()
}
