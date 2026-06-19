package com.securevault.app.ui.onboarding

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * ViewModel for LoginActivity (SCR-ONB-01).
 *
 * Spec refs:
 *   - Architecture.md §6 MVVM — ViewModel holds UI state, exposed via LiveData
 *   - Screens.md SCR-ONB-01 — Loading, Error, Success state variations
 *   - PRD F-AUTH-01 — AC#4 error state on failed/cancelled sign-in
 *
 * State machine: Idle → Loading → Success | Error → Idle
 */
class LoginViewModel : ViewModel() {

    private val _uiState = MutableLiveData<LoginUiState>(LoginUiState.Idle)
    val uiState: LiveData<LoginUiState> = _uiState

    fun setLoading() { _uiState.value = LoginUiState.Loading }
    fun setIdle() { _uiState.value = LoginUiState.Idle }
    fun setSuccess(registered: Boolean) { _uiState.value = LoginUiState.Success(registered) }
    fun setError(message: String) { _uiState.value = LoginUiState.Error(message) }
}

/** UI state sealed class for SCR-ONB-01 — Screens.md state variations */
sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class Success(val registered: Boolean) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

/**
 * Stub references to activities launched from LoginActivity.
 * Implemented in their respective tasks.
 */
// task-005: SecurityQuestionActivity
// task-006: PinUnlockActivity
