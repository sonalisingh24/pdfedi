package com.example.pdfedi

import android.graphics.Color

enum class ReadingMode { NORMAL, SEPIA, DARK }

data class EditorState(
    val isEditMode: Boolean = false,
    val activeTool: MainActivity.ActiveTool = MainActivity.ActiveTool.NONE,
    val strokeColor: Int = Color.parseColor("#F44336"),
    val strokeWidth: Float = 8f,
    val readingMode: ReadingMode = ReadingMode.NORMAL,
    val navigationLayoutMode: NavigationLayoutMode = NavigationLayoutMode.CONTINUOUS_VERTICAL,
    val eraserTargetMode: EraseTargetMode = EraseTargetMode.ALL,
    val isLineLockEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean? = null,
    val isPdfLoaded: Boolean = false,
    val activeDocumentUri: String = "",
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val searchResultCount: Int = 0,
    val selectedSearchResultIndex: Int = -1,
    val currentPageIndex: Int = 0,
    val pageCount: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isDirty: Boolean = false
) {
    val isDrawingMode: Boolean
        get() = activeTool != MainActivity.ActiveTool.NONE
}
