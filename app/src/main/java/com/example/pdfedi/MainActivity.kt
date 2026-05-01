package com.example.pdfedi

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import com.example.pdfedi.database.StudyNote
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    enum class ActiveTool {
        NONE,
        MARKER,
        HIGHLIGHTER,
        ERASER_OBJECT,
        ERASER_PIXEL,
        COMMENT,
        TEXT_BOX,
        SIGNATURE
    }

    private lateinit var topBarCard: View
    private lateinit var btnBack: View
    private lateinit var tvDocumentTitle: TextView
    private lateinit var btnSearch: View
    private lateinit var btnMode: View
    private lateinit var btnSave: View

    private lateinit var searchCard: View
    private lateinit var etSearchQuery: EditText
    private lateinit var tvSearchCount: TextView
    private lateinit var btnSearchPrev: View
    private lateinit var btnSearchNext: View
    private lateinit var btnSearchClose: View

    private lateinit var toolOptionsCard: View
    private lateinit var optionsPen: View
    private lateinit var optionsEraser: View
    private lateinit var sliderThickness: com.google.android.material.slider.Slider
    private lateinit var btnCustomColor: MaterialButton
    private lateinit var switchLineLock: SwitchCompat
    private lateinit var btnEraserStroke: MaterialButton
    private lateinit var btnEraserPixel: MaterialButton
    private lateinit var btnEraseAll: MaterialButton
    private lateinit var btnEraseInk: MaterialButton
    private lateinit var btnEraseHighlights: MaterialButton
    private lateinit var btnEraseText: MaterialButton
    private lateinit var btnClearPage: MaterialButton

    private lateinit var bottomToolPill: View
    private lateinit var btnUndo: View
    private lateinit var btnRedo: View
    private lateinit var toolPen: MaterialButton
    private lateinit var toolHighlighter: MaterialButton
    private lateinit var toolEraser: MaterialButton
    private lateinit var toolComment: MaterialButton
    private lateinit var toolText: MaterialButton
    private lateinit var toolSignature: MaterialButton
    private lateinit var fabEdit: FloatingActionButton

    private lateinit var pageIndicatorChip: View
    private lateinit var tvPageIndicator: TextView

    private lateinit var pdfRecyclerView: ZoomableRecyclerView
    private var pageAdapter: PdfPageAdapter? = null
    private var pagerSnapHelper: PagerSnapHelper? = null

    private var pendingFinishAfterSave = false
    private var lastSelectedSearchIndex = -2
    private var suppressSearchTextCallback = false

    private val viewModel: PdfViewModel by viewModels()

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.loadPdf(it) { displayPdf() } }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            handleExitRequest()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, backCallback)

        initViews()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        val pdfPath = intent.getStringExtra("PDF_PATH")
        val uriData = intent.data

        when {
            pdfPath != null -> {
                val file = File(pdfPath)
                tvDocumentTitle.text = file.name
                viewModel.loadPdf(Uri.fromFile(file)) { displayPdf() }
            }

            uriData != null -> {
                tvDocumentTitle.text = "External PDF"
                viewModel.loadPdf(uriData) { displayPdf() }
            }

            else -> openDocumentLauncher.launch(arrayOf("application/pdf"))
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && toolOptionsCard.visibility == View.VISIBLE) {
            val optionsRect = android.graphics.Rect()
            toolOptionsCard.getGlobalVisibleRect(optionsRect)
            val bottomRect = android.graphics.Rect()
            bottomToolPill.getGlobalVisibleRect(bottomRect)

            if (!optionsRect.contains(ev.rawX.toInt(), ev.rawY.toInt()) &&
                !bottomRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
            ) {
                animateUIChanges()
                toolOptionsCard.visibility = View.GONE
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun initViews() {
        topBarCard = findViewById(R.id.top_bar_card)
        searchCard = findViewById(R.id.search_card)
        pageIndicatorChip = findViewById(R.id.page_indicator_chip)

        ViewCompat.setOnApplyWindowInsetsListener(topBarCard) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())

            val topParams = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            topParams.topMargin = statusBarInsets.top + 32
            view.layoutParams = topParams

            val searchParams = searchCard.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            searchParams.topMargin = statusBarInsets.top + 100
            searchCard.layoutParams = searchParams

            insets
        }

        btnBack = findViewById(R.id.btn_back)
        tvDocumentTitle = findViewById(R.id.tv_document_title)
        btnSearch = findViewById(R.id.btn_search)
        btnMode = findViewById(R.id.btn_mode)
        btnSave = findViewById(R.id.btn_save)

        etSearchQuery = findViewById(R.id.et_search_query)
        tvSearchCount = findViewById(R.id.tv_search_count)
        btnSearchPrev = findViewById(R.id.btn_search_prev)
        btnSearchNext = findViewById(R.id.btn_search_next)
        btnSearchClose = findViewById(R.id.btn_search_close)

        toolOptionsCard = findViewById(R.id.tool_options_card)
        optionsPen = findViewById(R.id.options_pen)
        optionsEraser = findViewById(R.id.options_eraser)
        sliderThickness = findViewById(R.id.slider_thickness)

        sliderThickness.apply {
            valueFrom = 2.0f
            valueTo = 30.0f
            stepSize = 1.0f
            value = 8.0f
        }
        btnCustomColor = findViewById(R.id.btn_custom_color)
        switchLineLock = findViewById(R.id.switch_line_lock)
        btnEraserStroke = findViewById(R.id.btn_eraser_stroke)
        btnEraserPixel = findViewById(R.id.btn_eraser_pixel)
        btnEraseAll = findViewById(R.id.btn_erase_all)
        btnEraseInk = findViewById(R.id.btn_erase_ink)
        btnEraseHighlights = findViewById(R.id.btn_erase_highlights)
        btnEraseText = findViewById(R.id.btn_erase_text)
        btnClearPage = findViewById(R.id.btn_clear_page)

        bottomToolPill = findViewById(R.id.bottom_tool_pill)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        toolPen = findViewById(R.id.tool_pen)
        toolHighlighter = findViewById(R.id.tool_highlighter)
        toolEraser = findViewById(R.id.tool_eraser)
        toolComment = findViewById(R.id.tool_comment)
        toolText = findViewById(R.id.tool_text)
        toolSignature = findViewById(R.id.tool_signature)
        fabEdit = findViewById(R.id.fab_edit)

        tvPageIndicator = findViewById(R.id.tv_page_indicator)

        pdfRecyclerView = findViewById(R.id.pdf_recycler_view)
    }

    private fun setupRecyclerView() {
        pdfRecyclerView.layoutManager = LinearLayoutManager(this)
        pdfRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                updateCurrentPageFromViewport()
            }
        })
        pdfRecyclerView.onScaleSettled = { scale ->
            pageAdapter?.renderScale = (scale * 2.5f).coerceIn(2.5f, 5f)
        }
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener { handleExitRequest() }
        fabEdit.setOnClickListener { viewModel.enterEditMode() }
        btnSave.setOnClickListener {
            pendingFinishAfterSave = false
            viewModel.savePdf()
        }

        btnSearch.setOnClickListener {
            searchCard.visibility = View.VISIBLE
            viewModel.setSearchVisible(true)
            etSearchQuery.requestFocus()
        }
        btnSearchClose.setOnClickListener {
            searchCard.visibility = View.GONE
            suppressSearchTextCallback = true
            etSearchQuery.setText("")
            suppressSearchTextCallback = false
            viewModel.setSearchVisible(false)
        }
        btnSearchPrev.setOnClickListener { viewModel.selectPreviousSearchResult() }
        btnSearchNext.setOnClickListener { viewModel.selectNextSearchResult() }
        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!suppressSearchTextCallback) {
                    viewModel.updateSearchQuery(s?.toString().orEmpty())
                }
            }
        })

        btnMode.setOnClickListener { showNavigationMenu() }
        pageIndicatorChip.setOnClickListener { showPageJumpDialog() }

        btnUndo.setOnClickListener {
            val changedPages = viewModel.undo()
            refreshPages(changedPages)
        }
        btnRedo.setOnClickListener {
            val changedPages = viewModel.redo()
            refreshPages(changedPages)
        }

        toolPen.setOnClickListener {
            val isActive = viewModel.uiState.value.activeTool == ActiveTool.MARKER
            if (isActive) {
                viewModel.selectTool(ActiveTool.NONE)
                toolOptionsCard.visibility = View.GONE
            } else {
                viewModel.selectTool(ActiveTool.MARKER)
            }
        }
        toolPen.setOnLongClickListener {
            viewModel.selectTool(ActiveTool.MARKER)
            showPenOptions()
            true
        }

        toolHighlighter.setOnClickListener {
            val isActive = viewModel.uiState.value.activeTool == ActiveTool.HIGHLIGHTER
            if (isActive) {
                viewModel.selectTool(ActiveTool.NONE)
                toolOptionsCard.visibility = View.GONE
            } else {
                viewModel.selectTool(ActiveTool.HIGHLIGHTER)
            }
        }
        toolHighlighter.setOnLongClickListener {
            viewModel.selectTool(ActiveTool.HIGHLIGHTER)
            showPenOptions()
            true
        }

        toolEraser.setOnClickListener {
            val state = viewModel.uiState.value
            val isActive = state.activeTool == ActiveTool.ERASER_OBJECT || state.activeTool == ActiveTool.ERASER_PIXEL
            if (isActive) {
                viewModel.selectTool(ActiveTool.NONE)
                toolOptionsCard.visibility = View.GONE
            } else {
                viewModel.selectEraser()
            }
        }
        toolEraser.setOnLongClickListener {
            viewModel.selectEraser()
            showEraserOptions()
            true
        }

        toolComment.setOnClickListener {
            toggleSimpleTool(ActiveTool.COMMENT)
        }

        toolText.setOnClickListener {
            toggleSimpleTool(ActiveTool.TEXT_BOX)
        }

        toolSignature.setOnClickListener {
            if (viewModel.loadSignatureTemplate() == null) {
                showSignatureDialog(selectToolAfterSave = true)
            } else {
                toggleSimpleTool(ActiveTool.SIGNATURE)
            }
        }
        toolSignature.setOnLongClickListener {
            showSignatureDialog(selectToolAfterSave = false)
            true
        }

        btnEraserStroke.setOnClickListener { viewModel.setEraserMode(true) }
        btnEraserPixel.setOnClickListener { viewModel.setEraserMode(false) }
        btnEraseAll.setOnClickListener { viewModel.setEraserTargetMode(EraseTargetMode.ALL) }
        btnEraseInk.setOnClickListener { viewModel.setEraserTargetMode(EraseTargetMode.INK_ONLY) }
        btnEraseHighlights.setOnClickListener { viewModel.setEraserTargetMode(EraseTargetMode.HIGHLIGHTS_ONLY) }
        btnEraseText.setOnClickListener { viewModel.setEraserTargetMode(EraseTargetMode.TEXT_ONLY) }
        btnClearPage.setOnClickListener { confirmClearCurrentPage() }

        sliderThickness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setStrokeWidth(value)
            }
        }

        btnCustomColor.setOnClickListener { showCustomColorDialog() }
        switchLineLock.setOnCheckedChangeListener { _, isChecked ->
            if (switchLineLock.isPressed) {
                viewModel.setLineLockEnabled(isChecked)
            }
        }

        findViewById<View>(R.id.color_black).setOnClickListener { viewModel.setColor(Color.parseColor("#212121")) }
        findViewById<View>(R.id.color_red).setOnClickListener { viewModel.setColor(Color.parseColor("#F44336")) }
        findViewById<View>(R.id.color_blue).setOnClickListener { viewModel.setColor(Color.parseColor("#2196F3")) }
        findViewById<View>(R.id.color_green).setOnClickListener { viewModel.setColor(Color.parseColor("#4CAF50")) }
        findViewById<View>(R.id.color_yellow).setOnClickListener { viewModel.setColor(Color.parseColor("#FFEB3B")) }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                backCallback.isEnabled = state.isDirty || state.isEditMode

                val minVal = if (sliderThickness.valueFrom > 0f) sliderThickness.valueFrom else 2.0f
                val maxVal = if (sliderThickness.valueTo > 0f) sliderThickness.valueTo else 30.0f

                val safeWidth = state.strokeWidth.coerceIn(minVal, maxVal)
                if (sliderThickness.value != safeWidth) {
                    sliderThickness.value = safeWidth
                }
                if (switchLineLock.isChecked != state.isLineLockEnabled) {
                    switchLineLock.isChecked = state.isLineLockEnabled
                }
                searchCard.visibility = if (state.isSearching) View.VISIBLE else View.GONE
                if (etSearchQuery.text.toString() != state.searchQuery && !suppressSearchTextCallback) {
                    suppressSearchTextCallback = true
                    etSearchQuery.setText(state.searchQuery)
                    etSearchQuery.setSelection(state.searchQuery.length)
                    suppressSearchTextCallback = false
                }

                tvSearchCount.text = if (state.searchResultCount == 0) {
                    "0/0"
                } else {
                    "${state.selectedSearchResultIndex + 1}/${state.searchResultCount}"
                }

                updateToolSelectionUI(state)
                applySettingsToCanvas(state)
                applyNavigationLayout(state.navigationLayoutMode)
                updatePageIndicator(state.currentPageIndex, state.pageCount)

                if (state.selectedSearchResultIndex != lastSelectedSearchIndex) {
                    lastSelectedSearchIndex = state.selectedSearchResultIndex
                    val selected = viewModel.currentSearchResult()
                    pageAdapter?.selectedSearchHitId = selected?.id
                    if (selected != null) {
                        scrollToPage(selected.pageIndex, smooth = true)
                    }
                    invalidateVisiblePages()
                }

                if (state.saveSuccess != null) {
                    if (state.saveSuccess) {
                        Toast.makeText(this@MainActivity, "Saved successfully", Toast.LENGTH_SHORT).show()
                        invalidateVisiblePages()
                        if (pendingFinishAfterSave) {
                            finish()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Unable to save PDF", Toast.LENGTH_SHORT).show()
                    }
                    pendingFinishAfterSave = false
                    viewModel.resetSaveState()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.searchResults.collectLatest { results ->
                pageAdapter?.searchResults = results
                pageAdapter?.selectedSearchHitId = viewModel.currentSearchResult()?.id
                invalidateVisiblePages()
            }
        }
    }

    private fun displayPdf() {
        val document = viewModel.mupdfDocument ?: return
        val adapter = PdfPageAdapter(document, document.countPages())
        adapter.currentState = viewModel.uiState.value
        adapter.onSessionChanged = { changedPages ->
            viewModel.refreshSessionState()
            refreshPages(changedPages)
        }
        adapter.onCommentRequested = { pageIndex, pdfX, pdfY ->
            showCommentDialog(pageIndex, pdfX, pdfY, null)
        }
        adapter.onNoteTapped = { note ->
            showCommentDialog(note.pageIndex, note.x, note.y, note)
        }
        adapter.onTextBoxRequested = { pageIndex, pdfX, pdfY ->
            showTextBoxDialog(pageIndex, pdfX, pdfY, null)
        }
        adapter.onTextBoxTapped = { textBox ->
            showTextBoxDialog(textBox.pageIndex, textBox.rect.left, textBox.rect.top, textBox)
        }
        adapter.signatureTemplateProvider = { viewModel.loadSignatureTemplate() }
        adapter.searchResults = viewModel.searchResults.value
        adapter.selectedSearchHitId = viewModel.currentSearchResult()?.id

        pageAdapter = adapter
        pdfRecyclerView.adapter = adapter
        applyNavigationLayout(viewModel.uiState.value.navigationLayoutMode)
        applySettingsToCanvas(viewModel.uiState.value)
        updateCurrentPageFromViewport()
    }

    private fun applySettingsToCanvas(state: EditorState) {
        pdfRecyclerView.isDrawingMode = state.isEditMode && state.isDrawingMode
        pageAdapter?.currentState = state

        for (i in 0 until pdfRecyclerView.childCount) {
            val drawView = pdfRecyclerView.getChildAt(i).findViewById<CustomDrawView>(R.id.drawView) ?: continue
            drawView.isEditMode = state.isEditMode
            drawView.isDrawingEnabled = state.isEditMode && state.isDrawingMode
            drawView.currentDrawColor = state.strokeColor
            drawView.currentStrokeWidth = state.strokeWidth
            drawView.isEraserObject = state.activeTool == ActiveTool.ERASER_OBJECT
            drawView.isEraserPixel = state.activeTool == ActiveTool.ERASER_PIXEL
            drawView.isHighlighter = state.activeTool == ActiveTool.HIGHLIGHTER
            drawView.isCommentTool = state.activeTool == ActiveTool.COMMENT && state.isEditMode
            drawView.isTextBoxTool = state.activeTool == ActiveTool.TEXT_BOX && state.isEditMode
            drawView.isSignatureTool = state.activeTool == ActiveTool.SIGNATURE && state.isEditMode
            drawView.eraserTargetMode = state.eraserTargetMode
            drawView.isLineLockEnabled = state.isLineLockEnabled
        }
    }

    private fun applyNavigationLayout(mode: NavigationLayoutMode) {
        val orientation = if (mode == NavigationLayoutMode.CONTINUOUS_VERTICAL) {
            androidx.recyclerview.widget.RecyclerView.VERTICAL
        } else {
            androidx.recyclerview.widget.RecyclerView.HORIZONTAL
        }

        val currentManager = pdfRecyclerView.layoutManager as? LinearLayoutManager
        if (currentManager?.orientation == orientation) return

        val currentPage = viewModel.uiState.value.currentPageIndex
        pdfRecyclerView.layoutManager = LinearLayoutManager(this, orientation, false)

        if (mode == NavigationLayoutMode.HORIZONTAL_PAGED) {
            if (pagerSnapHelper == null) pagerSnapHelper = PagerSnapHelper()
            pagerSnapHelper?.attachToRecyclerView(pdfRecyclerView)
        } else {
            pagerSnapHelper?.attachToRecyclerView(null)
        }

        pdfRecyclerView.resetTransform()
        pdfRecyclerView.scrollToPosition(currentPage)
    }

    private fun updateToolSelectionUI(state: EditorState) {
        if (state.isEditMode) {
            fabEdit.visibility = View.GONE
            bottomToolPill.visibility = View.VISIBLE
            btnUndo.visibility = View.VISIBLE
            btnRedo.visibility = View.VISIBLE
            btnSave.visibility = View.VISIBLE
            btnSearch.visibility = View.GONE
        } else {
            fabEdit.visibility = View.VISIBLE
            bottomToolPill.visibility = View.GONE
            toolOptionsCard.visibility = View.GONE
            btnUndo.visibility = View.GONE
            btnRedo.visibility = View.GONE
            btnSave.visibility = View.GONE
            btnSearch.visibility = View.VISIBLE
        }

        val inactiveBg = Color.parseColor("#182231")
        val inactiveIcon = Color.parseColor("#F8FBFF")
        val disabledIcon = Color.parseColor("#617087")
        val activeIcon = Color.parseColor("#08111F")
        val activeBlue = Color.parseColor("#38BDF8")
        val activeAmber = Color.parseColor("#FBBF24")
        val activeRed = Color.parseColor("#F87171")
        val activeViolet = Color.parseColor("#A78BFA")
        val activeGreen = Color.parseColor("#34D399")
        val activePink = Color.parseColor("#F472B6")
        val panelInactive = Color.parseColor("#172033")
        val panelActive = Color.parseColor("#2563EB")

        fun colorState(color: Int) = android.content.res.ColorStateList.valueOf(color)

        fun setDockButtonInactive(button: MaterialButton) {
            button.backgroundTintList = colorState(inactiveBg)
            button.iconTint = colorState(inactiveIcon)
        }

        fun setDockButtonActive(button: MaterialButton, accent: Int) {
            button.backgroundTintList = colorState(accent)
            button.iconTint = colorState(activeIcon)
        }

        val tools = listOf(toolPen, toolHighlighter, toolEraser, toolComment, toolText, toolSignature)
        tools.forEach { tool ->
            setDockButtonInactive(tool)
        }

        (btnUndo as? MaterialButton)?.apply {
            backgroundTintList = colorState(Color.parseColor("#243244"))
            iconTint = colorState(if (state.canUndo) inactiveIcon else disabledIcon)
            isEnabled = state.canUndo
            alpha = if (state.canUndo) 1f else 0.62f
        }
        (btnRedo as? MaterialButton)?.apply {
            backgroundTintList = colorState(Color.parseColor("#243244"))
            iconTint = colorState(if (state.canRedo) inactiveIcon else disabledIcon)
            isEnabled = state.canRedo
            alpha = if (state.canRedo) 1f else 0.62f
        }

        btnEraserStroke.backgroundTintList = colorState(Color.parseColor("#DCEBFF"))
        btnEraserPixel.backgroundTintList = colorState(Color.parseColor("#DCEBFF"))
        btnEraserStroke.iconTint = colorState(panelInactive)
        btnEraserPixel.iconTint = colorState(panelInactive)

        val targetButtons = mapOf(
            EraseTargetMode.ALL to btnEraseAll,
            EraseTargetMode.INK_ONLY to btnEraseInk,
            EraseTargetMode.HIGHLIGHTS_ONLY to btnEraseHighlights,
            EraseTargetMode.TEXT_ONLY to btnEraseText
        )
        targetButtons.values.forEach { button ->
            button.backgroundTintList = colorState(Color.TRANSPARENT)
            button.setTextColor(panelInactive)
            button.strokeColor = colorState(Color.parseColor("#B9C7DA"))
        }
        targetButtons[state.eraserTargetMode]?.let { button ->
            button.backgroundTintList = colorState(panelActive)
            button.setTextColor(Color.WHITE)
            button.strokeColor = colorState(panelActive)
        }

        if (!state.isEditMode) return

        when (state.activeTool) {
            ActiveTool.NONE -> {}

            ActiveTool.MARKER -> {
                setDockButtonActive(toolPen, activeBlue)
            }

            ActiveTool.HIGHLIGHTER -> {
                setDockButtonActive(toolHighlighter, activeAmber)
            }

            ActiveTool.ERASER_OBJECT, ActiveTool.ERASER_PIXEL -> {
                setDockButtonActive(toolEraser, activeRed)
                if (state.activeTool == ActiveTool.ERASER_OBJECT) {
                    btnEraserStroke.backgroundTintList = colorState(activeRed)
                    btnEraserStroke.iconTint = colorState(activeIcon)
                } else {
                    btnEraserPixel.backgroundTintList = colorState(activeRed)
                    btnEraserPixel.iconTint = colorState(activeIcon)
                }
            }

            ActiveTool.COMMENT -> {
                setDockButtonActive(toolComment, activeViolet)
            }

            ActiveTool.TEXT_BOX -> {
                setDockButtonActive(toolText, activeGreen)
            }

            ActiveTool.SIGNATURE -> {
                setDockButtonActive(toolSignature, activePink)
            }
        }

        if (state.activeTool == ActiveTool.MARKER || state.activeTool == ActiveTool.HIGHLIGHTER) {
            showPenOptions()
        } else if (state.activeTool == ActiveTool.ERASER_OBJECT || state.activeTool == ActiveTool.ERASER_PIXEL) {
            showEraserOptions()
        } else if (state.activeTool == ActiveTool.NONE) {
            toolOptionsCard.visibility = View.GONE
        }
    }

    private fun toggleSimpleTool(tool: ActiveTool) {
        val isActive = viewModel.uiState.value.activeTool == tool
        viewModel.selectTool(if (isActive) ActiveTool.NONE else tool)
        animateUIChanges()
        toolOptionsCard.visibility = View.GONE
    }

    private fun showPenOptions() {
        animateUIChanges()
        toolOptionsCard.visibility = View.VISIBLE
        optionsPen.visibility = View.VISIBLE
        optionsEraser.visibility = View.GONE
    }

    private fun showEraserOptions() {
        animateUIChanges()
        toolOptionsCard.visibility = View.VISIBLE
        optionsPen.visibility = View.GONE
        optionsEraser.visibility = View.VISIBLE
    }

    private fun updatePageIndicator(pageIndex: Int, pageCount: Int) {
        val safePage = if (pageCount == 0) 0 else pageIndex + 1
        tvPageIndicator.text = "Page $safePage / ${pageCount.coerceAtLeast(1)}"
    }

    private fun updateCurrentPageFromViewport() {
        val manager = pdfRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val snappedView = pagerSnapHelper?.findSnapView(manager)
        val pageIndex = when {
            snappedView != null -> manager.getPosition(snappedView)
            else -> manager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: 0
        }
        viewModel.onPageChanged(pageIndex)
    }

    private fun refreshPages(changedPages: Set<Int>) {
        if (changedPages.isEmpty()) return
        for (i in 0 until pdfRecyclerView.childCount) {
            val drawView = pdfRecyclerView.getChildAt(i).findViewById<CustomDrawView>(R.id.drawView) ?: continue
            if (drawView.pageIndex in changedPages) {
                drawView.invalidate()
            }
        }
    }

    private fun invalidateVisiblePages() {
        for (i in 0 until pdfRecyclerView.childCount) {
            pdfRecyclerView.getChildAt(i).findViewById<CustomDrawView>(R.id.drawView)?.invalidate()
        }
    }

    private fun handleExitRequest() {
        if (viewModel.uiState.value.isDirty) {
            showExitWarningDialog()
        } else {
            finish()
        }
    }

    private fun showExitWarningDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unsaved Changes")
            .setMessage("Save your document changes before leaving?")
            .setPositiveButton("Save") { _, _ ->
                pendingFinishAfterSave = true
                viewModel.savePdf()
            }
            .setNegativeButton("Discard") { _, _ ->
                viewModel.discardChanges()
                finish()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun confirmClearCurrentPage() {
        val pageIndex = viewModel.uiState.value.currentPageIndex
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear Page")
            .setMessage("Remove every editable annotation from the current page?")
            .setPositiveButton("Clear") { _, _ ->
                val changedPages = viewModel.clearCurrentPage(pageIndex)
                refreshPages(changedPages)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNavigationMenu() {
        val popup = PopupMenu(this, btnMode)
        popup.menu.add(Menu.NONE, 1, 1, "Normal theme")
        popup.menu.add(Menu.NONE, 2, 2, "Sepia theme")
        popup.menu.add(Menu.NONE, 3, 3, "Dark theme")
        popup.menu.add(Menu.NONE, 4, 4, "Continuous scroll")
        popup.menu.add(Menu.NONE, 5, 5, "Horizontal pages")
        popup.menu.add(Menu.NONE, 6, 6, "Jump to page")
        popup.menu.add(Menu.NONE, 7, 7, "Outline and bookmarks")
        popup.menu.add(Menu.NONE, 8, 8, "Add bookmark here")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> viewModel.setReadingMode(ReadingMode.NORMAL)
                2 -> viewModel.setReadingMode(ReadingMode.SEPIA)
                3 -> viewModel.setReadingMode(ReadingMode.DARK)
                4 -> viewModel.setNavigationLayoutMode(NavigationLayoutMode.CONTINUOUS_VERTICAL)
                5 -> viewModel.setNavigationLayoutMode(NavigationLayoutMode.HORIZONTAL_PAGED)
                6 -> showPageJumpDialog()
                7 -> showOutlineAndBookmarksDialog()
                8 -> addBookmarkForCurrentPage()
            }
            true
        }
        popup.show()
    }

    private fun showPageJumpDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((viewModel.uiState.value.currentPageIndex + 1).toString())
            setSelection(text.length)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Jump to Page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val requestedPage = input.text.toString().toIntOrNull()?.minus(1) ?: return@setPositiveButton
                scrollToPage(requestedPage.coerceIn(0, (viewModel.uiState.value.pageCount - 1).coerceAtLeast(0)), smooth = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOutlineAndBookmarksDialog() {
        val entries = mutableListOf<Pair<String, Int>>()

        viewModel.outlineEntries.value.forEach { entry ->
            val prefix = "  ".repeat(entry.level.coerceAtLeast(0))
            entries += "$prefix${entry.title}" to entry.pageIndex
        }

        if (viewModel.customBookmarks.value.isNotEmpty()) {
            viewModel.customBookmarks.value.forEach { bookmark ->
                entries += "[Bookmark] ${bookmark.title}" to bookmark.pageIndex
            }
        }

        if (entries.isEmpty()) {
            Toast.makeText(this, "No outline or bookmarks available", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = entries.map { it.first }.toTypedArray()
        val currentPage = viewModel.uiState.value.currentPageIndex
        val currentBookmark = viewModel.customBookmarks.value.firstOrNull { it.pageIndex == currentPage }

        MaterialAlertDialogBuilder(this)
            .setTitle("Outline & Bookmarks")
            .setItems(labels) { _, which ->
                scrollToPage(entries[which].second, smooth = true)
            }
            .setPositiveButton("Add Bookmark") { _, _ ->
                addBookmarkForCurrentPage()
            }
            .apply {
                if (currentBookmark != null) {
                    setNeutralButton("Remove Current") { _, _ ->
                        viewModel.removeBookmark(currentBookmark)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addBookmarkForCurrentPage() {
        val currentPage = viewModel.uiState.value.currentPageIndex
        val defaultTitle = "Page ${currentPage + 1}"
        val input = EditText(this).apply {
            setText(defaultTitle)
            setSelection(text.length)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("New Bookmark")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().ifBlank { defaultTitle }
                viewModel.addBookmark(currentPage, title)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomColorDialog() {
        val input = EditText(this).apply {
            hint = "#FF5722 or 255,87,34"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Custom Color")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                parseCustomColor(input.text.toString())?.let { color ->
                    viewModel.setColor(color)
                } ?: Toast.makeText(this, "Invalid color format", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCommentDialog(pageIndex: Int, pdfX: Float, pdfY: Float, existingNote: StudyNote?) {
        val isEditMode = viewModel.uiState.value.isEditMode
        val dialogView = layoutInflater.inflate(R.layout.dialog_comment, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val etInput = dialogView.findViewById<EditText>(R.id.etCommentInput)
        val tvRead = dialogView.findViewById<TextView>(R.id.tvCommentRead)

        val builder = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setBackground(ColorDrawable(Color.TRANSPARENT))

        if (isEditMode) {
            tvTitle.text = if (existingNote == null) "New Note" else "Edit Note"
            etInput.visibility = View.VISIBLE
            tvRead.visibility = View.GONE
            etInput.setText(existingNote?.textContent.orEmpty())

            builder.setPositiveButton("Save") { _, _ ->
                val text = etInput.text.toString().trim()
                if (text.isNotBlank()) {
                    val note = existingNote?.copy(textContent = text) ?: StudyNote(
                        documentUri = viewModel.uiState.value.activeDocumentUri,
                        pageIndex = pageIndex,
                        x = pdfX,
                        y = pdfY,
                        textContent = text
                    )
                    val changedPages = viewModel.saveNote(note)
                    refreshPages(changedPages)
                }
            }
            builder.setNegativeButton("Cancel", null)
            if (existingNote != null) {
                builder.setNeutralButton("Delete") { _, _ ->
                    val changedPages = viewModel.deleteNote(existingNote)
                    refreshPages(changedPages)
                }
            }
        } else {
            tvTitle.text = "View Note"
            etInput.visibility = View.GONE
            tvRead.visibility = View.VISIBLE
            tvRead.text = existingNote?.textContent ?: "No content."
            builder.setPositiveButton("Close", null)
        }

        val dialog = builder.create()
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#F57F17"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#757575"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.parseColor("#D32F2F"))
    }

    private fun showTextBoxDialog(pageIndex: Int, pdfX: Float, pdfY: Float, existing: TextBoxAnnotation?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 0)
        }
        val textInput = EditText(this).apply {
            hint = "Type text"
            minLines = 3
            setText(existing?.text.orEmpty())
        }
        val sizeInput = EditText(this).apply {
            hint = "Font size"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.fontSize ?: 16f).toInt().toString())
        }
        container.addView(textInput)
        container.addView(sizeInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add Text Box" else "Edit Text Box")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = textInput.text.toString().trim()
                val fontSize = sizeInput.text.toString().toFloatOrNull()?.coerceIn(10f, 48f) ?: 16f
                if (text.isBlank()) return@setPositiveButton

                val changedPages = if (existing == null) {
                    viewModel.addTextBox(pageIndex, pdfX, pdfY, text, fontSize)
                } else {
                    viewModel.updateTextBox(existing.copy(text = text, fontSize = fontSize))
                }
                refreshPages(changedPages)
            }
            .setNegativeButton("Cancel", null)
            .apply {
                if (existing != null) {
                    setNeutralButton("Delete") { _, _ ->
                        val changedPages = viewModel.deleteTextBox(existing)
                        refreshPages(changedPages)
                    }
                }
            }
            .show()
    }

    private fun showSignatureDialog(selectToolAfterSave: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_signature, null)
        val signaturePad = dialogView.findViewById<SignaturePadView>(R.id.signature_pad)
        val clearButton = dialogView.findViewById<MaterialButton>(R.id.btn_clear_signature)
        clearButton.setOnClickListener { signaturePad.clear() }

        MaterialAlertDialogBuilder(this)
            .setTitle("Signature")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val template = signaturePad.buildTemplate()
                if (template != null) {
                    viewModel.saveSignatureTemplate(template)
                    if (selectToolAfterSave) {
                        viewModel.selectTool(ActiveTool.SIGNATURE)
                    }
                } else {
                    Toast.makeText(this, "Draw a signature before saving", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scrollToPage(pageIndex: Int, smooth: Boolean) {
        val safePage = pageIndex.coerceIn(0, (viewModel.uiState.value.pageCount - 1).coerceAtLeast(0))
        if (smooth) {
            pdfRecyclerView.smoothScrollToPosition(safePage)
        } else {
            pdfRecyclerView.scrollToPosition(safePage)
        }
        viewModel.onPageChanged(safePage)
    }

    private fun parseCustomColor(raw: String): Int? {
        val value = raw.trim()
        return try {
            when {
                value.startsWith("#") -> Color.parseColor(value)
                value.contains(",") -> {
                    val parts = value.split(",").map { it.trim().toIntOrNull() ?: return null }
                    if (parts.size == 3) Color.rgb(parts[0], parts[1], parts[2]) else null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun animateUIChanges() {
        val container = findViewById<android.view.ViewGroup>(R.id.editor_ui_container)
        if (container != null) {
            android.transition.TransitionManager.beginDelayedTransition(container)
        }
    }
}
