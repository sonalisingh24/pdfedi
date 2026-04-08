package com.example.pdfedi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSort: View
    private lateinit var topBarCard: View

    private lateinit var adapter: PdfListAdapter
    private var allPdfs = listOf<PdfFile>()
    private var currentSortOrder = 0 // 0: Date, 1: Name, 2: Size

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Edge-to-edge support
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)

        initViews()
        setupInteractions()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.pdfRecyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        etSearch = findViewById(R.id.etSearch)
        btnSort = findViewById(R.id.btn_sort)
        topBarCard = findViewById(R.id.top_bar_card)

        // Push top bar below status bar notch
        ViewCompat.setOnApplyWindowInsetsListener(topBarCard) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val layoutParams = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            layoutParams.topMargin = statusBarInsets.top + 32
            view.layoutParams = layoutParams
            insets
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PdfListAdapter(emptyList()) { selectedPdf ->
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("PDF_PATH", selectedPdf.file.absolutePath)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
    }

    private fun setupInteractions() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterPdfs(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnSort.setOnClickListener {
            val options = arrayOf("Sort by Date", "Sort by Name", "Sort by Size")
            AlertDialog.Builder(this)
                .setTitle("Sort PDFs")
                .setSingleChoiceItems(options, currentSortOrder) { dialog, which ->
                    currentSortOrder = which
                    filterPdfs(etSearch.text.toString())
                    dialog.dismiss()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndLoad()
    }

    private fun checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            loadAllPdfsAsync()
        }
    }

    private fun loadAllPdfsAsync() {
        progressBar.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE

        // Run heavy lifting off the main UI thread
        lifecycleScope.launch(Dispatchers.IO) {
            val loadedFiles = mutableListOf<PdfFile>()
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%.pdf")

            try {
                val cursor = contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection, selection, selectionArgs, null
                )

                cursor?.use {
                    val dataIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    val nameIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dateIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                    while (it.moveToNext()) {
                        val path = it.getString(dataIndex)
                        if (path != null) {
                            loadedFiles.add(
                                PdfFile(
                                    file = File(path),
                                    name = it.getString(nameIndex) ?: "Unknown",
                                    sizeBytes = it.getLong(sizeIndex),
                                    dateModifiedMs = it.getLong(dateIndex) * 1000L // Convert sec to ms
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Return to UI thread to update views
            withContext(Dispatchers.Main) {
                allPdfs = loadedFiles
                progressBar.visibility = View.GONE
                filterPdfs(etSearch.text.toString()) // Applies current sort and search
            }
        }
    }

    private fun filterPdfs(query: String) {
        var filteredList = if (query.isBlank()) {
            allPdfs
        } else {
            allPdfs.filter { it.name.contains(query, ignoreCase = true) }
        }

        filteredList = when (currentSortOrder) {
            0 -> filteredList.sortedByDescending { it.dateModifiedMs } // Newest first
            1 -> filteredList.sortedBy { it.name.lowercase() }         // A-Z
            2 -> filteredList.sortedByDescending { it.sizeBytes }      // Largest first
            else -> filteredList
        }

        adapter.updateData(filteredList)
        tvEmptyState.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
    }
}