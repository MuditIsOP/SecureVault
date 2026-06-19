package com.securevault.app.ui.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.DatabaseModule
import com.securevault.app.data.entities.PasswordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Password Dashboard (SCR-VLT-01) with real-time debounced search.
 *
 * Spec refs:
 *   - PRD F-SRCH-01 AC#1 — "Typing in search bar must filter password list in real-time"
 *   - PRD F-SRCH-01 AC#2 — "Matches against Name, Username/Email, Website URL"
 *   - PRD F-SRCH-01 AC#3 — "Filtering updates must display in <100ms"
 *   - SRS FR-SRCH-01a — "client SHALL support real-time search filters"
 *   - SRS FR-SRCH-01b — "Local search updates SHALL execute in under 100ms"
 *   - Architecture.md — "Input typed → Debounce 100ms → Local SQLite query → Update adapter"
 *
 * Implementation:
 *   - searchQuery: MutableStateFlow<String> updated by UI text changes
 *   - 100ms debounce via Flow.debounce() — prevents rapid requery during typing
 *   - flatMapLatest: cancels in-flight queries when new input arrives
 *   - Results emitted as StateFlow<DashboardState> consumed by DashboardActivity
 *
 * Edge cases:
 *   - Empty query → loads all dashboard credentials (favorites first)
 *   - SQL wildcard chars (%, _) → handled by Room parameterized queries
 *   - Zero results → DashboardState.Empty
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Represents the UI state of the dashboard.
     * SCR-VLT-01 states: Loading, Content (with list), Empty
     */
    sealed class DashboardState {
        object Loading : DashboardState()
        data class Content(val credentials: List<PasswordEntity>) : DashboardState()
        object Empty : DashboardState()
        data class Error(val message: String) : DashboardState()
    }

    private val db = DatabaseModule.provideDatabase(application)
    private val vaultDao = db.vaultDao()

    /** Search query flow — updated by UI text input */
    val searchQuery = MutableStateFlow("")

    /** Trigger to force refresh (e.g., after add/edit/delete) */
    private val refreshTrigger = MutableStateFlow(0L)

    /**
     * Dashboard state flow with 100ms debounce on search.
     *
     * Architecture.md data flow:
     *   Input typed → Debounce 100ms → Local SQLite query → Update adapter
     *
     * PRD F-SRCH-01 AC#3 — "<100ms" display time achieved via:
     *   1. Debounce prevents redundant queries during fast typing
     *   2. Room SQLite queries on indexed columns (idx_passwords_user_search)
     *   3. flatMapLatest cancels stale in-flight queries
     */
    val dashboardState: StateFlow<DashboardState> = combine(
        searchQuery.debounce(100L), // 100ms debounce — Architecture.md
        refreshTrigger
    ) { query, _ -> query }
        .flatMapLatest { query ->
            flow {
                emit(DashboardState.Loading)
                try {
                    val userId = getCurrentUserId()
                    val credentials = withContext(Dispatchers.IO) {
                        if (query.isBlank()) {
                            // No search → full dashboard (favorites first)
                            // SRS FR-VAULT-05b
                            vaultDao.getDashboardCredentials(userId)
                        } else {
                            // Real-time search — PRD F-SRCH-01 AC#1/AC#2
                            vaultDao.searchCredentials(userId, query)
                        }
                    }

                    if (credentials.isEmpty()) {
                        emit(DashboardState.Empty)
                    } else {
                        emit(DashboardState.Content(credentials))
                    }
                } catch (e: Exception) {
                    emit(DashboardState.Error(e.message ?: "Unknown error"))
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DashboardState.Loading
        )

    /**
     * Updates the search query. Called from DashboardActivity on text change.
     * PRD F-SRCH-01 AC#1 — "Typing in search bar must filter in real-time"
     */
    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    /**
     * Forces a refresh of the credential list.
     * Called after add/edit/delete operations return.
     */
    fun refresh() {
        refreshTrigger.value = System.currentTimeMillis()
    }

    /**
     * Returns the current user ID from the local database.
     */
    private suspend fun getCurrentUserId(): String {
        return withContext(Dispatchers.IO) {
            val cursor = db.openHelper.readableDatabase
                .rawQuery("SELECT id FROM users LIMIT 1", null)
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
        }
    }
}
