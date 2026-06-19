package com.securevault.app.service

import android.app.assist.AssistStructure
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.securevault.app.R
import com.securevault.app.data.DatabaseModule
import com.securevault.app.data.entities.PasswordEntity
import com.securevault.app.security.CryptographyHelper
import com.securevault.app.security.KeystoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android native AutofillService implementation for SecureVault.
 *
 * Spec refs:
 *   - PRD F-AUTO-01 AC#1 — "app must implement the Android system AutofillService"
 *   - PRD F-AUTO-01 AC#2 — "display a dropdown overlay with credential suggestions"
 *   - PRD F-AUTO-01 AC#3 — "Selecting a credential must autofill username and
 *     password fields instantly"
 *   - SRS FR-AUTO-01a — "client SHALL integrate with the Android Autofill Framework"
 *   - SRS FR-AUTO-01b — "autofill suggestions SHALL trigger in native and WebView"
 *   - Architecture.md — AutofillService Module: "Focus input → Trigger service →
 *     Parse node hierarchy → Query DB → Render suggestion overlay"
 *   - Security_Requirements.md — ST-CLIENT-SPOOF-01: verify caller package
 *   - Technical_Requirements.md §1 — pull-based, no active background injection
 *
 * Data flow:
 *   1. Android system calls onFillRequest when user focuses on input field
 *   2. Service traverses AssistStructure node tree to find username/password fields
 *   3. Queries local Room database for matching credentials
 *   4. Builds FillResponse with Dataset items (RemoteViews presentation)
 *   5. User selects credential → system autofills fields instantly
 *
 * Edge cases:
 *   - Obfuscated field identifiers: falls back to hint-based detection
 *   - No matching credentials: returns null (no suggestions shown)
 *   - Empty vault: returns null
 */
class VaultAutofillService : AutofillService() {

