package com.example.pdfedi

import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
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
import com.example.pdfedi.database.StudyNote
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    enum class ActiveTool { NONE, MARKER, HIGHLIGHTER, ERASER_OBJECT, ERASER_PIXEL, COMMENT }

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
    private var toolComment: MaterialButton? = null // Safely nullable until added to XML
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
        val uriData = intent.data

        if (pdfPath != null) {
            val file = File(pdfPath)
            tvDocumentTitle.text = file.name
            viewModel.loadPdf(Uri.fromFile(file)) { displayPdf() }
        } else if (uriData != null) {
            tvDocumentTitle.text = "External PDF"
            viewModel.loadPdf(uriData) { displayPdf() }
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

        toolComment = findViewById(R.id.tool_comment)

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

        toolPen.setOnClickListener {
            if (viewModel.uiState.value.activeTool == ActiveTool.MARKER) toggleOptions(ActiveTool.MARKER)
            else viewModel.selectTool(ActiveTool.MARKER)
        }

        // --- PEN ---
        toolPen.setOnClickListener {
            val currentState = viewModel.uiState.value
            if (currentState.activeTool == ActiveTool.MARKER) {
                viewModel.selectTool(ActiveTool.NONE) // Turn off to allow scrolling
                toolOptionsCard.visibility = View.GONE
            } else {
                viewModel.selectTool(ActiveTool.MARKER)
            }
        }
        toolPen.setOnLongClickListener {
            viewModel.selectTool(ActiveTool.MARKER)
            toggleOptions(ActiveTool.MARKER) // Open color/thickness menu
            true
        }

        // --- HIGHLIGHTER ---
        toolHighlighter.setOnClickListener {
            val currentState = viewModel.uiState.value
            if (currentState.activeTool == ActiveTool.HIGHLIGHTER) {
                viewModel.selectTool(ActiveTool.NONE)
                toolOptionsCard.visibility = View.GONE
            } else {
                viewModel.selectTool(ActiveTool.HIGHLIGHTER)
            }
        }
        toolHighlighter.setOnLongClickListener {
            viewModel.selectTool(ActiveTool.HIGHLIGHTER)
            toggleOptions(ActiveTool.HIGHLIGHTER)
            true
        }

        toolEraser.setOnClickListener {
            val currentState = viewModel.uiState.value
            val isEraserActive = currentState.activeTool == ActiveTool.ERASER_OBJECT || currentState.activeTool == ActiveTool.ERASER_PIXEL
            if (isEraserActive) {
                viewModel.selectTool(ActiveTool.NONE)
                toolOptionsCard.visibility = View.GONE
            } else {
                viewModel.selectEraser()
            }
        }
        toolEraser.setOnLongClickListener {
            viewModel.selectEraser()
            toggleOptions(ActiveTool.ERASER_OBJECT)
            true
        }

        toolComment?.setOnClickListener {
            val currentState = viewModel.uiState.value
            if (currentState.activeTool == ActiveTool.COMMENT) {
                viewModel.selectTool(ActiveTool.NONE)
            } else {
                viewModel.selectTool(ActiveTool.COMMENT)
            }
            toolOptionsCard.visibility = View.GONE
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

        lifecycleScope.launch {
            viewModel.activeNotes.collectLatest { notes ->
                val adapter = pdfRecyclerView.adapter as? PdfPageAdapter
                if (adapter != null) {
                    adapter.activeNotes = notes

                    for (i in 0 until pdfRecyclerView.childCount) {
                        val drawView = pdfRecyclerView.getChildAt(i).findViewById<CustomDrawView>(R.id.drawView)
                        if (drawView != null) {
                            drawView.activeNotes = notes.filter { it.pageIndex == drawView.pageIndex }
                        }
                    }
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

        val inactiveColor = Color.parseColor("#424242")

        val activeBg = Color.parseColor("#2196F3")
        val activeIcon = Color.WHITE
        val tools = listOfNotNull(toolPen, toolHighlighter, toolEraser, toolComment)

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
            ActiveTool.COMMENT -> {
                toolComment?.backgroundTintList = android.content.res.ColorStateList.valueOf(activeBg)
                toolComment?.iconTint = android.content.res.ColorStateList.valueOf(activeIcon)
            }
            ActiveTool.NONE -> {
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
                drawView.isCommentTool = state.activeTool == ActiveTool.COMMENT && state.isEditMode
            }
        }
    }

    private fun displayPdf() {
        viewModel.mupdfDocument?.let { document ->
            val adapter = PdfPageAdapter(document, document.countPages())
            adapter.currentState = viewModel.uiState.value
            adapter.onEraseCompleted = { viewModel.onEraseCompleted() }

            adapter.activeNotes = viewModel.activeNotes.value

            adapter.onEmptySpaceTapped = { pageIndex, pdfX, pdfY ->
                showCommentDialog(pageIndex, pdfX, pdfY, null)
            }
            adapter.onNoteTapped = { note ->
                showCommentDialog(note.pageIndex, note.x, note.y, note)
            }

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

    private fun showCommentDialog(pageIndex: Int, pdfX: Float, pdfY: Float, existingNote: StudyNote? = null) {
        val isEditMode = viewModel.uiState.value.isEditMode

        // Inflate our custom sticky-note layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_comment, null)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogTitle)
        val etInput = dialogView.findViewById<EditText>(R.id.etCommentInput)
        val tvRead = dialogView.findViewById<android.widget.TextView>(R.id.tvCommentRead)

        val builder = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setBackground(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        if (isEditMode) {
            tvTitle.text = if (existingNote == null) "New Note" else "Edit Note"
            etInput.visibility = View.VISIBLE
            tvRead.visibility = View.GONE
            etInput.setText(existingNote?.textContent ?: "")
            etInput.requestFocus()

            builder.setPositiveButton("Save") { _, _ ->
                val text = etInput.text.toString()
                if (text.isNotBlank()) {
                    val note = existingNote?.copy(textContent = text)
                        ?: StudyNote(
                            documentUri = viewModel.uiState.value.activeDocumentUri,
                            pageIndex = pageIndex,
                            x = pdfX,
                            y = pdfY,
                            textContent = text
                       )
                    viewModel.saveNoteToDatabase(note)
                }
            }
            builder.setNegativeButton("Cancel", null)

            if (existingNote != null) {
                builder.setNeutralButton("Delete") { _, _ ->
                    viewModel.deleteNote(existingNote)
                }
            }
        } else {
            tvTitle.text = "View Note"
            etInput.visibility = View.GONE
            tvRead.visibility = View.VISIBLE
            tvRead.text = existingNote?.textContent ?: "No content."
            tvRead.movementMethod = android.text.method.ScrollingMovementMethod()

            builder.setPositiveButton("Close", null)
        }

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#F57F17"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#757575"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.parseColor("#D32F2F"))
    }
}