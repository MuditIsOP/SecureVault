# SECUREVAULT - PERMISSIONS MATRIX DOCUMENT

---

## 1. Role Definitions

In SecureVault, "roles" represent the profiles of target users using the application. Since SecureVault is a personal password manager where each vault is strictly isolated per Google Account, these roles do not represent hierarchical RBAC (Role-Based Access Control) tiers within a single vault. Instead, they define the security configuration profiles, features, and capabilities enabled for each user type.

### 1. Students
* **Description**: General student profile focused on organizing educational and personal credentials.
* **Who Holds It**: Authenticated users identified as student profile consumers.
* **How Assigned**: Assigned automatically on onboarding based on student email domain verification or manual profile selection.
* **Coexistence**: Yes, can coexist with General Smartphone Users.

### 2. Professionals
* **Description**: Enterprise profile requiring stricter local security, compliance exports, and clipboard protection.
* **Who Holds It**: Business users managing work-related logins.
* **How Assigned**: Assigned automatically on onboarding by linking a corporate email address or by manually selecting the enterprise profile.
* **Coexistence**: Yes, can coexist with General Smartphone Users and Developers.

### 3. Developers
* **Description**: Technical profile requiring advanced generator features and warning indicators for modified local environments.
* **Who Holds It**: Software engineers, system administrators, and power users.
* **How Assigned**: Assigned manually in application settings.
* **Coexistence**: Yes, can coexist with Professionals and General Smartphone Users.

### 4. General Smartphone Users
* **Description**: Standard consumer profile focusing on simple, reliable credential storage and synchronization.
* **Who Holds It**: Default consumer profile users.
* **How Assigned**: Assigned automatically by default upon registration if no other profile is selected.
* **Coexistence**: Yes, can coexist with Students, Professionals, and Developers.

---

## 2. Permission Matrix

| Resource · Action | Students | Professionals | Developers | General Users |
| :--- | :---: | :---: | :---: | :---: |
| **Credentials · Create** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Credentials · View** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Credentials · Edit** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Credentials · Copy Username/URL** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Credentials · Copy Password** | ✅ Allowed | ⚠️ Conditional (C1) | ✅ Allowed | ✅ Allowed |
| **Credentials · Soft-Delete (Trash)** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Credentials · Restore from Trash** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Credentials · Permanent Delete** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Password History · View** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Backup Codes · View** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Backup Codes · Use (PIN Reset)** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Backup Codes · Regenerate** | ⚠️ Conditional (C2) | ⚠️ Conditional (C2) | ⚠️ Conditional (C2) | ⚠️ Conditional (C2) |
| **Security Question · Setup** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Security Question · Verify** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Security Question · Edit** | ⚠️ Conditional (C2) | ⚠️ Conditional (C2) | ⚠️ Conditional (C2) | ⚠️ Conditional (C2) |
| **Categories · CRUD & Assign** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Vault Master Key · Retrieve** | ⚠️ Conditional (C3) | ⚠️ Conditional (C3) | ⚠️ Conditional (C3) | ⚠️ Conditional (C3) |
| **Vault Master Key · Local Cache** | ⚠️ Conditional (C4) | ⚠️ Conditional (C4) | ⚠️ Conditional (C4) | ⚠️ Conditional (C4) |
| **Active Devices · View List** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Active Devices · Remove Session** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Plaintext Export · Generate PDF** | ⚠️ Conditional (C5) | ⚠️ Conditional (C5) | ⚠️ Conditional (C5) | ⚠️ Conditional (C5) |
| **Plaintext Export · Generate CSV** | ⚠️ Conditional (C6) | ⚠️ Conditional (C6) | ⚠️ Conditional (C6) | ⚠️ Conditional (C6) |
| **Password Health · View Dashboard** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |
| **Password Health · Reuse Alerts** | ✅ Allowed | ✅ Allowed | ✅ Allowed | ✅ Allowed |

---

## 3. Conditional Permission Definitions

### ⚠️ C1: Copy Password (Professionals Profile)
* **Condition**: Copying the plaintext password to the system clipboard is permitted on the condition that the clipboard cache is automatically erased after exactly 30 seconds.
* **Enforcement Layer**: Client App (implemented via a background Android Service handler thread running independently of UI transitions).

