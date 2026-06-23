package com.example.util

import android.graphics.*
import java.io.File
import java.io.FileOutputStream

object ImageFilterHelper {

    enum class FilterType {
        NONE,
        GRAYSCALE,
        DOCUMENT_BW, // Documents/Monochrome
        SHARPEN,
        VINTAGE,
        CONTRAST_BRIGHTNESS,
        CLEANUP // Screen shadow-removal/contrast boost
    }

    /**
     * Applies a chosen visual filter onto a Bitmap and returns a new Bitmap.
     */
    fun applyFilter(
        source: Bitmap,
        filterType: FilterType,
        contrastVal: Float = 1.0f,
        brightnessVal: Float = 0.0f
    ): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true }

        val colorMatrix = ColorMatrix()

        when (filterType) {
            FilterType.NONE -> {
                canvas.drawBitmap(source, 0f, 0f, paint)
                return output
            }
            FilterType.GRAYSCALE -> {
                colorMatrix.setSaturation(0f)
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.DOCUMENT_BW -> {
                // Highly aggressive monochrome threshold simulation using grayscaling & high contrast boost
                val grayscaleMatrix = ColorMatrix().apply { setSaturation(0f) }
                // High contrast scale and threshold shift
                val scale = 3.0f
                val shift = -128f * scale + 128f + 10f // Boost white point slightly to wash out gray backgrounds
                val contrastMatrix = ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, shift,
                    0f, scale, 0f, 0f, shift,
                    0f, 0f, scale, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                ))
                grayscaleMatrix.postConcat(contrastMatrix)
                paint.colorFilter = ColorMatrixColorFilter(grayscaleMatrix)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.VINTAGE -> {
                val vintageMatrix = ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f,     0f,     0f,     1f, 0f
                ))
                paint.colorFilter = ColorMatrixColorFilter(vintageMatrix)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.CONTRAST_BRIGHTNESS -> {
                val scale = contrastVal
                val shift = brightnessVal // brightness offset -255 to 255
                val matrix = ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, shift,
                    0f, scale, 0f, 0f, shift,
                    0f, 0f, scale, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                ))
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.CLEANUP -> {
                // Remove shadows and improve readability by aggressively scaling low tones to white and dark tones to darker
                // (simulating adaptive white point)
                val grayscaleMatrix = ColorMatrix().apply { setSaturation(0f) }
                val scale = 2.2f
                val shift = -60f * scale + 90f // Higher gamma shift
                val cleanupMatrix = ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, shift,
                    0f, scale, 0f, 0f, shift,
                    0f, 0f, scale, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                ))
                grayscaleMatrix.postConcat(cleanupMatrix)
                paint.colorFilter = ColorMatrixColorFilter(grayscaleMatrix)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
            FilterType.SHARPEN -> {
                // Draw normal bitmap first
                canvas.drawBitmap(source, 0f, 0f, paint)
                // Quick Convolution matrix filter for sharpen can be implemented manually or with a simple kernel.
                // We will use standard manual sharpened pixel logic or a simplified layout matrix.
                return applySharpenKernel(source)
            }
        }
        return output
    }

    /**
     * Natively sharpens an image using a standard 3x3 convolution kernel:
     * [  0, -1,  0 ]
     * [ -1,  5, -1 ]
     * [  0, -1,  0 ]
     */
    private fun applySharpenKernel(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)
        
        // Fast manual pixel manipulation
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Copy margins to avoid out of bounds
        System.arraycopy(pixels, 0, outPixels, 0, width)
        for (y in 1 until height - 1) {
            outPixels[y * width] = pixels[y * width]
            outPixels[y * width + width - 1] = pixels[y * width + width - 1]
        }
        System.arraycopy(pixels, (height - 1) * width, outPixels, (height - 1) * width, width)

        // Run sharpening kernel on inner pixels
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                
                // Neighbors
                val center = pixels[index]
                val top = pixels[index - width]
                val bottom = pixels[index + width]
                val left = pixels[index - 1]
                val right = pixels[index + 1]

                // Red channel
                val rC = Color.red(center)
                val rT = Color.red(top)
                val rB = Color.red(bottom)
                val rL = Color.red(left)
                val rR = Color.red(right)
                val rNew = (rC * 5 - rT - rB - rL - rR).coerceIn(0, 255)

                // Green channel
                val gC = Color.green(center)
                val gT = Color.green(top)
                val gB = Color.green(bottom)
                val gL = Color.green(left)
                val gR = Color.green(right)
                val gNew = (gC * 5 - gT - gB - gL - gR).coerceIn(0, 255)

                // Blue channel
                val bC = Color.blue(center)
                val bT = Color.blue(top)
                val bB = Color.blue(bottom)
                val bL = Color.blue(left)
                val bR = Color.blue(right)
                val bNew = (bC * 5 - bT - bB - bL - bR).coerceIn(0, 255)

                outPixels[index] = Color.rgb(rNew, gNew, bNew)
            }
        }
        
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Saves a Bitmap object to a local app-specific file path.
     */
    fun saveBitmapToFile(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 90) {
        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
        }
    }
}
