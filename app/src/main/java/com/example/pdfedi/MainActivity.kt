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
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
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
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton

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
    private var currentColor = Color.parseColor("#F44336")
    private var currentSize = 8f

    private var pdfRenderer: PdfRenderer? = null
    private var cachedPdfFile: File? = null
    private var originalUri: Uri? = null // WE NEED THIS TO OVERWRITE THE FILE

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            originalUri = it
            loadPdfFromUri(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PDFBoxResourceLoader.init(applicationContext)

        btnOpenPdf = findViewById(R.id.btnOpenPdf)
        btnSavePdf = findViewById(R.id.btnSavePdf)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)

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

        pdfRecyclerView.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean { return currentTool == ActiveTool.HAND }
        }

        // --- TOP TOOLBAR CLICKS ---
        btnOpenPdf.setOnClickListener { openDocumentLauncher.launch(arrayOf("application/pdf")) }

        btnSavePdf.setOnClickListener { savePdfToFile() }

        btnUndo.setOnClickListener {
            val page = StrokeManager.undo()
            if (page != null) refreshSpecificPage(page)
        }

        btnRedo.setOnClickListener {
            val page = StrokeManager.redo()
            if (page != null) refreshSpecificPage(page)
        }

        // --- TOOL CLICKS ---
        btnHand.setOnClickListener { selectTool(ActiveTool.HAND) }
        btnMarker.setOnClickListener { selectTool(ActiveTool.MARKER) }
        btnHighlighter.setOnClickListener { selectTool(ActiveTool.HIGHLIGHTER) }
        btnEraser.setOnClickListener { selectTool(ActiveTool.ERASER) }

        // --- COLOR & SIZE CLICKS ---
        btnColorRed.setOnClickListener { currentColor = Color.parseColor("#F44336"); applySettingsToCanvas() }
        btnColorGreen.setOnClickListener { currentColor = Color.parseColor("#4CAF50"); applySettingsToCanvas() }
        btnColorBlue.setOnClickListener { currentColor = Color.parseColor("#2196F3"); applySettingsToCanvas() }
        btnSizeThin.setOnClickListener { currentSize = 4f; selectSize(btnSizeThin) }
        btnSizeMed.setOnClickListener { currentSize = 8f; selectSize(btnSizeMed) }
        btnSizeThick.setOnClickListener { currentSize = 16f; selectSize(btnSizeThick) }

        selectTool(ActiveTool.HAND)
        selectSize(btnSizeMed)
    }

    // --- NEW: Refresh a single page smoothly for Undo/Redo ---
    private fun refreshSpecificPage(pageIndex: Int) {
        for (i in 0 until pdfRecyclerView.childCount) {
            val child = pdfRecyclerView.getChildAt(i)
            val drawView = child.findViewById<CustomDrawView>(R.id.drawView)
            if (drawView?.pageIndex == pageIndex) {
                drawView.invalidate() // Instantly repaints without lag
            }
        }
    }

    // --- NEW: The PDFBox Save Engine ---
    private fun savePdfToFile() {
        if (cachedPdfFile == null || originalUri == null) {
            Toast.makeText(this, "No PDF loaded!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Saving PDF...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Load the background file
                val document = PDDocument.load(cachedPdfFile)

                // 2. Loop through every stroke in memory
                for (stroke in StrokeManager.globalStrokes) {
                    if (stroke.isEraser) continue // We skip saving erasers to protect the text
                    if (stroke.points.isEmpty()) continue

                    val page = document.getPage(stroke.pageIndex)
                    val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)

                    // Set up Highlighter Transparency
                    if (stroke.isHighlighter) {
                        val graphicsState = PDExtendedGraphicsState()
                        graphicsState.strokingAlphaConstant = 0.4f
                        contentStream.setGraphicsStateParameters(graphicsState)
                    }

                    // Set Color and Size
                    contentStream.setStrokingColor(Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))

                    // The magic scale factor: Our adapter renders the page at 2.5x zoom. We have to divide by 2.5 to get true PDF coordinates.
                    val pdfScale = 2.5f
                    val pdfHeight = page.mediaBox.height
                    contentStream.setLineWidth(stroke.width / pdfScale)

                    // Draw the points
                    val start = stroke.points[0]
                    // PDF Y-axis is inverted (0 is at the bottom). Android is at the top.
                    contentStream.moveTo(start.x / pdfScale, pdfHeight - (start.y / pdfScale))

                    for (i in 1 until stroke.points.size) {
                        val pt = stroke.points[i]
                        contentStream.lineTo(pt.x / pdfScale, pdfHeight - (pt.y / pdfScale))
                    }

                    contentStream.stroke()
                    contentStream.close()
                }

                // 3. Overwrite the original file URI
                val outputStream = contentResolver.openOutputStream(originalUri!!, "wt")
                if (outputStream != null) {
                    document.save(outputStream)
                    outputStream.close()
                }
                document.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Saved Successfully!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to save.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun selectTool(tool: ActiveTool) {
        currentTool = tool
        btnHand.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnMarker.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnHighlighter.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))
        btnEraser.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B0BEC5"))

        when (tool) {
            ActiveTool.HAND -> btnHand.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.MARKER -> btnMarker.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.HIGHLIGHTER -> btnHighlighter.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            ActiveTool.ERASER -> btnEraser.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        }
        applySettingsToCanvas()
    }

    private fun selectSize(activeButton: Button) {
        btnSizeThin.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))
        btnSizeMed.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))
        btnSizeThick.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#757575"))
        activeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#212121"))
        applySettingsToCanvas()
    }

    private fun applySettingsToCanvas() {
        val isDrawing = currentTool != ActiveTool.HAND
        pdfRecyclerView.isDrawingMode = isDrawing
        val adapter = pdfRecyclerView.adapter as? PdfPageAdapter
        if (adapter != null) {
            adapter.isDrawingMode = isDrawing
        }
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

    private fun loadPdfFromUri(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Clear the old drawing memory when a new file opens!
                StrokeManager.globalStrokes.clear()
                StrokeManager.redoStrokes.clear()

                val inputStream = contentResolver.openInputStream(uri)
                val tempFile = File(cacheDir, "temp_working_pdf.pdf")
                val outputStream = FileOutputStream(tempFile)
                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()
                cachedPdfFile = tempFile
                withContext(Dispatchers.Main) { displayPdf() }
            } catch (e: Exception) { }
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
                applySettingsToCanvas()
            } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
    }
}