package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScannerScreen(
    onImageCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (cameraPermissionState.status.isGranted) {
                CameraPreviewContent(
                    onImageCaptured = onImageCaptured,
                    onClose = onClose
                )
            } else {
                // Safe Fallback - Simulated Document Camera Picker
                SimulatedScannerContent(
                    onImageCaptured = onImageCaptured,
                    onClose = onClose,
                    reason = "Camera permission requested. Alternatively, use simulated document picker for instant test."
                )
            }
        }
    }
}

@Composable
fun CameraPreviewContent(
    onImageCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var isFlashOn by remember { mutableStateOf(false) }
    var useHeuristicGuide by remember { mutableStateOf(true) }

    // Pulse animation for simulated edge overlay
    val infiniteTransition = rememberInfiniteTransition(label = "edges")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    LaunchedEffect(previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e("CameraPreview", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Scanner Auto-Boundary Finder Guidelines Overlays
        if (useHeuristicGuide) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val screenW = size.width
                val screenH = size.height
                val rectW = screenW * 0.85f
                val rectH = rectW * 1.414f // A4 aspect ratio helper
                val left = (screenW - rectW) / 2f
                val top = (screenH - rectH) / 2.3f

                // Gray transparent mask around camera central area
                drawRect(
                    color = Color.Black.copy(alpha = 0.35f)
                )

                // Transparent viewport hole
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(rectW, rectH),
                    blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                )

                // Glowing AI boundary lines
                drawRoundRect(
                    color = Color(0xFF00FFCC).copy(alpha = alphaAnim),
                    topLeft = Offset(left, top),
                    size = Size(rectW, rectH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
                    style = Stroke(width = 4.dp.toPx())
                )

                // Draw corner brackets
                val cornerLen = 40.dp.toPx()
                val cornerThick = 6.dp.toPx()
                val activeGreen = Color(0xFF00FFCC)

                // Top-Left corner
                drawLine(activeGreen, Offset(left - 2, top - 2), Offset(left + cornerLen, top - 2), cornerThick)
                drawLine(activeGreen, Offset(left - 2, top - 2), Offset(left - 2, top + cornerLen), cornerThick)

                // Bottom-Left corner
                drawLine(activeGreen, Offset(left - 2, top + rectH + 2), Offset(left + cornerLen, top + rectH + 2), cornerThick)
                drawLine(activeGreen, Offset(left - 2, top + rectH + 2), Offset(left - 2, top + rectH - cornerLen), cornerThick)

                // Top-Right corner
                drawLine(activeGreen, Offset(left + rectW + 2, top - 2), Offset(left + rectW - cornerLen, top - 2), cornerThick)
                drawLine(activeGreen, Offset(left + rectW + 2, top - 2), Offset(left + rectW + 2, top + cornerLen), cornerThick)

                // Bottom-Right corner
                drawLine(activeGreen, Offset(left + rectW + 2, top + rectH + 2), Offset(left + rectW - cornerLen, top + rectH + 2), cornerThick)
                drawLine(activeGreen, Offset(left + rectW + 2, top + rectH + 2), Offset(left + rectW + 2, top + rectH - cornerLen), cornerThick)
            }
        }

        // Camera control interface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(bottom = 48.dp, top = 24.dp, start = 24.dp, end = 24.dp)
        ) {
            // Cancel button
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            // Capture button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .align(Alignment.Center)
                    .clickable {
                        // Take photo using CameraX
                        imageCapture.takePicture(
                            cameraExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = imageProxyToBitmap(image)
                                    image.close()
                                    if (bitmap != null) {
                                        onImageCaptured(bitmap)
                                    } else {
                                        Toast
                                            .makeText(
                                                context,
                                                "Failed to parse captured image",
                                                Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("CameraPreview", "Capture failed: ${exception.message}")
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .background(Color.White, CircleShape)
                )
            }

            // Toggle guide layout / test simulation helper
            IconButton(
                onClick = { useHeuristicGuide = !useHeuristicGuide },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .background(
                        if (useHeuristicGuide) Color(0xFF00FFCC).copy(alpha = 0.4f) else Color.White.copy(
                            alpha = 0.2f
                        ), CircleShape
                    )
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "Toggle guides", tint = Color.White)
            }
        }

        // Simulator button in emulator
        TextButton(
            onClick = {
                // Trigger simulated scanner picker for rapid workspace testing
                val mockBitmap = generateSampleMockDocument(context)
                onImageCaptured(mockBitmap)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
                .background(Color.DarkGray.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
        ) {
            Icon(Icons.Default.Image, contentDescription = "Mock Scan", tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Simulate Doc Scan", color = Color.White)
        }
    }
}

@Composable
fun SimulatedScannerContent(
    onImageCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit,
    reason: String
) {
    val context = LocalContext.current
    var activeMockType by remember { mutableStateOf("Invoice") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = "Simulated Camera Scanner",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "AI Document Scanner Pro",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                Text("Select Mock Document Template to Capture:", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("Invoice", "Receipt", "Meeting Note").forEach { type ->
                        FilterChip(
                            selected = activeMockType == type,
                            onClick = { activeMockType = type },
                            label = { Text(type) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val bitmap = generateSampleMockDocument(context, activeMockType)
                        onImageCaptured(bitmap)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Camera, contentDescription = "Capture")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Trigger Mock AI Boundaries & Capture")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel Scan")
                }
            }
        }
    }
}

// Format Converter utils
private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    
    val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    
    // Rotate to match view display orientation if needed
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return original
    
    val matrix = Matrix()
    matrix.postRotate(rotation.toFloat())
    return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
}

