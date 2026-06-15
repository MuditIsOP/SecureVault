# SECUREVAULT - SECURITY REQUIREMENTS DOCUMENT (SRD)

---

## 1. Threat Model

This threat model outlines potential threat actors, targeted user profiles, high-value assets, and STRIDE risk metrics.

### Adversary List
1. **Malicious Local Actor (Script Kiddies / Opportunists)**:
   * *Objective*: Exploit unlocked physical devices or basic logical bypasses to view saved credentials.
   * *Target*: Students and General smartphone users who might leave their devices unattended in public spaces.
2. **Co-workers / Competitors (Insider Threat)**:
   * *Objective*: Expose business credentials or customer databases from employee vaults.
   * *Target*: Professionals managing enterprise logins.
3. **Targeted System Hackers (Network / API Adversaries)**:
   * *Objective*: Execute man-in-the-middle (MitM) attacks, token replay exploits, or target backend Firebase endpoints to decrypt VMK payloads.
   * *Target*: High-value vaults belonging to Developers or system administrators.
4. **Automated Scrapers & Botnets**:
   * *Objective*: Query authentication gateway endpoints using credential stuffing or brute-force to hijack active sessions.
   * *Target*: All registered accounts.

### High-Value Targets
1. **Vault Master Key (VMK)**: Critical. Decrypts all passwords and history. Compromise exposes the entire vault.
2. **Cloud Database (MongoDB Atlas) Contents**: High. Contains encrypted credentials, user configuration, category data, and active device details.
3. **Session Access Tokens (Firebase JWTs)**: High. Compromise allows API access impersonating the user.
4. **Local Room SQLite Database (SQLCipher File)**: High. Contains all local cached credentials. Secure local encryption key is required.

---

### STRIDE Threat Matrices

#### Component 1: Mobile Client (Android Application)
| Threat | Description | Mitigation | Risk Rating |
| :--- | :--- | :--- | :---: |
| **Spoofing** | Malware impersonating the SecureVault UI to capture user PIN or biometrics. | [MUST] Enforce system-provided `BiometricPrompt` window overlays; enforce Material 3 custom styles. | **HIGH** |
| **Tampering** | Modifying Room SQLite cache files on a rooted device. | [MUST] Key SQLite using SQLCipher with a key stored in Android Keystore; [MUST] Alert user if root is found. | **HIGH** |
| **Repudiation** | Denying exporting passwords via CSV/PDF. | [MUST] Force security question challenge and log transaction success locally/remote. | **MEDIUM** |
| **Info Leak** | Malicious background applications sniffing clipboard credentials or taking screen captures. | [MUST] Clear clipboard after 30 seconds; [MUST] Enforce `FLAG_SECURE` to block screenshots application-wide. | **CRITICAL** |
| **DoS** | Intentional database locking or cache corruption. | [MUST] Handle Room initialization errors gracefully and fallback to database rebuild if corrupted. | **LOW** |
| **Elev. Priv** | Overriding biometric locks using mock Android Keystore environments. | [MUST] Invalidate keys if a new fingerprint is enrolled on the Android device. | **HIGH** |

#### Component 2: API Gateway (Firebase Cloud Functions) & Database
| Threat | Description | Mitigation | Risk Rating |
| :--- | :--- | :--- | :---: |
| **Spoofing** | Spoofing request packets to impersonate authenticated clients. | [MUST] Enforce verification of Firebase JWT signatures and project bounds on every request. | **CRITICAL** |
| **Tampering** | Overwriting database records during sync requests. | [MUST] Verify client version counters and updated dates via backend API before accepting changes. | **HIGH** |
| **Repudiation** | Unauthorized deletion of database records without access history tracking. | [MUST] Maintain write logs in a `Sync Logs` collection tracking account operations. | **MEDIUM** |
| **Info Leak** | Leakage of decrypted VMKs during transit to the client. | [MUST] Enforce TLS 1.3 minimums for all REST queries; [MUST] Encrypt VMK at rest using Cloud KMS keys. | **CRITICAL** |
| **DoS** | Flooding API gateway with requests to exceed Firebase execution limits. | [MUST] Enforce IP-based and user-based rate limiting on all endpoints. | **HIGH** |
| **Elev. Priv** | Altering request headers to access another user's vault database. | [MUST] Enforce MongoDB query bounds matching the authenticated Google User ID (`uid`) claim. | **CRITICAL** |

