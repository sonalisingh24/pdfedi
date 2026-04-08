package com.example.pdfedi

import android.graphics.Color

enum class ReadingMode { NORMAL, SEPIA, DARK }

data class EditorState(
    val isEditMode: Boolean = false,
    val activeTool: MainActivity.ActiveTool = MainActivity.ActiveTool.MARKER,
    val strokeColor: Int = Color.parseColor("#F44336"),
    val strokeWidth: Float = 8f,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean? = null,
    val isPdfLoaded: Boolean = false,
    val readingMode: ReadingMode = ReadingMode.NORMAL,
    val isImmersiveMode: Boolean = false,
    val activeDocumentUri: String = "",
    val isSearching: Boolean = false
) {
    val isDrawingMode: Boolean = true
}