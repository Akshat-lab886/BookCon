package com.bookcon.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Page-turn animation for the **EPUB** reading path.
 *
 * The PDF path already has a real pager ([AnimatedPager] + [PageAnimation]) because
 * it owns its own renderer. EPUBs are rendered by Readium's navigator, which is a
 * single WebView scrolled internally by CSS columns — there is no per-page view to
 * transform, so [AnimatedPager] can never apply to it.
 *
 * This coordinator bridges that gap: it grabs a snapshot of the navigator surface,
 * performs the real navigation underneath it, and peels the snapshot away as a
 * turning sheet so the reader sees a genuine page turn rather than an instant jump.
 *
 * Modes:
 * - [PageAnimation.None]  — no overlay at all, navigation only.
 * - [PageAnimation.Slide]  — Readium's own native scroll; nothing to draw.
 * - [PageAnimation.Fade]   — the snapshot cross-fades out over the new page.
 * - [PageAnimation.PageTurn] — the snapshot rotates about its inner edge; past 90°
 *   it becomes the blank back of the sheet, which is what real paper does.
 *
 * Every mode degrades safely: if the navigator surface cannot be snapshotted (a
 * blank capture, a detached view, a zero-sized view) the coordinator simply runs the
 * navigation with no animation rather than flashing an empty sheet.
 */
class PageTurnCoordinator(private val scope: CoroutineScope) {

    /** The Readium navigator's container view, once the fragment is attached. */
    var navigatorView: View? = null

    private val _turn = mutableStateOf<Turn?>(null)

    /** Current in-flight turn, or null when idle. Compose observes this. */
    val turn: Turn? get() = _turn.value

    /**
     * Drives the sheet. Exposed so the overlay can read it as a snapshot state —
     * inside `graphicsLayer { }` that is a deferred per-frame read with no
     * recomposition, which is what keeps the turn smooth.
     */
    val progress = Animatable(0f)

    /**
     * How many turns actually took the animated path. Lets the tests assert the
     * snapshot succeeded without depending on where the animation happens to be
     * when the assertion runs.
     */
    @androidx.annotation.VisibleForTesting
    var animatedTurnCount: Int = 0
        private set

    data class Turn(
        val shot: Bitmap,
        val forward: Boolean,
        val mode: PageAnimation,
    )

    private var animationProvider: () -> PageAnimation = { PageAnimation.Slide }
    private var navigator: (Boolean) -> Unit = {}

    /**
     * Binds the coordinator to the current reading session.
     *
     * [animation] and [navigate] are taken as lambdas rather than captured
     * directly, so changing the page-turn setting or swapping books takes effect
     * without having to rebuild the coordinator.
     */
    fun configure(
        animation: () -> PageAnimation,
        navigate: (Boolean) -> Unit,
    ) {
        animationProvider = animation
        navigator = navigate
    }

    /** Entry point used by the tap zones, the bottom bar and the volume keys. */
    fun turnAnimated(forward: Boolean) {
        turn(animationProvider(), forward) { navigator(forward) }
    }

