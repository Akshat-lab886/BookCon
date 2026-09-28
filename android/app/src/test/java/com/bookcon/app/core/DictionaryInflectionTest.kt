package com.bookcon.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * The -ed branch used to append `if (base.endsWith("ied")) "" else ""` — both arms
 * produced the empty string, so the intended silent-e restoration never happened. A
 * word like "loved" therefore failed to find "love" on the first tap and the user had
 * to tap again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DictionaryInflectionTest {

    private fun candidates(raw: String): List<String> {
        val m = Dictionary::class.java.getDeclaredMethod("candidatesFor", String::class.java)
        m.isAccessible = true
        val dict = Dictionary.get(
            org.robolectric.RuntimeEnvironment.getApplication(),
        )
        @Suppress("UNCHECKED_CAST")
        return m.invoke(dict, raw) as List<String>
    }

    @Test
    fun a_silent_e_word_offers_its_stem() {
        assertTrue("'loved' must offer 'love'", candidates("loved").contains("love"))
    }

    @Test
    fun a_doubled_consonant_word_offers_its_stem() {
        assertTrue("'stopped' must offer 'stop'", candidates("stopped").contains("stop"))
    }

    @Test
    fun a_regular_word_offers_its_stem() {
        assertTrue("'walked' must offer 'walk'", candidates("walked").contains("walk"))
    }

    @Test
    fun the_ied_form_still_offers_the_y_form() {
        assertTrue("'tried' must offer 'try'", candidates("tried").contains("try"))
    }

    @Test
    fun ing_forms_still_double_or_restore() {
        val c = candidates("running")
        assertTrue("'running' must offer 'run'", c.contains("run"))
    }

    @Test
    fun plural_and_ly_forms_are_unaffected() {
        assertTrue(candidates("cats").contains("cat"))
        assertTrue(candidates("quickly").contains("quick"))
    }

    @Test
    fun candidates_are_distinct_and_long_enough_to_be_useful() {
        val c = candidates("loved")
        assertTrue("duplicates should be removed", c.size == c.distinct().size)
        assertTrue("one-letter stems are noise", c.all { it.length > 1 })
    }
}
