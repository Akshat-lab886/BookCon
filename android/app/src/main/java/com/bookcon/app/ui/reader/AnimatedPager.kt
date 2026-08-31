package com.bookcon.app.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * Animation modes selectable from Settings → Reader defaults → Page turn animation.
 * - [None]     : instant jump, no transition
 * - [Slide]    : Material default (HorizontalPager's built-in horizontal translate)
 * - [Fade]     : cross-fade with scale + alpha
 * - [PageTurn] : 3D Y-axis rotation that mimics turning a paper page
 */
enum class PageAnimation(val id: String) {
    None("none"),
    Slide("slide"),
    Fade("fade"),
    PageTurn("page_turn");

    companion object {
        fun fromId(id: String?): PageAnimation = entries.firstOrNull { it.id == id } ?: Slide
    }
}

/**
 * Wrapper around [HorizontalPager] that applies a [PageAnimation] to the children.
 *
 * - [None]: no transform, raw pager (default slide happens because the pager still
 *   snaps; we'd want no animation at all but Compose's HorizontalPager always
 *   animates the scroll, so we rely on the child to render itself at the new page
 *   immediately. With [None] we don't apply any modifier.)
 * - [Slide]: identity — pager's built-in slide.
 * - [Fade]: outgoing page fades + scales down, incoming page fades + scales up.
 * - [PageTurn]: outgoing page rotates around its left edge (when leaving to the
 *   left) or right edge (when leaving to the right). The incoming page is
 *   stationary behind.
 *
 * Note: the page-turn effect uses [PagerState.currentPageOffsetFraction] which
 * Compose updates during fling/animate. To make the flip feel like a real paper
 * page rather than a full 180° rotation, we cap the rotation at ±90° and pin the
 * origin to the inner edge.
 */
@Composable
fun AnimatedPager(
    state: PagerState,
    animation: PageAnimation,
    modifier: Modifier = Modifier,
    pageContent: @Composable (page: Int) -> Unit,
) {
    when (animation) {
        PageAnimation.None, PageAnimation.Slide -> {
            HorizontalPager(state = state, modifier = modifier) { page ->
                pageContent(page)
            }
        }
        PageAnimation.Fade -> {
            HorizontalPager(state = state, modifier = modifier) { page ->
                val pageOffset = (state.currentPage - page) + state.currentPageOffsetFraction
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress = abs(pageOffset).coerceIn(0f, 1f)
                            alpha = lerp(1f, 0f, progress)
                            scaleX = lerp(1f, 0.92f, progress)
                            scaleY = lerp(1f, 0.92f, progress)
                        },
                ) {
                    pageContent(page)
                }
            }
        }
        PageAnimation.PageTurn -> {
            val density = LocalDensity.current
            HorizontalPager(state = state, modifier = modifier) { page ->
                val pageOffset = (state.currentPage - page) + state.currentPageOffsetFraction
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Origin: pin to the edge we're turning from so the
                            // page pivots like a real piece of paper.
                            val direction = sign(pageOffset).let { if (it == 0f) 1f else it }
                            transformOrigin = if (direction > 0f) {
                                androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                            } else {
                                androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                            }
                            // Rotation: cap at ±90° so the back side never shows
                            // through (we keep the page visible during the flip).
                            rotationY = (pageOffset * 90f).coerceIn(-90f, 90f)
                            // Pull the camera back so the perspective feels natural.
                            cameraDistance = 24f * density.density
                            // The "leaving" page (offset between 0 and 1 going right,
                            // or 0 and -1 going left) fades slightly so the next
                            // page peeks through; the "incoming" page stays full.
                            val progress = abs(pageOffset).coerceIn(0f, 1f)
                            alpha = lerp(1f, 0.7f, progress)
                        },
                ) {
                    pageContent(page)
                }
            }
        }
    }
}
