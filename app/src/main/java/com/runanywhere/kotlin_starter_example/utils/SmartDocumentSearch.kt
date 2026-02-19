package com.runanywhere.kotlin_starter_example.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * SmartDocumentSearch v3 — FILE-BASED Intelligent document retrieval.
 *
 * ═══ THE PROBLEM (v2) ═══
 * v2 kept EVERYTHING in memory:
 *   fullText (500KB) + lines[] + chunks[] (with word maps) + wordIndex + coOccurrence + globalWordFreq
 *   = 5-10x the raw document size in Java heap.
 *   On 6GB devices: this + LLM (1GB) + KV cache (224MB) → SIGABRT crash.
 *
 * ═══ THE FIX (v3) ═══
 * Store document content in a FILE on the phone.
 * Keep only a TINY in-memory index: word → list of chunk IDs (ints).
 * On search: read only the matching chunks from disk.
 *
 * Memory footprint:
 *   v2: ~5MB for a 100-page PDF (all in heap)
 *   v3: ~200KB in heap (just word→chunkID map) + disk files
 *
 * Flow:
 * 1. User uploads doc → indexDocument() writes text to disk, builds tiny word→chunkID index in memory
 * 2. User asks "What is NLP?" → expandKeywords() finds related terms from in-memory word lists
 * 3. Multi-keyword scoring identifies best chunk IDs
 * 4. Only the BEST matching chunks are READ FROM DISK and sent to LLM
 */
object SmartDocumentSearch {

    private const val TAG = "SmartDocSearch"

    // ── Configuration ──
    private const val CHUNK_SIZE_LINES = 20
    private const val CHUNK_OVERLAP_LINES = 7
    private const val MAX_RESULTS_DEFAULT = 4
    private const val MAX_RESULTS_HIGH_CONF = 3
    private const val MAX_RESULTS_LOW_CONF = 5
    private const val CONTEXT_LINES_BEFORE = 3
    private const val CONTEXT_LINES_AFTER = 3
    private const val CO_OCCUR_TOP_N = 4

    // ── DISK-BASED storage ──
    private var docDir: File? = null
    private var fullTextFile: File? = null

    // ── LIGHTWEIGHT in-memory index (the only things kept in RAM) ──
    // word → set of chunk indices (just integers, very small)
    private var wordToChunks: Map<String, Set<Int>> = emptyMap()
    // word → top co-occurring words (just strings, compact)
    private var wordRelations: Map<String, List<String>> = emptyMap()
    private var totalChunks: Int = 0
    private var totalLines: Int = 0
    private var docLength: Int = 0
    // Line byte offsets for random access reading from disk
    private var lineByteOffsets: LongArray = LongArray(0)

    private var isIndexed: Boolean = false

    /**
     * Chunk metadata stored in memory — NO text content, just positions + word set.
     */
    data class ChunkMeta(
        val index: Int,
        val startLine: Int,
        val endLine: Int,
        val wordSet: Set<String>
    )

    private var chunkMetas: List<ChunkMeta> = emptyList()

    /**
     * Search result with relevance score.
     */
    data class SearchResult(
        val chunkIndex: Int,
        val startLine: Int,
        val endLine: Int,
        val score: Double,
        val matchedKeywords: List<String>,
        val confidence: Float
    )

    // ── Stop words ──
    private val STOP_WORDS = setOf(
        "a", "an", "the", "is", "it", "in", "on", "at", "to", "for", "of", "and",
        "or", "but", "not", "with", "this", "that", "from", "by", "as", "be", "was",
        "were", "been", "being", "have", "has", "had", "do", "does", "did", "will",
        "would", "could", "should", "may", "might", "can", "shall", "are", "am",
        "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their", "what", "which", "who", "whom",
        "how", "when", "where", "why", "if", "then", "so", "than", "too", "very",
        "just", "about", "also", "into", "over", "after", "before", "between",
        "under", "above", "such", "each", "all", "any", "both", "few", "more",
        "most", "other", "some", "no", "only", "own", "same", "up", "out", "off",
        "ka", "ki", "ke", "hai", "ho", "hain", "ya", "aur", "ye", "wo", "kya",
        "kaise", "kaha", "kab", "kaun", "ko", "se", "me", "par", "ne", "tha",
        "thi", "the", "mein", "bhi", "nahi", "nhi", "kr", "krna", "kro", "kre",
        "toh", "na", "haan", "ji", "ek", "do", "teen", "char",
        "tell", "explain", "describe", "define", "summarize", "summary", "mean",
        "meaning", "means", "please", "give", "show", "list", "write", "note",
        "discuss", "elaborate", "detail", "details", "briefly", "short", "long"
    )

