package com.securevault.app.ui.generator

import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.securevault.app.R
import com.securevault.app.security.PasswordGenerator
import com.securevault.app.security.PasswordStrengthAnalyzer
import com.securevault.app.security.SecureClipboardManager

/**
 * SCR-GEN-01 — Password Generator Fragment.
 *
 * Fragment version of GeneratorActivity for in-place display within DashboardActivity.
 * This fragment is used only for the standalone bottom-nav case (no "Use Password" button).
 *
 * Spec refs:
 *   - PRD F-GEN-01 AC#1 — "accessible via Bottom Nav Tab 3 or shortcut on Add Password"
 *   - PRD F-GEN-01 AC#2 — "Length Slider (8-64), toggles for Uppercase, Lowercase,
 *     Numbers, Symbols, Exclude Similar Characters"
 *   - PRD F-GEN-01 AC#3 — "real-time Strength Meter, Copy Button"
 *   - SRS FR-GEN-01a — "client SHALL generate random passwords"
 *   - SRS FR-GEN-01b — "client SHALL validate generated password strength locally"
 *   - Design.md §3.2 — monospace font, success green (#81C784)
 */
class GeneratorFragment : Fragment() {

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

    private var currentPassword: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_generator, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupSlider()
        setupToggles()
        setupButtons()

        // Auto-generate on open
        generatePassword()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Security_Requirements.md §9.1 — clear generated password from memory
        currentPassword = ""
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews(view: View) {
        tvGeneratedPassword = view.findViewById(R.id.tv_generated_password)
        progressStrength = view.findViewById(R.id.progress_strength)
        tvStrengthLabel = view.findViewById(R.id.tv_strength_label)
        sliderLength = view.findViewById(R.id.slider_length)
        tvLengthValue = view.findViewById(R.id.tv_length_value)
        switchUppercase = view.findViewById(R.id.switch_uppercase)
        switchLowercase = view.findViewById(R.id.switch_lowercase)
        switchNumbers = view.findViewById(R.id.switch_numbers)
        switchSymbols = view.findViewById(R.id.switch_symbols)
        switchExcludeSimilar = view.findViewById(R.id.switch_exclude_similar)
        btnGenerate = view.findViewById(R.id.btn_generate)
        btnCopy = view.findViewById(R.id.btn_copy)
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
                if (isAdded) {
                    Toast.makeText(requireContext(), "At least one character type required",
                        Toast.LENGTH_SHORT).show()
                }
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
            if (currentPassword.isNotEmpty() && isAdded) {
                SecureClipboardManager.copyPassword(requireContext(), currentPassword)
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
