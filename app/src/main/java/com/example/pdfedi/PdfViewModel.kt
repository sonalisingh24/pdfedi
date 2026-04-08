package com.example.pdfedi

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdfedi.database.AppDatabase
import com.example.pdfedi.database.StudyNote
import com.artifex.mupdf.fitz.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PdfRepository(application)
    private val noteDao = AppDatabase.getDatabase(application).noteDao()

    // 1. Initialize SharedPreferences for global app state
    private val prefs = application.getSharedPreferences("PdfEditorGlobalSettings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(EditorState())
    val uiState: StateFlow<EditorState> = _uiState.asStateFlow()

    val mupdfDocument: Document? get() = repository.mupdfDocument

    init {
        // 2. Load saved settings instantly when app opens
        val savedColor = prefs.getInt("savedStrokeColor", android.graphics.Color.parseColor("#F44336"))
        val savedWidth = prefs.getFloat("savedStrokeWidth", 8f)
        val savedReadingModeName = prefs.getString("savedReadingMode", ReadingMode.NORMAL.name)
        val savedReadingMode = try {
            ReadingMode.valueOf(savedReadingModeName ?: ReadingMode.NORMAL.name)
        } catch (e: Exception) {
            ReadingMode.NORMAL
        }

        _uiState.update {
            it.copy(strokeColor = savedColor, strokeWidth = savedWidth, readingMode = savedReadingMode)
        }
    }

    fun selectTool(tool: MainActivity.ActiveTool) {
        _uiState.update { it.copy(activeTool = tool) }
    }

    // 3. Select Eraser handles the saved mode (Stroke vs Pixel)
    fun selectEraser() {
        val isObjectEraser = prefs.getBoolean("isEraserObject", true)
        val tool = if (isObjectEraser) MainActivity.ActiveTool.ERASER_OBJECT else MainActivity.ActiveTool.ERASER_PIXEL
        _uiState.update { it.copy(activeTool = tool) }
    }

    // 4. Save settings to memory immediately when changed
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

    fun loadPdf(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            val uriString = uri.toString()
            _uiState.update { it.copy(activeDocumentUri = uriString) }
            launch { noteDao.getNotesForDocument(uriString).collect { notes -> _uiState.update { it.copy(studyNotes = notes) } } }

            val doc = repository.createWorkingCopy(uri)
            if (doc != null) {
                _uiState.update { it.copy(isPdfLoaded = true) }
                onReady()
            }
        }
    }

    fun searchPdf(query: String, onResult: (List<Int>) -> Unit) { /* Unchanged */ }
    fun savePdf() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = null) }
            val success = repository.saveAnnotationsToPdf(StrokeManager.globalStrokes)
            _uiState.update { it.copy(isSaving = false, saveSuccess = success) }
        }
    }
    fun resetSaveState() { _uiState.update { it.copy(saveSuccess = null) } }
    fun saveNote(note: StudyNote) { viewModelScope.launch(Dispatchers.IO) { noteDao.insertNote(note) } }
}