    // ── Question type patterns ──
    private data class QuestionType(
        val name: String,
        val contextWords: List<String>
    )

    private val QUESTION_TYPES = listOf(
        QuestionType("definition", listOf("definition", "defined", "means", "meaning", "refers", "is", "called", "known", "introduction", "overview", "concept")),
        QuestionType("process", listOf("process", "steps", "method", "procedure", "algorithm", "works", "working", "mechanism", "flow", "pipeline", "stages")),
        QuestionType("comparison", listOf("difference", "differences", "compare", "comparison", "versus", "vs", "contrast", "unlike", "similar", "similarity")),
        QuestionType("example", listOf("example", "examples", "instance", "instances", "case", "cases", "illustration", "demonstrate", "sample")),
        QuestionType("advantage", listOf("advantage", "advantages", "benefit", "benefits", "pros", "merit", "merits", "strength", "strengths", "useful")),
        QuestionType("disadvantage", listOf("disadvantage", "disadvantages", "drawback", "drawbacks", "cons", "limitation", "limitations", "weakness")),
        QuestionType("types", listOf("types", "type", "kinds", "categories", "classification", "classes", "forms", "variants", "varieties")),
        QuestionType("application", listOf("application", "applications", "uses", "used", "usage", "use", "applied", "real", "practical", "industry")),
        QuestionType("cause", listOf("cause", "causes", "reason", "reasons", "why", "because", "due", "leads", "results", "factor", "factors")),
        QuestionType("feature", listOf("feature", "features", "characteristic", "characteristics", "property", "properties", "attribute", "attributes"))
    )

    // ═════════════════════════════════════════
    // ── INDEXING (writes text to disk, keeps minimal index in RAM) ──
    // ═════════════════════════════════════════