### ⚠️ C2: Administrative Changes (Backup Codes & Security Questions)
* **Condition**: Modifying security questions or regenerating backup codes is permitted only after verifying the user's identity through the Security Question Challenge.
* **Enforcement Layer**: Client App UI + API Gateway Service (the client requests a signed challenge token, verifies the answer on the server, and uses the verification token to commit changes).

### ⚠️ C3: VMK Retrieval from Cloud Database
* **Condition**: The client is allowed to retrieve the encrypted VMK from the server only during initial authentication or new device login, contingent on passing Google OAuth validation AND verifying the user's hashed Security Question Answer.
* **Enforcement Layer**: API Gateway (Firebase Cloud Functions checking JWT signatures) + Database (MongoDB checking User ID and challenge confirmation state).

### ⚠️ C4: VMK Local Cache
* **Condition**: Caching the VMK locally on the client is allowed only in volatile memory (RAM) and stored encrypted within the hardware-backed Android Keystore for the duration of the active session. Key must never be committed to SQLite or SharedPreferences.
* **Enforcement Layer**: Client App (Android Keystore API).

### ⚠️ C5: Generate PDF Export
* **Condition**: Exporting credentials to a PDF is allowed only after:
  1. Passing the Security Question challenge.
  2. Inputting a custom PDF protection password (PIN).
* **Enforcement Layer**: Client App UI + API Gateway (challenge verification token check) + Local native PDF engine.

### ⚠️ C6: Generate CSV Export
* **Condition**: Exporting credentials to a CSV is allowed only after passing the Security Question challenge and explicitly acknowledging a full-screen plaintext warning screen.
* **Enforcement Layer**: Client App UI + API Gateway (challenge verification token check).

---

## 4. Ownership Rules

### Creator Ownership
* **Owner Assignment**: When a credential, category, device session, or sync log record is created, the backend automatically binds the record's owner field to the Google account user ID (`uid`) verified from the request's Firebase Authentication JWT.
* **Vault Isolation**: Cross-account access is strictly blocked; a user can only query or write records containing their verified `uid`.

### Transfer Conditions
* **Transfer Policy**: Vault ownership transfers are strictly forbidden. Credentials cannot be migrated or transferred between different Google Accounts.

### Shared Ownership
* **Sharing Policy**: Vault sharing and shared database folders are completely out of scope for Phase 1. Database records are strictly owned and accessed by a single unique Google Account identifier.

---

## 5. Role Assignment and Escalation

### Assignment and Revocation
* **Assignment Mechanism**: Profile configurations (Student, Professional, Developer, General) are assigned to the user account during the onboarding setup and can be toggled by the user in the Settings module. Since each Google account accesses a fully isolated personal database, there are no admin roles that assign permissions to other users.
* **Access Revocation**: User access is revoked globally when the user logs out, deletes their account, or if the backend detects >10 consecutive failed PIN attempts (triggering a 2-hour server-enforced lockout).

### Break-Glass Mechanism
* **Policy**: SecureVault implements a strict **no-backdoor** policy. There are no developer override keys, master recovery passwords, or recovery mechanisms.
* **PIN Recovery**: If a user forgets their PIN, they can only recover access to their vault by entering one of their single-use alphanumeric backup codes.
* **VMK Recovery**: If a user forgets their Security Question answer, they cannot retrieve their VMK on a new device. The vault data remains encrypted and unrecoverable.

---

## 6. Enforcement Layer Map

For sensitive operations, the table below maps security checks across the three primary enforcement layers:

| Sensitive Operation | Client-Side Enforcement | API Gateway (Firebase Functions) | Database (MongoDB Atlas) |
| :--- | :--- | :--- | :--- |
| **Retrieve VMK** | Requests Security Question UI; enforces Keystore storage. | Validates Google JWT token signature; checks Security Answer hash. | Queries specific VMK record matching the authenticated `uid`. |
| **Write/Sync Password** | Encrypts password value in memory using VMK via AES-256. | Validates Firebase JWT token; checks client's logical version counter. | Updates `vault_passwords` collection restricting query to `uid`. |
| **Delete Account** | Prompts for confirmation and challenges Security Question. | Validates Google JWT token; calls deletion transaction. | Deletes all collection entries matching user's `uid`. |
| **PDF/CSV Export** | Generates PDF protected by user PIN; clears cache. | Validates Google JWT token; verifies Security Question answer. | Restricts document query results to user's `uid`. |
| **Device Session Removal** | Renders session list; sends removal request. | Validates Google JWT token; checks active session counts. | Deletes device session record matching selected ID and `uid`. |
