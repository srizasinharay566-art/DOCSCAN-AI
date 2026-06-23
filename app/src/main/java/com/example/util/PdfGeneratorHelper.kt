package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

object PdfGeneratorHelper {

    enum class PageSize {
        A4, LETTER, CUSTOM
    }

    enum class CompressionLevel {
        HIGH_QUALITY,   // 95% quality, no downscaling
        BALANCED,       // 75% quality, slight downscaling
        MAXIMUM        // 45% quality, 50% image downscaling
    }

    /**
     * Converts a list of image paths to a single PDF document.
     * Supports page sizes A4, Letter, Custom.
     * Supports compression levels.
     */
    fun createPdfFromImages(
        imagePaths: List<String>,
        outputFile: File,
        pageSizeType: PageSize,
        compressionLevel: CompressionLevel
    ): File {
        val pdfDocument = PdfDocument()

        // Page dimensions in Standard points (1/72 inch)
        // A4: 595 x 842 points ~ 8.27 x 11.69 inches
        // Letter: 612 x 792 points ~ 8.5 x 11 inches
        // Custom: 1200 x 1600 points (hires layout)
        val (pageWidth, pageHeight) = when (pageSizeType) {
            PageSize.A4 -> 595 to 842
            PageSize.LETTER -> 612 to 792
            PageSize.CUSTOM -> 1000 to 1400
        }

        for ((index, path) in imagePaths.withIndex()) {
            val file = File(path)
            if (!file.exists()) continue

            var bitmap = BitmapFactory.decodeFile(path) ?: continue

            // Determine image modification based on compression level
            val quality = when (compressionLevel) {
                CompressionLevel.HIGH_QUALITY -> 95
                CompressionLevel.BALANCED -> 75
                CompressionLevel.MAXIMUM -> 45
            }

            // Downscale image inside BITMAP creation memory if maximum compression or balanced is chosen
            if (compressionLevel == CompressionLevel.MAXIMUM) {
                val targetW = (bitmap.width * 0.5f).toInt()
                val targetH = (bitmap.height * 0.5f).toInt()
                if (targetW > 10 && targetH > 10) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    bitmap = scaled
                }
            } else if (compressionLevel == CompressionLevel.BALANCED) {
                val targetW = (bitmap.width * 0.75f).toInt()
                val targetH = (bitmap.height * 0.75f).toInt()
                if (targetW > 10 && targetH > 10) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    bitmap = scaled
                }
            }

            // Create page
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val margin = 20f
            val availW = pageWidth - 2 * margin
            val availH = pageHeight - 2 * margin

            val scaleX = availW / bitmap.width
            val scaleY = availH / bitmap.height
            val scale = minOf(scaleX, scaleY)

            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale

            val left = margin + (availW - drawW) / 2f
            val top = margin + (availH - drawH) / 2f

            val destRect = Rect(
                left.toInt(),
                top.toInt(),
                (left + drawW).toInt(),
                (top + drawH).toInt()
            )

            // Draw bitmap on canvas
            canvas.drawBitmap(bitmap, null, destRect, null)
            pdfDocument.finishPage(page)
        }

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
    }
}
