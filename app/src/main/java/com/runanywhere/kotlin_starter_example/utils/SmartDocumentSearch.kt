package com.runanywhere.kotlin_starter_example.utils

import android.util.Log

/**
 * SmartDocumentSearch v2 — Intelligent multi-keyword document retrieval.
 *
 * Problem with v1: "What is NLP?" extracts just "nlp", finds 50 chunks, picks top 3 randomly.
 * That's dumb. If a keyword appears everywhere, we need SMARTER selection.
 *
 * v2 Solution:
 * 1. KEYWORD EXPANSION — Don't just use query words. Find RELATED words from the document.
 *    e.g., "nlp" → also search "natural", "language", "processing", "text", "linguistics"
 *    How? Co-occurrence analysis: words that appear in SAME chunks as "nlp" frequently.
 *
 * 2. QUESTION-TYPE DETECTION — "What is X?" needs definition chunks (intro, overview).
 *    "How does X work?" needs process/mechanism chunks. "Compare X and Y" needs both.
 *
 * 3. MULTI-KEYWORD INTERSECTION — Chunk matching 4 keywords >> chunk matching 1 keyword.
 *    Exponential scoring: more keywords matched = much higher score.
 *
 * 4. DYNAMIC RESULT COUNT — If we have high-confidence matches (3+ keywords), return fewer.
 *    If low confidence (1 keyword), return more chunks for coverage.
 *
 * 5. CO-OCCURRENCE GRAPH — Build a word relationship graph from the document.
 *    Words appearing together frequently are "related". Use this to expand queries.
 *
 * Flow:
 * 1. User uploads doc → indexDocument() creates chunks + builds co-occurrence graph
 * 2. User asks "What is NLP?" → expandKeywords() finds related terms from document
 * 3. Multi-keyword TF-IDF search with intersection scoring
 * 4. Only the BEST matching chunks sent to LLM
 */
object SmartDocumentSearch {

    private const val TAG = "SmartDocSearch"

    // ── Configuration ──
    private const val CHUNK_SIZE_LINES = 20        // Slightly larger chunks for better context
    private const val CHUNK_OVERLAP_LINES = 7      // More overlap for continuity
    private const val MAX_RESULTS_DEFAULT = 4      // Default max results — compact to prevent OOM
    private const val MAX_RESULTS_HIGH_CONF = 3    // High confidence — best chunks only
    private const val MAX_RESULTS_LOW_CONF = 5     // Low confidence — moderate net
    private const val CONTEXT_LINES_BEFORE = 3     // Small context before (was 10 — caused output > input!)
    private const val CONTEXT_LINES_AFTER = 3      // Small context after (was 10 — ±3 is sufficient)
    private const val CO_OCCUR_TOP_N = 4           // Top N co-occurring words to add as expansion

    // ── Indexed Data ──
    private var fullText: String = ""
    private var lines: List<String> = emptyList()
    private var chunks: List<DocumentChunk> = emptyList()
    private var wordIndex: Map<String, MutableSet<Int>> = emptyMap()  // word → set of chunk indices
    private var coOccurrence: Map<String, Map<String, Int>> = emptyMap() // word → {related_word → count}
    private var globalWordFreq: Map<String, Int> = emptyMap()  // word → total frequency across doc
    private var isIndexed: Boolean = false

    /**
     * A chunk of document text with metadata for scoring.
     */
    data class DocumentChunk(
        val index: Int,
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val words: Map<String, Int>,          // word → frequency in this chunk
        val uniqueWords: Set<String>          // unique meaningful words in chunk
    )

    /**
     * Search result with relevance score and debug info.
     */
    data class SearchResult(
        val chunk: DocumentChunk,
        val score: Double,
        val matchedKeywords: List<String>,
        val confidence: Float                 // 0.0 to 1.0 — how confident are we this is relevant
    )

