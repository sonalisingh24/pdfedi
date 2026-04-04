package com.example.pdfedi

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import com.example.pdfedi.database.StudyNote

class MainActivity : AppCompatActivity() {

    enum class ActiveTool { HAND, MARKER, HIGHLIGHTER, ERASER, NOTE }

    // === UI Variables ===
    private lateinit var btnOpenPdf: ImageButton
    private lateinit var btnSavePdf: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton

    // Tool Palette
    private lateinit var btnHand: ImageButton
    private lateinit var btnMarker: ImageButton
    private lateinit var btnHighlighter: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnNote: Button

    // Settings
    private lateinit var btnColorRed: Button
    private lateinit var btnColorGreen: Button
    private lateinit var btnColorBlue: Button
    private lateinit var btnSizeThin: Button
    private lateinit var btnSizeMed: Button
    private lateinit var btnSizeThick: Button

    // Toggles
    private lateinit var btnToggleReadingMode: Button
    private lateinit var btnToggleImmersive: Button

    private lateinit var pdfRecyclerView: ZoomableRecyclerView
    private var pdfRenderer: PdfRenderer? = null

    // ViewModel Integration
    private val viewModel: PdfViewModel by viewModels()

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            viewModel.loadPdf(it) { displayPdf() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PDFBoxResourceLoader.init(applicationContext)

        initViews()
        setupClickListeners()
        observeViewModel()

        val pdfPath = intent.getStringExtra("PDF_PATH")
        if (pdfPath != null) {
            val file = File(pdfPath)
            viewModel.loadPdf(Uri.fromFile(file)) { displayPdf() }
        }
    }

    private fun initViews() {
        btnOpenPdf = findViewById(R.id.btnOpenPdf)
        btnSavePdf = findViewById(R.id.btnSavePdf)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)

        btnHand = findViewById(R.id.btnHand)
        btnMarker = findViewById(R.id.btnMarker)
        btnHighlighter = findViewById(R.id.btnHighlighter)
        btnEraser = findViewById(R.id.btnEraser)
        btnNote = findViewById(R.id.btnNote)

        btnColorRed = findViewById(R.id.btnColorRed)
        btnColorGreen = findViewById(R.id.btnColorGreen)
        btnColorBlue = findViewById(R.id.btnColorBlue)

        btnSizeThin = findViewById(R.id.btnSizeThin)
        btnSizeMed = findViewById(R.id.btnSizeMed)
        btnSizeThick = findViewById(R.id.btnSizeThick)

        btnToggleReadingMode = findViewById(R.id.btnToggleReadingMode)
        btnToggleImmersive = findViewById(R.id.btnToggleImmersive)

