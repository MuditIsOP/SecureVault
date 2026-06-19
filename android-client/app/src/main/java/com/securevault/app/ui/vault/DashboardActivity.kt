package com.securevault.app.ui.vault

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.securevault.app.R
import kotlinx.coroutines.launch

/**
 * SCR-VLT-01 — Password Dashboard Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-VLT-01 — "Dashboard lists all active credentials"
 *   - PRD F-VAULT-01 AC#1 — "Search Bar, Password List, FAB, Bottom Navigation"
 *   - PRD F-VAULT-01 AC#2 — "Each list entry: Favicon/letter, Name, Username, Star"
 *   - PRD F-VAULT-01 AC#3 — "Tapping card → Password Details Screen"
 *   - PRD F-SRCH-01 AC#1 — "Typing in search bar filters list in real-time"
 *   - PRD F-SRCH-01 AC#3 — "Filtering updates display in <100ms"
 *   - SRS FR-VAULT-01 — "client SHALL display dashboard lists showing credentials"
 *   - SRS FR-VAULT-05b — "Starred favorites sorted to top of list views"
 *   - SRS FR-SRCH-01a — "client SHALL support real-time search filters"
 *   - SRS FR-SRCH-01b — "Local search updates SHALL execute in under 100ms"
 *   - Security_Requirements.md §1 — [MUST] FLAG_SECURE
 *   - Design.md §2.1 — background #141218, surface #1C1B1F, primary #D0BCFF
 *
 * Entry: After successful authentication (task-005 LoginActivity).
 * Exit: SCR-VLT-02 (Add Password via FAB), SCR-VLT-03 (Details via card tap),
 *       SCR-VLT-04 (Categories via BottomNav), SCR-SET-01 (Settings via BottomNav).
 *
 * States:
 *   - Loading: ProgressBar centered while fetching from Room
 *   - Content: RecyclerView with CredentialAdapter (favorites first)
 *   - Empty: "No passwords yet" with instructions
 *
 * Search (task-015):
 *   - DashboardViewModel handles 100ms debounce via Flow.debounce()
 *   - flatMapLatest cancels stale queries for responsive <100ms updates
 *   - Searches Name, Username/Email, Website URL fields — F-SRCH-01 AC#2
 */
