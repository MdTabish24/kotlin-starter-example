package com.runanywhere.kotlin_starter_example.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.*
import com.itextpdf.layout.properties.HorizontalAlignment
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.itextpdf.layout.properties.VerticalAlignment
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.itextpdf.io.image.ImageDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

private const val TAG = "PdfExport"

/**
 * Represents a single Q&A entry to include in the PDF.
 */
data class PdfQAEntry(
    val question: String,
    val answer: String,
    val keyPoints: String? = null,
    val diagramCode: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Generates professional study-notes PDFs from Q&A entries.
 * Uses iText 8 (already in dependencies) with lightweight operations
 * to avoid memory pressure on 6GB devices.
 */
object PdfExportManager {

    // Colors matching the app theme
    private val VIOLET = DeviceRgb(108, 99, 255)     // AccentViolet
    private val CYAN = DeviceRgb(0, 188, 212)        // AccentCyan
    private val DARK_BG = DeviceRgb(13, 13, 26)      // PrimaryDark
    private val LIGHT_GRAY = DeviceRgb(240, 244, 255)
    private val TEXT_DARK = DeviceRgb(26, 32, 44)
    private val TEXT_MUTED = DeviceRgb(120, 130, 150)
    private val KEY_POINT_BG = DeviceRgb(255, 251, 235) // Warm amber bg
    private val KEY_POINT_BORDER = DeviceRgb(245, 158, 11) // Amber

    /**
     * Generate a PDF file from the given Q&A entries.
     * Returns the File object, or null on failure.
     *
     * MEMORY SAFE: Uses streaming writer, no large in-memory buffers.
     */
    fun generatePdf(
        context: Context,
        entries: List<PdfQAEntry>,
        diagramImages: Map<Long, ByteArray> = emptyMap(),
        documentName: String? = null
    ): File? {
        if (entries.isEmpty()) return null

        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "YouLearn_Notes_$timestamp.pdf"
            val outputDir = File(context.cacheDir, "pdf_exports")
            outputDir.mkdirs()
            val outputFile = File(outputDir, fileName)

            val writer = PdfWriter(outputFile)
            val pdfDoc = PdfDocument(writer)
            val document = Document(pdfDoc, PageSize.A4)
            document.setMargins(40f, 40f, 40f, 40f)

            // Use standard fonts (no external font files needed)
            val fontRegular = PdfFontFactory.createFont("Helvetica")
            val fontBold = PdfFontFactory.createFont("Helvetica-Bold")
            val fontItalic = PdfFontFactory.createFont("Helvetica-Oblique")

            // ── Title Page Header ──
            val title = documentName?.let { "Study Notes: $it" } ?: "YouLearn Study Notes"
            document.add(
                Paragraph(title)
                    .setFont(fontBold)
                    .setFontSize(22f)
                    .setFontColor(VIOLET)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(5f)
            )

            val dateStr = SimpleDateFormat("MMMM dd, yyyy • hh:mm a", Locale.US).format(Date())
            document.add(
                Paragraph("Generated on $dateStr")
                    .setFont(fontItalic)
                    .setFontSize(10f)
                    .setFontColor(TEXT_MUTED)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(5f)
            )

            document.add(
                Paragraph("${entries.size} Q&A entries")
                    .setFont(fontRegular)
                    .setFontSize(10f)
                    .setFontColor(TEXT_MUTED)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(20f)
            )

            // Divider line
            document.add(
                Paragraph("")
                    .setBorderBottom(SolidBorder(CYAN, 2f))
                    .setMarginBottom(20f)
            )

            // ── Q&A Entries ──
            entries.forEachIndexed { index, entry ->
                // Question section
                document.add(
                    Paragraph("Q${index + 1}.")
                        .setFont(fontBold)
                        .setFontSize(11f)
                        .setFontColor(VIOLET)
                        .setMarginBottom(2f)
                        .setMarginTop(if (index > 0) 20f else 0f)
                )

                // Question box
                val questionTable = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
                    .useAllAvailableWidth()
                val questionCell = Cell()
                    .setBackgroundColor(DeviceRgb(237, 233, 254)) // Light violet
                    .setBorder(SolidBorder(VIOLET, 1f))
                    .setPadding(10f)
                    .add(
                        Paragraph(entry.question)
                            .setFont(fontBold)
                            .setFontSize(11f)
                            .setFontColor(TEXT_DARK)
                    )
                questionTable.addCell(questionCell)
                document.add(questionTable)
                document.add(Paragraph("").setMarginBottom(8f))

                // Answer section
                document.add(
                    Paragraph("Answer:")
                        .setFont(fontBold)
                        .setFontSize(11f)
                        .setFontColor(CYAN)
                        .setMarginBottom(4f)
                )

                // Format answer — handle markdown-like bullets and paragraphs
                val answerParagraphs = formatAnswerText(entry.answer, fontRegular, fontBold)
                answerParagraphs.forEach { document.add(it) }

                // Key Points section (if available)
                if (!entry.keyPoints.isNullOrBlank()) {
                    document.add(Paragraph("").setMarginTop(10f))
                    document.add(
                        Paragraph("Key Points")
                            .setFont(fontBold)
                            .setFontSize(11f)
                            .setFontColor(KEY_POINT_BORDER)
                            .setMarginBottom(4f)
                    )

                    val kpTable = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
                        .useAllAvailableWidth()
                    val kpCell = Cell()
                        .setBackgroundColor(KEY_POINT_BG)
                        .setBorder(SolidBorder(KEY_POINT_BORDER, 1f))
                        .setPadding(10f)

                    entry.keyPoints.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            kpCell.add(
                                Paragraph(trimmed)
                                    .setFont(fontRegular)
                                    .setFontSize(10f)
                                    .setFontColor(TEXT_DARK)
                                    .setMarginBottom(3f)
                            )
                        }
                    }
                    kpTable.addCell(kpCell)
                    document.add(kpTable)
                }

                // Diagram section (embedded image or text-based flowchart)
                if (!entry.diagramCode.isNullOrBlank()) {
                    document.add(Paragraph("").setMarginTop(10f))
                    document.add(
                        Paragraph("Visual Diagram")
                            .setFont(fontBold)
                            .setFontSize(11f)
                            .setFontColor(CYAN)
                            .setMarginBottom(4f)
                    )

                    val diagramBytes = diagramImages[entry.timestamp]
                    if (diagramBytes != null && diagramBytes.isNotEmpty()) {
                        try {
                            val imgData = ImageDataFactory.create(diagramBytes)
                            val img = com.itextpdf.layout.element.Image(imgData)
                            val availWidth = PageSize.A4.width - 80f
                            img.scaleToFit(availWidth, 400f)

                            val imgTable = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
                                .useAllAvailableWidth()
                            val imgCell = Cell()
                                .setBorder(SolidBorder(CYAN, 1f))
                                .setPadding(8f)
                                .add(img)
                            imgTable.addCell(imgCell)
                            document.add(imgTable)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to embed diagram image, using text: ${e.message}")
                            addTextDiagram(document, entry.diagramCode, fontRegular, fontBold)
                        }
                    } else {
                        addTextDiagram(document, entry.diagramCode, fontRegular, fontBold)
                    }
                }

                // Separator between entries
                if (index < entries.size - 1) {
                    document.add(
                        Paragraph("")
                            .setBorderBottom(SolidBorder(DeviceRgb(200, 200, 210), 0.5f))
                            .setMarginTop(15f)
                            .setMarginBottom(5f)
                    )
                }
            }

            // ── Footer ──
            document.add(
                Paragraph("")
                    .setBorderTop(SolidBorder(CYAN, 1f))
                    .setMarginTop(30f)
            )
            document.add(
                Paragraph("Generated by YouLearn AI • On-Device Learning Assistant")
                    .setFont(fontItalic)
                    .setFontSize(8f)
                    .setFontColor(TEXT_MUTED)
                    .setTextAlignment(TextAlignment.CENTER)
            )

            document.close()
            Log.d(TAG, "PDF generated: ${outputFile.absolutePath} (${outputFile.length() / 1024}KB)")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "PDF generation failed: ${e.message}", e)
            null
        }
    }

    /**
     * Format answer text: handle bullet points, numbered lists, paragraphs.
     * Returns a list of iText Paragraph elements.
     */
    private fun formatAnswerText(
        text: String,
        fontRegular: com.itextpdf.kernel.font.PdfFont,
        fontBold: com.itextpdf.kernel.font.PdfFont
    ): List<Paragraph> {
        val paragraphs = mutableListOf<Paragraph>()
        val lines = text.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                // Bold header lines (** text **)
                trimmed.startsWith("**") && trimmed.endsWith("**") -> {
                    val clean = trimmed.removePrefix("**").removeSuffix("**").trim()
                    paragraphs.add(
                        Paragraph(clean)
                            .setFont(fontBold)
                            .setFontSize(11f)
                            .setFontColor(TEXT_DARK)
                            .setMarginTop(6f)
                            .setMarginBottom(2f)
                    )
                }
                // Bullet points (- or • or *)
                trimmed.startsWith("- ") || trimmed.startsWith("• ") || trimmed.startsWith("* ") -> {
                    val content = trimmed.substring(2).trim()
                    paragraphs.add(
                        Paragraph("  •  $content")
                            .setFont(fontRegular)
                            .setFontSize(10f)
                            .setFontColor(TEXT_DARK)
                            .setMarginLeft(15f)
                            .setMarginBottom(2f)
                    )
                }
                // Numbered lists (1. 2. etc.)
                trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    paragraphs.add(
                        Paragraph("  $trimmed")
                            .setFont(fontRegular)
                            .setFontSize(10f)
                            .setFontColor(TEXT_DARK)
                            .setMarginLeft(10f)
                            .setMarginBottom(2f)
                    )
                }
                // Regular paragraph
                else -> {
                    // Handle inline bold (**text**)
                    val p = Paragraph()
                        .setFont(fontRegular)
                        .setFontSize(10f)
                        .setFontColor(TEXT_DARK)
                        .setMarginBottom(4f)

                    val parts = trimmed.split("**")
                    parts.forEachIndexed { idx, part ->
                        if (part.isEmpty()) return@forEachIndexed
                        if (idx % 2 == 1) {
                            // Bold part
                            p.add(Text(part).setFont(fontBold))
                        } else {
                            p.add(Text(part))
                        }
                    }
                    paragraphs.add(p)
                }
            }
        }

        return paragraphs
    }

    /**
     * Add a VISUAL flowchart diagram to the PDF using iText tables as boxes.
     * Parses Mermaid code → nodes + edges → tree levels → renders box grid.
     * Works 100% reliably — no WebView, no CDN dependency.
     */
    private fun addTextDiagram(
        document: Document,
        mermaidCode: String,
        fontRegular: com.itextpdf.kernel.font.PdfFont,
        fontBold: com.itextpdf.kernel.font.PdfFont
    ) {
        // ── Emoji stripping for clean PDF text ──
        val emojiRegex = Regex("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F900}-\\x{1F9FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{FE00}-\\x{FE0F}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]")
        fun strip(text: String): String {
            return try {
                text.replace(emojiRegex, "").trim()
            } catch (_: Exception) {
                text.filter { it.code < 0x2600 || (it.code in 0x2010..0x25FF) }.trim()
            }
        }

        // ── Parse Mermaid → nodes + edges ──
        val nodes = mutableMapOf<String, String>()
        val edges = mutableListOf<Pair<String, String>>()

        for (line in mermaidCode.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("graph") || t.startsWith("flowchart") || t.startsWith("%%")) continue

            // Extract node definitions: A[label]
            Regex("""(\w+)\s*\[([^\]]+)\]""").findAll(t).forEach { m ->
                val id = m.groupValues[1]
                val label = strip(m.groupValues[2])
                if (label.isNotBlank() && (id !in nodes || nodes[id] == id)) {
                    nodes[id] = label
                }
            }

            // Extract edges: handles A[...] --> B[...] format (inline labels)
            Regex("""(\w+)\s*(?:\[[^\]]*\])?\s*[-=]+>+\s*(\w+)""").findAll(t).forEach { m ->
                val from = m.groupValues[1]
                val to = m.groupValues[2]
                edges.add(from to to)
                nodes.putIfAbsent(from, from)
                nodes.putIfAbsent(to, to)
            }
        }

        if (nodes.isEmpty()) return

        // ── Build tree levels via BFS ──
        val targets = edges.map { it.second }.toSet()
        val roots = nodes.keys.filter { it !in targets }.ifEmpty { listOf(nodes.keys.first()) }
        val levels = mutableListOf<List<String>>()
        var currentLevel = roots.toList()
        val visited = mutableSetOf<String>()

        while (currentLevel.isNotEmpty()) {
            levels.add(currentLevel)
            visited.addAll(currentLevel)
            currentLevel = currentLevel.flatMap { parent ->
                edges.filter { it.first == parent && it.second !in visited }.map { it.second }
            }.distinct()
        }

        // Add orphan nodes
        val orphans = nodes.keys.filter { it !in visited }
        if (orphans.isNotEmpty()) levels.add(orphans)

        // ── Colors ──
        val nodeBoxBg = DeviceRgb(235, 240, 255)       // light blue
        val nodeBorderColor = DeviceRgb(59, 76, 107)   // dark blue-gray
        val nodeTextColor = DeviceRgb(26, 32, 44)       // near black
        val arrowColor = DeviceRgb(80, 100, 130)        // slate blue
        val wrapperBg = DeviceRgb(250, 251, 255)        // very light bg

        // ── Outer wrapper table ──
        val outerTable = Table(UnitValue.createPercentArray(floatArrayOf(100f)))
            .useAllAvailableWidth()
        val outerCell = Cell()
            .setBorder(SolidBorder(CYAN, 1.5f))
            .setPadding(15f)
            .setBackgroundColor(wrapperBg)

        // ── Title ──
        outerCell.add(
            Paragraph("FLOWCHART")
                .setFont(fontBold)
                .setFontSize(10f)
                .setFontColor(CYAN)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(12f)
        )

        // ── Render each tree level as a row of boxes ──
        for (levelIdx in levels.indices) {
            val levelNodes = levels[levelIdx]
            val cols = levelNodes.size

            // Add padding columns on sides for centering effect
            val totalCols = cols + 2
            val colWidths = FloatArray(totalCols) { if (it == 0 || it == totalCols - 1) 0.5f else 1f }
            val levelTable = Table(UnitValue.createPercentArray(colWidths))
                .useAllAvailableWidth()

            // Left spacer
            levelTable.addCell(Cell().setBorder(Border.NO_BORDER).setPadding(0f))

            for (nodeId in levelNodes) {
                val label = nodes[nodeId] ?: nodeId
                val nodeCell = Cell()
                    .setBackgroundColor(nodeBoxBg)
                    .setBorder(SolidBorder(nodeBorderColor, 1.5f))
                    .setPadding(7f)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                nodeCell.add(
                    Paragraph(label)
                        .setFont(fontBold)
                        .setFontSize(8.5f)
                        .setFontColor(nodeTextColor)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setMarginBottom(0f)
                )
                levelTable.addCell(nodeCell)
            }

            // Right spacer
            levelTable.addCell(Cell().setBorder(Border.NO_BORDER).setPadding(0f))

            outerCell.add(levelTable)

            // ── Arrow connectors between levels ──
            if (levelIdx < levels.size - 1) {
                val arrowTable = Table(UnitValue.createPercentArray(colWidths))
                    .useAllAvailableWidth()

                // Left spacer
                arrowTable.addCell(Cell().setBorder(Border.NO_BORDER).setPadding(0f))

                for (nodeId in levelNodes) {
                    val hasChild = edges.any { it.first == nodeId }
                    val arrowCell = Cell()
                        .setBorder(Border.NO_BORDER)
                        .setPadding(2f)
                        .setTextAlignment(TextAlignment.CENTER)
                    arrowCell.add(
                        Paragraph(if (hasChild) "|\nV" else "")
                            .setFont(fontRegular)
                            .setFontSize(9f)
                            .setFontColor(arrowColor)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setMarginBottom(0f)
                    )
                    arrowTable.addCell(arrowCell)
                }

                // Right spacer
                arrowTable.addCell(Cell().setBorder(Border.NO_BORDER).setPadding(0f))

                outerCell.add(arrowTable)
            }
        }

        outerTable.addCell(outerCell)
        document.add(outerTable)
    }

    /**
     * Capture a Mermaid diagram as a bitmap using off-screen WebView.
     * Returns PNG byte array or null. MEMORY SAFE: bitmap recycled after compress.
     */
    suspend fun captureMermaidBitmap(context: Context, mermaidCode: String): ByteArray? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    val webView = WebView(context)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.setBackgroundColor(android.graphics.Color.WHITE)

                    val width = 900
                    val height = 700
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, width, height)

                    val handler = Handler(Looper.getMainLooper())
                    val timeoutRunnable = Runnable {
                        if (!cont.isCompleted) {
                            Log.w(TAG, "Diagram capture timed out")
                            try { webView.destroy() } catch (_: Exception) {}
                            cont.resume(null)
                        }
                    }
                    handler.postDelayed(timeoutRunnable, 8000)

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.postDelayed({
                                try {
                                    if (cont.isCompleted) return@postDelayed
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    val canvas = Canvas(bitmap)
                                    canvas.drawColor(android.graphics.Color.WHITE)
                                    view.draw(canvas)
                                    val baos = ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
                                    bitmap.recycle()
                                    handler.removeCallbacks(timeoutRunnable)
                                    view.destroy()
                                    if (!cont.isCompleted) cont.resume(baos.toByteArray())
                                } catch (e: Exception) {
                                    Log.e(TAG, "Bitmap capture error: ${e.message}")
                                    handler.removeCallbacks(timeoutRunnable)
                                    try { view.destroy() } catch (_: Exception) {}
                                    if (!cont.isCompleted) cont.resume(null)
                                }
                            }, 3000)
                        }
                    }

                    webView.loadDataWithBaseURL(
                        "https://cdn.jsdelivr.net",
                        buildMermaidCaptureHtml(mermaidCode),
                        "text/html", "UTF-8", null
                    )

                    cont.invokeOnCancellation {
                        handler.removeCallbacks(timeoutRunnable)
                        try { webView.destroy() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WebView creation failed: ${e.message}")
                    if (!cont.isCompleted) cont.resume(null)
                }
            }
        }
    }

    private fun buildMermaidCaptureHtml(mermaidCode: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { background: #FFFFFF; padding: 16px; display: flex; justify-content: center; font-family: 'Segoe UI', system-ui, sans-serif; }
                    .mermaid { width: 100%; }
                    .mermaid svg { width: 100% !important; height: auto !important; }
                    .node rect, .node polygon {
                        fill: #F0F4FF !important; stroke: #3B4C6B !important;
                        stroke-width: 2px !important; rx: 10px !important;
                        filter: drop-shadow(0px 2px 4px rgba(0,0,0,0.1)) !important;
                    }
                    .nodeLabel { color: #1A202C !important; font-size: 14px !important; font-weight: 600 !important; }
                    .edgePath .path { stroke: #2D3748 !important; stroke-width: 2px !important; }
                    marker path { fill: #2D3748 !important; }
                </style>
            </head>
            <body>
                <div class="mermaid">${mermaidCode}</div>
                <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
                <script>
                    mermaid.initialize({
                        startOnLoad: true, theme: 'base',
                        themeVariables: { primaryColor: '#F0F4FF', primaryTextColor: '#1A202C', primaryBorderColor: '#3B4C6B', lineColor: '#2D3748', fontSize: '14px' },
                        flowchart: { useMaxWidth: true, htmlLabels: true, curve: 'basis', nodeSpacing: 40, rankSpacing: 50 }
                    });
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Share/save the PDF file using Android's share intent.
     */
    fun sharePdf(context: Context, pdfFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Save Study Notes PDF"))
        } catch (e: Exception) {
            Log.e(TAG, "Share PDF failed: ${e.message}", e)
        }
    }
}
