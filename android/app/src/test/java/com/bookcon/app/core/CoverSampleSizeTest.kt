package com.bookcon.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cover is only ever shown as a library tile, so it is decoded subsampled
 * straight to thumbnail size.
 *
 * The reason this matters: `BitmapFactory.decodeByteArray` without
 * `inSampleSize` allocates the image at full resolution first. An 8000x6000
 * cover is ~192 MB of ARGB_8888, allocated during an ordinary book import and
 * then immediately shrunk by [CoverExtractor.scaleDown] — an easy way to be
 * killed for memory while importing.
 */
class CoverSampleSizeTest {

    private fun sampled(w: Int, h: Int, maxDim: Int) = maxOf(w, h) / CoverExtractor.coverSampleSize(w, h, maxDim)

    @Test
    fun an_image_that_already_fits_is_not_downsampled() {
        assertEquals(1, CoverExtractor.coverSampleSize(512, 512, 512))
        assertEquals(1, CoverExtractor.coverSampleSize(300, 400, 512))
        // 513 is a hair over budget; halving it to 256 would be visibly worse, so it
        // is left for the exact resample instead.
        assertEquals(1, CoverExtractor.coverSampleSize(513, 400, 512))
    }

    @Test
    fun a_large_image_is_reduced_by_a_power_of_two() {
        // 8000x6000 -> sample 8 -> 1000x750, which scaleDown then takes to 512.
        assertEquals(8, CoverExtractor.coverSampleSize(8000, 6000, 512))
        assertEquals(4, CoverExtractor.coverSampleSize(2048, 2048, 512))
    }

    @Test
    fun the_sample_size_is_always_a_power_of_two() {
        // Only power-of-two factors can be honoured without resampling.
        for (side in listOf(513, 800, 1000, 1024, 1500, 2048, 4000, 5000, 8000, 12000, 40_000)) {
            val s = CoverExtractor.coverSampleSize(side, side, 512)
            assertTrue("$side produced $s", s > 0 && s and (s - 1) == 0)
        }
    }

    @Test
    fun sampling_lands_the_decoded_image_within_a_factor_of_two_of_the_budget() {
        // Not "at most 512" — the power-of-two steps cannot land exactly on a
        // budget, so the contract is that we never overshoot badly and never
        // waste a halving by undershooting badly. Anything outside [512, 1024] is
        // a bug: above it we allocate more than we need, below it we leave an
        // image too small to scale up.
        for (side in listOf(1024, 1100, 1500, 2048, 4000, 5000, 8000, 12000, 40000)) {
            val out = sampled(side, side, 512)
            assertTrue("$side -> $out overshoots the budget", out <= 2 * 512)
            assertTrue("$side -> $out wastes a halving", out >= 512)
        }
    }

    @Test
    fun the_longest_side_governs_not_the_shortest() {
        // A panorama is 12000 x 200: sampling on the short side would leave a
        // 7500px-wide bitmap and miss the real problem entirely.
        assertEquals(16, CoverExtractor.coverSampleSize(12000, 200, 512))
        assertTrue(sampled(12000, 200, 512) <= 2 * 512)
    }

    @Test
    fun degenerate_input_returns_one_rather_than_looping_forever() {
        assertEquals(1, CoverExtractor.coverSampleSize(0, 0, 512))
        assertEquals(1, CoverExtractor.coverSampleSize(-5, 100, 512))
        assertEquals(1, CoverExtractor.coverSampleSize(100, 100, 0))
    }
}
