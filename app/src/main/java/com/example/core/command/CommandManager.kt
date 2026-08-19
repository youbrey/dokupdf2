package com.example.core.command

import com.example.core.model.DocumentModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Manages Undo/Redo stacks and transactional command execution
 */
class CommandManager(private var initialDoc: DocumentModel) {

    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    private val _documentState = MutableStateFlow(initialDoc)
    val documentState: StateFlow<DocumentModel> = _documentState.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    fun resetDocument(doc: DocumentModel) {
        undoStack.clear()
        redoStack.clear()
        _documentState.value = doc
        updateFlags()
    }

    fun execute(command: Command) {
        val newDoc = command.execute(_documentState.value)
        undoStack.push(command)
        redoStack.clear()
        _documentState.value = newDoc.copy(modifiedAt = System.currentTimeMillis())
        updateFlags()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val command = undoStack.pop()
        val revertedDoc = command.undo(_documentState.value)
        redoStack.push(command)
        _documentState.value = revertedDoc.copy(modifiedAt = System.currentTimeMillis())
        updateFlags()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val command = redoStack.pop()
        val reappliedDoc = command.execute(_documentState.value)
        undoStack.push(command)
        _documentState.value = reappliedDoc.copy(modifiedAt = System.currentTimeMillis())
        updateFlags()
        return true
    }

    private fun updateFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}
