package com.example.pdfedi

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // === UI Variables ===
    private lateinit var btnOpenPdf: ImageButton
    private lateinit var btnSavePdf: ImageButton

    private lateinit var btnHand: ImageButton
    private lateinit var btnMarker: ImageButton
    private lateinit var btnHighlighter: ImageButton
    private lateinit var btnEraser: ImageButton

    private lateinit var btnColorRed: Button
    private lateinit var btnColorGreen: Button
    private lateinit var btnColorBlue: Button

    private lateinit var btnSizeThin: Button
    private lateinit var btnSizeMed: Button
    private lateinit var btnSizeThick: Button

    private lateinit var pdfRecyclerView: ZoomableRecyclerView

    // === Engine State ===
    enum class ActiveTool { HAND, MARKER, HIGHLIGHTER, ERASER }
    private var currentTool = ActiveTool.HAND
    private var currentColor = Color.parseColor("#F44336") // Red
    private var currentSize = 8f // Medium

    private var pdfRenderer: PdfRenderer? = null
    private var cachedPdfFile: File? = null

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { loadPdfFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PDFBoxResourceLoader.init(applicationContext)

        // 1. Link all buttons
        btnOpenPdf = findViewById(R.id.btnOpenPdf)
        btnSavePdf = findViewById(R.id.btnSavePdf)
        btnHand = findViewById(R.id.btnHand)
        btnMarker = findViewById(R.id.btnMarker)
        btnHighlighter = findViewById(R.id.btnHighlighter)
        btnEraser = findViewById(R.id.btnEraser)
        btnColorRed = findViewById(R.id.btnColorRed)
        btnColorGreen = findViewById(R.id.btnColorGreen)
        btnColorBlue = findViewById(R.id.btnColorBlue)
        btnSizeThin = findViewById(R.id.btnSizeThin)
        btnSizeMed = findViewById(R.id.btnSizeMed)
        btnSizeThick = findViewById(R.id.btnSizeThick)
        pdfRecyclerView = findViewById(R.id.pdfRecyclerView)

        // Lock vertical scrolling when drawing is active
        pdfRecyclerView.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean {
                return currentTool == ActiveTool.HAND
            }
        }

        // --- TOP TOOLBAR CLICKS ---
        btnOpenPdf.setOnClickListener { openDocumentLauncher.launch(arrayOf("application/pdf")) }
        btnSavePdf.setOnClickListener { Toast.makeText(this, "Save logic coming next!", Toast.LENGTH_SHORT).show() }

        // --- TOOL CLICKS ---
        btnHand.setOnClickListener { selectTool(ActiveTool.HAND) }
        btnMarker.setOnClickListener { selectTool(ActiveTool.MARKER) }
        btnHighlighter.setOnClickListener { selectTool(ActiveTool.HIGHLIGHTER) }
        btnEraser.setOnClickListener { selectTool(ActiveTool.ERASER) }

        // --- COLOR CLICKS ---
        btnColorRed.setOnClickListener { currentColor = Color.parseColor("#F44336"); applySettingsToCanvas() }
        btnColorGreen.setOnClickListener { currentColor = Color.parseColor("#4CAF50"); applySettingsToCanvas() }
        btnColorBlue.setOnClickListener { currentColor = Color.parseColor("#2196F3"); applySettingsToCanvas() }

        // --- SIZE CLICKS ---
        btnSizeThin.setOnClickListener { currentSize = 4f; selectSize(btnSizeThin) }
        btnSizeMed.setOnClickListener { currentSize = 8f; selectSize(btnSizeMed) }
        btnSizeThick.setOnClickListener { currentSize = 16f; selectSize(btnSizeThick) }

        // Initialize default UI state
        selectTool(ActiveTool.HAND)
        selectSize(btnSizeMed)
    }

    // Handles the UI color change for the tool buttons
    private fun selectTool(tool: ActiveTool) {
        currentTool = tool

        // Reset all to gray
        btnHand.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnMarker.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnHighlighter.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnEraser.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))

        // Highlight active tool to green
        when (tool) {
            ActiveTool.HAND -> btnHand.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.MARKER -> btnMarker.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.HIGHLIGHTER -> btnHighlighter.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.ERASER -> btnEraser.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        }
        applySettingsToCanvas()
    }

    // Handles the UI color change for the size buttons
    private fun selectSize(activeButton: Button) {
        btnSizeThin.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))
        btnSizeMed.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))
        btnSizeThick.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))

        activeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#212121")) // Darker when active
        applySettingsToCanvas()
    }

    // Pushes the exact current settings to the Engine and all visible pages instantly
    private fun applySettingsToCanvas() {
        val isDrawing = currentTool != ActiveTool.HAND
        pdfRecyclerView.isDrawingMode = isDrawing

        // 1. Update Adapter (for pages you haven't scrolled to yet)
        val adapter = pdfRecyclerView.adapter as? PdfPageAdapter
        if (adapter != null) {
            adapter.isDrawingMode = isDrawing
            adapter.currentDrawColor = currentColor
            adapter.currentStrokeWidth = currentSize
            adapter.isEraser = currentTool == ActiveTool.ERASER
            adapter.isHighlighter = currentTool == ActiveTool.HIGHLIGHTER
        }

        // 2. Update visible pages instantly (Silent Update)
        for (i in 0 until pdfRecyclerView.childCount) {
            val child = pdfRecyclerView.getChildAt(i)
            val drawView = child.findViewById<CustomDrawView>(R.id.drawView)
            if (drawView != null) {
                drawView.isDrawingEnabled = isDrawing
                drawView.currentDrawColor = currentColor
                drawView.currentStrokeWidth = currentSize
                drawView.isEraser = currentTool == ActiveTool.ERASER
                drawView.isHighlighter = currentTool == ActiveTool.HIGHLIGHTER
            }
        }
    }

    // --- PDF Loading Logic (Unchanged) ---
    private fun loadPdfFromUri(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val tempFile = File(cacheDir, "temp_working_pdf.pdf")
                val outputStream = FileOutputStream(tempFile)
                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()
                cachedPdfFile = tempFile
                withContext(Dispatchers.Main) { displayPdf() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Error loading PDF", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun displayPdf() {
        cachedPdfFile?.let { file ->
            try {
                pdfRenderer?.close()
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)
                val adapter = PdfPageAdapter(pdfRenderer!!, pdfRenderer!!.pageCount)
                pdfRecyclerView.adapter = adapter
                applySettingsToCanvas() // Ensure settings apply to the newly loaded document
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
    }
}