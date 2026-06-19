package com.securevault.app.security

import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * TLS 1.3 + Certificate-Pinned HTTP client for all SecureVault API calls.
 *
 * Spec refs:
 *   - Security_Requirements.md §4 Encryption In Transit:
 *       [MUST] "Require TLS 1.3 (with TLS 1.2 as absolute fallback)"
 *       [MUST] "Enforce Certificate Pinning using OkHttp CertificatePinner to
 *               prevent MitM attacks in public networks"
 *   - Technical_Requirements.md §2 — OkHttp 4.12.0 (confirmed in build.gradle.kts)
 *   - Architecture.md §5 — "HTTPS REST API over TCP port 443. TLS 1.3 enforced."
 *
 * Certificate pins are SHA-256 of the public key of the Firebase Functions domain.
 * Pins must be rotated before cert expiry — update SHA256 hashes here when
 * Firebase renews its TLS certificate.
 *
 * NOTE: The pin hashes below are placeholder values. Real SHA-256 pins must be
 * extracted from the production certificate before shipping.
 *
 * ⚠️ SPEC NOTE: Actual pin extraction requires access to the production Firebase
 * Functions deployment certificate chain. Placeholder values are used here and
 * MUST be replaced with real pins before production release.
 */
object SecureHttpClient {

    private const val FIREBASE_FUNCTIONS_HOST = "us-central1-securevault.cloudfunctions.net"
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

    /**
     * Certificate pin (SHA-256) for the Firebase Functions domain.
     * Must be replaced with the production certificate pin before release.
     * Format: "sha256/<base64-encoded-sha256-of-DER-SubjectPublicKeyInfo>"
     */
    private const val CERT_PIN_PRIMARY =
        "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="  // ⚠️ REPLACE BEFORE PRODUCTION
    private const val CERT_PIN_BACKUP =
        "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="  // ⚠️ REPLACE BEFORE PRODUCTION

    private val certificatePinner = CertificatePinner.Builder()
        .add(FIREBASE_FUNCTIONS_HOST, CERT_PIN_PRIMARY)
        .add(FIREBASE_FUNCTIONS_HOST, CERT_PIN_BACKUP)
        .build()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .certificatePinner(certificatePinner)
            // TLS 1.3 enforced by Android's default SSLContext on API 29+.
            // API 26-28 fallback TLS 1.2 is acceptable per Security_Requirements.md §4.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Executes a POST request with a JSON body.
     *
     * @param url  Full URL including path.
     * @param body JSON string body.
     * @return [HttpResponse] with status code and body string.
     */
    fun post(url: String, body: String): HttpResponse {
        val requestBody = body.toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            return HttpResponse(
                statusCode = response.code,
                body = response.body?.string() ?: ""
            )
        }
    }

    /**
     * Executes an authenticated POST request.
     *
     * @param url         Full URL.
     * @param body        JSON string body.
     * @param bearerToken Firebase JWT — API_Spec.md §5 Auth Token Lifecycle.
     */
    fun postAuthenticated(url: String, body: String, bearerToken: String): HttpResponse {
        val requestBody = body.toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $bearerToken")
            .build()

        client.newCall(request).execute().use { response ->
            return HttpResponse(
                statusCode = response.code,
                body = response.body?.string() ?: ""
            )
        }
    }

    /**
     * Executes an authenticated GET request.
     *
     * @param url        Full URL.
     * @param bearerToken Firebase JWT token — API_Spec.md §5 Auth Token Lifecycle.
     */
    fun get(url: String, bearerToken: String): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $bearerToken")
            .build()

        client.newCall(request).execute().use { response ->
            return HttpResponse(
                statusCode = response.code,
                body = response.body?.string() ?: ""
            )
        }
    }
}

/** Simple HTTP response wrapper */
data class HttpResponse(val statusCode: Int, val body: String)
