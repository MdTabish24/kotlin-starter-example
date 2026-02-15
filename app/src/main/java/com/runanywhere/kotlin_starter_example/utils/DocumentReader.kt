package com.runanywhere.kotlin_starter_example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.InputStream

private const val TAG = "DocumentReader"

object DocumentReader {

    /**
     * Read a document from URI. Returns Pair(filename, content) or null on failure.
     * Supports: TXT, PDF, DOCX, PPTX, PNG, JPG, JPEG, BMP, WEBP
     */
    suspend fun readDocument(context: Context, uri: Uri): Pair<String, String>? {
        return try {
            val name = getFileName(context, uri) ?: "document"
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val extension = name.substringAfterLast('.', "").lowercase()
            
            Log.d(TAG, "Reading document: $name (mime: $mimeType, ext: $extension)")

            val content = when {
                // PDF
                extension == "pdf" || mimeType == "application/pdf" ->
                    readPDF(context, uri)

                // Word documents
                extension == "docx" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    readDOCX(context, uri)
                    
                extension == "doc" || mimeType == "application/msword" ->
                    readDOCX(context, uri) // POI can sometimes handle .doc too

                // PowerPoint
                extension == "pptx" || mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    readPPTX(context, uri)
                    
                extension == "ppt" || mimeType == "application/vnd.ms-powerpoint" ->
                    readPPTX(context, uri)

                // Images (OCR)
                extension in listOf("png", "jpg", "jpeg", "bmp", "webp") ||
                mimeType.startsWith("image/") ->
                    readImageOCR(context, uri)

                // Plain text / code files (fallback)
                else -> readPlainText(context, uri)
            }

            if (content.isNullOrBlank()) {
                Log.w(TAG, "No text extracted from: $name")
                null
            } else {
                Log.d(TAG, "Extracted ${content.length} chars from: $name")
                Pair(name, content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read document: ${e.message}", e)
            null
        }
    }

    // ═══════════════════════════════════════
    // PDF extraction using Android PdfRenderer
    // ═══════════════════════════════════════
    private suspend fun readPDF(context: Context, uri: Uri): String? {
        return try {
            // Method 1: Try iText7 text extraction first (best for text-based PDFs)
            val textFromIText = readPDFWithIText(context, uri)
            if (!textFromIText.isNullOrBlank() && textFromIText.trim().length > 50) {
                Log.d(TAG, "PDF text extracted with iText: ${textFromIText.length} chars")
                return textFromIText
            }

            // Method 2: Fall back to PdfRenderer + OCR for scanned/image PDFs
            Log.d(TAG, "iText returned little text, trying PdfRenderer + OCR...")
            readPDFWithOCR(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "PDF read failed: ${e.message}", e)
            null
        }
    }

    private fun readPDFWithIText(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val pdfReader = com.itextpdf.kernel.pdf.PdfReader(inputStream)
                val pdfDoc = com.itextpdf.kernel.pdf.PdfDocument(pdfReader)
                val sb = StringBuilder()

                for (i in 1..pdfDoc.numberOfPages) {
                    val page = pdfDoc.getPage(i)
                    val strategy = com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy()
                    val text = com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(page, strategy)
                    if (text.isNotBlank()) {
                        sb.append("--- Page $i ---\n")
                        sb.append(text.trim())
                        sb.append("\n\n")
                    }
                }

                pdfDoc.close()
                sb.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "iText extraction failed: ${e.message}")
            null
        }
    }