---

### Risk Ratings Justifications
* **CRITICAL**: Threat leads directly to plain text exposure of the VMK or multiple decrypted passwords (e.g., Clipboard leaks, JWT validation bypass, transit snooping).
* **HIGH**: Threat allows local data extraction or logical session takeover (e.g., SQLCipher key extraction, biometric spoofing, brute-forcing auth endpoints).
* **MEDIUM**: Threat results in loss of data consistency, authorization escalation attempts, or unlogged administrative actions.
* **LOW**: Threat causes temporary service degradation or UI issues with no potential for database compromise.

---

## 2. Authentication and Session Management

### 1. Mechanism and Token Lifecycle
* **Mechanism**: [MUST] Authenticate sessions via Firebase Authentication Google Sign-In. The client receives a Firebase ID Token (JWT) and a Refresh Token.
* **ID Token Lifespan**: [MUST] Set Firebase ID Token (Access) validity to exactly 1 hour.
* **Refresh Token Lifespan**: [MUST] Set Refresh Token validity to a maximum of 30 days.
* **Revocation**: [MUST] Immediately invalidate refresh tokens on the backend when the user clicks "Logout" or "Switch Account".

### 2. Session Expiry Windows

| User Role | Inactivity Lockout Window | MFA Policy |
| :--- | :--- | :--- |
| **Students** | 5 Minutes Inactivity | **OPTIONAL** (Google MFA) |
| **Professionals** | 5 Minutes Inactivity | **REQUIRED** (Google MFA enforced) |
| **Developers** | 5 Minutes Inactivity | **REQUIRED** (Google MFA enforced) |
| **General Users** | 5 Minutes Inactivity | **OPTIONAL** (Google MFA) |

* **Lockout Enforcement**: [MUST] The app must monitor background transitions and lock the screen immediately if backgrounded for more than 5 minutes.
* **System Lock Event**: [MUST] Lock the app immediately if the device is locked, rebooted, or the app process is terminated.

### 3. Device Binding Strategy
* **Device ID Binding**: [MUST] Bind the Vault session to a hardware-specific identifier (`Settings.Secure.ANDROID_ID`) during the initial onboarding.
* **Keystore Enclosure**: [MUST] Bind the local session keys using Android Keystore keys generated with biometric or PIN validation dependencies.
* **Device Limit Verification**: [MUST] Validate on every Firebase Function request that the client device identifier matches one of the 3 active slots in the user's `Device Sessions` collection.

---

## 3. Authorisation

### Server-side Enforcements
* **Zero Client-Side Trust**: [MUST] Treat client-side screens as presentation layers only. The backend API must validate all read/write authorization rules on every request.
* **Usecase Binding**: [MUST] Enforce that every MongoDB query restricts actions using the Firebase JWT user ID (`auth.uid`) claim. A client must never pass a target user identifier in the body to request vault retrieval.
* **Scope Limits**: [MUST] Ensure that VMK requests are only authorized immediately following a successful Google Authentication event combined with a verified Security Question verification token.

### Escalation Behavior
* **Threat Alerting**: [MUST] If a user requests a database operation matching another user's identifier, the API gateway must:
  1. Log the escalation event under `escalation_attempts` logging.
  2. Send a high-priority system alert to database administrators.
  3. Immediately invalidate the user's active Refresh Token on the backend.
  4. Return an HTTP `403 Forbidden` response to the client.

---

## 4. Data Security

### Classification Table

