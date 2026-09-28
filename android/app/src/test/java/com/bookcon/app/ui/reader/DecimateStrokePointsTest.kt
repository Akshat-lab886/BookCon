package com.bookcon.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ink point decimation.
 *
 * Ink is captured at touch-sample rate — every event the OS delivers along a drag —
 * with no bound, then serialized into a single Room column and pushed whole on every
 * change. A few seconds of scribbling produced tens of thousands of coordinates, and
 * each further stroke re-serialized and re-uploaded all of them. Points arrive
 * normalized to 0..1, so a fraction of the page is the natural threshold.
 */
class DecimateStrokePointsTest {

    private fun pts(vararg v: Float) = v.toList()

    @Test
    fun a_single_point_stroke_is_left_alone() {
        assertEquals(pts(0f, 0f), decimateStrokePoints(pts(0f, 0f)))
    }

    @Test
    fun an_empty_stroke_is_left_alone() {
        assertEquals(emptyList<Float>(), decimateStrokePoints(emptyList()))
    }

    @Test
    fun closely_spaced_points_are_dropped() {
        // Ten samples 0.0001 apart: well under the 0.004 threshold, so only the
        // endpoints survive.
        val dense = List(10) { i -> listOf(i * 0.0001f, 0.5f) }.flatten()
        val out = decimateStrokePoints(dense)
        assertEquals(4, out.size)
        assertEquals(0f, out[0])
        assertEquals(0.0009f, out[2])
    }

    @Test
    fun well_separated_points_are_all_kept() {
        val spread = pts(0f, 0f, 0.5f, 0.5f, 0.9f, 0.1f)
        assertEquals(spread, decimateStrokePoints(spread))
    }

    @Test
    fun the_first_point_is_always_kept() {
        val out = decimateStrokePoints(pts(0.1f, 0.1f, 0.1001f, 0.1001f, 0.1002f, 0.1002f))
        assertEquals(0.1f, out[0])
        assertEquals(0.1f, out[1])
    }

    @Test
    fun the_endpoint_is_always_kept_even_when_close_to_the_previous_sample() {
        // The endpoint is what the stroke is judged to have reached, so it must not
        // be thinned away into a slightly short line.
        val out = decimateStrokePoints(pts(0f, 0f, 0.0001f, 0.0001f))
        assertEquals(0.0001f, out[out.size - 2])
        assertEquals(0.0001f, out[out.size - 1])
    }

    @Test
    fun decimation_never_increases_the_point_count() {
        val many = List(400) { i -> listOf(i * 0.001f, 0.5f) }.flatten()
        assertTrue(decimateStrokePoints(many).size <= many.size)
    }

    @Test
    fun output_always_has_whole_points() {
        val odd = pts(0f, 0f, 0.5f, 0.5f, 0.9f)
        val out = decimateStrokePoints(odd)
        assertEquals(0, out.size % 2)
    }

    @Test
    fun storage_grows_with_the_stroke_not_with_the_sampling_rate() {
        // The guarantee that matters: a stroke is stored at roughly the threshold's
        // resolution, so cost tracks how long the line *is* rather than how long the
        // finger was down. Sampling 40× more finely must not cost 40× the storage.
        //
        // The rule is greedy against the last point kept, so denser input is not
        // bit-identical — float precision erodes the small gaps and a few more
        // points survive. The bound, not exact equality, is the contract.
        fun diagonal(n: Int): List<Float> =
            List(n) { i ->
                val t = i / (n - 1f)
                listOf(t * 0.5f, t * 0.3f)
            }.flatten()

        val at120 = decimateStrokePoints(diagonal(120)).size
        val at4800 = decimateStrokePoints(diagonal(4800)).size

        // 120 samples are already coarser than the threshold, so they all survive;
        // 4800 is the same line reported 40× more finely.
        assertTrue(
            "40x the samples cost ${at4800 / at120}x the storage",
            at4800 <= at120 * 1.5,
        )
    }

    @Test
    fun holding_the_finger_down_no_longer_inflates_the_stroke() {
        // Ten seconds of 120 Hz touch sampling along a 10-second drag. Under the old
        // unbounded capture this stored 1200 points; it now costs about what two
        // seconds did, because both describe the same line.
        fun drag(seconds: Int) = List(seconds * 120) { i ->
            val t = i / (seconds * 120f - 1f)
            listOf(t * 0.5f, t * 0.3f)
        }.flatten()

        val twoSeconds = decimateStrokePoints(drag(2)).size
        val tenSeconds = decimateStrokePoints(drag(10)).size
        assertTrue(
            "a 5x longer drag cost ${tenSeconds / twoSeconds}x the storage",
            tenSeconds <= twoSeconds * 1.5,
        )
    }

    @Test
    fun a_slow_drag_is_stored_far_coarser_than_it_is_captured() {
        // 2 seconds of 120 Hz touch sampling, still described end to end.
        val samples = List(240) { i ->
            val t = i / 239f
            listOf(t * 0.5f, t * 0.3f)
        }.flatten()
        val out = decimateStrokePoints(samples)
        assertTrue("expected thinning, got ${out.size} of ${samples.size}", out.size < samples.size)
        assertEquals(samples[0], out[0])
        assertEquals(samples[samples.size - 2], out[out.size - 2])
        assertEquals(samples[samples.size - 1], out[out.size - 1])
    }
}
