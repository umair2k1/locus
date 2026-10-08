package com.locus.core.domain.dashboard

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.ModelTier
import com.locus.core.domain.routing.RouteAndSend
import com.locus.core.domain.routing.RouteDecision
import com.locus.core.domain.routing.TaskType
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.time.Clock
import kotlinx.coroutines.flow.first
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.sqrt

class ComputeClustersUseCase(
    private val chunkRepository: ChunkRepository,
    private val noteRepository: NoteRepository,
    private val chatModelClient: ChatModelClient,
    private val clock: Clock,
    private val routeAndSend: RouteAndSend? = null,
) {
    @Suppress("ReturnCount")
    suspend fun execute(
        currentModel: ModelRef? = null,
        confirmCloudTransition: (suspend (RouteDecision.RequiresCloudTransitionConfirmation) -> Boolean)? = null,
    ): List<ClusterCard> {
        val noteEmbeddings = chunkRepository.allNoteMeanEmbeddings()
        if (noteEmbeddings.isEmpty()) return emptyList()

        val sortedNotes = noteEmbeddings.entries.sortedBy { it.key }
        val k = computeK(sortedNotes.size)
        if (k <= 0) return emptyList()

        val clusters = runKMeans(sortedNotes, k, sortedNotes.first().value.size)
        if (clusters.isEmpty()) return emptyList()

        val allNotesMap =
            try {
                noteRepository.observeAllNotes().first().associateBy { it.id }
            } catch (_: Exception) {
                emptyMap()
            }

        val defaultModel = ModelRef(id = "local-utility", tier = ModelTier.LOCAL, providerId = null)
        val modelToRoute = currentModel ?: defaultModel

        return clusters.mapNotNull { clusterNoteIds ->
            if (clusterNoteIds.isEmpty()) return@mapNotNull null
            buildClusterCard(
                clusterNoteIds = clusterNoteIds,
                allNotesMap = allNotesMap,
                modelToRoute = modelToRoute,
                confirmCloudTransition = confirmCloudTransition,
            )
        }
    }

    private suspend fun buildClusterCard(
        clusterNoteIds: List<String>,
        allNotesMap: Map<String, com.locus.core.domain.notes.Note>,
        modelToRoute: ModelRef,
        confirmCloudTransition: (suspend (RouteDecision.RequiresCloudTransitionConfirmation) -> Boolean)?,
    ): ClusterCard {
        if (routeAndSend != null) {
            routeAndSend.route(
                task = TaskType.DIGEST_TAGGING_CLUSTER_LABEL,
                currentModel = modelToRoute,
                confirmCloudTransition = confirmCloudTransition ?: { true },
            )
        }

        val titles =
            clusterNoteIds.map { id ->
                allNotesMap[id]?.title?.ifBlank { "Untitled" } ?: "Note $id"
            }

        val generatedLabel = generateLabel(buildLabelPrompt(titles))
        val finalLabel =
            if (generatedLabel.isNotBlank()) {
                cleanLabel(generatedLabel)
            } else {
                "Topic: " + (titles.firstOrNull() ?: "General")
            }

        return ClusterCard(
            id = UUID.randomUUID().toString(),
            label = finalLabel,
            noteIds = clusterNoteIds,
            noteTitles = titles,
            computedAt = clock.now(),
        )
    }

    internal fun computeK(noteCount: Int): Int =
        when {
            noteCount <= 0 -> 0
            noteCount <= 2 -> noteCount
            else -> ceil(sqrt(noteCount / 2.0)).toInt().coerceIn(2, minOf(noteCount, MAX_K))
        }

    @Suppress("ReturnCount")
    private fun runKMeans(
        notes: List<Map.Entry<String, FloatArray>>,
        k: Int,
        dim: Int,
    ): List<List<String>> {
        if (notes.isEmpty() || k <= 0) return emptyList()
        if (k == 1) return listOf(notes.map { it.key })

        val centroids = initializeCentroids(notes, k)
        var assignments = IntArray(notes.size) { -1 }

        repeat(MAX_ITERATIONS) {
            val (newAssignments, changed) = assignToCentroids(notes, centroids)
            assignments = newAssignments
            updateCentroids(notes, assignments, centroids, k, dim)
            if (!changed) return groupAssignments(notes, assignments, k)
        }

        return groupAssignments(notes, assignments, k)
    }

    private fun initializeCentroids(
        notes: List<Map.Entry<String, FloatArray>>,
        k: Int,
    ): MutableList<FloatArray> {
        val step = notes.size.toDouble() / k
        return (0 until k)
            .map { i ->
                val idx = (i * step).toInt().coerceIn(0, notes.size - 1)
                notes[idx].value.copyOf()
            }.toMutableList()
    }

    private fun assignToCentroids(
        notes: List<Map.Entry<String, FloatArray>>,
        centroids: List<FloatArray>,
    ): Pair<IntArray, Boolean> {
        var changed = false
        val assignments = IntArray(notes.size)
        for (nIdx in notes.indices) {
            val vec = notes[nIdx].value
            val best = findNearestCentroid(vec, centroids)
            if (assignments[nIdx] != best) {
                changed = true
            }
            assignments[nIdx] = best
        }
        return Pair(assignments, changed)
    }

    private fun findNearestCentroid(
        vec: FloatArray,
        centroids: List<FloatArray>,
    ): Int {
        var bestCluster = 0
        var bestDist = Float.MAX_VALUE
        for (cIdx in centroids.indices) {
            val dist = 1.0f - cosineSimilarity(vec, centroids[cIdx])
            if (dist < bestDist) {
                bestDist = dist
                bestCluster = cIdx
            }
        }
        return bestCluster
    }

    private fun updateCentroids(
        notes: List<Map.Entry<String, FloatArray>>,
        assignments: IntArray,
        centroids: MutableList<FloatArray>,
        k: Int,
        dim: Int,
    ) {
        val clusterVectors = List(k) { mutableListOf<FloatArray>() }
        for (nIdx in notes.indices) {
            clusterVectors[assignments[nIdx]].add(notes[nIdx].value)
        }
        for (cIdx in 0 until k) {
            val members = clusterVectors[cIdx]
            if (members.isNotEmpty()) {
                centroids[cIdx] = computeMean(members, dim)
            }
        }
    }

    private fun groupAssignments(
        notes: List<Map.Entry<String, FloatArray>>,
        assignments: IntArray,
        k: Int,
    ): List<List<String>> {
        val grouped = List(k) { mutableListOf<String>() }
        for (nIdx in notes.indices) {
            grouped[assignments[nIdx]].add(notes[nIdx].key)
        }
        return grouped.filter { it.isNotEmpty() }
    }

    private fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val ai = a[i]
            val bi = b[i]
            dot += ai * bi
            normA += ai * ai
            normB += bi * bi
        }
        val denom = (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
        return if (denom > 0.0f) dot / denom else 0.0f
    }

    private fun computeMean(
        vectors: List<FloatArray>,
        dim: Int,
    ): FloatArray {
        val mean = FloatArray(dim)
        if (vectors.isEmpty()) return mean
        for (v in vectors) {
            val len = minOf(dim, v.size)
            for (i in 0 until len) {
                mean[i] += v[i]
            }
        }
        val count = vectors.size.toFloat()
        for (i in 0 until dim) {
            mean[i] /= count
        }
        return mean
    }

    private fun buildLabelPrompt(titles: List<String>): String =
        "Generate a concise, 2-to-4 word descriptive topic label for the following collection of notes:\n" +
            titles.take(MAX_TITLES_IN_PROMPT).joinToString("\n") { "- $it" } +
            "\nOutput ONLY the topic label (no markdown, no quotes, no extra text):"

    private suspend fun generateLabel(prompt: String): String {
        val sb = StringBuilder()
        try {
            chatModelClient.generate(prompt).collect { event ->
                if (event is StreamEvent.TokenDelta) {
                    sb.append(event.text)
                }
            }
        } catch (_: Exception) {
            // Return accumulated or blank
        }
        return sb.toString().trim()
    }

    private fun cleanLabel(raw: String): String =
        raw
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.removePrefix("-")
            ?.removePrefix("*")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.removePrefix("'")
            ?.removeSuffix("'")
            ?.trim()
            ?.take(MAX_LABEL_LENGTH)
            ?: "Topic Cluster"

    companion object {
        private const val MAX_K = 10
        private const val MAX_ITERATIONS = 20
        private const val MAX_TITLES_IN_PROMPT = 10
        private const val MAX_LABEL_LENGTH = 50
    }
}
