package com.securevault.app.ui.generator

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.securevault.app.R
import com.securevault.app.security.PasswordGenerator
import com.securevault.app.security.PasswordStrengthAnalyzer
import com.securevault.app.security.SecureClipboardManager

/**
 * SCR-GEN-01 — Password Generator Screen.
 *
 * Spec refs:
 *   - PRD F-GEN-01 AC#1 — "accessible via Bottom Nav Tab 3 or shortcut on Add Password"
 *   - PRD F-GEN-01 AC#2 — "Length Slider (8-64), toggles for Uppercase, Lowercase,
 *     Numbers, Symbols, Exclude Similar Characters"
 *   - PRD F-GEN-01 AC#3 — "real-time Strength Meter, Copy Button, Use Password button
 *     (only visible when opened from Add/Edit Screen)"
 *   - SRS FR-GEN-01a — "client SHALL generate random passwords"
 *   - SRS FR-GEN-01b — "client SHALL validate generated password strength locally"
 *   - Security_Requirements.md §1 — [MUST] FLAG_SECURE
 *   - Design.md §3.2 — monospace font, success green (#81C784)
 *
 * Launch modes:
 *   - Standalone (from Bottom Nav): Use Password hidden
 *   - From AddEditCredentialActivity (EXTRA_FROM_ADD_EDIT = true):
 *     Use Password visible, returns password via RESULT_OK Intent
 */
class GeneratorActivity : AppCompatActivity() {

    companion object {
        /** Pass true when launching from AddEditCredentialActivity */
        const val EXTRA_FROM_ADD_EDIT = "extra_from_add_edit"
        /** Result key for the generated password */
        const val RESULT_PASSWORD = "result_password"
        /** Result code for successful password selection */
        const val RESULT_GENERATED = 42
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvGeneratedPassword: TextView
    private lateinit var progressStrength: ProgressBar
    private lateinit var tvStrengthLabel: TextView
    private lateinit var sliderLength: Slider
    private lateinit var tvLengthValue: TextView
    private lateinit var switchUppercase: SwitchMaterial
    private lateinit var switchLowercase: SwitchMaterial
    private lateinit var switchNumbers: SwitchMaterial
    private lateinit var switchSymbols: SwitchMaterial
    private lateinit var switchExcludeSimilar: SwitchMaterial
    private lateinit var btnGenerate: MaterialButton
    private lateinit var btnCopy: MaterialButton
    private lateinit var btnUsePassword: MaterialButton

    private var currentPassword: String = ""
    private var isFromAddEdit: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_generator)

        isFromAddEdit = intent.getBooleanExtra(EXTRA_FROM_ADD_EDIT, false)

        bindViews()
        setupToolbar()
        setupSlider()
        setupToggles()
        setupButtons()

