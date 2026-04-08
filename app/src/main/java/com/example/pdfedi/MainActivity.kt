package com.example.pdfedi

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import com.example.pdfedi.database.StudyNote

class MainActivity : AppCompatActivity() {

    enum class ActiveTool { HAND, MARKER, HIGHLIGHTER, ERASER_OBJECT, ERASER_PIXEL, NOTE, TEXT_HIGHLIGHTER }

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
    private lateinit var btnUndo: View
    private lateinit var btnRedo: View
    private lateinit var toolHand: MaterialButton
    private lateinit var toolPen: MaterialButton
    private lateinit var toolHighlighter: MaterialButton
    private lateinit var toolTextHl: MaterialButton
    private lateinit var toolEraser: MaterialButton
    private lateinit var toolNote: MaterialButton

    private lateinit var pdfRecyclerView: ZoomableRecyclerView
    private val viewModel: PdfViewModel by viewModels()

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.loadPdf(it) { displayPdf() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make the app perfectly edge-to-edge
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)

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

    private fun initViews() {
        topBarCard = findViewById(R.id.top_bar_card)

        // Push the floating top bar safely below the device notch/status bar dynamically
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

        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        toolHand = findViewById(R.id.tool_hand)
        toolPen = findViewById(R.id.tool_pen)
        toolHighlighter = findViewById(R.id.tool_highlighter)
        toolTextHl = findViewById(R.id.tool_text_hl)
        toolEraser = findViewById(R.id.tool_eraser)
        toolNote = findViewById(R.id.tool_note)

        pdfRecyclerView = findViewById(R.id.pdf_recycler_view)
        pdfRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { viewModel.savePdf() }
        btnSearch.setOnClickListener { showSearchDialog() }

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

        // Main Tools Restored!
        toolHand.setOnClickListener { viewModel.selectTool(ActiveTool.HAND) }
        toolPen.setOnClickListener { viewModel.selectTool(ActiveTool.MARKER) }
        toolHighlighter.setOnClickListener { viewModel.selectTool(ActiveTool.HIGHLIGHTER) }
        toolTextHl.setOnClickListener { viewModel.selectTool(ActiveTool.TEXT_HIGHLIGHTER) }
        toolNote.setOnClickListener { viewModel.selectTool(ActiveTool.NOTE) }
        toolEraser.setOnClickListener { viewModel.selectEraser() }

        // Sub-Menus
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

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                if (sliderThickness.progress != state.strokeWidth.toInt()) {
                    sliderThickness.progress = state.strokeWidth.toInt()
                }

                updateToolSelectionUI(state.activeTool)
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

    private fun updateToolSelectionUI(tool: ActiveTool) {
        val inactiveColor = Color.parseColor("#757575") // Subtle grey
        val activeBg = Color.parseColor("#2196F3") // Primary Blue
        val activeIcon = Color.WHITE

        // 1. Reset all tools to transparent backgrounds
        val tools = listOf(toolHand, toolPen, toolHighlighter, toolEraser)
        for (t in tools) {
            t.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            t.iconTint = android.content.res.ColorStateList.valueOf(inactiveColor)
        }

        toolTextHl.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        toolTextHl.setTextColor(inactiveColor)

        toolNote.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        toolNote.setTextColor(inactiveColor)

        // 2. Toggle the Pop-up Option Cards
        if (tool == ActiveTool.MARKER || tool == ActiveTool.HIGHLIGHTER || tool == ActiveTool.TEXT_HIGHLIGHTER) {
            toolOptionsCard.visibility = View.VISIBLE
            optionsPen.visibility = View.VISIBLE
            optionsEraser.visibility = View.GONE
        } else if (tool == ActiveTool.ERASER_OBJECT || tool == ActiveTool.ERASER_PIXEL) {
            toolOptionsCard.visibility = View.VISIBLE
            optionsPen.visibility = View.GONE
            optionsEraser.visibility = View.VISIBLE

            if (tool == ActiveTool.ERASER_OBJECT) {
                btnEraserStroke.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                btnEraserStroke.setTextColor(activeIcon)
                btnEraserPixel.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                btnEraserPixel.setTextColor(inactiveColor)
            } else {
                btnEraserPixel.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                btnEraserPixel.setTextColor(activeIcon)
                btnEraserStroke.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                btnEraserStroke.setTextColor(inactiveColor)
            }
        } else {
            toolOptionsCard.visibility = View.GONE
        }

        // 3. Highlight the Active Tool Button
        when (tool) {
            ActiveTool.HAND -> {
                toolHand.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolHand.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
            }
            ActiveTool.MARKER -> {
                toolPen.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolPen.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
            }
            ActiveTool.HIGHLIGHTER -> {
                toolHighlighter.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolHighlighter.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
            }
            ActiveTool.TEXT_HIGHLIGHTER -> {
                toolTextHl.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolTextHl.setTextColor(activeIcon)
            }
            ActiveTool.ERASER_OBJECT, ActiveTool.ERASER_PIXEL -> {
                toolEraser.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolEraser.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
            }
            ActiveTool.NOTE -> {
                toolNote.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolNote.setTextColor(activeIcon)
            }
        }
    }

    private fun applySettingsToCanvas(state: EditorState) {
        pdfRecyclerView.isDrawingMode = state.isDrawingMode
        val adapter = pdfRecyclerView.adapter as? PdfPageAdapter

        if (adapter != null) {
            adapter.currentState = state
        }

        for (i in 0 until pdfRecyclerView.childCount) {
            val drawView = pdfRecyclerView.getChildAt(i).findViewById<CustomDrawView>(R.id.drawView)
            if (drawView != null) {
                drawView.isDrawingEnabled = state.isDrawingMode
                drawView.currentDrawColor = state.strokeColor
                drawView.currentStrokeWidth = state.strokeWidth
                drawView.isEraserObject = state.activeTool == ActiveTool.ERASER_OBJECT
                drawView.isEraserPixel = state.activeTool == ActiveTool.ERASER_PIXEL
                drawView.isHighlighter = state.activeTool == ActiveTool.HIGHLIGHTER
                drawView.isTextHighlighter = state.activeTool == ActiveTool.TEXT_HIGHLIGHTER
            }
        }
    }

    private fun displayPdf() {
        viewModel.mupdfDocument?.let { document ->
            val adapter = PdfPageAdapter(document, document.countPages())
            adapter.currentState = viewModel.uiState.value
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

    private fun showSearchDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Search Textbook")

        val input = android.widget.EditText(this)
        input.hint = "Enter keyword..."
        builder.setView(input)

        builder.setPositiveButton("Search") { dialog, _ ->
            val query = input.text.toString()
            if (query.isNotBlank()) {
                Toast.makeText(this, "Searching...", Toast.LENGTH_SHORT).show()
                viewModel.searchPdf(query) { matchingPages ->
                    if (matchingPages.isNotEmpty()) {
                        pdfRecyclerView.scrollToPosition(matchingPages[0])
                        Toast.makeText(this, "Found on page ${matchingPages[0] + 1}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "No matches found.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }
}