    private suspend fun readPDFWithOCR(context: Context, uri: Uri): String? {
        return try {
            val parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val renderer = PdfRenderer(parcelFileDescriptor)
            val sb = StringBuilder()
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            val maxPages = minOf(renderer.pageCount, 20) // Limit to 20 pages for performance
            
            for (i in 0 until maxPages) {
                val page = renderer.openPage(i)
                // Render at 2x for better OCR quality
                val bitmap = Bitmap.createBitmap(
                    page.width * 2,
                    page.height * 2,
                    Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // OCR the page bitmap
                val image = InputImage.fromBitmap(bitmap, 0)
                try {
                    val result = recognizer.process(image).await()
                    if (result.text.isNotBlank()) {
                        sb.append("--- Page ${i + 1} ---\n")
                        sb.append(result.text.trim())
                        sb.append("\n\n")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OCR failed for page ${i + 1}: ${e.message}")
                }
                bitmap.recycle()
            }

            renderer.close()
            parcelFileDescriptor.close()
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "PDF OCR failed: ${e.message}", e)
            null
        }
    }

    // ═══════════════════════════════════════
    // DOCX extraction using Apache POI
    // ═══════════════════════════════════════
    private fun readDOCX(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val document = XWPFDocument(inputStream)
                val sb = StringBuilder()

                // Extract paragraphs
                for (paragraph in document.paragraphs) {
                    val text = paragraph.text?.trim()
                    if (!text.isNullOrBlank()) {
                        sb.append(text)
                        sb.append("\n\n")
                    }
                }

                // Extract text from tables
                for (table in document.tables) {
                    for (row in table.rows) {
                        val rowTexts = row.tableCells.mapNotNull { cell ->
                            cell.text?.trim()?.takeIf { it.isNotBlank() }
                        }
                        if (rowTexts.isNotEmpty()) {
                            sb.append(rowTexts.joinToString(" | "))
                            sb.append("\n")
                        }
                    }
                    sb.append("\n")
                }

                document.close()
                sb.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "DOCX read failed: ${e.message}", e)
            // Fallback: try reading as plain text
            readPlainText(context, uri)
        }
    }

    // ═══════════════════════════════════════
    // PPTX extraction using Apache POI
    // ═══════════════════════════════════════
    private fun readPPTX(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val pptx = XMLSlideShow(inputStream)
                val sb = StringBuilder()

                pptx.slides.forEachIndexed { index, slide ->
                    sb.append("=== Slide ${index + 1} ===\n")

                    // Get title
                    slide.title?.let { title ->
                        if (title.isNotBlank()) {
                            sb.append("# $title\n\n")
                        }
                    }

                    // Extract text from all shapes
                    for (shape in slide.shapes) {
                        if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape) {
                            for (paragraph in shape.textParagraphs) {
                                val text = paragraph.text?.trim()
                                if (!text.isNullOrBlank()) {
                                    sb.append(text)
                                    sb.append("\n")
                                }
                            }
                        }
                        // Tables in slides
                        if (shape is org.apache.poi.xslf.usermodel.XSLFTable) {
                            for (row in shape.rows) {
                                val cells = row.cells.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotBlank() } }
                                if (cells.isNotEmpty()) {
                                    sb.append(cells.joinToString(" | "))
                                    sb.append("\n")
                                }
                            }
                        }
                    }

                    // Notes
                    slide.notes?.let { notes ->
                        for (shape in notes.shapes) {
                            if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape) {
                                val noteText = shape.text?.trim()
                                if (!noteText.isNullOrBlank() && noteText != "Slide ${index + 1}") {
                                    sb.append("\n[Speaker Notes: $noteText]\n")
                                }
                            }
                        }
                    }

                    sb.append("\n")
                }

                pptx.close()
                sb.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "PPTX read failed: ${e.message}", e)
            null
        }
    }

    // ═══════════════════════════════════════
    // Image OCR using Google ML Kit
    // ═══════════════════════════════════════
    private suspend fun readImageOCR(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image bitmap")
                return null
            }

            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            val result = recognizer.process(image).await()
            bitmap.recycle()
            
            if (result.text.isNotBlank()) {
                Log.d(TAG, "OCR extracted: ${result.text.length} chars")
                result.text
            } else {
                Log.w(TAG, "OCR returned no text")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image OCR failed: ${e.message}", e)
            null
        }
    }

    // ═══════════════════════════════════════
    // Plain text (TXT, code files, etc.)
    // ═══════════════════════════════════════
    private fun readPlainText(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════
    // Get file name
    // ═══════════════════════════════════════
    private fun getFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
