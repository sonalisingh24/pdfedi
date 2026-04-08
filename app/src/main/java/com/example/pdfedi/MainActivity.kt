package com.example.pdfedi

import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    enum class ActiveTool { MARKER, HIGHLIGHTER, ERASER_OBJECT, ERASER_PIXEL }

    // Top Bar
    private lateinit var topBarCard: View
    private lateinit var btnBack: View
    private lateinit var tvDocumentTitle: android.widget.TextView
    private lateinit var btnSearch: View
    private lateinit var btnMode: View
    private lateinit var btnSave: View

    // Tool Options
    private lateinit var toolOptionsCard: View
    private lateinit var optionsPen: View
    private lateinit var optionsEraser: View
    private lateinit var sliderThickness: android.widget.SeekBar
    private lateinit var btnEraserStroke: MaterialButton
    private lateinit var btnEraserPixel: MaterialButton

    // Floating Tools
    private lateinit var bottomToolPill: View
    private lateinit var btnUndo: View
    private lateinit var btnRedo: View
    private lateinit var toolPen: MaterialButton
    private lateinit var toolHighlighter: MaterialButton
    private lateinit var toolEraser: MaterialButton
    private lateinit var fabEdit: FloatingActionButton

    private lateinit var pdfRecyclerView: ZoomableRecyclerView
    private val viewModel: PdfViewModel by viewModels()

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.loadPdf(it) { displayPdf() } }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showExitWarningDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, backCallback)

        initViews()
        setupClickListeners()
        observeViewModel()

        val pdfPath = intent.getStringExtra("PDF_PATH")
        if (pdfPath != null) {
            val file = File(pdfPath)
            tvDocumentTitle.text = file.name
            viewModel.loadPdf(Uri.fromFile(file)) { displayPdf() }
        } else {
            openDocumentLauncher.launch(arrayOf("application/pdf"))
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (toolOptionsCard.visibility == View.VISIBLE) {
                val rect = Rect()
                toolOptionsCard.getGlobalVisibleRect(rect)
                val bottomPillRect = Rect()
                bottomToolPill.getGlobalVisibleRect(bottomPillRect)

                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt()) &&
                    !bottomPillRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    toolOptionsCard.visibility = View.GONE
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun initViews() {
        topBarCard = findViewById(R.id.top_bar_card)
        ViewCompat.setOnApplyWindowInsetsListener(topBarCard) { view: View, insets: WindowInsetsCompat ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val layoutParams = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            layoutParams.topMargin = statusBarInsets.top + 32
            view.layoutParams = layoutParams
            insets
        }

        btnBack = findViewById(R.id.btn_back)
        tvDocumentTitle = findViewById(R.id.tv_document_title)
        btnSearch = findViewById(R.id.btn_search)
        btnMode = findViewById(R.id.btn_mode)
        btnSave = findViewById(R.id.btn_save)

        toolOptionsCard = findViewById(R.id.tool_options_card)
        optionsPen = findViewById(R.id.options_pen)
        optionsEraser = findViewById(R.id.options_eraser)
        sliderThickness = findViewById(R.id.slider_thickness)
        btnEraserStroke = findViewById(R.id.btn_eraser_stroke)
        btnEraserPixel = findViewById(R.id.btn_eraser_pixel)

        bottomToolPill = findViewById(R.id.bottom_tool_pill)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        toolPen = findViewById(R.id.tool_pen)
        toolHighlighter = findViewById(R.id.tool_highlighter)
        toolEraser = findViewById(R.id.tool_eraser)

        fabEdit = findViewById(R.id.fab_edit)

        pdfRecyclerView = findViewById(R.id.pdf_recycler_view)
        pdfRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            if (viewModel.uiState.value.isEditMode) showExitWarningDialog() else finish()
        }

        fabEdit.setOnClickListener { viewModel.enterEditMode() }
        btnSave.setOnClickListener { viewModel.savePdf() }

        btnMode.setOnClickListener {
            val currentMode = viewModel.uiState.value.readingMode
            val nextMode = when(currentMode) {
                ReadingMode.NORMAL -> ReadingMode.SEPIA
                ReadingMode.SEPIA -> ReadingMode.DARK
                ReadingMode.DARK -> ReadingMode.NORMAL
            }
            viewModel.setReadingMode(nextMode)
        }

        btnUndo.setOnClickListener { val p = StrokeManager.undo(); if (p != null) refreshSpecificPage(p) }
        btnRedo.setOnClickListener { val p = StrokeManager.redo(); if (p != null) refreshSpecificPage(p) }

        // --- NEW LOGIC: Toggle options if already active, otherwise select the tool ---
        toolPen.setOnClickListener {
            if (viewModel.uiState.value.activeTool == ActiveTool.MARKER) toggleOptions(ActiveTool.MARKER)
            else viewModel.selectTool(ActiveTool.MARKER)
        }

        toolHighlighter.setOnClickListener {
            if (viewModel.uiState.value.activeTool == ActiveTool.HIGHLIGHTER) toggleOptions(ActiveTool.HIGHLIGHTER)
            else viewModel.selectTool(ActiveTool.HIGHLIGHTER)
        }

        toolEraser.setOnClickListener {
            val isEraser = viewModel.uiState.value.activeTool == ActiveTool.ERASER_OBJECT ||
                    viewModel.uiState.value.activeTool == ActiveTool.ERASER_PIXEL
            if (isEraser) toggleOptions(ActiveTool.ERASER_OBJECT)
            else viewModel.selectEraser()
        }

        btnEraserStroke.setOnClickListener { viewModel.setEraserMode(true) }
        btnEraserPixel.setOnClickListener { viewModel.setEraserMode(false) }

        sliderThickness.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.setStrokeWidth(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        findViewById<View>(R.id.color_black).setOnClickListener { viewModel.setColor(Color.parseColor("#212121")) }
        findViewById<View>(R.id.color_red).setOnClickListener { viewModel.setColor(Color.parseColor("#F44336")) }
        findViewById<View>(R.id.color_blue).setOnClickListener { viewModel.setColor(Color.parseColor("#2196F3")) }
        findViewById<View>(R.id.color_green).setOnClickListener { viewModel.setColor(Color.parseColor("#4CAF50")) }
        findViewById<View>(R.id.color_yellow).setOnClickListener { viewModel.setColor(Color.parseColor("#FFEB3B")) }
    }

    // --- HELPER FUNCTION: Toggles the options card visibility ---
    private fun toggleOptions(tool: ActiveTool) {
        if (toolOptionsCard.visibility == View.VISIBLE) {
            toolOptionsCard.visibility = View.GONE
        } else {
            toolOptionsCard.visibility = View.VISIBLE
            if (tool == ActiveTool.MARKER || tool == ActiveTool.HIGHLIGHTER) {
                optionsPen.visibility = View.VISIBLE
                optionsEraser.visibility = View.GONE
            } else {
                optionsPen.visibility = View.GONE
                optionsEraser.visibility = View.VISIBLE
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                backCallback.isEnabled = state.isEditMode

                if (sliderThickness.progress != state.strokeWidth.toInt()) {
                    sliderThickness.progress = state.strokeWidth.toInt()
                }

                updateToolSelectionUI(state)
                applySettingsToCanvas(state)

                if (state.saveSuccess != null) {
                    if (state.saveSuccess) {
                        Toast.makeText(this@MainActivity, "Saved Successfully!", Toast.LENGTH_SHORT).show()
                        (pdfRecyclerView.adapter as? PdfPageAdapter)?.clearCache()
                    }
                    viewModel.resetSaveState()
                }
            }
        }
    }

    private fun updateToolSelectionUI(state: EditorState) {
        if (state.isEditMode) {
            fabEdit.visibility = View.GONE
            bottomToolPill.visibility = View.VISIBLE
            btnUndo.visibility = View.VISIBLE
            btnRedo.visibility = View.VISIBLE
            btnSave.visibility = View.VISIBLE

            btnSearch.visibility = View.GONE
            btnMode.visibility = View.GONE
        } else {
            fabEdit.visibility = View.VISIBLE
            bottomToolPill.visibility = View.GONE
            toolOptionsCard.visibility = View.GONE
            btnUndo.visibility = View.GONE
            btnRedo.visibility = View.GONE
            btnSave.visibility = View.GONE

            btnSearch.visibility = View.VISIBLE
            btnMode.visibility = View.VISIBLE
        }

        val inactiveColor = Color.parseColor("#757575")
        val activeBg = Color.parseColor("#2196F3")
        val activeIcon = Color.WHITE
        val tools = listOf(toolPen, toolHighlighter, toolEraser)

        for (t in tools) {
            t.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            t.iconTint = android.content.res.ColorStateList.valueOf(inactiveColor)
        }

        if (!state.isEditMode) return

        val tool = state.activeTool
        if (tool == ActiveTool.MARKER || tool == ActiveTool.HIGHLIGHTER) {
            toolOptionsCard.visibility = View.VISIBLE
            optionsPen.visibility = View.VISIBLE
            optionsEraser.visibility = View.GONE
        } else if (tool == ActiveTool.ERASER_OBJECT || tool == ActiveTool.ERASER_PIXEL) {
            toolOptionsCard.visibility = View.VISIBLE
            optionsPen.visibility = View.GONE
            optionsEraser.visibility = View.VISIBLE

            if (tool == ActiveTool.ERASER_OBJECT) {
                btnEraserStroke.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                btnEraserStroke.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
                btnEraserPixel.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                btnEraserPixel.iconTint = android.content.res.ColorStateList.valueOf(inactiveColor)
            } else {
                btnEraserPixel.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                btnEraserPixel.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
                btnEraserStroke.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                btnEraserStroke.iconTint = android.content.res.ColorStateList.valueOf(inactiveColor)
            }
        }

        when (tool) {
            ActiveTool.MARKER -> {
                toolPen.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolPen.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
            }
            ActiveTool.HIGHLIGHTER -> {
                toolHighlighter.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolHighlighter.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
            }
            ActiveTool.ERASER_OBJECT, ActiveTool.ERASER_PIXEL -> {
                toolEraser.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolEraser.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
            }
        }
    }

    private fun showExitWarningDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unsaved Changes")
            .setMessage("You are currently in write mode. Do you want to save your changes before exiting?")
            .setPositiveButton("Save") { _, _ -> viewModel.savePdf() }
            .setNegativeButton("Discard") { _, _ ->
                viewModel.discardChanges()
                (pdfRecyclerView.adapter as? PdfPageAdapter)?.clearCache()
                for (i in 0 until pdfRecyclerView.childCount) {
                    pdfRecyclerView.getChildAt(i).findViewById<CustomDrawView>(R.id.drawView)?.invalidate()
                }
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun applySettingsToCanvas(state: EditorState) {
        pdfRecyclerView.isDrawingMode = state.isEditMode && state.isDrawingMode
        val adapter = pdfRecyclerView.adapter as? PdfPageAdapter
        if (adapter != null) adapter.currentState = state

        for (i in 0 until pdfRecyclerView.childCount) {
            val drawView = pdfRecyclerView.getChildAt(i).findViewById<CustomDrawView>(R.id.drawView)
            if (drawView != null) {
                drawView.isDrawingEnabled = state.isEditMode && state.isDrawingMode
                drawView.currentDrawColor = state.strokeColor
                drawView.currentStrokeWidth = state.strokeWidth
                drawView.isEraserObject = state.activeTool == ActiveTool.ERASER_OBJECT
                drawView.isEraserPixel = state.activeTool == ActiveTool.ERASER_PIXEL
                drawView.isHighlighter = state.activeTool == ActiveTool.HIGHLIGHTER
            }
        }
    }

    private fun displayPdf() {
        viewModel.mupdfDocument?.let { document ->
            val adapter = PdfPageAdapter(document, document.countPages())
            adapter.currentState = viewModel.uiState.value
            adapter.onEraseCompleted = { viewModel.onEraseCompleted() }
            pdfRecyclerView.adapter = adapter
            applySettingsToCanvas(viewModel.uiState.value)
        }
    }

    private fun refreshSpecificPage(pageIndex: Int) {
        for (i in 0 until pdfRecyclerView.childCount) {
            val drawView = pdfRecyclerView.getChildAt(i).findViewById<CustomDrawView>(R.id.drawView)
            if (drawView?.pageIndex == pageIndex) drawView.invalidate()
        }
    }
}