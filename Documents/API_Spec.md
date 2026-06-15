# SECUREVAULT - API SPECIFICATION DOCUMENT

---

## 1. REST API Summary Directory

| Method | Path | Description | Auth | Roles | Rate Limit |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **POST** | `/v1/auth/login` | Google Sign-in token verification and account registration. | None | All | 5/min/IP |
| **POST** | `/v1/auth/vmk` | Challenge Security Question and retrieve VMK. | JWT | All | 5/min/IP |
| **POST** | `/v1/auth/security-question/setup` | Register/Update security question & hashed answer. | JWT | All | 5/min/IP |
| **POST** | `/v1/auth/security-question/verify`| Validate security question answer for administrative tasks.| JWT | All | 5/min/IP |
| **POST** | `/v1/auth/lockout` | Sync user PIN lockout states. | JWT | All | 5/min/IP |
| **POST** | `/v1/auth/backup-codes/verify` | Verify a backup code to unlock local PIN reset. | JWT | All | 5/min/IP |
| **POST** | `/v1/auth/backup-codes/regenerate`| Regenerate new backup codes. | JWT | All | 5/min/IP |
| **GET** | `/v1/vault` | Retrieve all active credentials (sync retrieval fallback). | JWT | All | 30/min/User |
| **POST** | `/v1/vault` | Create a new credential entry. | JWT | All | 30/min/User |
| **PUT** | `/v1/vault/{id}` | Update an existing credential entry. | JWT | All | 30/min/User |
| **DELETE**| `/v1/vault/{id}` | Soft-delete a credential entry (move to Trash). | JWT | All | 30/min/User |
| **DELETE**| `/v1/vault/trash/empty` | Permanently purge all soft-deleted entries. | JWT | All | 30/min/User |
| **GET** | `/v1/categories` | Retrieve all custom and default categories. | JWT | All | 30/min/User |
| **POST** | `/v1/categories` | Create a new custom category. | JWT | All | 30/min/User |
| **PUT** | `/v1/categories/{id}` | Update a custom category. | JWT | All | 30/min/User |
| **DELETE**| `/v1/categories/{id}` | Delete a custom category. | JWT | All | 30/min/User |
| **GET** | `/v1/devices` | Retrieve lists of active device sessions. | JWT | All | 30/min/User |
| **DELETE**| `/v1/devices/{id}` | Revoke an active device session. | JWT | All | 30/min/User |
| **POST** | `/v1/sync` | Processes background Sync Queue operations. | JWT | All | 60/min/User |

---

## 2. Authentication & Administration Endpoints

### **POST /v1/auth/login**
* **Description**: Verifies Google OAuth JWT tokens returned by Android Credential Manager, registers user accounts, and returns custom Firebase session tokens.
* **Auth**: None
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 5 requests / 60 seconds / per IP
* **Idempotent**: No
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "googleIdToken": { "type": "string", "pattern": "^[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.?[A-Za-z0-9-_.+/=]*$" },
      "deviceId": { "type": "string", "minLength": 1 },
      "deviceName": { "type": "string", "minLength": 1 },
      "androidVersion": { "type": "string", "minLength": 1 }
    },
    "required": ["googleIdToken", "deviceId", "deviceName", "androidVersion"]
  }
  ```
* **Example Request**:
  ```json
  {
    "googleIdToken": "eyJhbGciOiJSUzI1NiIsImtpZCI...",
    "deviceId": "3589b2512f4581a0",
    "deviceName": "Pixel 7 Pro",
    "androidVersion": "14.0"
  }
  ```
* **Responses**:
  * **200 OK**: Account found. Returns session tokens.
    ```json
    {
      "firebaseToken": "eyJhbGciOiJSUzI1Ni...",
      "refreshToken": "7c94efb8-...",
      "registered": true
    }
    ```
  * **201 Created**: Account initialized. User must proceed to onboarding.
    ```json
    {
      "firebaseToken": "eyJhbGciOiJSUzI1Ni...",
      "refreshToken": "7c94efb8-...",
      "registered": false
    }
    ```
  * **400 Bad Request**: Invalid body parameters.
  * **401 Unauthorized**: Google token signature check failed.
  * **403 Forbidden**: Active device session limit (3) exceeded.
* **Side Effects**: Writes a new record to `device_sessions` table if successful.
* **DB Tables**: `users`, `device_sessions`

---

### **POST /v1/auth/vmk**
* **Description**: Retrieve the encrypted Vault Master Key (VMK) during onboarding, new device logins, or re-authentication.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 5 requests / 60 seconds / per IP
* **Idempotent**: Yes
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "securityQuestionAnswer": { "type": "string", "minLength": 1 }
    },
    "required": ["securityQuestionAnswer"]
  }
  ```
