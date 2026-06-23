package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement.SpaceBetween
import com.example.data.Document
import com.example.data.Page
import com.example.util.ImageFilterHelper
import com.example.util.PdfGeneratorHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DocScanApp(viewModel: DocScanViewModel) {
    val navController = rememberNavController()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()

    var activeCameraTarget by remember { mutableStateOf<((Bitmap) -> Unit)?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "dashboard") {
            composable("dashboard") {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToEditor = { docId -> navController.navigate("editor/$docId") },
                    onNavigateToDetail = { docId -> navController.navigate("detail/$docId") },
                    onCaptureWithCamera = { onCaptured -> activeCameraTarget = onCaptured }
                )
            }
            composable(
                route = "editor/{docId}",
                arguments = listOf(navArgument("docId") { type = NavType.IntType })
            ) { backStackEntry ->
                val docId = backStackEntry.arguments?.getInt("docId") ?: 0
                LaunchedEffect(docId) {
                    viewModel.loadDocument(docId)
                }
                DocumentCreatorScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDetail = { navController.navigate("detail/$docId") {
                        popUpTo("dashboard")
                    } },
                    onCaptureWithCamera = { onCaptured -> activeCameraTarget = onCaptured }
                )
            }
            composable(
                route = "detail/{docId}",
                arguments = listOf(navArgument("docId") { type = NavType.IntType })
            ) { backStackEntry ->
                val docId = backStackEntry.arguments?.getInt("docId") ?: 0
                LaunchedEffect(docId) {
                    viewModel.loadDocument(docId)
                }
                DocumentDetailScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // Processing loading shroud overlay
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "AI Processing Scans...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Extracting details, compiling PDF layouts...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Camera overlay standard
        activeCameraTarget?.let { captureCallback ->
            Box(modifier = Modifier.fillMaxSize()) {
                CameraScannerScreen(
                    onImageCaptured = { bitmap ->
                        captureCallback(bitmap)
                        activeCameraTarget = null
                    },
                    onClose = { activeCameraTarget = null }
                )
            }
        }
    }
}