    /**
     * Index a document: writes full text to disk file.
     * Only keeps a lightweight word→chunkID index in memory.
     * Returns the number of chunks created.
     */
    fun indexDocument(text: String, context: Context): Int {
        Log.d(TAG, "═══ Indexing document (FILE-BASED): ${text.length} chars ═══")

        // Step 0: Setup disk directory
        docDir = File(context.filesDir, "youlearn_doc_index").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        fullTextFile = File(docDir, "fulltext.txt")

        // Normalize line endings to \n so byte offsets are predictable.
        // Documents from Windows/PDF may have \r\n which would break
        // the +1 offset calculation and cause readLinesFromDisk to fail.
        val normalizedText = text.replace("\r\n", "\n").replace("\r", "\n")

        // Step 1: Write normalized text to disk
        fullTextFile!!.writeText(normalizedText)
        docLength = normalizedText.length

        val lines = normalizedText.split("\n")
        totalLines = lines.size

        // Build byte offsets for each line.
        // Since we normalized to \n, each line separator is exactly 1 byte.
        // The last line has no trailing \n, so handle it separately.
        val offsets = LongArray(lines.size + 1)
        var offset = 0L
        for (i in lines.indices) {
            offsets[i] = offset
            offset += lines[i].toByteArray(Charsets.UTF_8).size
            if (i < lines.size - 1) offset += 1  // +1 for \n between lines (not after last)
        }
        offsets[lines.size] = offset
        lineByteOffsets = offsets

        // Step 2: Build chunks — metadata only in memory, text stays on disk
        val tempChunkMetas = mutableListOf<ChunkMeta>()
        val tempWordToChunks = mutableMapOf<String, MutableSet<Int>>()
        val tempCoOccur = mutableMapOf<String, MutableMap<String, Int>>()

        var lineIdx = 0
        var chunkIdx = 0

        while (lineIdx < lines.size) {
            val endIdx = minOf(lineIdx + CHUNK_SIZE_LINES, lines.size)
            val chunkLines = lines.subList(lineIdx, endIdx)
            val chunkText = chunkLines.joinToString("\n")

            val words = tokenize(chunkText)
            val uniqueWords = words.toSet()

            for (word in uniqueWords) {
                tempWordToChunks.getOrPut(word) { mutableSetOf() }.add(chunkIdx)
            }

            tempChunkMetas.add(ChunkMeta(
                index = chunkIdx,
                startLine = lineIdx,
                endLine = endIdx - 1,
                wordSet = uniqueWords
            ))

            // Build co-occurrence (temporary — will be compressed and discarded)
            val meaningfulWords = uniqueWords
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .toList()
            for (i in meaningfulWords.indices) {
                for (j in meaningfulWords.indices) {
                    if (i != j) {
                        val map = tempCoOccur.getOrPut(meaningfulWords[i]) { mutableMapOf() }
                        map[meaningfulWords[j]] = (map[meaningfulWords[j]] ?: 0) + 1
                    }
                }
            }

            chunkIdx++
            lineIdx += (CHUNK_SIZE_LINES - CHUNK_OVERLAP_LINES)
            if (lineIdx >= lines.size) break
        }

        chunkMetas = tempChunkMetas
        totalChunks = chunkMetas.size
        wordToChunks = tempWordToChunks

        // Step 3: Compress co-occurrence into compact word relations
        // Keep only top N related words per keyword, then DISCARD full map
        val tempRelations = mutableMapOf<String, List<String>>()
        for ((word, related) in tempCoOccur) {
            val topRelated = related.entries
                .filter { it.key !in STOP_WORDS && it.key.length >= 3 }
                .sortedByDescending { it.value }
                .take(CO_OCCUR_TOP_N)
                .map { it.key }
            if (topRelated.isNotEmpty()) {
                tempRelations[word] = topRelated
            }
        }
        wordRelations = tempRelations
        // tempCoOccur is now garbage-collectible ✓

        isIndexed = true

        Log.d(TAG, "Indexed $totalChunks chunks from $totalLines lines (FILE-BASED)")
        Log.d(TAG, "In-memory: ${wordToChunks.size} word→chunk mappings, ${wordRelations.size} word relations")
        Log.d(TAG, "On disk: fulltext=${fullTextFile!!.length()} bytes")

        // Force GC to release the temporary text strings used during indexing
        System.gc()

        return totalChunks
    }

    /**
     * Backward-compatible indexDocument without context.
     * Falls back to in-memory-only mode (stores text in a temp string for reading).
     * Prefer the version with Context for full file-based behavior.
     */
    @Deprecated("Use indexDocument(text, context) for file-based indexing")
    fun indexDocument(text: String): Int {
        Log.w(TAG, "indexDocument called without Context — using in-memory fallback")
        // Can't write to disk without context, so store text temporarily
        // This is only used if someone calls the old API
        docLength = text.length
        val lines = text.lines()
        totalLines = lines.size
        lineByteOffsets = LongArray(0) // no offsets = will use fallback reading

        // Write to a temp approach: store lines in memory temporarily for building index
        val tempChunkMetas = mutableListOf<ChunkMeta>()
        val tempWordToChunks = mutableMapOf<String, MutableSet<Int>>()
        val tempCoOccur = mutableMapOf<String, MutableMap<String, Int>>()

        var lineIdx = 0
        var chunkIdx = 0

        while (lineIdx < lines.size) {
            val endIdx = minOf(lineIdx + CHUNK_SIZE_LINES, lines.size)
            val chunkLines = lines.subList(lineIdx, endIdx)
            val chunkText = chunkLines.joinToString("\n")

            val words = tokenize(chunkText)
            val uniqueWords = words.toSet()

            for (word in uniqueWords) {
                tempWordToChunks.getOrPut(word) { mutableSetOf() }.add(chunkIdx)
            }

            tempChunkMetas.add(ChunkMeta(
                index = chunkIdx,
                startLine = lineIdx,
                endLine = endIdx - 1,
                wordSet = uniqueWords
            ))

            val meaningfulWords = uniqueWords
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .toList()
            for (i in meaningfulWords.indices) {
                for (j in meaningfulWords.indices) {
                    if (i != j) {
                        val map = tempCoOccur.getOrPut(meaningfulWords[i]) { mutableMapOf() }
                        map[meaningfulWords[j]] = (map[meaningfulWords[j]] ?: 0) + 1
                    }
                }
            }

            chunkIdx++
            lineIdx += (CHUNK_SIZE_LINES - CHUNK_OVERLAP_LINES)
            if (lineIdx >= lines.size) break
        }

        chunkMetas = tempChunkMetas
        totalChunks = chunkMetas.size
        wordToChunks = tempWordToChunks

        val tempRelations = mutableMapOf<String, List<String>>()
        for ((word, related) in tempCoOccur) {
            val topRelated = related.entries
                .filter { it.key !in STOP_WORDS && it.key.length >= 3 }
                .sortedByDescending { it.value }
                .take(CO_OCCUR_TOP_N)
                .map { it.key }
            if (topRelated.isNotEmpty()) {
                tempRelations[word] = topRelated
            }
        }
        wordRelations = tempRelations

        isIndexed = true
        return totalChunks
    }

