package com.securevault.app.data.api

import com.securevault.app.security.SecureHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Request / Response data classes — per API_Spec.md §2 POST /v1/auth/login
// ---------------------------------------------------------------------------

/**
 * Request body for POST /v1/auth/login.
 * API_Spec.md §2 — required fields: googleIdToken, deviceId, deviceName, androidVersion
 */
data class LoginRequest(
    val googleIdToken: String,
    val deviceId: String,
    val deviceName: String,
    val androidVersion: String
)

/**
 * Response body for POST /v1/auth/login.
 *
 * 200 OK  (registered=true)  — existing account, tokens returned
 * 201 Created (registered=false) — new account, proceed to onboarding
 *
 * API_Spec.md §2:
 *   firebaseToken  — Firebase ID token (JWT) for authenticating subsequent requests
 *   refreshToken   — refresh token (30-day lifespan per Security_Requirements.md §2.1)
 *   registered     — true if account exists, false if new registration
 */
data class LoginResponse(
    val firebaseToken: String,
    val refreshToken: String,
    val registered: Boolean,
    val pinFailedAttempts: Int = 0,
    val pinLockoutUntil: Long? = null
)

// ---------------------------------------------------------------------------
// Security question request/response — API_Spec.md §2
// ---------------------------------------------------------------------------

/**
 * Request body for POST /v1/auth/security-question/setup.
 * API_Spec.md §2 — securityQuestionId, securityAnswerHash (PBKDF2), encryptedVmk
 */
data class SecurityQuestionSetupRequest(
    val securityQuestionId: String,
    val securityAnswerHash: String,
    val encryptedVmk: String,
    val challengeToken: String? = null
)

/**
 * Request body for POST /v1/auth/security-question/verify.
 * API_Spec.md §2 — securityAnswer (normalized plaintext sent for server-side comparison)
 */
data class SecurityQuestionVerifyRequest(
    val securityAnswer: String
)

/**
 * Response body for POST /v1/auth/security-question/verify.
 * API_Spec.md §2: { "challengeToken": "...", "expiresAt": 1718274567000 }
 */
data class SecurityQuestionVerifyResponse(
    val challengeToken: String,
    val expiresAt: Long
)

// ---------------------------------------------------------------------------
// Backup codes request/response — API_Spec.md §2
// ---------------------------------------------------------------------------

data class BackupCodesVerifyRequest(
    val backupCode: String
)

data class BackupCodesVerifyResponse(
    val challengeToken: String
)

data class BackupCodesRegenerateRequest(
    val challengeToken: String?,
    val hashedBackupCodes: List<String>
)

// ---------------------------------------------------------------------------
// AuthApiService — REST client for /v1/auth/* endpoints
// ---------------------------------------------------------------------------

/**
 * HTTP client for authentication API endpoints.
 *
 * Spec refs:
 *   - API_Spec.md §2 POST /v1/auth/login — request/response schema
 *   - API_Spec.md §2 POST /v1/auth/security-question/setup
 *   - API_Spec.md §2 POST /v1/auth/security-question/verify
 *   - Security_Requirements.md §4 — TLS 1.3 enforced by SecureHttpClient
 *   - Security_Requirements.md §4 — Certificate Pinning via OkHttp CertificatePinner
 *   - API_Spec.md §5 — global error envelope: { error: { code, message, timestamp } }
 *   - SRS FR-AUTH-01 — client sends Google ID token, receives Firebase custom token
 *
 * All network I/O dispatched to Dispatchers.IO per Architecture.md MVVM.
 * SecureHttpClient (implemented in task-004) enforces TLS 1.3 and cert pinning.
 */
object AuthApiService {

    private const val BASE_URL = BuildConfig.API_BASE_URL  // Injected via build config