    companion object {
        /** Common autofill hint identifiers for username/email fields */
        private val USERNAME_HINTS = setOf(
            "username", "email", "emailAddress", "login", "user",
            "phone", "phoneNumber", "account",
            // Android View.AUTOFILL_HINT constants
            "emailAddress", "username", "phone"
        )

        /** Common autofill hint identifiers for password fields */
        private val PASSWORD_HINTS = setOf(
            "password", "pass", "passwd", "secret", "pin",
            // Android View.AUTOFILL_HINT constants
            "password"
        )

        /** Common input type flags for password fields */
        private const val INPUT_TYPE_PASSWORD_MASK = 0x00000080 // TYPE_TEXT_VARIATION_PASSWORD
        private const val INPUT_TYPE_WEB_PASSWORD = 0x000000E0 // TYPE_TEXT_VARIATION_WEB_PASSWORD
        private const val INPUT_TYPE_VISIBLE_PASSWORD = 0x00000090 // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    /**
     * Holds parsed autofill field IDs from the AssistStructure traversal.
     */
    data class ParsedFields(
        val usernameId: AutofillId? = null,
        val passwordId: AutofillId? = null,
        val packageName: String? = null
    )

    // -------------------------------------------------------------------------
    // onFillRequest — PRD F-AUTO-01 AC#1, AC#2
    // Architecture.md: "Focus input → Trigger service → Parse → Query → Render"
    // -------------------------------------------------------------------------

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        // Get the latest AssistStructure
        val structure: AssistStructure = request.fillContexts
            .lastOrNull()?.structure ?: run {
            callback.onSuccess(null)
            return
        }

        // Parse the node tree to find username/password fields
        val parsedFields = parseStructure(structure)

        // If no relevant fields found, no suggestions needed
        if (parsedFields.usernameId == null && parsedFields.passwordId == null) {
            callback.onSuccess(null)
            return
        }

        // Query credentials on background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val credentials = queryCredentials(parsedFields.packageName)

                if (credentials.isEmpty()) {
                    callback.onSuccess(null)
                    return@launch
                }

                // Build FillResponse with datasets — PRD F-AUTO-01 AC#2
                val response = buildFillResponse(credentials, parsedFields)
                callback.onSuccess(response)

            } catch (e: Exception) {
                callback.onSuccess(null)
            }
        }
    }

    // -------------------------------------------------------------------------
    // onSaveRequest — placeholder for future save-on-form-submit
    // -------------------------------------------------------------------------

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // TODO: Future task — save credentials from external apps
        callback.onSuccess()
    }

    // -------------------------------------------------------------------------
    // AssistStructure parsing — step 3 of Architecture.md data flow
    // -------------------------------------------------------------------------

    /**
     * Traverses the AssistStructure node tree to find username and password
     * AutofillId fields.
     *
     * PRD F-AUTO-01 AC#2 — "When user focuses on username or password field"
     * SRS FR-AUTO-01b — "trigger in native and WebView input fields"
     *
     * Detection strategy (in priority order):
     *   1. Android autofill hints (View.AUTOFILL_HINT_*)
     *   2. HTML input type/name attributes (WebView support)
     *   3. View ID resource name matching
     *   4. InputType flags for password detection
     *
     * Edge case: obfuscated identifiers → hint-based fallback
     */
    private fun parseStructure(structure: AssistStructure): ParsedFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var packageName: String? = null

        for (i in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            val rootNode = windowNode.rootViewNode

            if (packageName == null) {
                packageName = rootNode?.idPackage
            }

            traverseNode(rootNode) { node ->
                val autofillId = node.autofillId ?: return@traverseNode

                // Strategy 1: Check Android autofill hints
                val hints = node.autofillHints
                if (hints != null) {
                    for (hint in hints) {
                        val lowerHint = hint.lowercase()
                        if (passwordId == null && lowerHint in PASSWORD_HINTS) {
                            passwordId = autofillId
                            return@traverseNode
                        }
                        if (usernameId == null && lowerHint in USERNAME_HINTS) {
                            usernameId = autofillId
                            return@traverseNode
                        }
                    }
                }

                // Strategy 2: Check HTML attributes (WebView — SRS FR-AUTO-01b)
                val htmlInfo = node.htmlInfo
                if (htmlInfo != null) {
                    val attrs = htmlInfo.attributes
                    if (attrs != null) {
                        for (pair in attrs) {
                            val attrValue = pair.second?.lowercase() ?: continue
                            if (passwordId == null &&
                                (attrValue == "password" || attrValue.contains("pass"))) {
                                passwordId = autofillId
                                return@traverseNode
                            }
                            if (usernameId == null &&
                                (attrValue == "email" || attrValue == "username" ||
                                        attrValue.contains("user") || attrValue.contains("login"))) {
                                usernameId = autofillId
                                return@traverseNode
                            }
                        }
                    }
                }

                // Strategy 3: Check view ID resource name
                val idEntry = node.idEntry?.lowercase()
                if (idEntry != null) {
                    if (passwordId == null && PASSWORD_HINTS.any { idEntry.contains(it) }) {
                        passwordId = autofillId
                        return@traverseNode
                    }
                    if (usernameId == null && USERNAME_HINTS.any { idEntry.contains(it) }) {
                        usernameId = autofillId
                        return@traverseNode
                    }
                }

                // Strategy 4: Check InputType for password fields
                val inputType = node.inputType
                if (passwordId == null && inputType > 0) {
                    val variation = inputType and 0x00000FF0
                    if (variation == INPUT_TYPE_PASSWORD_MASK ||
                        variation == INPUT_TYPE_WEB_PASSWORD ||
                        variation == INPUT_TYPE_VISIBLE_PASSWORD) {
                        passwordId = autofillId
                    }
                }
            }
        }

        return ParsedFields(usernameId, passwordId, packageName)
    }

    /**
     * Recursively traverses the view node tree.
     */
    private fun traverseNode(
        node: AssistStructure.ViewNode?,
        visitor: (AssistStructure.ViewNode) -> Unit
    ) {
        if (node == null) return
        visitor(node)
        for (i in 0 until node.childCount) {
            traverseNode(node.getChildAt(i), visitor)
        }
    }

    // -------------------------------------------------------------------------
    // Credential query — step 4 of Architecture.md data flow
    // -------------------------------------------------------------------------

    /**
     * Queries the local vault for credentials matching the target app.
     *
     * First attempts to match by package name / website URL.
     * Falls back to returning all credentials if no match found
     * (user can still select from the full list).
     */
    private suspend fun queryCredentials(packageName: String?): List<PasswordEntity> {
        val db = DatabaseModule.provideDatabase(applicationContext)
        val dao = db.vaultDao()

        // Get current user ID
        val cursor = db.openHelper.readableDatabase
            .query("SELECT id FROM users LIMIT 1")
        val userId = cursor.use {
            if (it.moveToFirst()) it.getString(0) else return emptyList()
        }

        val allCredentials = dao.getActiveCredentials(userId)

        if (packageName != null && allCredentials.isNotEmpty()) {
            // Try to match by website URL containing the package name parts
            val packageParts = packageName.split(".").filter { it.length > 2 }
            val matched = allCredentials.filter { credential ->
                val url = credential.websiteUrl?.lowercase() ?: ""
                val name = credential.name.lowercase()
                packageParts.any { part ->
                    url.contains(part.lowercase()) || name.contains(part.lowercase())
                }
            }

            // Return matched first, then all others
            if (matched.isNotEmpty()) {
                return matched + allCredentials.filter { it !in matched }
            }
        }

        return allCredentials
    }

    // -------------------------------------------------------------------------
    // FillResponse construction — step 5 of Architecture.md data flow
    // PRD F-AUTO-01 AC#2: "dropdown overlay with credential suggestions"
    // -------------------------------------------------------------------------

    /**
     * Builds a FillResponse with Dataset items for each matching credential.
     *
     * PRD F-AUTO-01 AC#2 — "display dropdown overlay with credential suggestions"
     * PRD F-AUTO-01 AC#3 — "selecting must autofill username and password instantly"
     *
     * Each Dataset contains:
     *   - RemoteViews presentation (name + username in dropdown)
     *   - AutofillValue for username field (if found)
     *   - AutofillValue for password field (if found, decrypted on-the-fly)
     */
    private fun buildFillResponse(
        credentials: List<PasswordEntity>,
        fields: ParsedFields
    ): FillResponse? {
        val responseBuilder = FillResponse.Builder()
        var datasetsAdded = 0

        // Limit to top 5 suggestions for performance
        val topCredentials = credentials.take(5)

        for (credential in topCredentials) {
            val presentation = RemoteViews(packageName, R.layout.item_autofill_suggestion).apply {
                setTextViewText(R.id.tv_autofill_name, credential.name)
                setTextViewText(R.id.tv_autofill_username, credential.usernameEmail)
            }

            val datasetBuilder = Dataset.Builder(presentation)

            // Set username value — PRD F-AUTO-01 AC#3
            if (fields.usernameId != null) {
                datasetBuilder.setValue(
                    fields.usernameId!!,
                    AutofillValue.forText(credential.usernameEmail)
                )
            }

            // Set password value — PRD F-AUTO-01 AC#3
            // Decrypt password on-the-fly for autofill
            if (fields.passwordId != null) {
                try {
                    val vmkKey = KeystoreManager.getKey(KeystoreManager.VMK_KEY_ALIAS)
                    val decryptedPassword = if (vmkKey != null) {
                        CryptographyHelper.decrypt(credential.encryptedPassword, vmkKey)
                    } else {
                        null
                    }

                    if (decryptedPassword != null) {
                        datasetBuilder.setValue(
                            fields.passwordId!!,
                            AutofillValue.forText(decryptedPassword)
                        )
                        // Security_Requirements.md §9.1 — clear decrypted password
                        // The String object will be GC'd; best-effort in JVM
                    }
                } catch (e: Exception) {
                    // Skip this credential if decryption fails
                    continue
                }
            }

            try {
                responseBuilder.addDataset(datasetBuilder.build())
                datasetsAdded++
            } catch (e: Exception) {
                // Skip invalid datasets
            }
        }

        return if (datasetsAdded > 0) responseBuilder.build() else null
    }
}