/**
 * Procedurally generates a clean mock physical document bitmap with typical structured receipts, invoices, or notes.
 * This ensures that a proper high-fidelity document OCR is obtainable inside any runtime, including the cloud sandbox!
 */
fun generateSampleMockDocument(context: Context, type: String = "Invoice"): Bitmap {
    val width = 1200
    val height = 1600
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)

    // Backdrop (realistic wooden scan table or concrete top)
    val bgPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#1C1E24")
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    // Document Paper rotated slightly for realistic simulated scan look
    val paperPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        setShadowLayer(25f, 10f, 15f, android.graphics.Color.BLACK)
    }
    
    canvas.save()
    // Simulated offset and slight rotation
    canvas.translate(80f, 120f)
    canvas.rotate(1.5f)
    
    val pW = 1040f
    val pH = 1360f
    canvas.drawRoundRect(0f, 0f, pW, pH, 12f, 12f, paperPaint)

    // Document styling
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        isAntiAlias = true
        textSize = 34f
    }
    val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#3366FF") // Premium M3 Blue branding accent
        isAntiAlias = true
        isFakeBoldText = true
        textSize = 58f
    }
    val boldPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        isAntiAlias = true
        isFakeBoldText = true
        textSize = 36f
    }
    val grayPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.GRAY
        isAntiAlias = true
        textSize = 30f
    }

    when (type) {
        "Invoice" -> {
            canvas.drawText("ACME SOLUTIONS INC.", 60f, 120f, titlePaint)
            canvas.drawText("Cloud infrastructure & Development Services", 60f, 165f, grayPaint)
            
            canvas.drawText("INVOICE #INV-2026-981", 600f, 120f, boldPaint)
            canvas.drawText("Date: June 22, 2026", 600f, 165f, textPaint)
            canvas.drawText("Due Date: July 22, 2026", 600f, 210f, textPaint)

            // Divider line
            canvas.drawRect(60f, 260f, pW - 60f, 264f, android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY })

            canvas.drawText("BILL TO:", 60f, 330f, boldPaint)
            canvas.drawText("Google Dev Sandbox", 60f, 375f, textPaint)
            canvas.drawText("Mountain View, California", 60f, 420f, textPaint)

            // Table Header
            canvas.drawText("ITEM DESCRIPTION", 60f, 540f, boldPaint)
            canvas.drawText("QTY", 620f, 540f, boldPaint)
            canvas.drawText("RATE", 750f, 540f, boldPaint)
            canvas.drawText("AMOUNT", 900f, 540f, boldPaint)
            
            canvas.drawRect(60f, 560f, pW - 60f, 562f, android.graphics.Paint().apply { color = android.graphics.Color.LTGRAY })

            // Row 1
            canvas.drawText("Gemini Pro API Integration", 60f, 620f, textPaint)
            canvas.drawText("1", 630f, 620f, textPaint)
            canvas.drawText("$550.00", 730f, 620f, textPaint)
            canvas.drawText("$550.00", 890f, 620f, textPaint)

            // Row 2
            canvas.drawText("Material 3 Mobile UX Polish", 60f, 680f, textPaint)
            canvas.drawText("1", 630f, 680f, textPaint)
            canvas.drawText("$375.00", 730f, 680f, textPaint)
            canvas.drawText("$375.00", 890f, 680f, textPaint)

            // Row 3
            canvas.drawText("Local SQLite Room Cache Engine", 60f, 740f, textPaint)
            canvas.drawText("1", 630f, 740f, textPaint)
            canvas.drawText("$420.00", 730f, 740f, textPaint)
            canvas.drawText("$420.00", 890f, 740f, textPaint)

            canvas.drawRect(60f, 800f, pW - 60f, 802f, android.graphics.Paint().apply { color = android.graphics.Color.LTGRAY })

            // Totals
            canvas.drawText("Subtotal:", 680f, 860f, textPaint)
            canvas.drawText("$1,345.00", 880f, 860f, textPaint)
            
            canvas.drawText("Tax (10%):", 680f, 910f, textPaint)
            canvas.drawText("$134.50", 880f, 910f, textPaint)

            canvas.drawText("Grand Total Due:", 680f, 980f, boldPaint)
            canvas.drawText("$1,479.50", 880f, 980f, boldPaint)

            // Footer / Payment Instructions
            canvas.drawText("Note: Bank transfer to ACME Corporate Account routing #90218731.", 60f, 1150f, grayPaint)
            canvas.drawText("Thank you for choosing ACME Solutions! We appreciate your business.", 60f, 1200f, boldPaint)
        }
        "Receipt" -> {
            val centerTextPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                isAntiAlias = true
                textSize = 34f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText("STARBUCKS COFFEE", pW / 2, 120f, titlePaint.apply { textAlign = android.graphics.Paint.Align.CENTER })
            canvas.drawText("Store #10482 - Downtown Hub", pW / 2, 170f, grayPaint.apply { textAlign = android.graphics.Paint.Align.CENTER })
            canvas.drawText("Tel: 555-019-2026", pW / 2, 210f, textPaint.apply { textAlign = android.graphics.Paint.Align.CENTER })

            canvas.drawText("-----------------------------------------", pW / 2, 270f, centerTextPaint)

            // Receipt body
            val leftAlign = 100f
            val rightAlign = pW - 100f

            canvas.drawText("ORDER #489182  -  REG 01", leftAlign, 330f, boldPaint.apply { textAlign = android.graphics.Paint.Align.LEFT })
            canvas.drawText("Time: 08:42 AM", leftAlign, 380f, textPaint)

            canvas.drawText("1  Grande Oatmilk Latte", leftAlign, 470f, textPaint)
            canvas.drawText("$5.45", rightAlign, 470f, textPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT })

            canvas.drawText("1  Warm Butter Croissant", leftAlign, 530f, textPaint.apply { textAlign = android.graphics.Paint.Align.LEFT })
            canvas.drawText("$3.95", rightAlign, 530f, textPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT })

            canvas.drawText("1  Coffee Mug (Silver Edition)", leftAlign, 590f, textPaint.apply { textAlign = android.graphics.Paint.Align.LEFT })
            canvas.drawText("$19.99", rightAlign, 590f, textPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT })

            canvas.drawText("-----------------------------------------", pW / 2, 670f, centerTextPaint)

            canvas.drawText("Subtotal", leftAlign, 730f, textPaint.apply { textAlign = android.graphics.Paint.Align.LEFT })
            canvas.drawText("$29.39", rightAlign, 730f, textPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT })

            canvas.drawText("HST 13%", leftAlign, 780f, textPaint.apply { textAlign = android.graphics.Paint.Align.LEFT })
            canvas.drawText("$3.82", rightAlign, 780f, textPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT })

            canvas.drawText("TOTAL", leftAlign, 850f, boldPaint.apply { textAlign = android.graphics.Paint.Align.LEFT })
            canvas.drawText("$33.21", rightAlign, 850f, boldPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT })

            canvas.drawText("Visa Approved *********4182", leftAlign, 950f, grayPaint.apply { textAlign = android.graphics.Paint.Align.LEFT })
            canvas.drawText("Entry: Chip & Pin / Auth 0826A", leftAlign, 1000f, grayPaint)

            canvas.drawText("Thank You for Visiting Starbucks!", pW / 2, 1150f, boldPaint.apply { textAlign = android.graphics.Paint.Align.CENTER })
            canvas.drawText("Follow your streak in our mobile loyalty hub.", pW / 2, 1200f, grayPaint.apply { textAlign = android.graphics.Paint.Align.CENTER })
        }
        else -> { // Meeting Notes
            canvas.drawText("MEMORANDUM OF MEETING", 60f, 120f, titlePaint)
            canvas.drawText("Strategic Alignment & AI Roadmap Planning", 60f, 175f, grayPaint)

            canvas.drawText("Date: June 22, 2026", 60f, 260f, boldPaint)
            canvas.drawText("Moderator: Chief Architecture Officer", 60f, 310f, textPaint)
            canvas.drawText("Attendees: Engineering Team Leads", 60f, 360f, textPaint)

            canvas.drawRect(60f, 420f, pW - 60f, 422f, android.graphics.Paint().apply { color = android.graphics.Color.LTGRAY })

            canvas.drawText("SUMMARY OF KEY ISSUES & INITIATIVES:", 60f, 490f, boldPaint)
            
            var currentY = 560f
            val bullets = listOf(
                "Phase 1: Rollout localized offline Room SQLite indexes to cache files.",
                "Phase 2: Introduce edge image operations - Contrast adjustments & Grayscale matrix.",
                "Phase 3: Deploy unified REST connectors for Gemini multimodal Flash models.",
                "Phase 4: Run auto OCR boundaries to extract textual entities intelligently.",
                "Phase 5: Convert and bundle pages into standardized postscript point documents.",
                "Action Item: Configure API keys safely through AI Studio build BuildConfig mechanism."
            )

            for (bullet in bullets) {
                canvas.drawText("•", 60f, currentY, boldPaint)
                canvas.drawText(bullet, 110f, currentY, textPaint)
                currentY += 80f
            }

            canvas.drawText("Document Class: Highly Confidential Corporate Records", 60f, currentY + 120f, grayPaint)
        }
    }
    
    canvas.restore()
    return bmp
}
