package com.example.pdfedi

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfedi.database.AppDatabase
import com.example.pdfedi.database.StudyNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PdfRepository(application)
    private val noteDao = AppDatabase.getDatabase(application).noteDao()

    private val _uiState = MutableStateFlow(EditorState())
    val uiState: StateFlow<EditorState> = _uiState.asStateFlow()

    val cachedFile: File? get() = repository.cachedPdfFile

    fun selectTool(tool: MainActivity.ActiveTool) {
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun setColor(color: Int) {
        _uiState.update { it.copy(strokeColor = color) }
    }

    fun setStrokeWidth(width: Float) {
        _uiState.update { it.copy(strokeWidth = width) }
    }

    fun setReadingMode(mode: ReadingMode) {
        _uiState.update { it.copy(readingMode = mode) }
    }

    fun toggleImmersiveMode() {
        _uiState.update { it.copy(isImmersiveMode = !it.isImmersiveMode) }
    }

    fun loadPdf(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            StrokeManager.globalStrokes.clear()
            StrokeManager.redoStrokes.clear()

            val uriString = uri.toString()
            _uiState.update { it.copy(activeDocumentUri = uriString) }

            launch {
                noteDao.getNotesForDocument(uriString).collect { notes ->
                    _uiState.update { it.copy(studyNotes = notes) }
                }
            }

            val file = repository.createWorkingCopy(uri)
            if (file != null) {
                _uiState.update { it.copy(isPdfLoaded = true) }
                onReady()
            }
        }
    }

    fun searchPdf(query: String, onResult: (List<Int>) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            val results = repository.searchPdfForText(query)
            _uiState.update { it.copy(isSearching = false) }
            onResult(results)
        }
    }

    fun savePdf() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = null) }
            val success = repository.saveAnnotationsToPdf(StrokeManager.globalStrokes)
            _uiState.update { it.copy(isSaving = false, saveSuccess = success) }
        }
    }

    fun resetSaveState() {
        _uiState.update { it.copy(saveSuccess = null) }
    }

    fun saveNote(note: StudyNote) {
        viewModelScope.launch(Dispatchers.IO) { noteDao.insertNote(note) }
    }

    fun deleteNote(note: StudyNote) {
        viewModelScope.launch(Dispatchers.IO) { noteDao.deleteNote(note) }
    }
}