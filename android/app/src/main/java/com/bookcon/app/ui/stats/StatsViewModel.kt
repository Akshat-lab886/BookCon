package com.bookcon.app.ui.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.ReadingTracker
import com.bookcon.app.core.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class StatsUiState(
    val todayMinutes: Int = 0,
    val goalMinutes: Int = 0,
    val streak: Int = 0,
    val last30: List<ReadingTracker.DailyStat> = emptyList(),
    /** (title, minutes) for the last 7 days, highest first. */
    val weekBooks: List<Pair<String, Int>> = emptyList(),
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    @ApplicationContext appContext: Context,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val tracker = ReadingTracker(appContext)
    private val bookTitles: MutableMap<String, String> = mutableMapOf()

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val goal = settingsRepo.settings.value.statsGoalMinutes
            val today = tracker.today()
            val last30 = tracker.last(30)
            val streak = tracker.streak()

            // Aggregate the trailing 7 days per book; titles resolved lazily from
            // the library database when available.
            val byBook = HashMap<String, Int>()
            for (offset in 0 until 7) {
                val key = LocalDate.now().minusDays(offset.toLong()).toString()
                tracker.booksFor(key).forEach { byBook[it.bookId] = (byBook[it.bookId] ?: 0) + it.minutes }
            }
            val weekBooks = byBook.entries
                .sortedByDescending { it.value }
                .map { (resolveTitle(it.key)) to it.value }

            _state.update {
                it.copy(
                    todayMinutes = today.second,
                    goalMinutes = goal,
                    streak = streak,
                    last30 = last30,
                    weekBooks = weekBooks,
                )
            }
        }
    }

    fun setGoal(minutes: Int) {
        val clamped = minutes.coerceIn(0, 180)
        _state.update { it.copy(goalMinutes = clamped) }
        viewModelScope.launch { settingsRepo.setStatsGoalMinutes(clamped) }
    }

    /**
     * Book titles come from whatever source is handy: callers holding a title map
     * can push it here so [refresh] renders friendly names instead of raw ids.
     */
    fun supplyTitles(titlesById: Map<String, String>) {
        bookTitles.putAll(titlesById)
        refresh()
    }

    private fun resolveTitle(bookId: String): String =
        bookTitles[bookId] ?: bookId.take(12) + "…"
}
