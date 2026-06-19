package com.securevault.app.ui.generator

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.securevault.app.R
import com.securevault.app.data.DatabaseModule
import com.securevault.app.data.entities.PasswordEntity
import com.securevault.app.security.VaultHealthAuditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SCR-GEN-02 — Password Health Dashboard.
 *
 * Spec refs:
 *   - PRD F-GEN-02 AC#1 — "display total counts for: Weak, Medium, Strong,
 *     Reused Passwords, and Total Entries"
 *   - PRD F-GEN-02 AC#2 — "clear security recommendations: 'Weak passwords detected',
 *     'Duplicate passwords detected', with list of offending entries"
 *   - SRS FR-GEN-02a — "client SHALL audit password complexity"
 *   - SRS FR-GEN-02b — "client SHALL alert users to password reuse"
 *   - Security_Requirements.md §1 — [MUST] FLAG_SECURE
 *   - Design.md — error #F2B8B5, warning #E3A857, success #81C784
 *
 * States:
 *   - Loading: ProgressBar while scanning vault
 *   - Content: Stats cards + recommendations
 *   - Empty: No credentials to analyze
 */
class HealthDashboardActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var progressHealth: ProgressBar
    private lateinit var layoutContent: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var tvTotalEntries: TextView
    private lateinit var tvWeakCount: TextView
    private lateinit var tvMediumCount: TextView
    private lateinit var tvStrongCount: TextView
    private lateinit var tvReusedCount: TextView
    private lateinit var tvRecommendationsHeader: TextView
    private lateinit var layoutRecommendations: LinearLayout
    private lateinit var cardAllGood: MaterialCardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_health_dashboard)

        bindViews()
        setupToolbar()
        runHealthAudit()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar_health)
        progressHealth = findViewById(R.id.progress_health)
        layoutContent = findViewById(R.id.layout_health_content)
        layoutEmpty = findViewById(R.id.layout_empty)
        tvTotalEntries = findViewById(R.id.tv_total_entries)
        tvWeakCount = findViewById(R.id.tv_weak_count)
        tvMediumCount = findViewById(R.id.tv_medium_count)
        tvStrongCount = findViewById(R.id.tv_strong_count)
        tvReusedCount = findViewById(R.id.tv_reused_count)
        tvRecommendationsHeader = findViewById(R.id.tv_recommendations_header)
        layoutRecommendations = findViewById(R.id.layout_recommendations)
        cardAllGood = findViewById(R.id.card_all_good)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }
    }

    // -------------------------------------------------------------------------
    // Health Audit — SRS FR-GEN-02a, FR-GEN-02b
    // -------------------------------------------------------------------------

    private fun runHealthAudit() {
        progressHealth.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE
        layoutEmpty.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val db = DatabaseModule.provideDatabase(applicationContext)
                val userId = withContext(Dispatchers.IO) {
                    val cursor = db.openHelper.readableDatabase
                        .query("SELECT id FROM users LIMIT 1")
                    cursor.use {
                        if (it.moveToFirst()) it.getString(0) else ""
                    }
                }

                val credentials = withContext(Dispatchers.IO) {
                    db.vaultDao().getActiveCredentials(userId)
                }

                if (credentials.isEmpty()) {
                    // Empty state
                    progressHealth.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                    return@launch
                }

                // Run audit on background thread — task-017 tech consideration
                val report = withContext(Dispatchers.IO) {
                    VaultHealthAuditor.audit(credentials)
                }

                // Display results
                displayReport(report)

            } catch (e: Exception) {
                Toast.makeText(this@HealthDashboardActivity,
                    "Failed to run health audit.", Toast.LENGTH_SHORT).show()
            } finally {
                progressHealth.visibility = View.GONE
            }
        }
    }

    // -------------------------------------------------------------------------
    // Display Report — PRD F-GEN-02 AC#1, AC#2
    // -------------------------------------------------------------------------

    private fun displayReport(report: VaultHealthAuditor.HealthReport) {
        layoutContent.visibility = View.VISIBLE

        // PRD F-GEN-02 AC#1 — total counts
        tvTotalEntries.text = report.totalEntries.toString()
        tvWeakCount.text = report.weakCount.toString()
        tvMediumCount.text = report.mediumCount.toString()
        tvStrongCount.text = report.strongCount.toString()
        tvReusedCount.text = report.reusedCount.toString()

        // PRD F-GEN-02 AC#2 — recommendations
        layoutRecommendations.removeAllViews()

        if (report.recommendations.isEmpty()) {
            // All good!
            cardAllGood.visibility = View.VISIBLE
            tvRecommendationsHeader.visibility = View.GONE
        } else {
            cardAllGood.visibility = View.GONE
            tvRecommendationsHeader.visibility = View.VISIBLE

            for (recommendation in report.recommendations) {
                addRecommendationCard(recommendation)
            }
        }
    }

    /**
     * Adds a recommendation card to the layout.
     * PRD F-GEN-02 AC#2 — "clear security recommendations with list of offending entries"
     */
    private fun addRecommendationCard(recommendation: VaultHealthAuditor.Recommendation) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dpToPx()
            }
            radius = 12f.dpToPxF()
            setCardBackgroundColor(getColor(R.color.color_surface))
            strokeColor = when (recommendation.type) {
                VaultHealthAuditor.RecommendationType.WEAK_PASSWORD ->
                    Color.parseColor("#F2B8B5") // Design.md error
                VaultHealthAuditor.RecommendationType.DUPLICATE_PASSWORD ->
                    Color.parseColor("#E3A857") // Design.md warning
            }
            strokeWidth = 1.dpToPx()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
        }

        // Type label
        val typeIcon = when (recommendation.type) {
            VaultHealthAuditor.RecommendationType.WEAK_PASSWORD -> "⚠️ Weak Passwords"
            VaultHealthAuditor.RecommendationType.DUPLICATE_PASSWORD -> "🔁 Duplicate Passwords"
        }
        val tvType = TextView(this).apply {
            text = typeIcon
            textSize = 15f
            setTextColor(getColor(R.color.color_on_surface))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(tvType)

        // Message
        val tvMessage = TextView(this).apply {
            text = recommendation.message
            textSize = 13f
            setTextColor(getColor(R.color.color_secondary))
            setPadding(0, 4.dpToPx(), 0, 8.dpToPx())
        }
        container.addView(tvMessage)

        // Offending entries list — PRD F-GEN-02 AC#2 "list of the offending entries"
        for (entry in recommendation.affectedEntries) {
            val tvEntry = TextView(this).apply {
                text = "• ${entry.name} (${entry.usernameEmail})"
                textSize = 13f
                setTextColor(getColor(R.color.color_on_surface_variant))
                setPadding(8.dpToPx(), 2.dpToPx(), 0, 2.dpToPx())
            }
            container.addView(tvEntry)
        }

        card.addView(container)
        layoutRecommendations.addView(card)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Float.dpToPxF(): Float = this * resources.displayMetrics.density
}
