package com.securevault.app.ui.vault

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.securevault.app.R
import com.securevault.app.data.DatabaseModule
import com.securevault.app.security.CryptographyHelper
import com.securevault.app.security.KeystoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import androidx.lifecycle.lifecycleScope

/**
 * Bottom sheet dialog for quickly adding a new password credential
 * from the dashboard without navigating to a full-screen activity.
 *
 * For editing existing credentials, the full AddEditCredentialActivity
 * is still used (opened from CredentialDetailsActivity).
 */
class AddCredentialBottomSheet : BottomSheetDialogFragment() {

    interface OnCredentialSavedListener {
        fun onCredentialSaved()
    }

    private var listener: OnCredentialSavedListener? = null

    fun setOnCredentialSavedListener(l: OnCredentialSavedListener) {
        listener = l
    }

    private lateinit var etName: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etWebsiteUrl: TextInputEditText
    private lateinit var actCategory: AutoCompleteTextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnGenerate: MaterialButton
    private lateinit var btnClose: ImageButton
    private lateinit var progressSaving: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_add_credential, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupButtons()
        loadCategories()

        // Make the sheet expand fully
        (dialog as? BottomSheetDialog)?.let { d ->
            d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            d.behavior.skipCollapsed = true
        }
    }

    override fun getTheme(): Int = R.style.ThemeOverlay_SecureVault_BottomSheetDialog

    private fun bindViews(view: View) {
        etName = view.findViewById(R.id.et_name)
        etUsername = view.findViewById(R.id.et_username_email)
        etPassword = view.findViewById(R.id.et_password)
        etWebsiteUrl = view.findViewById(R.id.et_website_url)
        actCategory = view.findViewById(R.id.act_category)
        btnSave = view.findViewById(R.id.btn_save)
        btnGenerate = view.findViewById(R.id.btn_generate_password)
        btnClose = view.findViewById(R.id.btn_sheet_close)
        progressSaving = view.findViewById(R.id.progress_saving)
    }

    private fun setupButtons() {
        btnClose.setOnClickListener { dismiss() }

        btnGenerate.setOnClickListener {
            // Generate a random 16-char password
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
            val secureRandom = java.security.SecureRandom()
            val password = (1..16).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
            etPassword.setText(password)
        }

        btnSave.setOnClickListener { saveCredential() }
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val db = DatabaseModule.provideDatabase(ctx)
                val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                val categories = withContext(Dispatchers.IO) {
                    db.categoryDao().getCategoriesByUser(userId)
                }
                val names: List<String> = categories.map { it.name }
                val adapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, names)
                actCategory.setAdapter(adapter)
            } catch (e: Exception) {
                // Categories are optional — ignore
            }
        }
    }

    private fun saveCredential() {
        val name = etName.text?.toString()?.trim() ?: ""
        val username = etUsername.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""
        val websiteUrl = etWebsiteUrl.text?.toString()?.trim() ?: ""
        val categoryName = actCategory.text?.toString()?.trim() ?: ""

        // Validate required fields
        if (name.isEmpty()) {
            etName.error = "Name is required"
            etName.requestFocus()
            return
        }
        if (username.isEmpty()) {
            etUsername.error = "Username is required"
            etUsername.requestFocus()
            return
        }
        if (password.isEmpty()) {
            etPassword.error = "Password is required"
            etPassword.requestFocus()
            return
        }

        // Validate URL format if provided
        if (websiteUrl.isNotEmpty()) {
            val urlToCheck = if (!websiteUrl.startsWith("http://") && !websiteUrl.startsWith("https://")) {
                "https://$websiteUrl"
            } else {
                websiteUrl
            }
            if (!android.util.Patterns.WEB_URL.matcher(urlToCheck).matches()) {
                etWebsiteUrl.error = "Enter a valid URL (e.g. instagram.com)"
                etWebsiteUrl.requestFocus()
                return
            }
        }

        btnSave.isEnabled = false
        progressSaving.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val db = DatabaseModule.provideDatabase(ctx)
                val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""

                // Encrypt the password
                val encryptedPassword = encryptPassword(password)

                // Determine password strength
                val strength = when {
                    password.length >= 12 && password.any { it.isUpperCase() }
                        && password.any { it.isDigit() } && password.any { !it.isLetterOrDigit() } -> "STRONG"
                    password.length >= 8 -> "MEDIUM"
                    else -> "WEAK"
                }

                // Find category ID if provided
                var categoryId: String? = null
                if (categoryName.isNotEmpty()) {
                    val categories = withContext(Dispatchers.IO) {
                        db.categoryDao().getCategoriesByUser(userId)
                    }
                    categoryId = categories.find { it.name == categoryName }?.id
                }

                val entity = com.securevault.app.data.entities.PasswordEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    name = name,
                    usernameEmail = username,
                    encryptedPassword = encryptedPassword,
                    // Normalize URL — prepend https:// if missing
                    websiteUrl = if (websiteUrl.isNotEmpty() && !websiteUrl.startsWith("http")) {
                        "https://$websiteUrl"
                    } else {
                        websiteUrl.ifEmpty { null }
                    },
                    categoryId = categoryId,
                    favorite = 0,
                    passwordStrength = strength,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    deletedAt = null
                )

                withContext(Dispatchers.IO) {
                    db.vaultDao().insert(entity)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Password saved", Toast.LENGTH_SHORT).show()
                    listener?.onCredentialSaved()
                    dismiss()
                }
            } catch (e: Exception) {
                android.util.Log.e("AddCredential", "Save failed", e)
                withContext(Dispatchers.Main) {
                    btnSave.isEnabled = true
                    progressSaving.visibility = View.GONE
                    val msg = e.message ?: "Unknown error"
                    Toast.makeText(context ?: return@withContext, "Failed: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Encrypts the password using the VMK key. If the key requires per-operation
     * auth (legacy), regenerates it with a 300-second auth window and retries.
     */
    private fun encryptPassword(plaintext: String): String {
        // Try with existing key
        val existingKey = KeystoreManager.getKey(KeystoreManager.VMK_KEY_ALIAS)
        if (existingKey != null) {
            try {
                return CryptographyHelper.encrypt(plaintext, existingKey)
            } catch (e: Exception) {
                // Key auth failed — regenerate with timed auth window
                android.util.Log.w("AddCredential", "VMK auth failed, regenerating key", e)
            }
        }
        // Generate a fresh key with 300s auth window
        val newKey = KeystoreManager.generateVmkWrappingKey()
        return CryptographyHelper.encrypt(plaintext, newKey)
    }

    companion object {
        const val TAG = "AddCredentialBottomSheet"
    }
}
