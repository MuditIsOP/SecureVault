package com.securevault.app.ui.vault

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.securevault.app.R
import com.securevault.app.data.dao.CategoryDao
import com.securevault.app.data.dao.HistoryDao
import com.securevault.app.data.dao.VaultDao
import com.securevault.app.data.entities.CategoryEntity
import com.securevault.app.data.entities.HistoryEntity
import com.securevault.app.data.entities.PasswordEntity
import com.securevault.app.security.CryptographyHelper
import com.securevault.app.security.KeystoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * SCR-VLT-02 — Add/Edit Password Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-VLT-02 — Captures new credential inputs or modifies existing
 *   - PRD F-VAULT-02 AC#1 — inputs: Name, Username/Email, Password, Website URL,
 *     Category dropdown, Generate Password button, Save button
 *   - PRD F-VAULT-02 AC#2 — "Saving must encrypt Password via AES-256 using VMK
 *     and write to local Room database"
 *   - PRD F-VAULT-02 AC#4 — "Editing must write new state with incremented version
 *     counter and updated timestamp"
 *   - SRS FR-VAULT-02 — "client SHALL encrypt credentials locally using AES-256"
 *   - Security_Requirements.md §1 — [MUST] FLAG_SECURE
 *   - Security_Requirements.md §9.1 — memory sanitisation: clear decrypted secrets
 *   - Design.md §2.1 — background #141218, surface #1C1B1F, surface-variant #49454F
 *
 * Entry: FAB on SCR-VLT-01 or "Edit" on SCR-VLT-03.
 * Exit: SCR-VLT-01 (Dashboard), SCR-VLT-03 (Details).
 */
class AddEditCredentialActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CREDENTIAL_ID = "extra_credential_id"
        const val RESULT_SAVED = 1001
    }

    private lateinit var etName: TextInputEditText
    private lateinit var etUsernameEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etWebsiteUrl: TextInputEditText
    private lateinit var actCategory: AutoCompleteTextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnGeneratePassword: MaterialButton
    private lateinit var progressSaving: ProgressBar
    private lateinit var tvTitle: TextView

    private var editingCredentialId: String? = null
    private var existingCredential: PasswordEntity? = null
    private val categoryList = mutableListOf<CategoryEntity>()
    private var selectedCategoryId: String? = null

    /** Launcher for GeneratorActivity — PRD F-GEN-01 AC#1 "shortcut on Add Password screen" */
    private val generatorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == com.securevault.app.ui.generator.GeneratorActivity.RESULT_GENERATED) {
            val password = result.data?.getStringExtra(
                com.securevault.app.ui.generator.GeneratorActivity.RESULT_PASSWORD
            )
            if (!password.isNullOrEmpty()) {
                etPassword.setText(password)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_add_edit_credential)

        bindViews()
        editingCredentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID)
        loadCategories()

        if (editingCredentialId != null) {
            tvTitle.text = "Edit Password"
            loadExistingCredential()
        }

        setupSaveButton()
        setupGenerateButton()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        tvTitle = findViewById(R.id.tv_screen_title)
        etName = findViewById(R.id.et_name)
        etUsernameEmail = findViewById(R.id.et_username_email)
        etPassword = findViewById(R.id.et_password)
        etWebsiteUrl = findViewById(R.id.et_website_url)
        actCategory = findViewById(R.id.act_category)
        btnSave = findViewById(R.id.btn_save)
        btnGeneratePassword = findViewById(R.id.btn_generate_password)
        progressSaving = findViewById(R.id.progress_saving)
    }

    // -------------------------------------------------------------------------
    // Load categories for dropdown — SCR-VLT-02 "Category drop list"
    // -------------------------------------------------------------------------

    private fun loadCategories() {
        CoroutineScope(Dispatchers.Main).launch {
            val dao = getCategoryDao()
            val userId = getCurrentUserId()

            val categories = withContext(Dispatchers.IO) {
                dao.getCategoriesByUser(userId)
            }

            categoryList.clear()
            categoryList.addAll(categories)

            val names = categories.map { it.name }
            val adapter = ArrayAdapter(this@AddEditCredentialActivity,
                android.R.layout.simple_dropdown_item_1line, names)
            actCategory.setAdapter(adapter)

            actCategory.setOnItemClickListener { _, _, position, _ ->
                selectedCategoryId = categoryList[position].id
            }
        }
    }

    // -------------------------------------------------------------------------
    // Load existing credential for edit mode — PRD F-VAULT-02 AC#4
    // -------------------------------------------------------------------------

    private fun loadExistingCredential() {
        CoroutineScope(Dispatchers.Main).launch {
            val dao = getVaultDao()
            val userId = getCurrentUserId()

            val credential = withContext(Dispatchers.IO) {
                dao.getCredentialById(editingCredentialId!!, userId)
            }

            if (credential == null) {
                Toast.makeText(this@AddEditCredentialActivity,
                    "Credential not found.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            existingCredential = credential
            etName.setText(credential.name)
            etUsernameEmail.setText(credential.usernameEmail)
            etWebsiteUrl.setText(credential.websiteUrl ?: "")
            selectedCategoryId = credential.categoryId

            // Decrypt password for edit field — SRS FR-VAULT-02
            try {
                val vmkKey = KeystoreManager.getVmkKey()
                if (vmkKey != null) {
                    val decrypted = CryptographyHelper.decrypt(
                        credential.encryptedPassword, vmkKey
                    )
                    etPassword.setText(decrypted)
                    // Security_Requirements.md §9.1: clear decrypted from String pool
                    // (Kotlin Strings are immutable; best-effort via GC hint)
                }
            } catch (e: Exception) {
                // Edge case: Decryption fails due to Keystore key mismatch
                Toast.makeText(this@AddEditCredentialActivity,
                    "Could not decrypt password.", Toast.LENGTH_SHORT).show()
            }

            // Set category dropdown to current value
            val categoryName = categoryList.find { it.id == credential.categoryId }?.name
            if (categoryName != null) {
                actCategory.setText(categoryName, false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Save — PRD F-VAULT-02 AC#2, AC#4
    // -------------------------------------------------------------------------

    private fun setupSaveButton() {
        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim() ?: ""
            val usernameEmail = etUsernameEmail.text?.toString()?.trim() ?: ""
            val password = etPassword.text?.toString() ?: ""
            val websiteUrl = etWebsiteUrl.text?.toString()?.trim()?.ifEmpty { null }

            // Validation — edge case: blank fields
            if (name.isEmpty()) {
                etName.error = "Name is required"
                return@setOnClickListener
            }
            if (usernameEmail.isEmpty()) {
                etUsernameEmail.error = "Username or email is required"
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                etPassword.error = "Password is required"
                return@setOnClickListener
            }

            setSaving(true)

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val dao = getVaultDao()
                    val userId = getCurrentUserId()

                    // PRD F-VAULT-02 AC#2 — encrypt via AES-256 using VMK
                    val vmkKey = KeystoreManager.getVmkKey()
                        ?: throw IllegalStateException("VMK key not available")

                    val encryptedPassword = CryptographyHelper.encrypt(password, vmkKey)

                    // Security_Requirements.md §9.1 — clear plaintext from memory
                    // (best-effort: overwrite EditText)
                    etPassword.setText("")

                    // -----------------------------------------------------------
                    // task-017: Duplicate detection — PRD F-GEN-02 AC#3
                    // "Upon saving, the app must decrypt passwords in memory and
                    //  compare them. If the password matches another active vault
                    //  entry, display a Warning Dialog."
                    // SRS FR-GEN-02b — "client SHALL alert users to password reuse"
                    // -----------------------------------------------------------
                    val allCredentials = withContext(Dispatchers.IO) {
                        dao.getActiveCredentials(userId)
                    }
                    // Exclude the credential being edited from comparison
                    val otherCredentials = allCredentials.filter {
                        it.id != editingCredentialId
                    }
                    val duplicates = com.securevault.app.security.VaultHealthAuditor
                        .checkForReuse(encryptedPassword, otherCredentials)

                    if (duplicates.isNotEmpty()) {
                        // Show warning dialog — PRD F-GEN-02 AC#3
                        val dupeNames = duplicates.joinToString(", ") { it.name }
                        withContext(Dispatchers.Main) {
                            setSaving(false)
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                this@AddEditCredentialActivity
                            )
                                .setTitle("⚠️ Duplicate Password Detected")
                                .setMessage(
                                    "This password is already used by: $dupeNames.\n\n" +
                                    "Using unique passwords for each account is strongly recommended."
                                )
                                .setPositiveButton("Save Anyway") { _, _ ->
                                    // User chose to proceed — continue save
                                    performSave(dao, userId, name, usernameEmail,
                                        encryptedPassword, websiteUrl, password)
                                }
                                .setNegativeButton("Go Back", null)
                                .setCancelable(true)
                                .show()
                        }
                        return@launch // Dialog handles the rest
                    }

                    // No duplicates — proceed with save directly
                    performSaveInternal(dao, userId, name, usernameEmail,
                        encryptedPassword, websiteUrl, password)

                    setResult(RESULT_SAVED)
                    finish()

                } catch (e: Exception) {
                    Toast.makeText(this@AddEditCredentialActivity,
                        "Failed to save credential: ${e.message}",
                        Toast.LENGTH_SHORT).show()
                } finally {
                    setSaving(false)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Save logic (extracted for duplicate dialog flow) — task-017
    // -------------------------------------------------------------------------

    /**
     * Called from the duplicate warning dialog's "Save Anyway" button.
     * PRD F-GEN-02 AC#3 — user chose to proceed despite duplicate.
     */
    private fun performSave(
        dao: VaultDao,
        userId: String,
        name: String,
        usernameEmail: String,
        encryptedPassword: String,
        websiteUrl: String?,
        plainPassword: String
    ) {
        setSaving(true)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                performSaveInternal(dao, userId, name, usernameEmail,
                    encryptedPassword, websiteUrl, plainPassword)
                setResult(RESULT_SAVED)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AddEditCredentialActivity,
                    "Failed to save credential: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            } finally {
                setSaving(false)
            }
        }
    }

    /**
     * Core save logic: handles both create and edit modes.
     * Extracted from setupSaveButton to allow reuse from dialog callback.
     */
    private suspend fun performSaveInternal(
        dao: VaultDao,
        userId: String,
        name: String,
        usernameEmail: String,
        encryptedPassword: String,
        websiteUrl: String?,
        plainPassword: String
    ) {
        if (editingCredentialId != null && existingCredential != null) {
            // EDIT MODE — PRD F-VAULT-02 AC#4
            val existing = existingCredential!!

            // task-012: Only archive to history if password actually changed
            val passwordChanged = encryptedPassword != existing.encryptedPassword

            if (passwordChanged) {
                // Archive old password — PRD F-VAULT-03 AC#1
                val historyDao = getHistoryDao()
                withContext(Dispatchers.IO) {
                    historyDao.archiveAndPurge(
                        HistoryEntity(
                            id = UUID.randomUUID().toString(),
                            passwordEntryId = existing.id,
                            encryptedPassword = existing.encryptedPassword
                        )
                    )
                }
            }

            val updatedCredential = existing.copy(
                name = name,
                usernameEmail = usernameEmail,
                encryptedPassword = encryptedPassword,
                websiteUrl = websiteUrl,
                categoryId = selectedCategoryId,
                updatedAt = System.currentTimeMillis()
            )

            withContext(Dispatchers.IO) {
                dao.update(updatedCredential)
            }
        } else {
            // CREATE MODE — PRD F-VAULT-02 AC#2
            val newCredential = PasswordEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                name = name,
                usernameEmail = usernameEmail,
                encryptedPassword = encryptedPassword,
                websiteUrl = websiteUrl,
                categoryId = selectedCategoryId,
                passwordStrength = evaluateStrength(plainPassword)
            )

            withContext(Dispatchers.IO) {
                dao.insert(newCredential)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Generate Password — SCR-VLT-02 "Generate Password shortcut"
    // -------------------------------------------------------------------------

    private fun setupGenerateButton() {
        btnGeneratePassword.setOnClickListener {
            // PRD F-GEN-01 AC#1 — "shortcut on the Add Password screen"
            val intent = Intent(this, com.securevault.app.ui.generator.GeneratorActivity::class.java)
            intent.putExtra(com.securevault.app.ui.generator.GeneratorActivity.EXTRA_FROM_ADD_EDIT, true)
            generatorLauncher.launch(intent)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setSaving(saving: Boolean) {
        progressSaving.visibility = if (saving) View.VISIBLE else View.GONE
        btnSave.isEnabled = !saving
    }

    /**
     * Basic password strength evaluation.
     * Full zxcvbn evaluation is in task-016.
     */
    private fun evaluateStrength(password: String): String {
        return when {
            password.length >= 12 && password.any { it.isUpperCase() }
                && password.any { it.isDigit() }
                && password.any { !it.isLetterOrDigit() } -> PasswordEntity.PasswordStrength.STRONG.value
            password.length >= 8 -> PasswordEntity.PasswordStrength.MEDIUM.value
            else -> PasswordEntity.PasswordStrength.WEAK.value
        }
    }

    private fun getVaultDao(): VaultDao {
        return com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext).vaultDao()
    }

    private fun getCategoryDao(): CategoryDao {
        return com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext).categoryDao()
    }

    private fun getHistoryDao(): HistoryDao {
        return com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext).historyDao()
    }

    private suspend fun getCurrentUserId(): String {
        return withContext(Dispatchers.IO) {
            val db = com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext)
            val cursor = db.openHelper.readableDatabase
                .rawQuery("SELECT id FROM users LIMIT 1", null)
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
        }
    }
}