* **Example Request**:
  ```json
  {
    "securityQuestionAnswer": "london"
  }
  ```
* **Responses**:
  * **200 OK**: Hashed answer validated; returns KMS encrypted VMK.
    ```json
    {
      "encryptedVmk": "U2VjdXJlVmF1bHRLZXlQYXlsb2FkMTIzNDU2..."
    }
    ```
  * **401 Unauthorized**: Validation failed or verification token invalid.
* **Side Effects**: Logs successful VMK retrieval under system audit metrics.
* **DB Tables**: `users`

---

### **POST /v1/auth/security-question/setup**
* **Description**: Register or modify the security question ID and salted answer hash.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 5 requests / 60 seconds / per IP
* **Idempotent**: Yes
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "securityQuestionId": { "type": "string", "minLength": 1 },
      "securityAnswerHash": { "type": "string", "pattern": "^[a-fA-F0-9]{64}$" },
      "encryptedVmk": { "type": "string", "minLength": 1 },
      "challengeToken": { "type": "string" }
    },
    "required": ["securityQuestionId", "securityAnswerHash", "encryptedVmk"]
  }
  ```
* **Example Request**:
  ```json
  {
    "securityQuestionId": "question_birthplace_04",
    "securityAnswerHash": "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918",
    "encryptedVmk": "U2VjdXJlVmF1bHRLZXlQ...",
    "challengeToken": "verification_token_string"
  }
  ```
* **Responses**:
  * **200 OK**: Security question configuration updated.
    ```json
    {
      "status": "success",
      "message": "Security question successfully configured."
    }
    ```
  * **401 Unauthorized**: Token check failed or administrative challenge verification token (`challengeToken`) missing for updates.
* **Side Effects**: Overwrites `security_question_id`, `security_answer_hash`, and `encrypted_vmk` values.
* **DB Tables**: `users`

---

### **POST /v1/auth/security-question/verify**
* **Description**: Validate security answers before administrative changes or data exports.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 5 requests / 60 seconds / per IP
* **Idempotent**: No
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "securityAnswer": { "type": "string", "minLength": 1 }
    },
    "required": ["securityAnswer"]
  }
  ```
* **Example Request**:
  ```json
  {
    "securityAnswer": "london"
  }
  ```
* **Responses**:
  * **200 OK**: Verified. Returns a short-lived challenge token.
    ```json
    {
      "challengeToken": "jwt_challenge_token_string",
      "expiresAt": 1718274567000
    }
    ```
  * **401 Unauthorized**: Mismatch. Failed count updated.
* **Side Effects**: Increments failed verification counts.
* **DB Tables**: `users`

---

### **POST /v1/auth/lockout**
* **Description**: Sync failed PIN attempts and lockout timestamps to block bypasses.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 5 requests / 60 seconds / per IP
* **Idempotent**: Yes
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "pinFailedAttempts": { "type": "integer", "minimum": 0 },
      "pinLockoutUntil": { "type": "integer" }
    },
    "required": ["pinFailedAttempts", "pinLockoutUntil"]
  }
  ```
* **Example Request**:
  ```json
  {
    "pinFailedAttempts": 11,
    "pinLockoutUntil": 1718281767000
  }
  ```
* **Responses**:
  * **200 OK**: Lockout state synced.
  * **401 Unauthorized**: Authentication failed.
* **Side Effects**: Updates account lockout columns in the database.
* **DB Tables**: `users`

---

### **POST /v1/auth/backup-codes/verify**
* **Description**: Verify a backup code to unlock local PIN reset.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 5 requests / 60 seconds / per IP
* **Idempotent**: No
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "backupCode": { "type": "string", "pattern": "^[A-Z0-9]{4}-[A-Z0-9]{4}$" }
    },
    "required": ["backupCode"]
  }
  ```