        pdfRecyclerView = findViewById(R.id.pdfRecyclerView)
        pdfRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupClickListeners() {
        btnOpenPdf.setOnClickListener { openDocumentLauncher.launch(arrayOf("application/pdf")) }
        btnSavePdf.setOnClickListener { viewModel.savePdf() }

        btnUndo.setOnClickListener {
            val page = StrokeManager.undo()
            if (page != null) refreshSpecificPage(page)
        }

        btnRedo.setOnClickListener {
            val page = StrokeManager.redo()
            if (page != null) refreshSpecificPage(page)
        }

        btnHand.setOnClickListener { viewModel.selectTool(ActiveTool.HAND) }
        btnMarker.setOnClickListener { viewModel.selectTool(ActiveTool.MARKER) }
        btnHighlighter.setOnClickListener { viewModel.selectTool(ActiveTool.HIGHLIGHTER) }
        btnEraser.setOnClickListener { viewModel.selectTool(ActiveTool.ERASER) }
        btnNote.setOnClickListener { viewModel.selectTool(ActiveTool.NOTE) }

        btnColorRed.setOnClickListener { viewModel.setColor(Color.parseColor("#F44336")) }
        btnColorGreen.setOnClickListener { viewModel.setColor(Color.parseColor("#4CAF50")) }
        btnColorBlue.setOnClickListener { viewModel.setColor(Color.parseColor("#2196F3")) }

        btnSizeThin.setOnClickListener { viewModel.setStrokeWidth(4f) }
        btnSizeMed.setOnClickListener { viewModel.setStrokeWidth(8f) }
        btnSizeThick.setOnClickListener { viewModel.setStrokeWidth(16f) }

        btnToggleImmersive.setOnClickListener { viewModel.toggleImmersiveMode() }

        btnToggleReadingMode.setOnClickListener {
            val currentState = viewModel.uiState.value.readingMode
            val nextMode = when(currentState) {
                ReadingMode.NORMAL -> ReadingMode.SEPIA
                ReadingMode.SEPIA -> ReadingMode.DARK
                ReadingMode.DARK -> ReadingMode.NORMAL
            }
            viewModel.setReadingMode(nextMode)
        }

        findViewById<Button>(R.id.btnSearch).setOnClickListener { showSearchDialog() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updateToolSelectionUI(state.activeTool)
                updateSizeSelectionUI(state.strokeWidth)
                toggleSystemUI(state.isImmersiveMode)
                applySettingsToCanvas(state)

                if (state.isSaving) {
                    Toast.makeText(this@MainActivity, "Saving PDF...", Toast.LENGTH_SHORT).show()
                } else if (state.saveSuccess == true) {
                    Toast.makeText(this@MainActivity, "Saved Successfully!", Toast.LENGTH_LONG).show()
                    viewModel.resetSaveState()
                } else if (state.saveSuccess == false) {
                    Toast.makeText(this@MainActivity, "Failed to save.", Toast.LENGTH_SHORT).show()
                    viewModel.resetSaveState()
                }
            }
        }
    }

    private fun applySettingsToCanvas(state: EditorState) {
        pdfRecyclerView.isDrawingMode = state.isDrawingMode
        val adapter = pdfRecyclerView.adapter as? PdfPageAdapter

        if (adapter != null) {
            val modeChanged = adapter.currentState.readingMode != state.readingMode
            adapter.currentState = state

            if (modeChanged) {
                adapter.notifyDataSetChanged()
            }
        }

        for (i in 0 until pdfRecyclerView.childCount) {
            val child = pdfRecyclerView.getChildAt(i)
            val drawView = child.findViewById<CustomDrawView>(R.id.drawView)

            if (drawView != null) {
                drawView.isDrawingEnabled = state.isDrawingMode
                drawView.currentDrawColor = state.strokeColor
                drawView.currentStrokeWidth = state.strokeWidth
                drawView.isEraser = state.activeTool == ActiveTool.ERASER
                drawView.isHighlighter = state.activeTool == ActiveTool.HIGHLIGHTER

                drawView.isNoteTool = state.activeTool == ActiveTool.NOTE
                drawView.currentNotes = state.studyNotes.filter { it.pageIndex == drawView.pageIndex }

                drawView.onNoteInteraction = { x, y, clickedNote ->
                    showNoteDialog(drawView.pageIndex, x, y, clickedNote)
                }
            }
        }
    }

    private fun toggleSystemUI(isImmersive: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        if (isImmersive) {
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateToolSelectionUI(tool: ActiveTool) {
        btnHand.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnMarker.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnHighlighter.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnEraser.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnNote.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))

        // Show/Hide the Contextual Settings Panel
        val settingsPanel = findViewById<View>(R.id.settingsPanelCard)
        if (tool == ActiveTool.MARKER || tool == ActiveTool.HIGHLIGHTER) {
            settingsPanel.visibility = View.VISIBLE
        } else {
            settingsPanel.visibility = View.GONE
        }

        when (tool) {
            ActiveTool.HAND -> btnHand.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.MARKER -> btnMarker.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.HIGHLIGHTER -> btnHighlighter.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.ERASER -> btnEraser.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.NOTE -> btnNote.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        }
    }

    private fun updateSizeSelectionUI(size: Float) {
        btnSizeThin.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))
        btnSizeMed.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))
        btnSizeThick.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))

        when (size) {
            4f -> btnSizeThin.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#212121"))
            8f -> btnSizeMed.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#212121"))
            16f -> btnSizeThick.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#212121"))
        }
    }
    private fun displayPdf() {
        viewModel.cachedFile?.let { file ->
            try {
                pdfRenderer?.close()
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)
                val adapter = PdfPageAdapter(pdfRenderer!!, pdfRenderer!!.pageCount)

                adapter.currentState = viewModel.uiState.value
                pdfRecyclerView.adapter = adapter

                applySettingsToCanvas(viewModel.uiState.value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun refreshSpecificPage(pageIndex: Int) {
        for (i in 0 until pdfRecyclerView.childCount) {
            val child = pdfRecyclerView.getChildAt(i)
            val drawView = child.findViewById<CustomDrawView>(R.id.drawView)
            if (drawView?.pageIndex == pageIndex) {
                drawView.invalidate()
            }
        }
    }

    private fun showNoteDialog(pageIndex: Int, x: Float, y: Float, existingNote: StudyNote?) {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle(if (existingNote == null) "Add Study Note" else "Edit Note")

        val input = android.widget.EditText(this)
        input.setText(existingNote?.textContent ?: "")
        input.hint = "Type your notes here..."
        input.setLines(4)
        builder.setView(input)

        builder.setPositiveButton("Save") { dialog, _ ->
            val text = input.text.toString()
            if (text.isNotBlank()) {
                val note = StudyNote(
                    id = existingNote?.id ?: 0,
                    documentUri = viewModel.uiState.value.activeDocumentUri,
                    pageIndex = pageIndex,
                    x = existingNote?.x ?: x,
                    y = existingNote?.y ?: y,
                    textContent = text
                )
                viewModel.saveNote(note)
            }
            dialog.dismiss()
        }

        if (existingNote != null) {
            builder.setNeutralButton("Delete") { dialog, _ ->
                viewModel.deleteNote(existingNote)
                dialog.dismiss()
            }
        }

        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
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

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
    }
}