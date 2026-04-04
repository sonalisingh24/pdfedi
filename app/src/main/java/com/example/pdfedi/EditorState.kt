package com.example.pdfedi

import android.graphics.Color
import com.example.pdfedi.database.StudyNote

enum class ReadingMode { NORMAL, SEPIA, DARK }

data class EditorState(
    val activeTool: MainActivity.ActiveTool = MainActivity.ActiveTool.HAND,
    val strokeColor: Int = Color.parseColor("#F44336"),
    val strokeWidth: Float = 8f,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean? = null,
    val isPdfLoaded: Boolean = false,
    val readingMode: ReadingMode = ReadingMode.NORMAL,
    val isImmersiveMode: Boolean = false,
    val activeDocumentUri: String = "",
    val studyNotes: List<StudyNote> = emptyList(),
    val isSearching: Boolean = false
) {
    val isDrawingMode: Boolean
        get() = activeTool != MainActivity.ActiveTool.HAND && activeTool != MainActivity.ActiveTool.NOTE
}