    // ── Common stop words (English + Hinglish + question words) ──
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
        // Hindi/Urdu common words
        "ka", "ki", "ke", "hai", "ho", "hain", "ya", "aur", "ye", "wo", "kya",
        "kaise", "kaha", "kab", "kaun", "ko", "se", "me", "par", "ne", "tha",
        "thi", "the", "mein", "bhi", "nahi", "nhi", "kr", "krna", "kro", "kre",
        "toh", "na", "haan", "ji", "ek", "do", "teen", "char",
        // Question/command words to skip
        "tell", "explain", "describe", "define", "summarize", "summary", "mean",
        "meaning", "means", "please", "give", "show", "list", "write", "note",
        "discuss", "elaborate", "detail", "details", "briefly", "short", "long"
    )

    // ── Question type patterns for context-aware keyword generation ──
    private data class QuestionType(
        val name: String,
        val contextWords: List<String>  // Words to look for near the main keyword
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
    // ── INDEXING ──
    // ═════════════════════════════════════════

    /**
     * Index a document for fast multi-keyword search.
     * Builds: chunks, inverted index, co-occurrence graph, global word frequencies.
     */
    fun indexDocument(text: String): Int {
        Log.d(TAG, "═══ Indexing document: ${text.length} chars ═══")

        fullText = text
        lines = text.lines()

        // Step 1: Create overlapping chunks
        val chunkList = mutableListOf<DocumentChunk>()
        val tempWordIndex = mutableMapOf<String, MutableSet<Int>>()
        val tempGlobalFreq = mutableMapOf<String, Int>()

        var lineIdx = 0
        var chunkIdx = 0

        while (lineIdx < lines.size) {
            val endIdx = minOf(lineIdx + CHUNK_SIZE_LINES, lines.size)
            val chunkLines = lines.subList(lineIdx, endIdx)
            val chunkText = chunkLines.joinToString("\n")

            val wordFreqs = mutableMapOf<String, Int>()
            val words = tokenize(chunkText)
            val uniqueWords = mutableSetOf<String>()

            for (word in words) {
                wordFreqs[word] = (wordFreqs[word] ?: 0) + 1
                tempGlobalFreq[word] = (tempGlobalFreq[word] ?: 0) + 1
                uniqueWords.add(word)
                tempWordIndex.getOrPut(word) { mutableSetOf() }.add(chunkIdx)
            }

            chunkList.add(DocumentChunk(
                index = chunkIdx,
                text = chunkText,
                startLine = lineIdx,
                endLine = endIdx - 1,
                words = wordFreqs,
                uniqueWords = uniqueWords
            ))

            chunkIdx++
            lineIdx += (CHUNK_SIZE_LINES - CHUNK_OVERLAP_LINES)
            if (lineIdx >= lines.size) break
        }

        chunks = chunkList
        wordIndex = tempWordIndex
        globalWordFreq = tempGlobalFreq

        // Step 2: Build co-occurrence graph
        // For each pair of meaningful words that appear in the same chunk, they are "related"
        val tempCoOccur = mutableMapOf<String, MutableMap<String, Int>>()

        for (chunk in chunks) {
            val meaningfulWords = chunk.uniqueWords
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .toList()

            // For each word, record which other words appear with it
            for (i in meaningfulWords.indices) {
                for (j in meaningfulWords.indices) {
                    if (i != j) {
                        val wordA = meaningfulWords[i]
                        val wordB = meaningfulWords[j]
                        val map = tempCoOccur.getOrPut(wordA) { mutableMapOf() }
                        map[wordB] = (map[wordB] ?: 0) + 1
                    }
                }
            }
        }

        coOccurrence = tempCoOccur
        isIndexed = true

        Log.d(TAG, "Indexed ${chunks.size} chunks from ${lines.size} lines")
        Log.d(TAG, "Vocabulary: ${wordIndex.size} unique words")
        Log.d(TAG, "Co-occurrence graph: ${coOccurrence.size} words with relationships")
        return chunks.size
    }

    // ═════════════════════════════════════════
    // ── SMART KEYWORD EXPANSION ──
    // ═════════════════════════════════════════

    /**
     * The BRAIN of the system: expand user's query keywords into a rich set of search terms.
     *
     * Example: "What is NLP?" →
     *   Primary:   ["nlp"]
     *   Expanded:  ["natural", "language", "processing", "text", "linguistics", "tokenization"]
     *   Question:  ["definition", "introduction", "overview", "concept"]
     *   Final:     All combined, with weights
     */
    data class WeightedKeyword(
        val word: String,
        val weight: Double,  // 1.0 = primary, 0.7 = expanded, 0.4 = question-context
        val source: String   // "primary", "co-occur", "question-type", "substring"
    )

    fun expandKeywords(query: String): List<WeightedKeyword> {
        val rawKeywords = extractRawKeywords(query)
        Log.d(TAG, "Raw query keywords: $rawKeywords")

        if (rawKeywords.isEmpty()) return emptyList()

        val expanded = mutableListOf<WeightedKeyword>()
        val seenWords = mutableSetOf<String>()

        // ── 1. Primary keywords (from user's question directly) ──
        for (kw in rawKeywords) {
            expanded.add(WeightedKeyword(kw, 1.0, "primary"))
            seenWords.add(kw)
        }

        // ── 2. Co-occurrence expansion: what words appear WITH these keywords in the doc? ──
        for (kw in rawKeywords) {
            val relatedWords = coOccurrence[kw]
            if (relatedWords != null) {
                // Sort by co-occurrence frequency, take top N
                val topRelated = relatedWords.entries
                    .filter { it.key !in seenWords && it.key !in STOP_WORDS && it.key.length >= 3 }
                    .sortedByDescending { it.value }
                    .take(CO_OCCUR_TOP_N)

                for ((relWord, count) in topRelated) {
                    // Weight based on how often they co-occur (normalized)
                    val maxCount = relatedWords.values.maxOrNull() ?: 1
                    val normalizedWeight = 0.4 + 0.4 * (count.toDouble() / maxCount)
                    expanded.add(WeightedKeyword(relWord, normalizedWeight, "co-occur"))
                    seenWords.add(relWord)
                }
            }

            // Also check for substring matches in vocabulary (e.g., "nlp" matches "nlp-based")
            for (vocabWord in wordIndex.keys) {
                if (vocabWord !in seenWords && vocabWord.length >= 4) {
                    if ((vocabWord.contains(kw) && kw.length >= 3) ||
                        (kw.contains(vocabWord) && vocabWord.length >= 4)) {
                        expanded.add(WeightedKeyword(vocabWord, 0.6, "substring"))
                        seenWords.add(vocabWord)
                    }
                }
            }
        }

        // ── 3. Question-type keywords: detect what KIND of question it is ──
        val queryLower = query.lowercase()
        val detectedType = detectQuestionType(queryLower)
        if (detectedType != null) {
            Log.d(TAG, "Detected question type: ${detectedType.name}")
            for (contextWord in detectedType.contextWords) {
                if (contextWord !in seenWords && contextWord in wordIndex) {
                    // Only add if this word actually exists in the document
                    expanded.add(WeightedKeyword(contextWord, 0.3, "question-type"))
                    seenWords.add(contextWord)
                }
            }
        }

        Log.d(TAG, "Expanded keywords (${expanded.size} total):")
        expanded.groupBy { it.source }.forEach { (source, kws) ->
            Log.d(TAG, "  $source: ${kws.map { "${it.word}(${String.format("%.1f", it.weight)})" }}")
        }

        return expanded
    }

    /**
     * Detect what type of question the user is asking.
     */
    private fun detectQuestionType(query: String): QuestionType? {
        // Pattern matching for question types
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
    // ── MULTI-KEYWORD SEARCH ──
    // ═════════════════════════════════════════

    /**
     * Search using expanded multi-keyword strategy.
     * Chunks matching MORE keywords get EXPONENTIALLY higher scores.
     */
    fun search(query: String, topK: Int = MAX_RESULTS_DEFAULT): List<SearchResult> {
        if (!isIndexed || chunks.isEmpty()) {
            Log.w(TAG, "Document not indexed yet!")
            return emptyList()
        }

        // Step 1: Expand keywords
        val expandedKeywords = expandKeywords(query)

        if (expandedKeywords.isEmpty()) {
            return chunks.take(topK).map { SearchResult(it, 0.0, emptyList(), 0.0f) }
        }

        val primaryKeywords = expandedKeywords.filter { it.source == "primary" }

        // Step 2: Score each chunk with multi-keyword intersection
        val scoredChunks = chunks.map { chunk ->
            var score = 0.0
            val matched = mutableListOf<String>()
            var primaryMatches = 0

            for (kwInfo in expandedKeywords) {
                val keyword = kwInfo.word
                val weight = kwInfo.weight

                // Exact match
                val tf = chunk.words[keyword] ?: 0
                if (tf > 0) {
                    // TF component
                    val tfScore = 1.0 + kotlin.math.ln(tf.toDouble())

                    // IDF component: rarer words are more important
                    val docsWithWord = wordIndex[keyword]?.size ?: 1
                    val idf = kotlin.math.ln((chunks.size.toDouble() + 1) / (docsWithWord.toDouble() + 1)) + 1.0

                    score += tfScore * idf * weight
                    if (keyword !in matched) matched.add(keyword)

                    if (kwInfo.source == "primary") primaryMatches++
                }

                // Partial/substring match (fuzzy) — but only for words >= 4 chars
                if (keyword.length >= 4) {
                    for ((word, freq) in chunk.words) {
                        if (word != keyword && word.length >= 4) {
                            if (word.contains(keyword) || keyword.contains(word)) {
                                score += 0.3 * weight * (1.0 + kotlin.math.ln(freq.toDouble()))
                                if (keyword !in matched) matched.add(keyword)
                            }
                        }
                    }
                }
            }

            // ── CRITICAL: Multi-keyword intersection bonus ──
            // This is the KEY innovation: chunks matching MORE different keywords
            // get exponentially higher scores.
            // Matching 1 keyword = score * 1
            // Matching 2 keywords = score * 1.8
            // Matching 3 keywords = score * 2.7
            // Matching 4 keywords = score * 4.0
            // Matching 5+ keywords = score * 6.0+
            val uniqueMatchCount = matched.distinct().size
            if (uniqueMatchCount > 1) {
                val intersectionBonus = when {
                    uniqueMatchCount >= 5 -> 3.0 + uniqueMatchCount * 0.6
                    uniqueMatchCount >= 3 -> 1.5 + uniqueMatchCount * 0.4
                    else -> 1.0 + uniqueMatchCount * 0.4
                }
                score *= intersectionBonus
            }

            // Extra bonus: if ALL primary keywords are found in this chunk
            if (primaryKeywords.isNotEmpty() && primaryMatches == primaryKeywords.size) {
                score *= 1.5  // 50% bonus for complete primary match
            }

            // Calculate confidence
            val confidence = when {
                uniqueMatchCount >= 4 && primaryMatches > 0 -> 0.95f
                uniqueMatchCount >= 3 && primaryMatches > 0 -> 0.85f
                uniqueMatchCount >= 2 && primaryMatches > 0 -> 0.70f
                primaryMatches > 0 -> 0.50f
                uniqueMatchCount >= 2 -> 0.40f
                uniqueMatchCount >= 1 -> 0.25f
                else -> 0.0f
            }

            SearchResult(chunk, score, matched.distinct(), confidence)
        }

        // Step 3: Dynamic result count based on confidence
        val positiveResults = scoredChunks.filter { it.score > 0.0 }.sortedByDescending { it.score }

        val dynamicTopK = when {
            positiveResults.isEmpty() -> topK
            positiveResults.first().confidence >= 0.85f -> MAX_RESULTS_HIGH_CONF  // High confidence → fewer, better results
            positiveResults.first().confidence >= 0.50f -> MAX_RESULTS_DEFAULT
            else -> MAX_RESULTS_LOW_CONF  // Low confidence → cast wider net
        }

        val finalResults = positiveResults.take(dynamicTopK)

        // Step 4: Deduplicate overlapping chunks (since we have overlap in chunking)
        val deduped = deduplicateOverlapping(finalResults)

        Log.d(TAG, "═══ Search Results ═══")
        Log.d(TAG, "Query: ${query.take(80)}")
        Log.d(TAG, "Found ${deduped.size} results (from ${positiveResults.size} positive, ${chunks.size} total)")
        deduped.forEachIndexed { i, result ->
            Log.d(TAG, "  #${i + 1}: score=${String.format("%.2f", result.score)}, " +
                    "confidence=${String.format("%.0f", result.confidence * 100)}%, " +
                    "keywords=${result.matchedKeywords}, " +
                    "lines=${result.chunk.startLine}-${result.chunk.endLine}")
        }

        return deduped
    }

    /**
     * Remove overlapping chunks (prefer higher-scored one).
     */
    private fun deduplicateOverlapping(results: List<SearchResult>): List<SearchResult> {
        if (results.size <= 1) return results

        val deduped = mutableListOf<SearchResult>()
        val usedLineRanges = mutableListOf<IntRange>()

        for (result in results) {
            val range = result.chunk.startLine..result.chunk.endLine
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
    // ── MAIN API: getRelevantContext ──
    // ═════════════════════════════════════════

    /**
     * Get relevant context for a question — the main method to call from UI.
     * Returns formatted relevant document sections ready for the LLM prompt.
     */
    fun getRelevantContext(query: String, maxChars: Int = 35000): String? {
        if (!isIndexed) return null

        val results = search(query)

        if (results.isEmpty()) {
            // No keyword matches — use document overview as fallback
            Log.d(TAG, "No keyword matches, using document overview as fallback")
            return getDocumentOverview(maxChars)
        }

        // Build context from search results with expanded surrounding lines
        val contextBuilder = StringBuilder()
        var totalChars = 0

        // Add a brief note about search quality
        val topConfidence = results.firstOrNull()?.confidence ?: 0f
        if (topConfidence >= 0.7f) {
            contextBuilder.append("[High-confidence matches found]\n")
        }

        for ((i, result) in results.withIndex()) {
            if (totalChars >= maxChars) break

            // Expand context: include lines before and after the chunk
            val expandedStart = maxOf(0, result.chunk.startLine - CONTEXT_LINES_BEFORE)
            val expandedEnd = minOf(lines.size - 1, result.chunk.endLine + CONTEXT_LINES_AFTER)
            val expandedText = lines.subList(expandedStart, expandedEnd + 1).joinToString("\n")

            if (i > 0) contextBuilder.append("\n\n---\n\n")
            contextBuilder.append("[Section ${i + 1} | Lines ${expandedStart + 1}-${expandedEnd + 1} | ")
            contextBuilder.append("Matched: ${result.matchedKeywords.take(5).joinToString(", ")}]\n")
            contextBuilder.append(expandedText)

            totalChars += expandedText.length + 50
        }

        val context = contextBuilder.toString().take(maxChars)
        Log.d(TAG, "Returning ${context.length} chars of relevant context")
        return context
    }

    // ═════════════════════════════════════════
    // ── KEYWORD EXTRACTION ──
    // ═════════════════════════════════════════

    /**
     * Extract meaningful keywords from a user query.
     * Removes stop words, keeps significant terms.
     */
    fun extractRawKeywords(query: String): List<String> {
        return tokenize(query)
            .filter { it.length >= 2 }
            .filter { it !in STOP_WORDS }
            .distinct()
            .take(8)
    }

    /**
     * Public wrapper that returns expanded keywords for display/debug.
     */
    fun extractKeywords(query: String): List<String> {
        return expandKeywords(query).map { it.word }.distinct()
    }

    /**
     * Tokenize text into lowercase words.
     */
    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.length >= 2 }
    }

    // ═════════════════════════════════════════
    // ── UTILITY METHODS ──
    // ═════════════════════════════════════════

    fun getStats(): String {
        if (!isIndexed) return "No document indexed"
        val vocabSize = wordIndex.size
        val coOccurSize = coOccurrence.size
        return "${lines.size} lines, ${chunks.size} chunks, ${fullText.length} chars, $vocabSize vocab, $coOccurSize word-relations"
    }

    fun getDocumentLength(): Int = fullText.length

    fun isDocumentIndexed(): Boolean = isIndexed

    fun clear() {
        fullText = ""
        lines = emptyList()
        chunks = emptyList()
        wordIndex = emptyMap()
        coOccurrence = emptyMap()
        globalWordFreq = emptyMap()
        isIndexed = false
        Log.d(TAG, "Index cleared")
    }

    /**
     * Get a summary overview of the document (first + last chunks) for initial/fallback context.
     */
    fun getDocumentOverview(maxChars: Int = 5000): String {
        if (!isIndexed || chunks.isEmpty()) return ""

        val firstChunk = chunks.first().text
        val lastChunk = if (chunks.size > 1) chunks.last().text else ""

        val overview = buildString {
            append("[Beginning of document]\n")
            append(firstChunk.take(maxChars / 2))
            if (lastChunk.isNotBlank() && chunks.size > 2) {
                append("\n\n...\n\n[End of document]\n")
                append(lastChunk.take(maxChars / 2))
            }
        }

        return overview.take(maxChars)
    }

    /**
     * Debug: Show the top co-occurring words for a given keyword.
     * Useful for understanding what the system "knows" about word relationships.
     */
    fun debugShowRelatedWords(keyword: String): String {
        val related = coOccurrence[keyword.lowercase()]
            ?: return "No relationships found for '$keyword'"

        val topRelated = related.entries
            .filter { it.key !in STOP_WORDS }
            .sortedByDescending { it.value }
            .take(15)

        return buildString {
            append("Words related to '$keyword' in this document:\n")
            topRelated.forEach { (word, count) ->
                append("  • $word (co-occurs $count times)\n")
            }
        }
    }
}