// ==========================================
// SCREEN 1: DASHBOARD / DOCUMENT LIST
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DocScanViewModel,
    onNavigateToEditor: (Int) -> Unit,
    onNavigateToDetail: (Int) -> Unit,
    onCaptureWithCamera: ((Bitmap) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val documents by viewModel.filteredDocuments.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var showRenameDialog by remember { mutableStateOf<Document?>(null) }
    var renameInputVal by remember { mutableStateOf("") }

    // Media Picker launcher
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                // Instantly create a new document draft and insert the file
                viewModel.createNewDocument("Imported Doc") { newDocId ->
                    viewModel.addPageFromUri(uri, context)
                    onNavigateToEditor(newDocId)
                }
            }
        }
    )

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Camera app logo",
                                tint = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = "ScanPro AI",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    // Avatar badge right side
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "JD",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar Component
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text("Search scanned files, tags, summaries...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_field"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action Quick Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.createNewDocument("New Scan") { id ->
                            onCaptureWithCamera { capturedBmp ->
                                viewModel.addPageFromBitmap(capturedBmp, context)
                            }
                            onNavigateToEditor(id)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("action_scan_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Scan")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Document", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = {
                        galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("action_import_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Browse")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Files", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // AI Powered Tools Grid Section
            Text(
                text = "AI Powered Tools",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            var alertTextToShow by remember { mutableStateOf<String?>(null) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Image to OCR button
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            alertTextToShow = "Gemini OCR is integrated natively! Inside any scanned document page editor, click the 'Trigger Gemini OCR' clean button, or preview summary tabs inside details view!"
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD1E4FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TextFields,
                                contentDescription = "OCR",
                                tint = Color(0xFF001D35),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Image to OCR",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Compress PDF button
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            alertTextToShow = "Advanced compiler options (High/Balanced/Max) and custom Page Size models are ready directly inside Compile options on document detail board!"
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFD8E4)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Compress,
                                contentDescription = "Compress",
                                tint = Color(0xFF31111D),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Compress PDF",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Merge Files button
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            alertTextToShow = "You can merge files instantly! Import or scan multiple page sheets inside any document draft, organize order, and print/share a combined PDF artifact."
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFC2F0C2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MergeType,
                                contentDescription = "Merge",
                                tint = Color(0xFF002100),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Merge Files",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // AI Translate button
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            alertTextToShow = "Gemini Translate is active! Open any compiled document detail and tap on the Translate tab to translate files into Spanish, French, Japanese, etc.!"
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFDAD6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Translate",
                                tint = Color(0xFF410002),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "AI Translate",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (alertTextToShow != null) {
                AlertDialog(
                    onDismissRequest = { alertTextToShow = null },
                    title = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "AI", tint = Color(0xFFFF9100))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Interactive Companion Help")
                    }},
                    text = { Text(alertTextToShow!!) },
                    confirmButton = {
                        Button(onClick = { alertTextToShow = null }) {
                            Text("Got it!")
                        }
                    }
                )
            }

            Text(
                text = "Recent Handheld Scans",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Documents List empty state as per layout guidelines
            if (documents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(96.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.DocumentScanner,
                                    contentDescription = "No Scans",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            "Your Scan Cabinet is Empty",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Scan document sheets or import photos to convert them to PDFs, extract text, and compile summaries built by Gemini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                viewModel.createNewDocument("Camera Scan") { id ->
                                    onCaptureWithCamera { capturedBmp ->
                                        viewModel.addPageFromBitmap(capturedBmp, context)
                                    }
                                    onNavigateToEditor(id)
                                }
                            }
                        ) {
                            Text("Fast Scan Simulation Draft")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(documents, key = { it.id }) { doc ->
                        DocumentListCard(
                            document = doc,
                            onClick = { onNavigateToDetail(doc.id) },
                            onRename = {
                                showRenameDialog = doc
                                renameInputVal = doc.name
                            },
                            onDelete = { viewModel.deleteDocument(doc) },
                            onEditPages = { onNavigateToEditor(doc.id) }
                        )
                    }
                }
            }
        }
    }

    // Rename Dialog Overlay
    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Scan Document") },
            text = {
                OutlinedTextField(
                    value = renameInputVal,
                    onValueChange = { renameInputVal = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRenameDialog?.let {
                            if (renameInputVal.isNotBlank()) {
                                viewModel.renameDocument(renameInputVal)
                            }
                        }
                        showRenameDialog = null
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DocumentListCard(
    document: Document,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEditPages: () -> Unit
) {
    val dateString = remember(document.createdAt) {
        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        sdf.format(Date(document.createdAt))
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp), // rounded-3xl equivalent
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("document_card_${document.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp) // p-5 equivalence
        ) {
            // Upper Metadata Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LAST SCAN",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified // tracking-wider
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Visual Document Template Placeholder Thumbnail
                Box(
                    modifier = Modifier
                        .size(64.dp, 88.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "Document visual thumbnail",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Info columns & actions
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = document.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Interactive Document Compiled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Buttons actions inside card
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Edit Capsule Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable { onEditPages() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "EDIT PAGES",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        // Open Dialog Capsule Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(100.dp))
                                .background(Color.White)
                                .clickable { onClick() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "SHARE/OPEN",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Micro options Dropdown
                var expandedMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expandedMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Document options")
                    }
                    DropdownMenu(
                        expanded = expandedMenu,
                        onDismissRequest = { expandedMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.BorderColor, contentDescription = "Rename Icon") },
                            onClick = {
                                expandedMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Document", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = "Delete Icon", tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                expandedMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            // Summary abstract snippet if exists
            if (!document.summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "AI Bullet Overview", tint = Color(0xFFFF9100), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Gemini Summary Preview", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = document.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}


// ==========================================
// SCREEN 2: CREATOR & FILTER EDITOR CORE
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentCreatorScreen(
    viewModel: DocScanViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: () -> Unit,
    onCaptureWithCamera: ((Bitmap) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val doc by viewModel.activeDocument.collectAsStateWithLifecycle()
    val pages by viewModel.activePages.collectAsStateWithLifecycle()

    var activeEditingPage by remember { mutableStateOf<Page?>(null) }

    // Media Picker launcher
    val childPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.addPageFromUri(uri, context)
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.name ?: "Document Pages Draft", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (pages.isNotEmpty()) {
                        Button(
                            onClick = onNavigateToDetail,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Generate PDF", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        childPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Import Page")
                }
                FloatingActionButton(
                    onClick = {
                        onCaptureWithCamera { capturedBmp ->
                            viewModel.addPageFromBitmap(capturedBmp, context)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera capture")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Tip", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Tap any page thumbnail to open the Editor: apply custom visual filters (Grayscale, Shadow Removal, Sharpness), adjust contrast, or run offline OCR text extraction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (pages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = "No pages", modifier = Modifier.size(64.dp), tint = Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No Scanned Pages Yet", fontWeight = FontWeight.Bold)
                        Text("Click the Camera button below to capture or import.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(pages, key = { it.id }) { page ->
                        CreatorPageGridItem(
                            page = page,
                            onMoveUp = { viewModel.movePageUp(page.orderIndex) },
                            onMoveDown = { viewModel.movePageDown(page.orderIndex) },
                            onDelete = { viewModel.deletePage(page) },
                            onSelect = { activeEditingPage = page }
                        )
                    }
                }
            }
        }
    }

    // Modal Page Filters Editor Sheet
    if (activeEditingPage != null) {
        val editingPageRecord = pages.find { it.id == activeEditingPage!!.id }
        if (editingPageRecord != null) {
            DocFilterEditionDialog(
                page = editingPageRecord,
                onDismiss = { activeEditingPage = null },
                onApplyOptions = { filter, contr, bright ->
                    viewModel.applyFilterToPage(editingPageRecord, filter, contr, bright)
                },
                onTriggerOcr = {
                    viewModel.runOcrOnPage(editingPageRecord)
                }
            )
        }
    }
}

@Composable
fun CreatorPageGridItem(
    page: Page,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("page_grid_item_${page.id}")
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column {
                AsyncImage(
                    model = page.imagePath,
                    contentDescription = "Scan Page ${page.orderIndex + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(Color.LightGray),
                    contentScale = ContentScale.Crop
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = SpaceBetween
                ) {
                    Text(
                        "Page ${page.orderIndex + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Move Left", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Move Right", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Quick delete badge
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(28.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Delete Page", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// Dialog combining visual filters and manual sliders
@Composable
fun DocFilterEditionDialog(
    page: Page,
    onDismiss: () -> Unit,
    onApplyOptions: (ImageFilterHelper.FilterType, Float, Float) -> Unit,
    onTriggerOcr: () -> Unit
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf(ImageFilterHelper.FilterType.valueOf(page.filterApplied)) }
    var contrastSlider by remember { mutableStateOf(page.contrastVal) }
    var brightnessSlider by remember { mutableStateOf(page.brightnessVal) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            LazyColumn(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "Page ${page.orderIndex + 1} Visual Processing",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Mini preview inside sheet
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.LightGray)
                    ) {
                        AsyncImage(
                            model = page.imagePath,
                            contentDescription = "Editing page",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                item {
                    Text("Select Process Filter Preset:", style = MaterialTheme.typography.labelLarge)
                }

                // Filter chips list
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Triple("Original", ImageFilterHelper.FilterType.NONE, Icons.Default.FilterFrames),
                            Triple("B&W Doc", ImageFilterHelper.FilterType.DOCUMENT_BW, Icons.Default.FilterBAndW),
                            Triple("Grayscale", ImageFilterHelper.FilterType.GRAYSCALE, Icons.Default.BrightnessMedium),
                            Triple("AI Clean", ImageFilterHelper.FilterType.CLEANUP, Icons.Default.AutoFixHigh)
                        ).forEach { (label, type, icon) ->
                            FilterChip(
                                selected = selectedFilter == type,
                                onClick = { selectedFilter = type },
                                label = { Text(label) },
                                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Triple("Sharpen", ImageFilterHelper.FilterType.SHARPEN, Icons.Default.Details),
                            Triple("Vintage", ImageFilterHelper.FilterType.VINTAGE, Icons.Default.FilterVintage),
                            Triple("Contrast", ImageFilterHelper.FilterType.CONTRAST_BRIGHTNESS, Icons.Default.Tune)
                        ).forEach { (label, type, icon) ->
                            FilterChip(
                                selected = selectedFilter == type,
                                onClick = { selectedFilter = type },
                                label = { Text(label) },
                                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                // Contrast Manual Adjustment (from 0.5 to 3.0)
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = SpaceBetween) {
                            Text("Contrast Boost", style = MaterialTheme.typography.bodySmall)
                            Text(String.format(Locale.US, "%.1f x", contrastSlider), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = contrastSlider,
                            onValueChange = { contrastSlider = it },
                            valueRange = 0.5f..3.0f
                        )
                    }
                }

                // Brightness Slider (from -100 to 100)
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = SpaceBetween) {
                            Text("Brightness Offset", style = MaterialTheme.typography.bodySmall)
                            Text(String.format(Locale.US, "%.0f", brightnessSlider), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = brightnessSlider,
                            onValueChange = { brightnessSlider = it },
                            valueRange = -100f..100f
                        )
                    }
                }

                // AI actions on this page
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable { onTriggerOcr() }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Gemini OCR", tint = Color(0xFFFF9100))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Trigger Gemini OCR", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (page.ocrText.isNullOrBlank()) "Extract printable text layout from this page." else "OCR Completed! Click to re-run.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (!page.ocrText.isNullOrBlank()) {
                    item {
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                        ) {
                            Box(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = page.ocrText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Frame controls
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                onApplyOptions(selectedFilter, contrastSlider, brightnessSlider)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Apply Filters")
                        }
                    }
                }
            }
        }
    }
}


// ==========================================
// SCREEN 3: DOCUMENT DETAIL & AI CABINET
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    viewModel: DocScanViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val doc by viewModel.activeDocument.collectAsStateWithLifecycle()
    val pages by viewModel.activePages.collectAsStateWithLifecycle()

    // AI states
    val aiOcrResult by viewModel.aiOcrResult.collectAsStateWithLifecycle()
    val aiSummaryResult by viewModel.aiSummaryResult.collectAsStateWithLifecycle()
    val aiTranslationResult by viewModel.translationResult.collectAsStateWithLifecycle()

    var activeTabIdx by remember { mutableStateOf(0) }

    // Export setups
    var selectedPageSize by remember { mutableStateOf(PdfGeneratorHelper.PageSize.A4) }
    var selectedCompression by remember { mutableStateOf(PdfGeneratorHelper.CompressionLevel.BALANCED) }

    var showTranslationSelection by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.name ?: "Scanned Document Board", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.suggestSmartRename() }) {
                        Icon(Icons.Default.AutoMode, contentDescription = "Smart AI Name Suggested")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Document preview thumbnails tape
            Text("Filtered Print Layout:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                if (pages.isEmpty()) {
                    Text("No pages available.", color = Color.Gray, modifier = Modifier.align(Alignment.CenterVertically))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center
                    ) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pages.forEach { page ->
                                    Box(
                                        modifier = Modifier
                                            .size(60.dp, 80.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White)
                                    ) {
                                        AsyncImage(
                                            model = page.imagePath,
                                            contentDescription = "Scan layout",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // PDF Compiler Settings Panel
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BoxBorder(MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("PDF Builder Configs:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Page Size
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Page Size", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PdfGeneratorHelper.PageSize.values().forEach { size ->
                                    ElevatedFilterChip(
                                        selected = selectedPageSize == size,
                                        onClick = { selectedPageSize = size },
                                        label = { Text(size.name, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        // Compression Quality
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Compression", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    Pair("High", PdfGeneratorHelper.CompressionLevel.HIGH_QUALITY),
                                    Pair("Balanced", PdfGeneratorHelper.CompressionLevel.BALANCED),
                                    Pair("Max", PdfGeneratorHelper.CompressionLevel.MAXIMUM)
                                ).forEach { (lbl, cmp) ->
                                    ElevatedFilterChip(
                                        selected = selectedCompression == cmp,
                                        onClick = { selectedCompression = cmp },
                                        label = { Text(lbl, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.exportToPdf(context, selectedPageSize, selectedCompression) { compiledFile ->
                                triggerPdfShareIntent(context, compiledFile)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("export_compile_pdf_button")
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Compile PDF")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Compile and Share PDF", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // AI Studio Cabinet Section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Gemini AI Copilot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            // Tab Rows
            TabRow(
                selectedTabIndex = activeTabIdx,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(selected = activeTabIdx == 0, onClick = { activeTabIdx = 0 }) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        Text("Summary", fontWeight = FontWeight.Bold)
                    }
                }
                Tab(selected = activeTabIdx == 1, onClick = { activeTabIdx = 1 }) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        Text("OCR Text", fontWeight = FontWeight.Bold)
                    }
                }
                Tab(selected = activeTabIdx == 2, onClick = { activeTabIdx = 2 }) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        Text("Translation", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Panels
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                when (activeTabIdx) {
                    0 -> { // Summary Tab
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (aiSummaryResult.isBlank()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "No Summary Compiled",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Let Gemini scan all extracted OCR pages and synthesize standard notes.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 24.dp)
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = { viewModel.summarizeActiveDocument() }) {
                                            Icon(Icons.Default.AutoAwesome, contentDescription = "AI Summarize")
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Compile AI Summary")
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Summary Output", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                            IconButton(onClick = { viewModel.summarizeActiveDocument() }) {
                                                Icon(Icons.Default.Refresh, contentDescription = "Re-summarize", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = aiSummaryResult,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    1 -> { // OCR Text Extract Tab
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (aiOcrResult.isBlank() || aiOcrResult == "No text found on page.") {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No printable layout extracted. Tap Edit Pages and trigger Gemini OCR on page thumbnails first.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(24.dp)
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Aggregated Extracted Text", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                            Row {
                                                IconButton(
                                                    onClick = {
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("Scanned OCR", aiOcrResult))
                                                        Toast.makeText(context, "Copied OCR text", Toast.LENGTH_SHORT).show()
                                                    }
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = aiOcrResult,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    2 -> { // Translation Tab
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Translate Extract", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Button(
                                    onClick = { showTranslationSelection = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                                ) {
                                    Icon(Icons.Default.Translate, contentDescription = "Translate")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pick Language")
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (aiTranslationResult.isBlank()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Translation panel ready. Pick a destination language to parse entire OCR file automatically.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        Text(
                                            text = aiTranslationResult,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Language selector overlay popup
    if (showTranslationSelection) {
        AlertDialog(
            onDismissRequest = { showTranslationSelection = false },
            title = { Text("Choose Translation Language") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("English", "Spanish (ES)", "Spanish (Latin)", "French", "German", "Japanese", "Chinese (Simplified)", "Hindi").forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.translateActiveOcr(lang)
                                    showTranslationSelection = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(lang, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTranslationSelection = false }) {
                    Text("Close")
                }
            }
        )
    }
}

// Border creator utility
@Composable
fun BoxBorder(color: Color) = androidx.compose.foundation.BorderStroke(1.dp, color)

// Document Sharing Trigger
fun triggerPdfShareIntent(context: Context, pdfFile: File) {
    if (!pdfFile.exists()) return

    try {
        val authority = "${context.packageName}.fileprovider"
        val pathUri = FileProvider.getUriForFile(context, authority, pdfFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pathUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(intent, "Share Compiled PDF Scan"))
    } catch (e: Exception) {
        Toast.makeText(context, "Sharing Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        e.printStackTrace()
    }
}