    /**
     * Runs [navigate] wrapped in the configured animation.
     *
     * The navigation is issued *first* so the new page is already rendering while
     * the snapshot is still on top; the snapshot is then peeled away to reveal it.
     */
    fun turn(animation: PageAnimation, forward: Boolean, navigate: () -> Unit) {
        val view = navigatorView
        if (animation == PageAnimation.None || animation == PageAnimation.Slide) {
            navigate()
            return
        }
        if (view == null || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            // Nothing to snapshot yet — navigate plainly rather than stalling.
            navigate()
            return
        }
        val shot = snapshot(view)
        if (shot == null) {
            navigate()
            return
        }
        animatedTurnCount++
        scope.launch {
            _turn.value = Turn(shot, forward, animation)
            progress.snapTo(0f)
            val duration = if (animation == PageAnimation.Fade) 190 else 420
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(duration, easing = FastOutSlowInEasing),
            )
            // The reference is dropped here and the bitmap is left to the garbage
            // collector rather than recycled eagerly. The old code called recycle()
            // in the same composition pass that cleared `turn`, so a frame that was
            // still drawing the sheet could touch freed pixels. These are one-off
            // screen-sized allocations; reclaiming them a GC cycle later costs
            // nothing real and removes the use-after-free entirely.
            _turn.value = null
        }
        navigate()
    }

    /**
     * Draws the navigator view into a bitmap, returning null when the result is
     * unusable. A hardware-accelerated WebView can hand back an empty surface, so the
     * pixels are sanity-checked before we trust the snapshot.
     */
    private fun snapshot(view: View): Bitmap? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            if (isBlank(bmp)) {
                bmp.recycle()
                null
            } else {
                bmp
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Detects a capture that came back empty.
     *
     * A hardware-accelerated WebView can hand back a surface with nothing drawn on
     * it, and animating that would flash a blank sheet over the page. This only
     * rejects genuinely empty surfaces — a fully transparent bitmap, or one with no
     * variation at all across the sampled grid. A real page with a flat background
     * but actual text on it is NOT rejected, because a failed capture is uniform
     * while a real page is not.
     */
    private fun isBlank(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 24).coerceAtLeast(1)
        val stepY = (bmp.height / 24).coerceAtLeast(1)
        val first = bmp.getPixel(0, 0)
        var sawOpaque = false
        var sawDifferent = false
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val px = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(px) != 0) sawOpaque = true
                if (px != first) sawDifferent = true
                x += stepX
            }
            y += stepY
        }
        // Nothing was drawn at all.
        if (!sawOpaque && !sawDifferent) return true
        // Perfectly flat surface: a capture that never happened.
        return !sawDifferent
    }
}

@Composable
fun rememberPageTurnCoordinator(): PageTurnCoordinator {
    val scope = rememberCoroutineScope()
    return remember { PageTurnCoordinator(scope) }
}

@Composable
fun PageTurnOverlay(
    coordinator: PageTurnCoordinator,
    pageBackground: Color,
    modifier: Modifier = Modifier,
) {
    val turn = coordinator.turn ?: return
    val density = LocalDensity.current.density
    val progress = coordinator.progress
    // Every per-frame quantity below is read INSIDE a graphicsLayer lambda, which is
    // a deferred read on the draw thread. Reading `progress.value` here instead made
    // the whole overlay recompose on each of the ~25 frames of a turn, which is
    // exactly the cost the deferred read exists to avoid.

    Box(modifier = modifier.fillMaxSize()) {
        when (turn.mode) {
            PageAnimation.Fade -> {
                Image(
                    bitmap = turn.shot.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 1f - progress.value },
                )
            }

            PageAnimation.PageTurn -> {
                val forward = turn.forward
                val origin = if (forward) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)
                val sign = if (forward) -1f else 1f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = progress.value
                            // The sheet swings through 180 degrees; past edge-on it
                            // is the blank back of the paper, and it dims as it goes.
                            transformOrigin = origin
                            rotationY = sign * p * 180f
                            cameraDistance = 14f * density
                            alpha = if (p <= 0.5f) 1f else (1f - (p - 0.5f) * 0.25f)
                        }
                        // The face swap is a draw-phase read, so turning the sheet
                        // over costs no recomposition.
                        .drawBehind {
                            val p = progress.value
                            if (p >= 0.5f) {
                                drawRect(pageBackground)
                            } else {
                                drawImage(turn.shot.asImageBitmap())
                            }
                        }
                )

                // A soft shadow hugging the fold sells the depth far better than the
                // rotation alone; it peaks at the halfway point and fades at both
                // ends. The old version computed this from a composition-level read
                // and picked its origin with an if/else whose two arms were
                // identical, while the gradient itself was a fixed black-at-both-
                // edges wash that never tracked the fold. It now darkens only on the
                // side the sheet is lifting from.
                val foldSide = if (forward) 0f else 1f
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = kotlin.math.sin(progress.value * Math.PI).toFloat() * 0.45f
                            transformOrigin = TransformOrigin(foldSide, 0.5f)
                        }
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                0f to if (forward) Color.Black.copy(alpha = 0.5f) else Color.Transparent,
                                0.30f to Color.Transparent,
                                0.70f to Color.Transparent,
                                1f to if (forward) Color.Transparent else Color.Black.copy(alpha = 0.5f),
                            )
                        )
                )
            }

            else -> Unit
        }
    }
}
