package com.example.core.command

import com.example.core.model.DocumentModel

/**
 * Command interface for transactional undo/redo
 */
interface Command {
    val description: String
    fun execute(currentDoc: DocumentModel): DocumentModel
    fun undo(currentDoc: DocumentModel): DocumentModel
}