* **Example Request**:
  ```json
  {
    "backupCode": "AB7K-XP92"
  }
  ```
* **Responses**:
  * **200 OK**: Verified. Aligns PIN reset challenge state.
    ```json
    {
      "status": "success",
      "challengeToken": "jwt_challenge_token_string"
    }
    ```
  * **401 Unauthorized**: Invalid backup code.
* **Side Effects**: Marks verified backup code as used (deleted).
* **DB Tables**: `users`

---

### **POST /v1/auth/backup-codes/regenerate**
* **Description**: Generate new backup codes (requires challenge token verification).
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 5 requests / 60 seconds / per IP
* **Idempotent**: Yes
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "challengeToken": { "type": "string" }
    },
    "required": ["challengeToken"]
  }
  ```
* **Example Request**:
  ```json
  {
    "challengeToken": "jwt_challenge_token_string"
  }
  ```
* **Responses**:
  * **200 OK**: Previous codes invalidated. Returns two new plaintext backup codes.
    ```json
    {
      "backupCodes": ["M4QR-LN88", "XP92-AB7K"]
    }
    ```
  * **401 Unauthorized**: Challenge token invalid or missing.
* **Side Effects**: Overwrites the backup code hashes array.
* **DB Tables**: `users`

---

## 3. Password Vault & Categories Endpoints

### **GET /v1/vault**
* **Description**: Retrieve all active credentials (for sync recovery).
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Responses**:
  * **200 OK**: Returns list of password objects.
    ```json
    [
      {
        "id": "entry-uuid-1",
        "name": "Github",
        "usernameEmail": "devuser",
        "encryptedPassword": "AES_GCM_CIPHERTEXT...",
        "websiteUrl": "https://github.com",
        "categoryId": "cat_personal_id",
        "favorite": 1,
        "createdAt": 1718274567000,
        "updatedAt": 1718274567000,
        "passwordStrength": "STRONG",
        "version": 1
      }
    ]
    ```
* **Side Effects**: None.
* **DB Tables**: `vault_passwords`

---

### **POST /v1/vault**
* **Description**: Create a new password entry.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: No
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "id": { "type": "string", "format": "uuid" },
      "name": { "type": "string", "minLength": 1 },
      "usernameEmail": { "type": "string", "minLength": 1 },
      "encryptedPassword": { "type": "string", "minLength": 1 },
      "websiteUrl": { "type": "string", "format": "uri", "nullable": true },
      "categoryId": { "type": "string", "nullable": true },
      "favorite": { "type": "integer", "enum": [0, 1] },
      "passwordStrength": { "type": "string", "enum": ["WEAK", "MEDIUM", "STRONG"] }
    },
    "required": ["id", "name", "usernameEmail", "encryptedPassword", "favorite", "passwordStrength"]
  }
  ```
* **Example Request**:
  ```json
  {
    "id": "060d4b8f-7c98-4e8c-8f12-32b4b4b4b4b4",
    "name": "Github",
    "usernameEmail": "devuser",
    "encryptedPassword": "AES_GCM_CIPHERTEXT...",
    "websiteUrl": "https://github.com",
    "categoryId": "cat_personal_id",
    "favorite": 1,
    "passwordStrength": "STRONG"
  }
  ```
* **Responses**:
  * **201 Created**: Password entry saved.
* **Side Effects**: Inserts record into `vault_passwords` table.
* **DB Tables**: `vault_passwords`

---

