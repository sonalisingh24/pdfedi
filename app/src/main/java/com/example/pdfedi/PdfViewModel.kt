package com.example.pdfedi

import android.app.Application
import android.content.Context
import com.example.pdfedi.database.StudyNote
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfedi.database.AppDatabase
import com.artifex.mupdf.fitz.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PdfRepository(application)
    private val prefs = application.getSharedPreferences("PdfEditorGlobalSettings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(EditorState())
    val uiState: StateFlow<EditorState> = _uiState.asStateFlow()
    val mupdfDocument: Document? get() = repository.mupdfDocument

    private val noteDao = AppDatabase.getDatabase(application).noteDao()

    private val _activeNotes = MutableStateFlow<List<StudyNote>>(emptyList())
    val activeNotes: StateFlow<List<StudyNote>> = _activeNotes.asStateFlow()

    private val notesPendingDeletion = mutableListOf<StudyNote>()

    private var previousTool = MainActivity.ActiveTool.MARKER

    init {
        val savedColor = prefs.getInt("savedStrokeColor", android.graphics.Color.parseColor("#F44336"))
        val savedWidth = prefs.getFloat("savedStrokeWidth", 8f)
        val savedReadingModeName = prefs.getString("savedReadingMode", ReadingMode.NORMAL.name)
        val savedReadingMode = try { ReadingMode.valueOf(savedReadingModeName ?: ReadingMode.NORMAL.name) } catch (e: Exception) { ReadingMode.NORMAL }

        _uiState.update {
            it.copy(strokeColor = savedColor, strokeWidth = savedWidth, readingMode = savedReadingMode)
        }
    }

    // Mode Transitions
    fun enterEditMode() {
        StrokeManager.startSession()
        _uiState.update { it.copy(isEditMode = true) }
    }

    fun discardChanges() {
        StrokeManager.discardSession()
        notesPendingDeletion.clear()

        viewModelScope.launch(Dispatchers.IO) {
            val uriString = _uiState.value.activeDocumentUri
            if (uriString.isNotEmpty()) {
                noteDao.getNotesForDocument(uriString).collect { notes ->
                    _activeNotes.value = notes
                }
            }
        }
        _uiState.update { it.copy(isEditMode = false) }
    }
    fun savePdf() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = null) }

            withContext(Dispatchers.IO) {
                for (note in notesPendingDeletion) {
                    noteDao.deleteNote(note)
                }
                notesPendingDeletion.clear()
            }

            val success = repository.saveAnnotationsToPdf(StrokeManager.globalStrokes, _activeNotes.value)
            if (success) StrokeManager.clearAll()

            _uiState.update {
                it.copy(
                    isSaving = false,
                    saveSuccess = success,
                    isEditMode = if (success) false else it.isEditMode
                )
            }
        }
    }
    fun selectTool(tool: MainActivity.ActiveTool) {
        if (tool != MainActivity.ActiveTool.ERASER_OBJECT && tool != MainActivity.ActiveTool.ERASER_PIXEL) {
            previousTool = tool
        }
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun selectEraser() {
        val isObjectEraser = prefs.getBoolean("isEraserObject", true)
        val tool = if (isObjectEraser) MainActivity.ActiveTool.ERASER_OBJECT else MainActivity.ActiveTool.ERASER_PIXEL
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun onEraseCompleted() {
        _uiState.update { it.copy(activeTool = previousTool) }
    }

    fun setEraserMode(isObject: Boolean) {
        prefs.edit().putBoolean("isEraserObject", isObject).apply()
        val tool = if (isObject) MainActivity.ActiveTool.ERASER_OBJECT else MainActivity.ActiveTool.ERASER_PIXEL
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun setColor(color: Int) {
        prefs.edit().putInt("savedStrokeColor", color).apply()
        _uiState.update { it.copy(strokeColor = color) }
    }

    fun setStrokeWidth(width: Float) {
        prefs.edit().putFloat("savedStrokeWidth", width).apply()
        _uiState.update { it.copy(strokeWidth = width) }
    }

    fun setReadingMode(mode: ReadingMode) {
        prefs.edit().putString("savedReadingMode", mode.name).apply()
        _uiState.update { it.copy(readingMode = mode) }
    }

    fun saveNoteToDatabase(note: StudyNote) {
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.insertNote(note)
        }
    }

    fun deleteNote(note: StudyNote) {
        notesPendingDeletion.add(note)
        val currentList = _activeNotes.value.toMutableList()
        currentList.remove(note)
        _activeNotes.value = currentList
    }

    fun loadPdf(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            val uriString = uri.toString()
            _uiState.update { it.copy(activeDocumentUri = uriString) }
            val doc = repository.createWorkingCopy(uri)
            if (doc != null) {
                _uiState.update { it.copy(isPdfLoaded = true) }
                onReady()

                launch(Dispatchers.IO) {
                    noteDao.getNotesForDocument(uriString).collect { notes ->
                        _activeNotes.value = notes
                    }
                }
            }
        }
    }

    fun resetSaveState() { _uiState.update { it.copy(saveSuccess = null) } }
}