| Data Type | Classification | Encryption Standard | Justification |
| :--- | :--- | :--- | :--- |
| **VMK (Vault Master Key)** | **RESTRICTED** | AES-256-GCM (Cloud KMS) | Master decryption key. |
| **User Passwords** | **RESTRICTED** | AES-256-GCM (VMK-encrypted) | Core credential payload. |
| **Password History** | **RESTRICTED** | AES-256-GCM (VMK-encrypted) | Prior credential payloads. |
| **Backup Codes** | **RESTRICTED** | Hashed (Argon2 / SHA-256) | Recovery access vectors. |
| **Security Answer** | **RESTRICTED** | Hashed (PBKDF2-SHA256) | Re-auth verification factor. |
| **Google Profile Data** | **INTERNAL** | Table-Level (MongoDB Atlas) | User identity mapping. |
| **Category Names** | **CONFIDENTIAL** | Table-Level (MongoDB Atlas) | Personal labeling indicators. |
| **Device Session Lists** | **INTERNAL** | Table-Level (MongoDB Atlas) | Device authorization metrics. |
| **Sync Logs** | **INTERNAL** | Table-Level (MongoDB Atlas) | Transaction history metrics. |

### Encryption At Rest
* **Field-Level Encryption**: [MUST] Encrypt the `password` field in `vault_passwords` and `password_history` collections using `AES-GCM-256` on the client device prior to synchronization.
* **Local Database Encryption**: [MUST] Encrypt the entire Room SQLite database file using `SQLCipher` (AES-256-CBC mode).
* **Storage Keys**: [MUST] Store the SQLCipher database key in the hardware-backed `Android Keystore`.

### Encryption In Transit
* **TLS Minimum**: [MUST] Require `TLS 1.3` (with `TLS 1.2` as absolute fallback) for all API communication.
* **Certificate Pinning**: [MUST] Enforce Certificate Pinning for client connections to the Firebase Functions domain using OkHttp's `CertificatePinner` to prevent MitM attacks in public networks.

### PII Management
* **PII Inventory**: Google Profile Name, Email Address, and Profile Image URL.
* **Retention Policy**: [MUST] Retain PII only during active account status.
* **Deletion Procedure**: [MUST] Tapping "Delete Account" in settings must execute a transaction on the backend that purges all passwords, history, backup codes, security questions, device sessions, and user records from MongoDB Atlas and Firebase Auth.

---

## 5. Input Validation

### Input Type Validation Rules
* **String Inputs (Names, Categories)**: [MUST] Sanitise using regex matching `^[a-zA-Z0-9\s.\-\_]+$` to block HTML tags or SQL special characters. Length limits: min 1, max 64.
* **Email Inputs**: [MUST] Validate against RFC 5322 regex validation rules. Maximum length: 128 characters.
* **Website URL**: [MUST] Validate that string starts with `http://` or `https://` and compiles with Java `android.util.Patterns.WEB_URL` regex.
* **6-Digit PIN**: [MUST] Validate that input is exactly 6 digits matching `^[0-9]{6}$`.
* **Security Answers**: [MUST] Strip leading/trailing whitespaces and convert characters to lowercase before generating hashes.

### Attack Surface
* **Local Client**: User credentials forms (Add Password, Edit, Search Query bar).
* **Autofill Hook**: App domain strings sent by Android system targets.
* **API Endpoints**: Request headers (Authorization bearer JWT tokens) and Request bodies (JSON payloads containing syncing logs, device data, and encrypted vault entries).

---

## 6. API Security

### Rate Limiting
* **Sync/Read Endpoints**: [MUST] Limit requests to 60 requests per minute per IP, and 30 requests per minute per user account.
* **Authentication/Re-auth Endpoints**: [MUST] Limit requests to 5 requests per minute per IP.
* **Standard Limits**: [SHOULD] Return HTTP `429 Too Many Requests` responses when limit is exceeded.