    /**
     * POST /v1/auth/login — verifies Google ID token and registers/retrieves session.
     *
     * @param request LoginRequest body per API_Spec.md §2
     * @return LoginResponse with firebaseToken, refreshToken, registered flag
     * @throws AuthException on 400/401/403 responses
     * @throws NetworkException on connection failures
     */
    @Throws(AuthException::class, NetworkException::class)
    suspend fun login(request: LoginRequest): LoginResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("googleIdToken", request.googleIdToken)
            put("deviceId", request.deviceId)
            put("deviceName", request.deviceName)
            put("androidVersion", request.androidVersion)
        }.toString()

        val response = SecureHttpClient.post("$BASE_URL/v1/auth/login", body)

        when (response.statusCode) {
            200, 201 -> {
                val json = JSONObject(response.body)
                LoginResponse(
                    firebaseToken = json.getString("firebaseToken"),
                    refreshToken = json.getString("refreshToken"),
                    registered = json.getBoolean("registered"),
                    pinFailedAttempts = json.optInt("pinFailedAttempts", 0),
                    pinLockoutUntil = if (json.isNull("pinLockoutUntil")) null else json.getLong("pinLockoutUntil")
                )
            }
            400 -> throw AuthException("Invalid request parameters (400)", "INVALID_ARGUMENT")
            401 -> throw AuthException("Google token verification failed (401)", "UNAUTHENTICATED")
            403 -> throw AuthException("Device limit exceeded (403)", "SESSION_LIMIT_EXCEEDED")
            else -> throw NetworkException("Unexpected status: ${response.statusCode}")
        }
    }

    /**
     * POST /v1/auth/security-question/setup — registers security question + hashed answer.
     *
     * @param request SecurityQuestionSetupRequest — answer hash is PBKDF2, never plaintext
     * @throws AuthException on 401 (JWT invalid or challengeToken missing for updates)
     */
    @Throws(AuthException::class, NetworkException::class)
    suspend fun setupSecurityQuestion(
        request: SecurityQuestionSetupRequest,
        bearerToken: String = SessionStore.firebaseToken
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("securityQuestionId", request.securityQuestionId)
            put("securityAnswerHash", request.securityAnswerHash)
            put("encryptedVmk", request.encryptedVmk)
            request.challengeToken?.let { put("challengeToken", it) }
        }.toString()

        val response = SecureHttpClient.postAuthenticated(
            "$BASE_URL/v1/auth/security-question/setup", body, bearerToken
        )

        when (response.statusCode) {
            200 -> Unit
            401 -> throw AuthException("Unauthorized (401)", "UNAUTHENTICATED")
            else -> throw NetworkException("Unexpected status: ${response.statusCode}")
        }
    }

    /**
     * POST /v1/auth/security-question/verify — validates answer, returns challenge token.
     *
     * Challenge token is short-lived and used to authorize admin actions per
     * Permissions_Matrix.md §3 C2 (backup codes, question change) and C3 (VMK retrieve).
     *
     * @param request SecurityQuestionVerifyRequest — normalized plaintext answer
     * @return SecurityQuestionVerifyResponse with challengeToken and expiresAt
     * @throws AuthException on 401 (wrong answer)
     */
    @Throws(AuthException::class, NetworkException::class)
    suspend fun verifySecurityQuestion(
        request: SecurityQuestionVerifyRequest,
        bearerToken: String = SessionStore.firebaseToken
    ): SecurityQuestionVerifyResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("securityAnswer", request.securityAnswer)
        }.toString()

        val response = SecureHttpClient.postAuthenticated(
            "$BASE_URL/v1/auth/security-question/verify", body, bearerToken
        )

        when (response.statusCode) {
            200 -> {
                val json = JSONObject(response.body)
                SecurityQuestionVerifyResponse(
                    challengeToken = json.getString("challengeToken"),
                    expiresAt = json.getLong("expiresAt")
                )
            }
            401 -> throw AuthException("Incorrect answer (401)", "UNAUTHENTICATED")
            else -> throw NetworkException("Unexpected status: ${response.statusCode}")
        }
    }

    /**
     * POST /v1/auth/lockout — synchronizes failed PIN attempts and lockout timestamp.
     *
     * @param pinFailedAttempts consecutive failed PIN attempts count
     * @param pinLockoutUntil lockout expiration timestamp in epoch ms, or null if active lockout cleared
     * @param bearerToken JWT token for auth
     */
    @Throws(AuthException::class, NetworkException::class)
    suspend fun syncLockout(
        pinFailedAttempts: Int,
        pinLockoutUntil: Long?,
        bearerToken: String = SessionStore.firebaseToken
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("pinFailedAttempts", pinFailedAttempts)
            put("pinLockoutUntil", pinLockoutUntil ?: JSONObject.NULL)
        }.toString()

        val response = SecureHttpClient.postAuthenticated(
            "$BASE_URL/v1/auth/lockout", body, bearerToken
        )

        when (response.statusCode) {
            200 -> Unit
            401 -> throw AuthException("Unauthorized (401)", "UNAUTHENTICATED")
            else -> throw NetworkException("Unexpected status: ${response.statusCode}")
        }
    }

    /**
     * POST /v1/auth/backup-codes/verify — verifies backup code to unlock local PIN reset.
     */
    @Throws(AuthException::class, NetworkException::class)
    suspend fun verifyBackupCode(
        request: BackupCodesVerifyRequest,
        bearerToken: String = SessionStore.firebaseToken
    ): BackupCodesVerifyResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("backupCode", request.backupCode)
        }.toString()

        val response = SecureHttpClient.postAuthenticated(
            "$BASE_URL/v1/auth/backup-codes/verify", body, bearerToken
        )

        when (response.statusCode) {
            200 -> {
                val json = JSONObject(response.body)
                BackupCodesVerifyResponse(
                    challengeToken = json.getString("challengeToken")
                )
            }
            401 -> throw AuthException("Invalid backup code (401)", "UNAUTHENTICATED")
            else -> throw NetworkException("Unexpected status: ${response.statusCode}")
        }
    }

    /**
     * POST /v1/auth/backup-codes/regenerate — regenerates new backup codes.
     */
    @Throws(AuthException::class, NetworkException::class)
    suspend fun regenerateBackupCodes(
        request: BackupCodesRegenerateRequest,
        bearerToken: String = SessionStore.firebaseToken
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            request.challengeToken?.let { put("challengeToken", it) }
            val array = JSONArray()
            request.hashedBackupCodes.forEach { array.put(it) }
            put("hashedBackupCodes", array)
        }.toString()

        val response = SecureHttpClient.postAuthenticated(
            "$BASE_URL/v1/auth/backup-codes/regenerate", body, bearerToken
        )

        when (response.statusCode) {
            200 -> Unit
            401 -> throw AuthException("Unauthorized (401)", "UNAUTHENTICATED")
            else -> throw NetworkException("Unexpected status: ${response.statusCode}")
        }
    }
}

// ---------------------------------------------------------------------------
// Typed exceptions — per API_Spec.md §5 Global Error Envelope
// ---------------------------------------------------------------------------

/** Thrown on API-level authentication/authorisation errors (4xx) */
class AuthException(message: String, val code: String) : Exception(message)

/** Thrown on network-level failures */
class NetworkException(message: String) : Exception(message)

/** Build config accessor — populated by build.gradle.kts productFlavors */
object BuildConfig {
    // In real builds this comes from BuildConfig generated by Gradle.
    // Placeholder here to allow compilation without product flavors configured.
    const val API_BASE_URL = "https://us-central1-securevault.cloudfunctions.net/api"
}

/**
 * Volatile in-memory token store.
 * The Firebase token is cached in RAM only — Technical_Requirements.md §8 hard constraint.
 * Never persisted to disk/SharedPreferences/SQLite.
 */
object SessionStore {
    @Volatile
    var firebaseToken: String = ""
        private set

    fun setToken(token: String) { firebaseToken = token }
    fun clearToken() { firebaseToken = "" }
}
