package com.securevault.app.ui.vault

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.securevault.app.R
import com.securevault.app.data.dao.VaultDao
import com.securevault.app.data.entities.PasswordEntity
import com.securevault.app.security.CryptographyHelper
import com.securevault.app.security.KeystoreManager
import com.securevault.app.security.SecureClipboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKey

/**
 * SCR-VLT-03 — Password Details Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-VLT-03 — Renders credential details, copy/reveal operations
 *   - PRD F-VAULT-02 AC#3 — "Details Screen must display: Website Favicon, Entry Name,
 *     Username, decrypted Password (hidden by default), Website URL, Created Date, Updated Date"
 *   - PRD F-VAULT-02 AC#5 — "Deleting must write deletedDate timestamp and move to Trash"
 *   - PRD F-VAULT-04 — "Copy action with 30s clipboard auto-clear"
 *   - PRD F-VAULT-03 — "Password History list — last 3 passwords (hidden by default)"
 *   - Security_Requirements.md §1 STRIDE Info Leak:
 *     [MUST] Clear clipboard after 30 seconds
 *     [MUST] Enforce FLAG_SECURE to block screenshots
 *   - Security_Requirements.md §9.1 — memory sanitisation
 *   - Design.md §2.1 — background #141218, surface #1C1B1F
 *
 * Entry: Selection of password card on SCR-VLT-01.
 * Exit: SCR-VLT-01 (Dashboard), SCR-VLT-02 (Edit Password).
 */
class CredentialDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CREDENTIAL_ID = "extra_credential_id"
    }

    private lateinit var tvEntryName: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvPassword: TextView
    private lateinit var tvWebsiteUrl: TextView
    private lateinit var tvCategory: TextView
    private lateinit var tvCreatedDate: TextView
    private lateinit var tvUpdatedDate: TextView
    private lateinit var btnRevealPassword: ImageButton
    private lateinit var btnCopyPassword: ImageButton
    private lateinit var btnCopyUsername: ImageButton
    private lateinit var btnCopyWebsite: ImageButton
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnEdit: MaterialButton
    private lateinit var btnDelete: MaterialButton
    private lateinit var cardPasswordHistory: MaterialCardView
    private lateinit var llHistoryEntries: LinearLayout
    private lateinit var progressLoading: ProgressBar

    private var credentialId: String = ""
    private var currentCredential: PasswordEntity? = null
    private var decryptedPassword: String? = null
    private var isPasswordRevealed = false

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AddEditCredentialActivity.RESULT_SAVED) {
            loadCredential() // Refresh after edit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1 (ST-STRIDE-03)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_credential_details)

        credentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID) ?: ""
        if (credentialId.isEmpty()) {
            finish()
            return
        }

        bindViews()
        setupActions()
        loadCredential()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Security_Requirements.md §9.1 — clear decrypted password from memory
        decryptedPassword = null
        // Cancel any pending clipboard clear timer
        SecureClipboardManager.cancelPendingClear()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        tvEntryName = findViewById(R.id.tv_entry_name)
        tvUsername = findViewById(R.id.tv_username)
        tvPassword = findViewById(R.id.tv_password)
        tvWebsiteUrl = findViewById(R.id.tv_website_url)
        tvCategory = findViewById(R.id.tv_category)
        tvCreatedDate = findViewById(R.id.tv_created_date)
        tvUpdatedDate = findViewById(R.id.tv_updated_date)
        btnRevealPassword = findViewById(R.id.btn_reveal_password)
        btnCopyPassword = findViewById(R.id.btn_copy_password)
        btnCopyUsername = findViewById(R.id.btn_copy_username)
        btnCopyWebsite = findViewById(R.id.btn_copy_website)
        btnFavorite = findViewById(R.id.btn_favorite)
        btnEdit = findViewById(R.id.btn_edit)
        btnDelete = findViewById(R.id.btn_delete)
        cardPasswordHistory = findViewById(R.id.card_password_history)
        llHistoryEntries = findViewById(R.id.ll_history_entries)
        progressLoading = findViewById(R.id.progress_loading)

        // Back button
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
    }

    // -------------------------------------------------------------------------
    // Load credential — SCR-VLT-03 content inventory
    // -------------------------------------------------------------------------

    private fun loadCredential() {
        progressLoading.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val dao = getVaultDao()
                val userId = getCurrentUserId()

                val credential = withContext(Dispatchers.IO) {
                    dao.getCredentialById(credentialId, userId)
                }

                if (credential == null) {
                    Toast.makeText(this@CredentialDetailsActivity,
                        "Credential not found.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                currentCredential = credential

                // Update last_viewed timestamp
                withContext(Dispatchers.IO) {
                    dao.updateLastViewed(credentialId)
                }

                // Populate UI — PRD F-VAULT-02 AC#3
                tvEntryName.text = credential.name
                tvUsername.text = credential.usernameEmail
                tvPassword.text = "••••••••" // Hidden by default — SCR-VLT-03
                tvWebsiteUrl.text = credential.websiteUrl ?: "—"
                // Also set the copy-section URL
                findViewById<TextView>(R.id.tv_website_url_copy).text = credential.websiteUrl ?: "—"
                tvCreatedDate.text = formatDate(credential.createdAt)
                tvUpdatedDate.text = formatDate(credential.updatedAt)

                // Load website favicon
                val domain = CredentialAdapter.extractDomain(credential.websiteUrl)
                if (domain != null) {
                    val ivFavicon = findViewById<android.widget.ImageView>(R.id.iv_favicon)
                    coil.ImageLoader(this@CredentialDetailsActivity).enqueue(
                        coil.request.ImageRequest.Builder(this@CredentialDetailsActivity)
                            .data("https://www.google.com/s2/favicons?domain=$domain&sz=128")
                            .target(ivFavicon)
                            .crossfade(true)
                            .build()
                    )
                }

                // Load category name
                val categoryName = withContext(Dispatchers.IO) {
                    if (credential.categoryId != null) {
                        val categoryDao = getCategoryDao()
                        categoryDao.getCategoryById(credential.categoryId)?.name
                    } else null
                }
                tvCategory.text = categoryName ?: "Uncategorized"

                // Favorite state
                updateFavoriteIcon(credential.favorite == 1)

                // Load password history — SCR-VLT-03 "Password History list"
                loadPasswordHistory()

            } catch (e: Exception) {
                Toast.makeText(this@CredentialDetailsActivity,
                    "Failed to load credential.", Toast.LENGTH_SHORT).show()
            } finally {
                progressLoading.visibility = View.GONE
            }
        }
    }

    // -------------------------------------------------------------------------
    // Password History — SCR-VLT-03, PRD F-VAULT-03
    // -------------------------------------------------------------------------

    /**
     * Loads and displays the last 3 password history entries.
     * PRD F-VAULT-03 AC#2 — "displayed on Details Screen, hidden by default."
     * PRD F-VAULT-03 AC#3 — "Tapping Eye Icon next to a history entry
     *   must decrypt and display it in plaintext."
     * SRS FR-VAULT-03b — "max 3 entries per credential."
     */
    private fun loadPasswordHistory() {
        CoroutineScope(Dispatchers.Main).launch {
            val historyDao = getHistoryDao()
            val history = withContext(Dispatchers.IO) {
                historyDao.getLastThree(credentialId)
            }

            if (history.isNotEmpty()) {
                cardPasswordHistory.visibility = View.VISIBLE
                llHistoryEntries.removeAllViews()

                history.forEach { entry ->
                    // Each history item: date label + masked password + Eye toggle
                    val entryRow = android.widget.LinearLayout(this@CredentialDetailsActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, 8, 0, 8)
                    }

                    val tvDate = TextView(this@CredentialDetailsActivity).apply {
                        text = "Changed ${formatDate(entry.createdAt)}"
                        setTextColor(resources.getColor(R.color.color_secondary, theme))
                        textSize = 12f
                    }

                    // Password text — hidden by default (F-VAULT-03 AC#2)
                    val tvHistoryPassword = TextView(this@CredentialDetailsActivity).apply {
                        text = " — ••••••"
                        setTextColor(resources.getColor(R.color.color_on_surface, theme))
                        textSize = 14f
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }

                    // Eye Icon toggle — F-VAULT-03 AC#3
                    val btnRevealHistory = ImageButton(this@CredentialDetailsActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_view)
                        setBackgroundResource(android.R.attr.selectableItemBackgroundBorderless)
                        contentDescription = "Reveal history password"
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            dpToPx(36), dpToPx(36)
                        )
                    }

                    var isHistoryRevealed = false

                    btnRevealHistory.setOnClickListener {
                        if (isHistoryRevealed) {
                            tvHistoryPassword.text = " — ••••••"
                            isHistoryRevealed = false
                        } else {
                            try {
                                val vmkKey = KeystoreManager.getOrCreateVmkKey()
                                    ?: throw IllegalStateException("VMK key not available")
                                val decrypted = CryptographyHelper.decrypt(
                                    entry.encryptedPassword, vmkKey
                                )
                                tvHistoryPassword.text = " — $decrypted"
                                isHistoryRevealed = true
                                // Security_Requirements.md §9.1 — clear on next toggle
                            } catch (e: Exception) {
                                tvHistoryPassword.text = " — [Decryption error]"
                            }
                        }
                    }

                    entryRow.addView(tvDate)
                    entryRow.addView(tvHistoryPassword)
                    entryRow.addView(btnRevealHistory)
                    llHistoryEntries.addView(entryRow)
                }
            }
        }
    }

    /**
     * Converts dp to pixels for layout params.
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // -------------------------------------------------------------------------
    // Actions — SCR-VLT-03 available actions
    // -------------------------------------------------------------------------

    private fun setupActions() {
        // Reveal Password — SCR-VLT-03 "Tap Eye Icon"
        btnRevealPassword.setOnClickListener {
            togglePasswordVisibility()
        }

        // Copy Password — F-VAULT-04 AC#1 "Copy Password", AC#2, AC#3
        // Delegates to SecureClipboardManager for 30s auto-clear
        btnCopyPassword.setOnClickListener {
            copyPasswordToClipboard()
        }

        // Copy Username — F-VAULT-04 AC#1 "Copy Username"
        btnCopyUsername.setOnClickListener {
            val username = currentCredential?.usernameEmail ?: return@setOnClickListener
            SecureClipboardManager.copyUsername(this, username)
        }

        // Copy Website — F-VAULT-04 AC#1 "Copy Website"
        btnCopyWebsite.setOnClickListener {
            val website = currentCredential?.websiteUrl
            if (website.isNullOrEmpty()) {
                Toast.makeText(this, "No website URL to copy.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SecureClipboardManager.copyWebsite(this, website)
        }

        // Favorite toggle
        btnFavorite.setOnClickListener {
            toggleFavorite()
        }

        // Edit — navigate to SCR-VLT-02
        btnEdit.setOnClickListener {
            val intent = Intent(this, AddEditCredentialActivity::class.java)
            intent.putExtra(AddEditCredentialActivity.EXTRA_CREDENTIAL_ID, credentialId)
            editLauncher.launch(intent)
        }

        // Soft Delete — PRD F-VAULT-02 AC#5, SCR-VLT-03 "Tap Delete Icon"
        btnDelete.setOnClickListener {
            confirmSoftDelete()
        }
    }

    // -------------------------------------------------------------------------
    // Reveal/Hide password — SCR-VLT-03 "Decrypts and shows password in <100ms"
    // -------------------------------------------------------------------------

    private fun togglePasswordVisibility() {
        if (isPasswordRevealed) {
            tvPassword.text = "••••••••"
            isPasswordRevealed = false
            // Security_Requirements.md §9.1 — clear decrypted from memory
            decryptedPassword = null
            return
        }

        val credential = currentCredential ?: return

        try {
            val vmkKey = KeystoreManager.getOrCreateVmkKey()
                ?: throw IllegalStateException("VMK key not available")

            decryptedPassword = CryptographyHelper.decrypt(
                credential.encryptedPassword, vmkKey
            )
            tvPassword.text = decryptedPassword
            isPasswordRevealed = true

        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            // VMK auth window expired — re-prompt for PIN/biometric
            promptReAuthentication()
        } catch (e: javax.crypto.AEADBadTagException) {
            // Password was encrypted with a different key — unrecoverable
            AlertDialog.Builder(this)
                .setTitle("Password Unreadable")
                .setMessage("This password was encrypted with a previous key and cannot be decrypted. You'll need to re-enter it.")
                .setPositiveButton("Edit") { _, _ ->
                    val intent = Intent(this, AddEditCredentialActivity::class.java)
                    intent.putExtra(AddEditCredentialActivity.EXTRA_CREDENTIAL_ID, credentialId)
                    editLauncher.launch(intent)
                }
                .setNegativeButton("OK", null)
                .show()
        } catch (e: Exception) {
            // Other decryption error
            AlertDialog.Builder(this)
                .setTitle("Decryption Error")
                .setMessage("Could not decrypt: ${e.javaClass.simpleName}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // -------------------------------------------------------------------------
    // Copy password — PRD F-VAULT-04 AC#2/AC#3, Security_Requirements.md §1
    // Uses SecureClipboardManager (task-013) for 30s auto-clear + service
    // -------------------------------------------------------------------------

    private fun copyPasswordToClipboard() {
        val credential = currentCredential ?: return

        try {
            val vmkKey = KeystoreManager.getOrCreateVmkKey()
                ?: throw IllegalStateException("VMK key not available")

            val password = CryptographyHelper.decrypt(
                credential.encryptedPassword, vmkKey
            )

            // Delegate to SecureClipboardManager — handles clipboard write,
            // 30s auto-clear via Handler, and ClipboardClearService safety net
            // PRD F-VAULT-04 AC#2, AC#3
            // SRS FR-VAULT-04a, FR-VAULT-04b
            SecureClipboardManager.copyPassword(this, password)

            // Security_Requirements.md §9.1 — password variable is GC-eligible
            // after scope exit (Kotlin String immutability; best-effort)

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to copy password.", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // Favorite toggle — PRD F-VAULT-05
    // -------------------------------------------------------------------------

    private fun toggleFavorite() {
        val credential = currentCredential ?: return
        val newFavorite = if (credential.favorite == 1) 0 else 1

        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                getVaultDao().updateFavorite(credentialId, newFavorite)
            }
            currentCredential = credential.copy(favorite = newFavorite)
            updateFavoriteIcon(newFavorite == 1)
        }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        btnFavorite.isSelected = isFavorite
    }

    // -------------------------------------------------------------------------
    // Soft Delete — PRD F-VAULT-02 AC#5, SRS FR-VAULT-07
    // -------------------------------------------------------------------------

    private fun confirmSoftDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Credential")
            .setMessage("Move this credential to Trash? It will be permanently deleted after 30 days.")
            .setPositiveButton("Delete") { _, _ -> executeSoftDelete() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeSoftDelete() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val userId = getCurrentUserId()
                withContext(Dispatchers.IO) {
                    getVaultDao().softDelete(credentialId, userId)
                }
                Toast.makeText(this@CredentialDetailsActivity,
                    "Credential moved to Trash.", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@CredentialDetailsActivity,
                    "Failed to delete credential.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun getVaultDao(): VaultDao {
        return com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext).vaultDao()
    }

    private fun getCategoryDao(): com.securevault.app.data.dao.CategoryDao {
        return com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext).categoryDao()
    }

    private fun getHistoryDao(): com.securevault.app.data.dao.HistoryDao {
        return com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext).historyDao()
    }

    private suspend fun getCurrentUserId(): String {
        return withContext(Dispatchers.IO) {
            val db = com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext)
            val cursor = db.openHelper.readableDatabase
                .query("SELECT id FROM users LIMIT 1")
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
        }
    }

    /**
     * Redirects to PIN unlock to re-authenticate when VMK auth window has expired.
     */
    private fun promptReAuthentication() {
        Toast.makeText(this, "Session expired. Please re-authenticate.", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, com.securevault.app.ui.auth.PinUnlockActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
