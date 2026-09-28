package com.bookcon.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Runs [onResume] every time the screen becomes visible again.
 *
 * Screens behind `hiltViewModel()` are scoped to their NavBackStackEntry, so the
 * ViewModel (and its `init { refresh() }`) outlives any back-and-forth navigation.
 * The result was a screen that silently showed stale data on return: stats that did
 * not include the minutes just read, and a vocabulary list that did not include a
 * word saved a moment earlier.
 *
 * This is deliberately lifecycle-driven rather than LaunchedEffect(Unit), which
 * would only fire on first composition.
 */
@Composable
fun RefreshOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
