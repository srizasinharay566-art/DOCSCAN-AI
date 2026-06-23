package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.util.ImageFilterHelper
import com.example.util.PdfGeneratorHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class DocScanViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.documentDao()
    private val geminiService = GeminiService()

    // Screen States
    val documents: StateFlow<List<Document>> = dao.getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _activeDocument = MutableStateFlow<Document?>(null)
    val activeDocument: StateFlow<Document?> = _activeDocument.asStateFlow()

    private val _activePages = MutableStateFlow<List<Page>>(emptyList())
    val activePages: StateFlow<List<Page>> = _activePages.asStateFlow()

    // AI States
    private val _aiOcrResult = MutableStateFlow<String>("")
    val aiOcrResult: StateFlow<String> = _aiOcrResult.asStateFlow()

    private val _aiSummaryResult = MutableStateFlow<String>("")
    val aiSummaryResult: StateFlow<String> = _aiSummaryResult.asStateFlow()

    private val _translationResult = MutableStateFlow<String>("")
    val translationResult: StateFlow<String> = _translationResult.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredDocuments: StateFlow<List<Document>> = combine(documents, _searchQuery) { docs, query ->
        if (query.isBlank()) {
            docs
        } else {
            docs.filter {
                it.name.contains(query, ignoreCase = true) ||
                        (it.tags?.contains(query, ignoreCase = true) == true) ||
                        (it.summary?.contains(query, ignoreCase = true) == true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Creates a new Document draft.
     */
    fun createNewDocument(name: String = "New Scan Document", onReady: (Int) -> Unit) {
        viewModelScope.launch {
            _isProcessing.value = true
            val newDoc = Document(name = name)
            val id = dao.insertDocument(newDoc).toInt()
            _activeDocument.value = newDoc.copy(id = id)
            _activePages.value = emptyList()
            _isProcessing.value = false
            withContext(Dispatchers.Main) {
                onReady(id)
            }
        }
    }

    /**
     * Loads an existing Document and its pages.
     */
    fun loadDocument(docId: Int) {
        viewModelScope.launch {
            _isProcessing.value = true
            val doc = dao.getDocumentById(docId)
            _activeDocument.value = doc
            if (doc != null) {
                val pagesList = dao.getPagesForDocument(docId)
                _activePages.value = pagesList
                
                // Clear AI results for new active doc and cache if text exists
                val accumulatedOcr = pagesList.mapNotNull { it.ocrText }.joinToString("\n\n")
                _aiOcrResult.value = accumulatedOcr
                _aiSummaryResult.value = doc.summary ?: ""
                _translationResult.value = ""
            }
            _isProcessing.value = false
        }
    }

    /**
     * Save/import page from gallery Uri
     */
    fun addPageFromUri(uri: Uri, context: Context) {
        val docId = _activeDocument.value?.id ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val copiedFile = copyUriToInternalStorage(uri, context)
            if (copiedFile != null) {
                val order = _activePages.value.size
                val newPage = Page(
                    documentId = docId,
                    imagePath = copiedFile.absolutePath,
                    orderIndex = order
                )
                dao.insertPage(newPage)
                // Reload pages
                _activePages.value = dao.getPagesForDocument(docId)
            }
            _isProcessing.value = false
        }
    }

    /**
     * Save page took from CameraX Bitmap
     */
    fun addPageFromBitmap(bitmap: Bitmap, context: Context) {
        val docId = _activeDocument.value?.id ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val savedFile = saveBitmapToInternalStorage(bitmap, context)
            if (savedFile != null) {
                val order = _activePages.value.size
                val newPage = Page(
                    documentId = docId,
                    imagePath = savedFile.absolutePath,
                    orderIndex = order
                )
                dao.insertPage(newPage)
                // Reload pages
                _activePages.value = dao.getPagesForDocument(docId)
            }
            _isProcessing.value = false
        }
    }

    /**
     * Updates local Document name
     */
    fun renameDocument(newName: String) {
        val doc = _activeDocument.value ?: return
        viewModelScope.launch {
            val updated = doc.copy(name = newName)
            dao.updateDocument(updated)
            _activeDocument.value = updated
        }
    }

    /**
     * Rearranges pages: move page up
     */
    fun movePageUp(pageIndex: Int) {
        if (pageIndex <= 0 || pageIndex >= _activePages.value.size) return
        viewModelScope.launch {
            _isProcessing.value = true
            val pages = _activePages.value.toMutableList()
            val temp = pages[pageIndex]
            pages[pageIndex] = pages[pageIndex - 1]
            pages[pageIndex - 1] = temp
            
            // Re-index
            for (i in pages.indices) {
                val updatedPage = pages[i].copy(orderIndex = i)
                dao.updatePage(updatedPage)
            }
            
            _activePages.value = dao.getPagesForDocument(_activeDocument.value!!.id)
            _isProcessing.value = false
        }
    }

    /**
     * Rearranges pages: move page down
     */
    fun movePageDown(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= _activePages.value.size - 1) return
        viewModelScope.launch {
            _isProcessing.value = true
            val pages = _activePages.value.toMutableList()
            val temp = pages[pageIndex]
            pages[pageIndex] = pages[pageIndex + 1]
            pages[pageIndex + 1] = temp
            
            // Re-index
            for (i in pages.indices) {
                val updatedPage = pages[i].copy(orderIndex = i)
                dao.updatePage(updatedPage)
            }
            
            _activePages.value = dao.getPagesForDocument(_activeDocument.value!!.id)
            _isProcessing.value = false
        }
    }

    /**
     * Deletes a page from the document
     */
    fun deletePage(page: Page) {
        viewModelScope.launch {
            _isProcessing.value = true
            // Delete actual file
            try {
                val f = File(page.imagePath)
                if (f.exists()) f.delete()
            } catch (e: Exception) { e.printStackTrace() }

            dao.deletePage(page)
            
            // Re-index remaining pages
            val remaining = dao.getPagesForDocument(page.documentId)
            for (i in remaining.indices) {
                dao.updatePage(remaining[i].copy(orderIndex = i))
            }
            
            _activePages.value = dao.getPagesForDocument(page.documentId)
            _isProcessing.value = false
        }
    }

    /**
     * Apply image processing filters (Grayscale, BW, Cleanup, etc.)
     */
    fun applyFilterToPage(page: Page, filterType: ImageFilterHelper.FilterType, contrast: Float = 1.0f, brightness: Float = 0.0f) {
        viewModelScope.launch {
            _isProcessing.value = true
            withContext(Dispatchers.IO) {
                try {
                    // Filter helper requires a Bitmap. Load base image.
                    val originalBitmap = BitmapFactory.decodeFile(page.imagePath)
                    if (originalBitmap != null) {
                        // Apply filter
                        val filteredBitmap = ImageFilterHelper.applyFilter(originalBitmap, filterType, contrast, brightness)
                        // Save back over the same path (or a modified one, but overwriting stores it efficiently)
                        val outFile = File(page.imagePath)
                        ImageFilterHelper.saveBitmapToFile(filteredBitmap, outFile)
                        
                        // Update db metadata
                        val updatedPage = page.copy(
                            filterApplied = filterType.name,
                            contrastVal = contrast,
                            brightnessVal = brightness
                        )
                        dao.updatePage(updatedPage)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Reload
            _activePages.value = dao.getPagesForDocument(page.documentId)
            _isProcessing.value = false
        }
    }

    /**
     * Delete entire Document bundle
     */
    fun deleteDocument(doc: Document) {
        viewModelScope.launch {
            _isProcessing.value = true
            val pages = dao.getPagesForDocument(doc.id)
            for (p in pages) {
                try {
                    val f = File(p.imagePath)
                    if (f.exists()) f.delete()
                } catch (e: Exception) { e.printStackTrace() }
            }
            dao.deleteDocument(doc)
            _isProcessing.value = false
        }
    }

    /**
     * Run Gemini OCR on a specific page
     */
    fun runOcrOnPage(page: Page) {
        viewModelScope.launch {
            _isProcessing.value = true
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(page.imagePath)
            }
            if (bitmap != null) {
                val ocrResult = geminiService.performOcr(bitmap)
                val updatedPage = page.copy(ocrText = ocrResult)
                dao.updatePage(updatedPage)
                
                // Refresh activePages and update UI state
                val freshPages = dao.getPagesForDocument(page.documentId)
                _activePages.value = freshPages
                
                // Update aggregate OCR
                _aiOcrResult.value = freshPages.mapNotNull { it.ocrText }.joinToString("\n\n")
            }
            _isProcessing.value = false
        }
    }

    /**
     * Generate structured summary of active document
     */
    fun summarizeActiveDocument() {
        val doc = _activeDocument.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val textToSummarize = _aiOcrResult.value
            if (textToSummarize.isNotBlank() && textToSummarize != "No text found on page.") {
                val summary = geminiService.summarizeDocument(textToSummarize)
                val updatedDoc = doc.copy(summary = summary)
                dao.updateDocument(updatedDoc)
                _activeDocument.value = updatedDoc
                _aiSummaryResult.value = summary
            } else {
                _aiSummaryResult.value = "Please run 'OCR Text Extraction' on document pages first to synthesize metadata."
            }
            _isProcessing.value = false
        }
    }

    /**
     * Translate extracted text
     */
    fun translateActiveOcr(targetLanguage: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            val text = _aiOcrResult.value
            if (text.isNotBlank()) {
                val result = geminiService.translateText(text, targetLanguage)
                _translationResult.value = result
            } else {
                _translationResult.value = "No OCR text. Please extract text first before translating."
            }
            _isProcessing.value = false
        }
    }

    /**
     * Intelligently renames the file using Gemini OCR insights
     */
    fun suggestSmartRename() {
        val doc = _activeDocument.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val text = _aiOcrResult.value
            if (text.isNotBlank()) {
                val suggested = geminiService.suggestSmartName(text)
                renameDocument(suggested)
            }
            _isProcessing.value = false
        }
    }

    /**
     * Live search query update
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Export Document Pages to PDF
     */
    fun exportToPdf(context: Context, pageSize: PdfGeneratorHelper.PageSize, compression: PdfGeneratorHelper.CompressionLevel, onReady: (File) -> Unit) {
        val doc = _activeDocument.value ?: return
        val pages = _activePages.value
        if (pages.isEmpty()) return

        viewModelScope.launch {
            _isProcessing.value = true
            val pdfFile = withContext(Dispatchers.IO) {
                val safeDocName = doc.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val outPdfFile = File(context.cacheDir, "$safeDocName.pdf")
                val paths = pages.map { it.imagePath }
                PdfGeneratorHelper.createPdfFromImages(paths, outPdfFile, pageSize, compression)
            }
            _isProcessing.value = false
            withContext(Dispatchers.Main) {
                onReady(pdfFile)
            }
        }
    }

    // --- Private IO Utils ---

    private suspend fun copyUriToInternalStorage(uri: Uri, context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val inputStream: InputStream? = resolver.openInputStream(uri)
            if (inputStream != null) {
                val dir = File(context.filesDir, "scans")
                if (!dir.exists()) dir.mkdirs()
                
                val filename = "scan_page_${UUID.randomUUID()}.jpg"
                val outFile = File(dir, filename)
                
                // For direct reliability, we decode & save with slight JPEG quality compression to optimize space
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    FileOutputStream(outFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    return@withContext outFile
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private suspend fun saveBitmapToInternalStorage(bitmap: Bitmap, context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "scans")
            if (!dir.exists()) dir.mkdirs()

            val filename = "scan_page_${UUID.randomUUID()}.jpg"
            val outFile = File(dir, filename)

            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            return@withContext outFile
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
