package com.bookcon.app.screenshots

import android.graphics.Color as AndroidColor
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookcon.app.ui.reader.PageAnimation
import com.bookcon.app.ui.reader.PageTurnCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioural tests for the EPUB page-turn coordinator.
 *
 * The property that matters most is not how the turn looks, it is that a turn
 * ALWAYS reaches the reader. Every failure mode — no navigator view yet, a view
 * that has not been laid out, or a WebView that hands back an empty surface — must
 * fall through to a plain navigation rather than swallowing the page turn.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi")
class PageTurnCoordinatorTest {

    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: PageTurnCoordinator

    @Before
    fun setUp() {
        activity = org.robolectric.Robolectric
            .buildActivity(android.app.Activity::class.java)
            .setup()
            .get()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinator = PageTurnCoordinator(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A view that was never attached — the navigator has not landed yet. */
    private fun detachedView(): View = View(activity).also { it.layout(0, 0, 400, 800) }

    private lateinit var activity: android.app.Activity

    /**
     * A laid-out view whose surface carries real content, the way a rendered
     * reading page does. `draw` is overridden rather than relying on background
     * drawables so the snapshot content is deterministic — Robolectric does not
     * rasterise a real view hierarchy here.
     *
     * The view is added to a live activity's content view because the coordinator
     * refuses to animate a view that is not attached to a window, which is exactly
     * the guard that stops it snapshotting a navigator that has gone away.
     */
    private fun paintedView(width: Int = 400, height: Int = 800): View {
        val view = object : View(activity) {
            override fun draw(canvas: android.graphics.Canvas) {
                val paint = android.graphics.Paint()
                var y = 0
                while (y < height) {
                    paint.color = if ((y / 20) % 2 == 0) {
                        AndroidColor.rgb(18, 21, 27)
                    } else {
                        AndroidColor.rgb(240, 244, 247)
                    }
                    canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 20).toFloat(), paint)
                    y += 20
                }
            }
        }
        activity.setContentView(view, android.view.ViewGroup.LayoutParams(width, height))
        // Force the attach + layout pass: the coordinator skips a view that is not
        // attached or has no size, and Robolectric does not run one on its own here.
        ShadowLooper.idleMainLooper()
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view
    }

    @Test
    fun none_and_slide_navigate_without_a_snapshot() {
        coordinator.navigatorView = paintedView()
        listOf(PageAnimation.None, PageAnimation.Slide).forEach { mode ->
            var navigated = false
            coordinator.turn(mode, forward = true) { navigated = true }
            assertTrue("$mode must navigate", navigated)
            assertNull("$mode must not animate", coordinator.turn)
            assertEquals("$mode must not take the animated path", 0, coordinator.animatedTurnCount)
        }
    }

    @Test
    fun missing_navigator_view_still_navigates() {
        // The navigator fragment has not attached yet.
        coordinator.navigatorView = null
        var navigated = false
        coordinator.turn(PageAnimation.PageTurn, forward = true) { navigated = true }
        assertTrue("a turn with no view must still navigate", navigated)
        assertNull(coordinator.turn)
        assertEquals(0, coordinator.animatedTurnCount)
    }

    @Test
    fun unlaid_out_view_still_navigates() {
        // Never attached: a detached view cannot be snapshotted, so the turn must
        // fall through to a plain navigation.
        coordinator.navigatorView = detachedView()
        var navigated = false
        coordinator.turn(PageAnimation.PageTurn, forward = false) { navigated = true }
        assertTrue(navigated)
        assertNull(coordinator.turn)
    }

    @Test
    fun blank_capture_falls_back_to_a_plain_navigation() {
        // An attached view that paints nothing renders as a single flat colour. The
        // coordinator must detect that and skip the animation rather than flashing a
        // blank sheet over the page.
        val blank = View(activity)
        activity.setContentView(blank, android.view.ViewGroup.LayoutParams(400, 800))
        ShadowLooper.idleMainLooper()
        blank.layout(0, 0, 400, 800)
        coordinator.navigatorView = blank
        var navigated = false
        coordinator.turn(PageAnimation.PageTurn, forward = true) { navigated = true }
        assertTrue("a blank capture must still navigate", navigated)
        assertNull("a blank capture must not animate a blank sheet", coordinator.turn)
        assertEquals("a blank capture must not animate", 0, coordinator.animatedTurnCount)
    }

    @Test
    fun page_turn_animates_and_navigates_when_the_snapshot_is_usable() {
        coordinator.navigatorView = paintedView()
        var navigated = false
        coordinator.turn(PageAnimation.PageTurn, forward = true) { navigated = true }
        // Navigation is issued up-front so the next page renders underneath.
        assertTrue("navigation must be issued immediately", navigated)
        assertEquals(
            "a usable snapshot must take the animated path",
            1, coordinator.animatedTurnCount,
        )
    }

    @Test
    fun fade_animates_and_navigates_when_the_snapshot_is_usable() {
        coordinator.navigatorView = paintedView()
        var navigated = false
        coordinator.turn(PageAnimation.Fade, forward = false) { navigated = true }
        assertTrue(navigated)
        assertEquals(
            "fade must animate too, not fall back",
            1, coordinator.animatedTurnCount,
        )
    }

}