### Brute Force Protection
* **Adaptive Delay Challenge**: [MUST] If the backend receives consecutive failed Security Question validation attempts, it must introduce an adaptive delay before processing subsequent requests (1st: instant, 2nd: 2s, 3rd: 5s, 4th+: 30s).
* **Cloud Enforced Block**: [MUST] Lock the user account session for 2 hours if consecutive failed PIN entries reach 11, synced across all devices via the database.

### Response Sanitisation
* **Sensitive Exclusions**: [MUST] Never return user VMKs in plaintext.
* **Hashed Exclusions**: [MUST] Never include the Security Question Answer hashes or Backup Code hashes in API responses.
* **Role Sanitisation**: [MUST] Responses sent to the Android client must only contain data fields matching the requesting user's `uid` (verified from JWT validation).

---

## 7. Dependency Security

### Scan Cadence
* **Static Analysis**: [MUST] Scan code repositories for hardcoded secrets and dependency issues on every pull request using Github Actions.
* **Dependency Scans**: [MUST] Run weekly Snyk / OWASP dependency scans on `develop` and `master` branches.

### CVE Response SLA
If a vulnerability is discovered in an active library or third-party dependency, developers must release a patch within the following windows:
* **CRITICAL (CVSS 9.0 - 10.0)**: [MUST] Release patch within **24 hours**.
* **HIGH (CVSS 7.0 - 8.9)**: [MUST] Release patch within **72 hours**.
* **MEDIUM (CVSS 4.0 - 6.9)**: [MUST] Release patch within **14 days**.
* **LOW (CVSS 0.1 - 3.9)**: [SHOULD] Release patch within **30 days**.

---

## 8. Logging and Incident Response

### Mandatory Logged Events
[MUST] Log the following events with timestamps, device details, and user identifiers:
* Successful and failed login attempts.
* Security question verification successes and failures.
* Password exports (PDF or CSV file generation).
* Account deletion transactions.
* Device limits reached / device removals.
* Insecure environments detected (root/debugging warnings bypassed).

### Prohibited Logged Data
[MUST NOT] log the following fields under any circumstances (including debug files):
* Plaintext credentials (passwords, usernames, security answers).
* Plaintext VMKs.
* Client-side Security Question Answer hashes.
* Firebase Access/Refresh token strings.
* Personal PII details except user `uid`.

### Alerting Thresholds
[MUST] Trigger system administrator alerts when:
* A single user account fails PIN verification 10 times consecutively in <10 minutes.
* A single IP address makes >100 requests in a 1-minute window.
* A user profile attempts to read data from a different database workspace (`escalation_attempt`).

### Breach Notification
* **Compliance Duty**: [MUST] In the event of a database breach or compromised cloud infrastructure, SecureVault must notify all affected users via email within **72 hours** of breach confirmation, outlining mitigation instructions (e.g. rotating passwords).

---

## 9. Technology-Specific Vulnerability Protections

### 1. SQLCipher / SQLite Protection
* **Key Generation**: [MUST] Generate database keys with 256 bits of entropy.
* **PRAGMA Settings**: [MUST] Disable database logging (`PRAGMA journal_mode = OFF` or `PRAGMA secure_delete = ON`) to prevent plaintext password remnants from residing in temporary system directories.

### 2. WebView Protection
* **JS Execution**: [MUST] Disable JavaScript in WebViews unless required for login flows.
* **Access Control**: [MUST] Set `setAllowFileAccess(false)` and `setAllowContentAccess(false)` to prevent local directory traversal attacks.

### 3. Firebase JWT Protection
* **Signature checks**: [MUST] Validate JWT signature keys on the Firebase gateway using Google public certificates.
* **Replay Block**: [MUST] Enforce JWT `nbf` (not before) and `exp` (expiration) validation to reject replayed tokens.

### 4. Android Clipboard Protection
* **Erase Queue**: [MUST] Ensure that password copying to the clipboard launches a foreground Android Service that runs a handler thread to clear the clipboard after 30 seconds, even if the app moves to the background.
