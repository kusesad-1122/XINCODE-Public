package com.xincode.provider

import android.util.Log
import com.xincode.data.MemoryEntity
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Embedding generation and vector similarity utilities.
 *
 * floatArrayToBytes / bytesToFloatArray: convert FloatArray ↔ ByteArray (Little-Endian).
 * cosineSimilarity: standard cosine distance between two vectors.
 */
object EmbeddingService {
    private const val TAG = "EmbeddingService"

    /** Serialize FloatArray to ByteArray for Room BLOB storage. */
    fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4)
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    /** Deserialize ByteArray back to FloatArray. */
    fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes)
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buf.float
        }
        return floats
    }

    /** Cosine similarity in [-1, 1]. Higher = more similar. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    /**
     * Hybrid search: FTS4 keyword + vector similarity.
     * @param queryFts FTS4 MATCH expression
     * @param queryVector embedding of the query
     * @param ftsResults results from FTS4 MATCH search
     * @param embeddedCandidates all memories that have embeddings (for vector scoring)
     * @param vectorWeight how much to weight vector similarity [0..1]
     * @param limit max results to return
     */
    fun hybridRank(
        ftsResults: List<com.xincode.data.MemoryEntity>,
        queryVector: FloatArray,
        embeddedCandidates: List<com.xincode.data.MemoryEntity>,
        vectorWeight: Float = 0.5f,
        limit: Int = 20
    ): List<com.xincode.data.MemoryEntity> {
        val ftsSet = ftsResults.associateBy { it.id }
        val vecScores = embeddedCandidates.mapNotNull { mem ->
            val emb = mem.embedding ?: return@mapNotNull null
            val vec = bytesToFloatArray(emb)
            val sim = cosineSimilarity(queryVector, vec)
            mem to sim
        }

        data class Scored(val memory: com.xincode.data.MemoryEntity, val score: Float)

        val scored = buildList {
            for (mem in ftsResults) {
                val ftsScore = 1.0f - (ftsResults.indexOf(mem).toFloat() / maxOf(1, ftsResults.size))
                val vecScore = vecScores.firstOrNull { it.first.id == mem.id }?.second ?: 0f
                val hybrid = (1 - vectorWeight) * ftsScore + vectorWeight * vecScore
                add(Scored(mem, hybrid))
            }
            for ((mem, vecScore) in vecScores) {
                if (mem.id !in ftsSet) {
                    add(Scored(mem, vectorWeight * vecScore))
                }
            }
        }

        return scored.sortedByDescending { it.score }.map { it.memory }.take(limit)
    }
}