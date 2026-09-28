package com.bookcon.app.di

import android.content.Context
import com.bookcon.app.data.repo.AuthRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Gives the nav graph access to [AuthRepository] without constructing a ViewModel
 * for it.
 *
 * Sign-out is a navigation-graph concern, not a screen concern: clearing the
 * session is what actually swaps between the auth and library graphs. Reaching for
 * the repository directly keeps that in one place instead of forcing every caller
 * to own a ViewModel scoped to a destination it does not render.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionEntryPoint {
    fun authRepository(): AuthRepository
}

fun Context.authRepository(): AuthRepository =
    EntryPointAccessors.fromApplication(applicationContext, SessionEntryPoint::class.java)
        .authRepository()