        // Auto-generate on open
        generatePassword()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Security_Requirements.md §9.1 — clear generated password from memory
        currentPassword = ""
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar_generator)
        tvGeneratedPassword = findViewById(R.id.tv_generated_password)
        progressStrength = findViewById(R.id.progress_strength)
        tvStrengthLabel = findViewById(R.id.tv_strength_label)
        sliderLength = findViewById(R.id.slider_length)
        tvLengthValue = findViewById(R.id.tv_length_value)
        switchUppercase = findViewById(R.id.switch_uppercase)
        switchLowercase = findViewById(R.id.switch_lowercase)
        switchNumbers = findViewById(R.id.switch_numbers)
        switchSymbols = findViewById(R.id.switch_symbols)
        switchExcludeSimilar = findViewById(R.id.switch_exclude_similar)
        btnGenerate = findViewById(R.id.btn_generate)
        btnCopy = findViewById(R.id.btn_copy)
        btnUsePassword = findViewById(R.id.btn_use_password)

        // PRD F-GEN-01 AC#3 — "Use Password button (only visible from Add/Edit)"
        if (isFromAddEdit) {
            btnUsePassword.visibility = android.view.View.VISIBLE
        }
    }

    // -------------------------------------------------------------------------
    // Toolbar — navigation back
    // -------------------------------------------------------------------------

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    // -------------------------------------------------------------------------
    // Length Slider — PRD F-GEN-01 AC#2 "Length Slider (8 to 64)"
    // -------------------------------------------------------------------------

    private fun setupSlider() {
        sliderLength.addOnChangeListener { _, value, _ ->
            tvLengthValue.text = value.toInt().toString()
            // Auto-regenerate on length change for real-time feedback
            generatePassword()
        }
    }

    // -------------------------------------------------------------------------
    // Character Type Toggles — PRD F-GEN-01 AC#2
    // -------------------------------------------------------------------------

    private fun setupToggles() {
        val toggleListener = { _: android.widget.CompoundButton, _: Boolean ->
            // Edge case: if all toggles off, re-enable lowercase
            if (!switchUppercase.isChecked && !switchLowercase.isChecked
                && !switchNumbers.isChecked && !switchSymbols.isChecked) {
                switchLowercase.isChecked = true
                Toast.makeText(this, "At least one character type required",
                    Toast.LENGTH_SHORT).show()
            }
            generatePassword()
        }

        switchUppercase.setOnCheckedChangeListener(toggleListener)
        switchLowercase.setOnCheckedChangeListener(toggleListener)
        switchNumbers.setOnCheckedChangeListener(toggleListener)
        switchSymbols.setOnCheckedChangeListener(toggleListener)
        switchExcludeSimilar.setOnCheckedChangeListener(toggleListener)
    }

    // -------------------------------------------------------------------------
    // Action Buttons
    // -------------------------------------------------------------------------

    private fun setupButtons() {
        // Generate — SRS FR-GEN-01a
        btnGenerate.setOnClickListener {
            generatePassword()
        }

        // Copy — PRD F-GEN-01 AC#3 "Copy Button"
        // Uses SecureClipboardManager for 30s auto-clear (task-013)
        btnCopy.setOnClickListener {
            if (currentPassword.isNotEmpty()) {
                SecureClipboardManager.copyPassword(this, currentPassword)
            }
        }

        // Use Password — PRD F-GEN-01 AC#3 "Use Password button"
        // Returns generated password to AddEditCredentialActivity
        btnUsePassword.setOnClickListener {
            if (currentPassword.isNotEmpty()) {
                val resultIntent = Intent()
                resultIntent.putExtra(RESULT_PASSWORD, currentPassword)
                setResult(RESULT_GENERATED, resultIntent)
                finish()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Password generation — SRS FR-GEN-01a, PRD F-GEN-01 AC#2
    // -------------------------------------------------------------------------

    private fun generatePassword() {
        val config = PasswordGenerator.GeneratorConfig(
            length = sliderLength.value.toInt(),
            includeUppercase = switchUppercase.isChecked,
            includeLowercase = switchLowercase.isChecked,
            includeNumbers = switchNumbers.isChecked,
            includeSymbols = switchSymbols.isChecked,
            excludeSimilar = switchExcludeSimilar.isChecked
        )

        currentPassword = PasswordGenerator.generate(config)
        tvGeneratedPassword.text = currentPassword

        // Update strength meter — PRD F-GEN-01 AC#3, SRS FR-GEN-01b
        updateStrengthMeter(currentPassword)
    }

    // -------------------------------------------------------------------------
    // Strength Meter — PRD F-GEN-01 AC#3, Design.md §3.2
    // -------------------------------------------------------------------------

    private fun updateStrengthMeter(password: String) {
        val result = PasswordStrengthAnalyzer.analyze(password)

        progressStrength.progress = result.score
        tvStrengthLabel.text = "Strength: ${result.label}"

        // Color the progress bar — Design.md §3.2
        try {
            val color = Color.parseColor(result.colorHex)
            progressStrength.progressDrawable?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            tvStrengthLabel.setTextColor(color)
        } catch (e: Exception) {
            // Fallback if color parse fails
        }
    }
}
