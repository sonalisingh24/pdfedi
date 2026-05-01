package com.example.pdfedi

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Paint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.artifex.mupdf.fitz.Document
import com.example.pdfedi.database.AppDatabase
import com.example.pdfedi.database.StudyNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PdfRepository(application)
    private val prefs = application.getSharedPreferences("PdfEditorGlobalSettings", Context.MODE_PRIVATE)
    private val noteDao = AppDatabase.getDatabase(application).noteDao()

    private val _uiState = MutableStateFlow(EditorState())
    val uiState: StateFlow<EditorState> = _uiState.asStateFlow()

    private val _activeNotes = MutableStateFlow<List<StudyNote>>(emptyList())
    val activeNotes: StateFlow<List<StudyNote>> = _activeNotes.asStateFlow()

    private val _customBookmarks = MutableStateFlow<List<ReaderBookmark>>(emptyList())
    val customBookmarks: StateFlow<List<ReaderBookmark>> = _customBookmarks.asStateFlow()

    private val _outlineEntries = MutableStateFlow<List<OutlineEntry>>(emptyList())
    val outlineEntries: StateFlow<List<OutlineEntry>> = _outlineEntries.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchResults: StateFlow<List<SearchHit>> = _searchResults.asStateFlow()

    val mupdfDocument: Document? get() = repository.mupdfDocument

    private var searchJob: Job? = null

    init {
        val savedColor = prefs.getInt("savedStrokeColor", Color.parseColor("#F44336"))
        val savedWidth = prefs.getFloat("savedStrokeWidth", 8f)
        val savedReadingMode = prefs.getString("savedReadingMode", ReadingMode.NORMAL.name)
        val savedLayoutMode = prefs.getString("savedLayoutMode", NavigationLayoutMode.CONTINUOUS_VERTICAL.name)
        val savedLineLock = prefs.getBoolean("savedLineLock", false)
        val savedEraserTargetMode = prefs.getString("savedEraserTargetMode", EraseTargetMode.ALL.name)

        _uiState.update {
            it.copy(
                strokeColor = savedColor,
                strokeWidth = savedWidth,
                readingMode = parseEnum(savedReadingMode, ReadingMode.NORMAL),
                navigationLayoutMode = parseEnum(savedLayoutMode, NavigationLayoutMode.CONTINUOUS_VERTICAL),
                isLineLockEnabled = savedLineLock,
                eraserTargetMode = parseEnum(savedEraserTargetMode, EraseTargetMode.ALL)
            )
        }
    }

    fun enterEditMode() {
        _uiState.update { it.copy(isEditMode = true) }
        refreshSessionState()
    }

    fun discardChanges() {
        StrokeManager.discardSession()
        refreshSessionState()
        _uiState.update { it.copy(isEditMode = false, activeTool = MainActivity.ActiveTool.NONE) }
    }

    fun savePdf() {
        val session = StrokeManager.sessionState()
        val documentUri = _uiState.value.activeDocumentUri

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = null) }

            val success = repository.saveAnnotationsToPdf(session)
            if (success) {
                withContext(Dispatchers.IO) {
                    noteDao.deleteNotesForDocument(documentUri)
                    noteDao.insertNotes(session.notes.map { it.copy(id = 0, documentUri = documentUri) })
                }
                saveBookmarks(documentUri, session.bookmarks)
                StrokeManager.markSaved()
            }

            refreshSessionState()
            _uiState.update {
                it.copy(
                    isSaving = false,
                    saveSuccess = success,
                    isEditMode = if (success) false else it.isEditMode,
                    activeTool = if (success) MainActivity.ActiveTool.NONE else it.activeTool
                )
            }
        }
    }

    fun resetSaveState() {
        _uiState.update { it.copy(saveSuccess = null) }
    }

    fun selectTool(tool: MainActivity.ActiveTool) {
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun selectEraser() {
        val isObjectEraser = prefs.getBoolean("isEraserObject", true)
        val tool = if (isObjectEraser) MainActivity.ActiveTool.ERASER_OBJECT else MainActivity.ActiveTool.ERASER_PIXEL
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun setEraserMode(isObject: Boolean) {
        prefs.edit().putBoolean("isEraserObject", isObject).apply()
        val tool = if (isObject) MainActivity.ActiveTool.ERASER_OBJECT else MainActivity.ActiveTool.ERASER_PIXEL
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun setEraserTargetMode(mode: EraseTargetMode) {
        prefs.edit().putString("savedEraserTargetMode", mode.name).apply()
        _uiState.update { it.copy(eraserTargetMode = mode) }
    }

    fun setColor(color: Int) {
        prefs.edit().putInt("savedStrokeColor", color).apply()
        _uiState.update { it.copy(strokeColor = color) }
    }

    fun setStrokeWidth(width: Float) {
        prefs.edit().putFloat("savedStrokeWidth", width).apply()
        _uiState.update { it.copy(strokeWidth = width.coerceAtLeast(2f)) }
    }

    fun setReadingMode(mode: ReadingMode) {
        prefs.edit().putString("savedReadingMode", mode.name).apply()
        _uiState.update { it.copy(readingMode = mode) }
    }

    fun setNavigationLayoutMode(mode: NavigationLayoutMode) {
        prefs.edit().putString("savedLayoutMode", mode.name).apply()
        _uiState.update { it.copy(navigationLayoutMode = mode) }
    }

    fun setLineLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("savedLineLock", enabled).apply()
        _uiState.update { it.copy(isLineLockEnabled = enabled) }
    }

    fun setSearchVisible(visible: Boolean) {
        _uiState.update { state ->
            if (!visible) {
                state.copy(
                    isSearching = false,
                    searchQuery = "",
                    searchResultCount = 0,
                    selectedSearchResultIndex = -1
                )
            } else {
                state.copy(isSearching = true)
            }
        }
        if (!visible) {
            searchJob?.cancel()
            _searchResults.value = emptyList()
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, isSearching = true) }
        searchJob?.cancel()

        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            _searchResults.value = emptyList()
            _uiState.update { it.copy(searchResultCount = 0, selectedSearchResultIndex = -1) }
            return
        }

        searchJob = viewModelScope.launch {
            val results = repository.searchDocument(cleanQuery)
            _searchResults.value = results
            _uiState.update {
                it.copy(
                    searchResultCount = results.size,
                    selectedSearchResultIndex = if (results.isEmpty()) -1 else 0
                )
            }
        }
    }

    fun selectNextSearchResult() {
        moveSearchSelection(1)
    }

    fun selectPreviousSearchResult() {
        moveSearchSelection(-1)
    }

    fun currentSearchResult(): SearchHit? {
        val index = _uiState.value.selectedSearchResultIndex
        return _searchResults.value.getOrNull(index)
    }

    fun onPageChanged(pageIndex: Int) {
        _uiState.update { it.copy(currentPageIndex = pageIndex.coerceAtLeast(0)) }
    }

    fun loadPdf(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            StrokeManager.clearDocument()
            searchJob?.cancel()
            _searchResults.value = emptyList()
            _outlineEntries.value = emptyList()

            val documentUri = uri.toString()
            val loaded = repository.createWorkingCopy(uri, documentUri)

            if (loaded != null) {
                val persistedNotes = withContext(Dispatchers.IO) {
                    noteDao.getNotesForDocumentOnce(documentUri)
                }
                val mergedNotes = mergeNotes(persistedNotes, loaded.notes, documentUri)
                val bookmarks = loadBookmarks(documentUri)

                StrokeManager.beginDocument(
                    strokes = loaded.strokes,
                    highlights = loaded.textHighlights,
                    textBoxes = loaded.textBoxes,
                    notes = mergedNotes,
                    bookmarks = bookmarks
                )

                _outlineEntries.value = loaded.outlineEntries
                refreshSessionState()

                _uiState.update {
                    it.copy(
                        isPdfLoaded = true,
                        activeDocumentUri = documentUri,
                        pageCount = loaded.document.countPages(),
                        currentPageIndex = 0,
                        isSearching = false,
                        searchQuery = "",
                        searchResultCount = 0,
                        selectedSearchResultIndex = -1,
                        activeTool = MainActivity.ActiveTool.NONE
                    )
                }

                onReady()
            }
        }
    }

    private fun calculateTextBoxHeight(text: String, fontSize: Float, width: Float): Float {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = fontSize }
        val padding = 12f
        val textWidth = (width - padding * 2).coerceAtLeast(50f)
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.2f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, textWidth.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.2f, 0f, false)
        }
        return layout.height + (padding * 2)
    }

    private fun clampRect(rect: PdfRect, pdfWidth: Float, pdfHeight: Float): PdfRect {
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top

        val clampedLeft = rect.left.coerceIn(0f, (pdfWidth - width).coerceAtLeast(0f))
        val clampedTop = rect.top.coerceIn(0f, (pdfHeight - height).coerceAtLeast(0f))

        return PdfRect(clampedLeft, clampedTop, clampedLeft + width, clampedTop + height)
    }

    fun addTextBox(pageIndex: Int, x: Float, y: Float, text: String, fontSize: Float, pdfWidth: Float, pdfHeight: Float): Set<Int> {
        val width = 250f
        val height = calculateTextBoxHeight(text, fontSize, width)

        val requestedRect = PdfRect(x, y, x + width, y + height)
        val clampedRect = clampRect(requestedRect, pdfWidth, pdfHeight)

        val textBox = TextBoxAnnotation(
            pageIndex = pageIndex,
            rect = clampedRect,
            text = text,
            color = _uiState.value.strokeColor,
            fontSize = fontSize
        )
        val changedPages = StrokeManager.addTextBox(textBox)
        refreshSessionState()
        return changedPages
    }

    fun updateTextBox(textBox: TextBoxAnnotation, pdfWidth: Float, pdfHeight: Float): Set<Int> {
        val width = textBox.rect.right - textBox.rect.left
        val height = calculateTextBoxHeight(textBox.text, textBox.fontSize, width)

        val requestedRect = PdfRect(textBox.rect.left, textBox.rect.top, textBox.rect.left + width, textBox.rect.top + height)
        val clampedRect = clampRect(requestedRect, pdfWidth, pdfHeight)

        val updatedBox = textBox.copy(rect = clampedRect)
        val changedPages = StrokeManager.updateTextBox(updatedBox)
        refreshSessionState()
        return changedPages
    }

    fun deleteTextBox(textBox: TextBoxAnnotation): Set<Int> {
        val changedPages = StrokeManager.deleteTextBox(textBox)
        refreshSessionState()
        return changedPages
    }

    fun saveNote(note: StudyNote): Set<Int> {
        val changedPages = StrokeManager.upsertNote(note)
        refreshSessionState()
        return changedPages
    }

    fun deleteNote(note: StudyNote): Set<Int> {
        val changedPages = StrokeManager.deleteNote(note)
        refreshSessionState()
        return changedPages
    }

    fun clearCurrentPage(pageIndex: Int): Set<Int> {
        val changedPages = StrokeManager.clearPage(pageIndex)
        refreshSessionState()
        return changedPages
    }

    fun addBookmark(pageIndex: Int, title: String): ReaderBookmark {
        val bookmark = ReaderBookmark(pageIndex = pageIndex, title = title)
        StrokeManager.addBookmark(bookmark)
        refreshSessionState()
        return bookmark
    }

    fun removeBookmark(bookmark: ReaderBookmark) {
        StrokeManager.removeBookmark(bookmark)
        refreshSessionState()
    }

    fun refreshSessionState() {
        _activeNotes.value = StrokeManager.notes
        _customBookmarks.value = StrokeManager.bookmarks
        _uiState.update {
            it.copy(
                canUndo = StrokeManager.canUndo(),
                canRedo = StrokeManager.canRedo(),
                isDirty = StrokeManager.isDirty()
            )
        }
    }

    fun undo(): Set<Int> {
        val changedPages = StrokeManager.undo()
        refreshSessionState()
        return changedPages
    }

    fun redo(): Set<Int> {
        val changedPages = StrokeManager.redo()
        refreshSessionState()
        return changedPages
    }

    fun loadSignatureTemplate(): SignatureTemplate? {
        val raw = prefs.getString("savedSignatureTemplate", null) ?: return null
        return try {
            val root = JSONObject(raw)
            val aspectRatio = root.optDouble("aspectRatio", 3.2).toFloat()
            val strokesJson = root.optJSONArray("strokes") ?: JSONArray()
            val strokes = buildList {
                for (i in 0 until strokesJson.length()) {
                    val strokeJson = strokesJson.getJSONArray(i)
                    val stroke = buildList {
                        for (j in 0 until strokeJson.length()) {
                            val point = strokeJson.getJSONObject(j)
                            add(PdfPoint(point.getDouble("x").toFloat(), point.getDouble("y").toFloat()))
                        }
                    }
                    if (stroke.isNotEmpty()) add(stroke)
                }
            }
            if (strokes.isEmpty()) null else SignatureTemplate(strokes, aspectRatio)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveSignatureTemplate(template: SignatureTemplate) {
        val root = JSONObject().apply {
            put("aspectRatio", template.aspectRatio.toDouble())
            put("strokes", JSONArray().apply {
                template.strokes.forEach { stroke ->
                    put(JSONArray().apply {
                        stroke.forEach { point ->
                            put(JSONObject().apply {
                                put("x", point.x.toDouble())
                                put("y", point.y.toDouble())
                            })
                        }
                    })
                }
            })
        }
        prefs.edit().putString("savedSignatureTemplate", root.toString()).apply()
    }

    private fun moveSearchSelection(offset: Int) {
        val results = _searchResults.value
        if (results.isEmpty()) return

        val currentIndex = _uiState.value.selectedSearchResultIndex.takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + offset).floorMod(results.size)
        _uiState.update { it.copy(selectedSearchResultIndex = nextIndex) }
    }

    private fun mergeNotes(
        persistedNotes: List<StudyNote>,
        embeddedNotes: List<StudyNote>,
        documentUri: String
    ): List<StudyNote> {
        if (persistedNotes.isEmpty()) {
            return embeddedNotes.map { it.copy(documentUri = documentUri) }
        }

        val unique = linkedMapOf<String, StudyNote>()
        (embeddedNotes + persistedNotes).forEach { note ->
            val normalized = note.copy(documentUri = documentUri)
            val key = "${normalized.pageIndex}:${normalized.x}:${normalized.y}:${normalized.textContent}"
            unique[key] = normalized
        }
        return unique.values.toList()
    }

    private fun loadBookmarks(documentUri: String): List<ReaderBookmark> {
        val raw = prefs.getString(bookmarkKey(documentUri), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        ReaderBookmark(
                            id = item.optLong("id", newAnnotationId()),
                            pageIndex = item.getInt("pageIndex"),
                            title = item.getString("title"),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveBookmarks(documentUri: String, bookmarks: List<ReaderBookmark>) {
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            array.put(JSONObject().apply {
                put("id", bookmark.id)
                put("pageIndex", bookmark.pageIndex)
                put("title", bookmark.title)
                put("createdAt", bookmark.createdAt)
            })
        }
        prefs.edit().putString(bookmarkKey(documentUri), array.toString()).apply()
    }

    private fun bookmarkKey(documentUri: String): String = "bookmarks_${documentUri.hashCode()}"

    private fun <T : Enum<T>> parseEnum(name: String?, fallback: T): T {
        return try {
            java.lang.Enum.valueOf(fallback.declaringJavaClass, name ?: fallback.name)
        } catch (e: Exception) {
            fallback
        }
    }
}

private fun Int.floorMod(size: Int): Int {
    if (size <= 0) return 0
    val mod = this % size
    return if (mod < 0) mod + size else mod
}