package com.panmusic.carplayer.util

import org.junit.Assert.*
import org.junit.Test

class ShuffleAlgorithmTest {

    private fun fisherYatesShuffle(size: Int, previousLast: Int = -1): List<Int> {
        if (size <= 0) return emptyList()
        val indices = (0 until size).toMutableList()
        for (i in size - 1 downTo 1) {
            val j = (Math.random() * (i + 1)).toInt()
            val t = indices[i]; indices[i] = indices[j]; indices[j] = t
        }
        if (previousLast >= 0 && indices.isNotEmpty() && indices.first() == previousLast) {
            val idx = minOf(1 + (Math.random() * (size - 1)).toInt(), size - 1)
            val t = indices[0]; indices[0] = indices[idx]; indices[idx] = t
        }
        return indices
    }

    @Test fun `shuffle preserves all elements`() {
        for (size in listOf(1, 3, 5, 10)) {
            val r = fisherYatesShuffle(size)
            assertEquals(size, r.size)
            assertEquals((0 until size).toSet(), r.toSet())
        }
    }

    @Test fun `shuffle has no duplicates`() {
        repeat(10) {
            val r = fisherYatesShuffle(20)
            assertEquals(r.size, r.toSet().size)
        }
    }

    @Test fun `different shuffles produce different sequences`() {
        val results = (0 until 50).map { fisherYatesShuffle(10) }
        assertTrue(results.distinct().size > 1)
    }

    @Test fun `each position has uniform distribution`() {
        val size = 8; val trials = 2000
        val counts = Array(size) { IntArray(size) }
        repeat(trials) {
            fisherYatesShuffle(size).forEachIndexed { pos, v -> counts[v][pos]++ }
        }
        val expected = trials / size; val tol = (expected * 0.3).toInt()
        for (v in 0 until size) for (p in 0 until size)
            assertTrue("v=$v p=$p", counts[v][p] in expected - tol..expected + tol)
    }

    @Test fun `first element not same as previous last`() {
        repeat(50) { assertNotEquals(2L, fisherYatesShuffle(5, previousLast = 2).first().toLong()) }
    }

    @Test fun `empty shuffle`() { assertTrue(fisherYatesShuffle(0).isEmpty()) }

    @Test fun `single element shuffle`() { assertEquals(listOf(0), fisherYatesShuffle(1)) }

    @Test fun `shuffle with size 1 and previousLast matches`() {
        assertEquals(listOf(0), fisherYatesShuffle(1, previousLast = 0))
    }

    @Test fun `shuffle is O(n) fast`() {
        val start = System.currentTimeMillis()
        repeat(100) { fisherYatesShuffle(1000) }
        assertTrue("${System.currentTimeMillis() - start}ms", System.currentTimeMillis() - start < 200)
    }
}