    // ═════════════════════════════════════════
    // ── READ CHUNKS FROM DISK ──
    // ═════════════════════════════════════════

    /**
     * Read specific lines from the full text file on disk.
     * Uses byte offsets for fast random access, with line-by-line fallback.
     */
    private fun readLinesFromDisk(startLine: Int, endLine: Int): String {
        val file = fullTextFile
        if (file == null || !file.exists()) {
            Log.w(TAG, "readLinesFromDisk: fullTextFile is ${if (file == null) "null" else "missing"}")
            return ""
        }

        val safeStart = startLine.coerceIn(0, maxOf(totalLines - 1, 0))
        val safeEnd = endLine.coerceIn(0, maxOf(totalLines - 1, 0))

        return try {
            if (lineByteOffsets.isNotEmpty() && safeEnd + 1 < lineByteOffsets.size) {
                // Fast path: random access read using byte offsets
                val startByte = lineByteOffsets[safeStart]
                val endByte = lineByteOffsets[safeEnd + 1]
                val length = (endByte - startByte).toInt()

                if (length <= 0) return ""

                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(startByte)
                    val bytes = ByteArray(length)
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8).trimEnd('\n', '\r')
                }
            } else {
                // Fallback: read file line by line
                Log.d(TAG, "readLinesFromDisk: using line-by-line fallback for lines $safeStart-$safeEnd (offsets=${lineByteOffsets.size})")
                val allLines = file.readLines()
                if (allLines.isEmpty()) return ""
                val clampedEnd = minOf(safeEnd + 1, allLines.size)
                val clampedStart = minOf(safeStart, clampedEnd)
                allLines.subList(clampedStart, clampedEnd).joinToString("\n")
            }
        } catch (e: Exception) {
            // If byte-offset read failed, try the line-by-line fallback
            Log.w(TAG, "readLinesFromDisk: byte-offset read failed for lines $startLine-$endLine, trying fallback: ${e.message ?: e.javaClass.simpleName}")
            try {
                val allLines = file.readLines()
                if (allLines.isEmpty()) return ""
                val clampedEnd = minOf(safeEnd + 1, allLines.size)
                val clampedStart = minOf(safeStart, clampedEnd)
                allLines.subList(clampedStart, clampedEnd).joinToString("\n")
            } catch (e2: Exception) {
                Log.e(TAG, "readLinesFromDisk: total failure for lines $startLine-$endLine: ${e2.message ?: e2.javaClass.simpleName}")
                ""
            }
        }
    }

    private fun readChunkText(meta: ChunkMeta): String {
        return readLinesFromDisk(meta.startLine, meta.endLine)
    }

    // ═════════════════════════════════════════
    // ── KEYWORD EXPANSION (in-memory, lightweight) ──
    // ═════════════════════════════════════════

    data class WeightedKeyword(
        val word: String,
        val weight: Double,
        val source: String
    )

    fun expandKeywords(query: String): List<WeightedKeyword> {
        val rawKeywords = extractRawKeywords(query)
        Log.d(TAG, "Raw query keywords: $rawKeywords")
        if (rawKeywords.isEmpty()) return emptyList()

        val expanded = mutableListOf<WeightedKeyword>()
        val seenWords = mutableSetOf<String>()

        // 1. Primary keywords
        for (kw in rawKeywords) {
            expanded.add(WeightedKeyword(kw, 1.0, "primary"))
            seenWords.add(kw)
        }

        // 2. Co-occurrence expansion from compact word relations
        for (kw in rawKeywords) {
            val related = wordRelations[kw] ?: continue
            for ((idx, relWord) in related.withIndex()) {
                if (relWord !in seenWords) {
                    val weight = 0.8 - idx * 0.1
                    expanded.add(WeightedKeyword(relWord, weight.coerceAtLeast(0.4), "co-occur"))
                    seenWords.add(relWord)
                }
            }

            // Substring matches in vocabulary
            for (vocabWord in wordToChunks.keys) {
                if (vocabWord !in seenWords && vocabWord.length >= 4) {
                    if ((vocabWord.contains(kw) && kw.length >= 3) ||
                        (kw.contains(vocabWord) && vocabWord.length >= 4)) {
                        expanded.add(WeightedKeyword(vocabWord, 0.6, "substring"))
                        seenWords.add(vocabWord)
                    }
                }
            }
        }

        // 3. Question-type keywords
        val queryLower = query.lowercase()
        val detectedType = detectQuestionType(queryLower)
        if (detectedType != null) {
            Log.d(TAG, "Detected question type: ${detectedType.name}")
            for (contextWord in detectedType.contextWords) {
                if (contextWord !in seenWords && contextWord in wordToChunks) {
                    expanded.add(WeightedKeyword(contextWord, 0.3, "question-type"))
                    seenWords.add(contextWord)
                }
            }
        }

        return expanded
    }

    private fun detectQuestionType(query: String): QuestionType? {
        val patterns = mapOf(
            "definition" to listOf("what is", "what are", "define", "meaning of", "kya hai", "kya hota", "kya hoti", "matlab", "definition"),
            "process" to listOf("how does", "how do", "how to", "how is", "kaise", "process", "steps", "explain how", "working of"),
            "comparison" to listOf("difference between", "compare", "vs", "versus", "distinguish", "fark", "antar"),
            "example" to listOf("example", "give example", "examples of", "instance", "udaharan", "jaise"),
            "advantage" to listOf("advantage", "benefit", "pros", "fayde", "fayda", "merit"),
            "disadvantage" to listOf("disadvantage", "drawback", "cons", "nuksan", "limitation"),
            "types" to listOf("types of", "kinds of", "categories", "prakar", "kitne type", "classification"),
            "application" to listOf("application", "uses of", "used for", "where is", "use case", "real world"),
            "cause" to listOf("why does", "why is", "reason", "cause", "kyu", "kyun", "wajah"),
            "feature" to listOf("feature", "characteristics", "properties", "visheshta")
        )

        for ((typeName, typePatterns) in patterns) {
            for (pattern in typePatterns) {
                if (query.contains(pattern)) {
                    return QUESTION_TYPES.find { it.name == typeName }
                }
            }
        }
        return null
    }

    // ═════════════════════════════════════════
    // ── SEARCH (reads only matched chunks from disk!) ──
    // ═════════════════════════════════════════

    fun search(query: String, topK: Int = MAX_RESULTS_DEFAULT): List<SearchResult> {
        if (!isIndexed || chunkMetas.isEmpty()) {
            Log.w(TAG, "Document not indexed yet!")
            return emptyList()
        }

        val expandedKeywords = expandKeywords(query)
        if (expandedKeywords.isEmpty()) {
            return chunkMetas.take(topK).map {
                SearchResult(it.index, it.startLine, it.endLine, 0.0, emptyList(), 0.0f)
            }
        }

        val primaryKeywords = expandedKeywords.filter { it.source == "primary" }

        // Score each chunk using in-memory metadata only (zero disk reads here!)
        val scoredChunks = chunkMetas.map { meta ->
            var score = 0.0
            val matched = mutableListOf<String>()
            var primaryMatches = 0

            for (kwInfo in expandedKeywords) {
                val keyword = kwInfo.word
                val weight = kwInfo.weight

                if (keyword in meta.wordSet) {
                    val tfScore = 1.0
                    val docsWithWord = wordToChunks[keyword]?.size ?: 1
                    val idf = kotlin.math.ln((totalChunks.toDouble() + 1) / (docsWithWord.toDouble() + 1)) + 1.0

                    score += tfScore * idf * weight
                    if (keyword !in matched) matched.add(keyword)
                    if (kwInfo.source == "primary") primaryMatches++
                }

                // Partial/substring match
                if (keyword.length >= 4) {
                    for (word in meta.wordSet) {
                        if (word != keyword && word.length >= 4) {
                            if (word.contains(keyword) || keyword.contains(word)) {
                                score += 0.3 * weight
                                if (keyword !in matched) matched.add(keyword)
                            }
                        }
                    }
                }
            }

            // Multi-keyword intersection bonus
            val uniqueMatchCount = matched.distinct().size
            if (uniqueMatchCount > 1) {
                val intersectionBonus = when {
                    uniqueMatchCount >= 5 -> 3.0 + uniqueMatchCount * 0.6
                    uniqueMatchCount >= 3 -> 1.5 + uniqueMatchCount * 0.4
                    else -> 1.0 + uniqueMatchCount * 0.4
                }
                score *= intersectionBonus
            }

            if (primaryKeywords.isNotEmpty() && primaryMatches == primaryKeywords.size) {
                score *= 1.5
            }

            val confidence = when {
                uniqueMatchCount >= 4 && primaryMatches > 0 -> 0.95f
                uniqueMatchCount >= 3 && primaryMatches > 0 -> 0.85f
                uniqueMatchCount >= 2 && primaryMatches > 0 -> 0.70f
                primaryMatches > 0 -> 0.50f
                uniqueMatchCount >= 2 -> 0.40f
                uniqueMatchCount >= 1 -> 0.25f
                else -> 0.0f
            }

            SearchResult(meta.index, meta.startLine, meta.endLine, score, matched.distinct(), confidence)
        }

        val positiveResults = scoredChunks.filter { it.score > 0.0 }.sortedByDescending { it.score }

        val dynamicTopK = when {
            positiveResults.isEmpty() -> topK
            positiveResults.first().confidence >= 0.85f -> MAX_RESULTS_HIGH_CONF
            positiveResults.first().confidence >= 0.50f -> MAX_RESULTS_DEFAULT
            else -> MAX_RESULTS_LOW_CONF
        }

        val finalResults = positiveResults.take(dynamicTopK)
        val deduped = deduplicateOverlapping(finalResults)

        Log.d(TAG, "═══ Search Results (FILE-BASED) ═══")
        Log.d(TAG, "Query: ${query.take(80)}")
        Log.d(TAG, "Found ${deduped.size} results (from ${positiveResults.size} positive, $totalChunks total)")
        deduped.forEachIndexed { i, result ->
            Log.d(TAG, "  #${i + 1}: score=${String.format("%.2f", result.score)}, " +
                    "conf=${String.format("%.0f", result.confidence * 100)}%, " +
                    "keywords=${result.matchedKeywords}, lines=${result.startLine}-${result.endLine}")
        }

        return deduped
    }

    private fun deduplicateOverlapping(results: List<SearchResult>): List<SearchResult> {
        if (results.size <= 1) return results
        val deduped = mutableListOf<SearchResult>()
        val usedLineRanges = mutableListOf<IntRange>()

        for (result in results) {
            val range = result.startLine..result.endLine
            val overlaps = usedLineRanges.any { existing ->
                range.first <= existing.last && range.last >= existing.first
            }
            if (!overlaps) {
                deduped.add(result)
                usedLineRanges.add(range)
            }
        }
        return deduped
    }

    // ═════════════════════════════════════════
    // ── MAIN API: getRelevantContext (reads from disk!) ──
    // ═════════════════════════════════════════

    /**
     * Get relevant context for a question.
     * READS ONLY MATCHED CHUNKS FROM DISK — minimal memory usage!
     */
    fun getRelevantContext(query: String, maxChars: Int = 35000): String? {
        if (!isIndexed) return null

        val results = search(query)

        if (results.isEmpty()) {
            Log.d(TAG, "No keyword matches, using document overview as fallback")
            return cleanContextForLLM(getDocumentOverview(maxChars) ?: "")
        }

        // ── STRATEGY: For small docs, send FULL text (best accuracy) ──
        // Small LLMs (1B-1.7B) understand documents MUCH better when they
        // see the full content instead of fragmented keyword-matched chunks.
        // Chunked fragments confuse small models — they hallucinate names/data.
        if (docLength <= maxChars && docLength > 0) {
            Log.d(TAG, "Document small enough ($docLength chars), sending FULL text for best accuracy")
            val fullText = readLinesFromDisk(0, totalLines - 1)
            if (fullText.isNotBlank()) return cleanContextForLLM(fullText.take(maxChars))
        }

        // ── For large docs: send CLEAN chunks WITHOUT metadata headers ──
        // Small models get confused by "[Section 1 | Lines 5-25 | Matched: ...]" markers.
        // They try to parse these as document content → garbled answers.
        // Just send clean text with simple separators.
        val contextBuilder = StringBuilder()
        var totalCharsUsed = 0

        // Sort results by their position in document (preserves reading order)
        val sortedResults = results.sortedBy { it.startLine }

        for ((i, result) in sortedResults.withIndex()) {
            if (totalCharsUsed >= maxChars) break

            // READ FROM DISK: the matching chunk + context window
            val expandedStart = maxOf(0, result.startLine - CONTEXT_LINES_BEFORE)
            val expandedEnd = minOf(totalLines - 1, result.endLine + CONTEXT_LINES_AFTER)
            val expandedText = readLinesFromDisk(expandedStart, expandedEnd)

            if (expandedText.isBlank()) continue

            // Clean separator — no metadata that confuses small LLMs
            if (i > 0) contextBuilder.append("\n\n")
            contextBuilder.append(expandedText)

            totalCharsUsed += expandedText.length + 10
        }

        val context = cleanContextForLLM(contextBuilder.toString().take(maxChars))
        Log.d(TAG, "Returning ${context.length} chars of relevant context (READ FROM DISK, ${sortedResults.size} chunks)")
        return context
    }

    /**
     * Final cleanup pass on context text before sending to LLM.
     * Ensures no extraction artifacts survived and formatting is clean.
     */
    private fun cleanContextForLLM(text: String): String {
        if (text.isBlank()) return text
        return text
            // Remove any residual page/slide markers
            .replace(Regex("""---\s*Page\s*\d+\s*---"""), "")
            .replace(Regex("""===\s*Slide\s*\d+\s*==="""), "")
            .replace(Regex("""\[Page\s*\d+]"""), "")
            .replace(Regex("""\[Speaker Notes:.*?]"""), "")
            // Remove lines that are purely formatting/noise
            .replace(Regex("""^[\s.\-_*=|#~─═│┃]+$""", RegexOption.MULTILINE), "")
            // Collapse excessive blank lines
            .replace(Regex("""\n{3,}"""), "\n\n")
            // Trim each line
            .lines()
            .filter { it.isNotBlank() || it.isEmpty() } // keep single blank lines for paragraph breaks
            .joinToString("\n") { it.trim() }
            .trim()
    }

    // ═════════════════════════════════════════
    // ── KEYWORD EXTRACTION ──
    // ═════════════════════════════════════════

    fun extractRawKeywords(query: String): List<String> {
        return tokenize(query)
            .filter { it.length >= 2 }
            .filter { it !in STOP_WORDS }
            .distinct()
            .take(8)
    }

    fun extractKeywords(query: String): List<String> {
        return expandKeywords(query).map { it.word }.distinct()
    }

    private fun tokenize(text: String): List<String> {
        // Preserve alphanumeric tokens including those with dots/hyphens (e.g. "BUNSITM201", "3.5")
        // Split on whitespace and common delimiters, keep meaningful tokens
        return text.lowercase()
            .replace(Regex("[^a-z0-9.\\-\\s]"), " ")
            .split(Regex("[\\s,;:|()+]+"))
            .map { it.trim('.', '-') }
            .filter { it.isNotBlank() && it.length >= 2 }
    }

    // ═════════════════════════════════════════
    // ── UTILITY METHODS ──
    // ═════════════════════════════════════════

    fun getStats(): String {
        if (!isIndexed) return "No document indexed"
        return "$totalLines lines, $totalChunks chunks, $docLength chars, ${wordToChunks.size} vocab (file-based)"
    }

    fun getDocumentLength(): Int = docLength

    fun isDocumentIndexed(): Boolean = isIndexed

    fun clear() {
        wordToChunks = emptyMap()
        wordRelations = emptyMap()
        chunkMetas = emptyList()
        totalChunks = 0
        totalLines = 0
        docLength = 0
        lineByteOffsets = LongArray(0)
        isIndexed = false

        try {
            docDir?.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete doc index dir: ${e.message}")
        }
        docDir = null
        fullTextFile = null

        Log.d(TAG, "Index cleared (memory + disk)")
    }

    /**
     * Get a summary overview of the document — reads from disk.
     */
    fun getDocumentOverview(maxChars: Int = 5000): String {
        if (!isIndexed || totalChunks == 0) return ""

        // For small docs, return FULL text (best for small LLMs)
        if (docLength <= maxChars && docLength > 0) {
            val fullText = readLinesFromDisk(0, totalLines - 1)
            if (fullText.isNotBlank()) return fullText.take(maxChars)
        }

        val firstMeta = chunkMetas.first()
        val firstText = readChunkText(firstMeta)

        val lastText = if (chunkMetas.size > 1) readChunkText(chunkMetas.last()) else ""

        // Clean format — no [Beginning/End] markers that confuse small LLMs
        val overview = buildString {
            append(firstText.take(maxChars / 2))
            if (lastText.isNotBlank() && chunkMetas.size > 2) {
                append("\n\n")
                append(lastText.take(maxChars / 2))
            }
        }
        return overview.take(maxChars)
    }

    fun debugShowRelatedWords(keyword: String): String {
        val related = wordRelations[keyword.lowercase()]
            ?: return "No relationships found for '$keyword'"
        return buildString {
            append("Words related to '$keyword' in this document:\n")
            related.forEach { word -> append("  • $word\n") }
        }
    }

    // ═════════════════════════════════════════
    // ── TEXT CLEANING (makes stored text LLM-friendly) ──
    // ═════════════════════════════════════════

    /**
     * Clean raw extracted text so small LLMs (1B-3B) can understand it.
     * Removes extraction artifacts, normalizes formatting, keeps content intact.
     */
    private fun cleanForLLM(raw: String): String {
        return raw
            // Remove page/slide markers from document extractors
            .replace(Regex("""---\s*Page\s*\d+\s*---"""), "")
            .replace(Regex("""===\s*Slide\s*\d+\s*==="""), "")
            .replace(Regex("""\[Page\s*\d+]"""), "")
            .replace(Regex("""\[Speaker Notes:.*?]"""), "")
            // Remove lines that are only noise (dots, dashes, stars, etc.)
            .replace(Regex("""^[\s.\-_*=|#~─═│┃]+$""", RegexOption.MULTILINE), "")
            // Collapse 3+ blank lines to 2 (preserve paragraph breaks)
            .replace(Regex("""\n{3,}"""), "\n\n")
            // Collapse multiple spaces/tabs to single space
            .replace(Regex("""[ \t]{2,}"""), " ")
            // Trim each line
            .lines()
            .joinToString("\n") { it.trim() }
            // Remove leading/trailing whitespace
            .trim()
    }

    // ═════════════════════════════════════════
    // ── DOCUMENT FILE STORAGE (for session persistence) ──
    // ═════════════════════════════════════════

    /**
     * Save document content to a file instead of keeping in memory/session JSON.
     * Cleans the text first so the stored version is already LLM-friendly.
     * Returns the file path.
     */
    fun saveDocumentToFile(content: String, context: Context, sessionId: String): String {
        val docStorageDir = File(context.filesDir, "youlearn_docs").apply { mkdirs() }
        val docFile = File(docStorageDir, "$sessionId.txt")
        val cleanedContent = cleanForLLM(content)
        docFile.writeText(cleanedContent)
        Log.d(TAG, "Saved cleaned document to file: ${docFile.absolutePath} (${content.length} raw -> ${cleanedContent.length} clean chars)")
        return docFile.absolutePath
    }

    /**
     * Load document content from file by session ID.
     * Returns null if file doesn't exist.
     */
    fun loadDocumentFromFile(context: Context, sessionId: String): String? {
        val docFile = File(context.filesDir, "youlearn_docs/$sessionId.txt")
        return if (docFile.exists()) docFile.readText() else null
    }

    /**
     * Delete stored document file for a session.
     */
    fun deleteDocumentFile(context: Context, sessionId: String) {
        val docFile = File(context.filesDir, "youlearn_docs/$sessionId.txt")
        if (docFile.exists()) docFile.delete()
    }
}