class DashboardActivity : AppCompatActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    private lateinit var rvCredentials: RecyclerView
    private lateinit var progressLoading: ProgressBar
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var fabAddCredential: FloatingActionButton
    private lateinit var etSearch: TextInputEditText
    private lateinit var bottomNavigation: BottomNavigationView

    private lateinit var credentialAdapter: CredentialAdapter

    // Launcher for Add/Edit returning with result
    private val addEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AddEditCredentialActivity.RESULT_SAVED
            || result.resultCode == RESULT_OK) {
            viewModel.refresh() // Trigger ViewModel refresh
        }
    }

    // Launcher for Details screen (may delete)
    private val detailsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.refresh() // Trigger ViewModel refresh
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_dashboard)

        bindViews()
        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupSearch()
        setupBottomNavigation()
        observeViewModel()
    }

    // -------------------------------------------------------------------------
    // Toolbar — task-017: Health Dashboard access via menu
    // -------------------------------------------------------------------------

    private fun setupToolbar() {
        val toolbar: com.google.android.material.appbar.MaterialToolbar =
            findViewById(R.id.toolbar_dashboard)
        // PRD F-GEN-02 — Health Dashboard accessible from main screen
        toolbar.inflateMenu(R.menu.dashboard_toolbar_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_health -> {
                    val intent = Intent(this,
                        com.securevault.app.ui.generator.HealthDashboardActivity::class.java)
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh() // Reload on resume (e.g., returning from details)
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        rvCredentials = findViewById(R.id.rv_credentials)
        progressLoading = findViewById(R.id.progress_loading)
        layoutEmpty = findViewById(R.id.layout_empty)
        fabAddCredential = findViewById(R.id.fab_add_credential)
        etSearch = findViewById(R.id.et_search)
        bottomNavigation = findViewById(R.id.bottom_navigation)
    }

    // -------------------------------------------------------------------------
    // RecyclerView setup — PRD F-VAULT-01 AC#2, AC#3
    // -------------------------------------------------------------------------

    private fun setupRecyclerView() {
        credentialAdapter = CredentialAdapter { credential ->
            // PRD F-VAULT-01 AC#3 — "Tapping → Password Details Screen"
            val intent = Intent(this, CredentialDetailsActivity::class.java)
            intent.putExtra(CredentialDetailsActivity.EXTRA_CREDENTIAL_ID, credential.id)
            detailsLauncher.launch(intent)
        }

        rvCredentials.apply {
            layoutManager = LinearLayoutManager(this@DashboardActivity)
            adapter = credentialAdapter
        }
    }

    // -------------------------------------------------------------------------
    // FAB — PRD F-VAULT-01 AC#1 "Floating Action Button (FAB) to add passwords"
    // -------------------------------------------------------------------------

    private fun setupFab() {
        fabAddCredential.setOnClickListener {
            val intent = Intent(this, AddEditCredentialActivity::class.java)
            addEditLauncher.launch(intent)
        }
    }

    // -------------------------------------------------------------------------
    // Search — PRD F-SRCH-01 AC#1 "real-time filter", task-015
    // Debounce handled by DashboardViewModel (100ms Flow.debounce)
    // -------------------------------------------------------------------------

    private fun setupSearch() {
        etSearch.addTextChangedListener { text ->
            val query = text?.toString()?.trim() ?: ""
            // Forward to ViewModel — debounce + DB query happens there
            viewModel.onSearchQueryChanged(query)
        }
    }

    // -------------------------------------------------------------------------
    // ViewModel observation — task-015 state-driven UI
    // -------------------------------------------------------------------------

    /**
     * Observes DashboardViewModel.dashboardState and renders UI accordingly.
     * Uses repeatOnLifecycle(STARTED) for lifecycle-aware collection.
     *
     * States map to SCR-VLT-01:
     *   Loading → ProgressBar visible
     *   Content → RecyclerView visible with list
     *   Empty   → Empty state layout visible
     *   Error   → Toast error message
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dashboardState.collect { state ->
                    when (state) {
                        is DashboardViewModel.DashboardState.Loading -> {
                            progressLoading.visibility = View.VISIBLE
                            rvCredentials.visibility = View.GONE
                            layoutEmpty.visibility = View.GONE
                        }
                        is DashboardViewModel.DashboardState.Content -> {
                            progressLoading.visibility = View.GONE
                            rvCredentials.visibility = View.VISIBLE
                            layoutEmpty.visibility = View.GONE
                            credentialAdapter.submitList(state.credentials)
                        }
                        is DashboardViewModel.DashboardState.Empty -> {
                            progressLoading.visibility = View.GONE
                            rvCredentials.visibility = View.GONE
                            layoutEmpty.visibility = View.VISIBLE
                            credentialAdapter.submitList(emptyList())
                        }
                        is DashboardViewModel.DashboardState.Error -> {
                            progressLoading.visibility = View.GONE
                            rvCredentials.visibility = View.GONE
                            layoutEmpty.visibility = View.VISIBLE
                            Toast.makeText(this@DashboardActivity,
                                state.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bottom Navigation — SCR-VLT-01 "Bottom Navigation bar"
    // -------------------------------------------------------------------------

    private fun setupBottomNavigation() {
        bottomNavigation.selectedItemId = R.id.nav_dashboard

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    // Already on dashboard
                    true
                }
                R.id.nav_categories -> {
                    val intent = Intent(this, CategoriesActivity::class.java)
                    startActivity(intent)
                    true
                }
                R.id.nav_generator -> {
                    // PRD F-GEN-01 AC#1 — "accessible via Bottom Navigation Tab 3"
                    val intent = Intent(this, com.securevault.app.ui.generator.GeneratorActivity::class.java)
                    startActivity(intent)
                    true
                }
                R.id.nav_settings -> {
                    // TODO: task-017/task-018 — Navigate to SettingsActivity
                    Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
    }
}