### **PUT /v1/vault/{id}**
* **Description**: Update an existing credential entry.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Request Body (JSON Schema)**: Same as `POST /v1/vault` (excluding `id`).
* **Responses**:
  * **200 OK**: Entry updated.
  * **404 Not Found**: Target entry ID does not exist or matches another user `uid`.
* **Side Effects**: Modifies values and increments `version` count.
* **DB Tables**: `vault_passwords`

---

### **DELETE /v1/vault/{id}**
* **Description**: Soft-delete a credential entry (moves it to the Trash).
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Responses**:
  * **200 OK**: Soft-delete timestamp committed.
* **Side Effects**: Sets `deleted_at` timestamp.
* **DB Tables**: `vault_passwords`

---

### **DELETE /v1/vault/trash/empty**
* **Description**: Permanently purge all soft-deleted entries.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Responses**:
  * **200 OK**: Soft-deleted entries purged.
* **Side Effects**: Deletes records from `vault_passwords` and associated `password_history` tables.
* **DB Tables**: `vault_passwords`, `password_history`

---

### **GET /v1/categories**
* **Description**: Retrieve custom and default categories.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Responses**:
  * **200 OK**: Returns category array list.
* **Side Effects**: None.
* **DB Tables**: `categories`

---

### **POST /v1/categories**
* **Description**: Create a custom category.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: No
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "id": { "type": "string", "format": "uuid" },
      "name": { "type": "string", "minLength": 1, "maxLength": 64 }
    },
    "required": ["id", "name"]
  }
  ```
* **Example Request**:
  ```json
  {
    "id": "category-uuid-03",
    "name": "University"
  }
  ```
* **Responses**:
  * **201 Created**: Custom category saved.
* **Side Effects**: Inserts category record.
* **DB Tables**: `categories`

---

### **PUT /v1/categories/{id}**
* **Description**: Modify a custom category's name.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Request Body (JSON Schema)**: Same as `POST /v1/categories` (excluding `id`).
* **Responses**:
  * **200 OK**: Category modified.
* **Side Effects**: Updates name column.
* **DB Tables**: `categories`

---

### **DELETE /v1/categories/{id}**
* **Description**: Delete a custom category.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Responses**:
  * **200 OK**: Category removed.
* **Side Effects**: Deletes category; resets associated password entries' `category_id` to NULL.
* **DB Tables**: `categories`, `vault_passwords`

---

## 4. Sync & Device Session Endpoints

### **GET /v1/devices**
* **Description**: Retrieve active device sessions.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Responses**:
  * **200 OK**: Returns active session lists.
    ```json
    [
      {
        "id": "3589b2512f4581a0",
        "deviceName": "Pixel 7 Pro",
        "androidVersion": "14.0",
        "lastActiveTime": 1718274567000
      }
    ]
    ```
* **Side Effects**: None.
* **DB Tables**: `device_sessions`

---

### **DELETE /v1/devices/{id}**
* **Description**: Revoke a device session (logs that device out).
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 30 requests / 60 seconds / per user
* **Idempotent**: Yes
* **Responses**:
  * **200 OK**: Session revoked.
* **Side Effects**: Deletes device session record.
* **DB Tables**: `device_sessions`

---

### **POST /v1/sync**
* **Description**: Processes synchronization queue payloads and returns remote updates.
* **Auth**: Required (Bearer Firebase JWT)
* **Roles**: Students, Professionals, Developers, General Users
* **Rate Limit**: 60 requests / 60 seconds / per user
* **Idempotent**: No
* **Request Body (JSON Schema)**:
  ```json
  {
    "type": "object",
    "properties": {
      "syncActions": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "id": { "type": "string", "format": "uuid" },
            "transactionType": { "type": "string", "enum": ["INSERT", "UPDATE", "DELETE"] },
            "tableName": { "type": "string", "enum": ["vault_passwords", "categories"] },
            "recordId": { "type": "string" },
            "payloadJson": { "type": "string" },
            "version": { "type": "integer" },
            "updatedAt": { "type": "integer" }
          },
          "required": ["id", "transactionType", "tableName", "recordId", "payloadJson", "version", "updatedAt"]
        }
      },
      "lastSyncTime": { "type": "integer" }
    },
    "required": ["syncActions", "lastSyncTime"]
  }
  ```
* **Example Request**:
  ```json
  {
    "syncActions": [
      {
        "id": "queue-uuid-01",
        "transactionType": "UPDATE",
        "tableName": "vault_passwords",
        "recordId": "entry-uuid-1",
        "payloadJson": "{\"name\":\"Github\",\"usernameEmail\":\"devuser\",\"encryptedPassword\":\"AES_GCM_CIPHERTEXT...\"}",
        "version": 2,
        "updatedAt": 1718278167000
      }
    ],
    "lastSyncTime": 1718274567000
  }
  ```
* **Responses**:
  * **200 OK**: Sync processed. Returns resolved actions and remote updates.
    ```json
    {
      "resolvedActions": ["queue-uuid-01"],
      "remoteUpdates": [
        {
          "tableName": "vault_passwords",
          "recordId": "entry-uuid-99",
          "transactionType": "INSERT",
          "payloadJson": "{\"name\":\"Slack\",\"usernameEmail\":\"devuser\",\"encryptedPassword\":\"AES_GCM_CIPHERTEXT...\"}",
          "version": 1,
          "updatedAt": 1718276167000
        }
      ],
      "syncTime": 1718279967000
    }
    ```
* **Side Effects**: Processes sync updates, conflicts, and writes record transaction histories.
* **DB Tables**: `vault_passwords`, `categories`, `password_history`, `sync_queue`

---

## 5. Global API Protocols

### Global Error Envelope
Every error response returned by SecureVault APIs conforms to the following JSON structure:
```json
{
  "error": {
    "code": "ERROR_CODE_STRING",
    "message": "Human-readable description of error.",
    "details": {},
    "timestamp": 1718274567000
  }
}
```
* **Common Error Codes**:
  * `INVALID_ARGUMENT` (400): Body parameter validations failed.
  * `UNAUTHENTICATED` (401): JWT session token missing, signature invalid, or expired.
  * `SESSION_LIMIT_EXCEEDED` (403): User already has 3 active device sessions.
  * `NOT_FOUND` (404): Target database row does not exist or belongs to another user.
  * `RATE_LIMIT_EXCEEDED` (429): Rate limits breached.
  * `SERVICE_UNAVAILABLE` (503): Database connection timed out.

---

### Authentication Token Lifecycle
* **1. Obtain Token**: Exchange Google ID Token for Firebase session tokens via `/v1/auth/login`.
* **2. Use Token**: Include the retrieved `firebaseToken` in the headers:
  `Authorization: Bearer <firebaseToken>`
* **3. Refresh Token**: ID Tokens expire in 1 hour. Refresh them using the Firebase Auth Refresh Token:
  `POST https://securetoken.googleapis.com/v1/token?key=<API_KEY>`
  * *Request Body*: `grant_type=refresh_token&refresh_token=<refreshToken>`
  * *Response*: Returns a new ID Token and refresh token.
* **4. Revoke Token**: Explicitly invalidate refresh tokens on the backend when logging out:
  `DELETE /v1/devices/{current_device_id}`

---

### Pagination Contract
* **Mechanism**: Cursor-based pagination.
* **Query Parameters**:
  * `limit`: Integer (default 50, maximum allowed 100).
  * `cursor`: String (identifier for page marker offsets).
* **Response Envelope**:
  ```json
  {
    "data": [],
    "paging": {
      "nextCursor": "page_cursor_string_or_null",
      "hasMore": false
    }
  }
  ```

---

### Versioning Strategy
* **API Versioning**: Enforced via URI path routing prefix (`/v1`).
* **Implementation Rules**: Major version increments (e.g. `/v2`) will be introduced if breaking changes occur (such as structural vault payload changes). Minor backward-compatible changes (adding fields) must be implemented on the active `/v1` endpoint.
