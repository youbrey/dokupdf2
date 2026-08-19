package com.example.core.engine

import android.content.Context
import android.graphics.Bitmap
import com.example.core.command.CommandManager
import com.example.core.model.DocumentModel
import com.example.core.model.PageModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Manages active Document state, persistence hooks, and page cache
 */
class DocumentEngine(
    private val context: Context,
    initialDoc: DocumentModel = DocumentModel()
) {
    val commandManager = CommandManager(initialDoc)
    val documentState: StateFlow<DocumentModel> = commandManager.documentState

    fun loadDocument(doc: DocumentModel) {
        commandManager.resetDocument(doc)
    }

    fun getCurrentDocument(): DocumentModel = documentState.value

    fun getPage(pageId: String): PageModel? {
        return documentState.value.pages.firstOrNull { it.id == pageId }
    }

    fun getPageByIndex(index: Int): PageModel? {
        return documentState.value.pages.getOrNull(index)
    }

    fun getPageCount(): Int = documentState.value.pages.size
}
