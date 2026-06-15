# SECUREVAULT - PRODUCT REQUIREMENTS DOCUMENT (PRD)

---

## 1. Executive Summary

### Problem Statement
In the modern mobile ecosystem, users face significant security and usability challenges managing online credentials:
1. Reusing weak or identical passwords across multiple online accounts due to memory limitations.
2. Storing passwords in unencrypted, vulnerable formats (e.g., standard notes applications).
3. Difficulty transferring and synchronizing credentials securely between devices.
4. Exposure to environment risks such as clipboard sniffing, screen recording, and local session hijacking.
5. Inability to access credentials offline, leading to locked sessions when internet connectivity is lost.

### Solution
SecureVault is an Android native password manager built using Kotlin, Material 3, Firebase Authentication, and MongoDB Atlas. It leverages local encrypted storage (Room with SQLCipher) and hardware-backed device keys (Android Keystore) to deliver a secure, offline-first, and cloud-synchronized password management experience. The application features automated Android Autofill integration, a customizable password generator, local security checks (root/debugging detection, clipboard clearing), and secure export functions.

### Why Now
With the rise of credential stuffing attacks, zero-knowledge/secure key management architectures on mobile are essential. By utilizing the Android Keystore API and SQLCipher alongside the modern Android Credential Manager API, SecureVault provides a seamless authentication experience that keeps credential storage secure, even in the event of device theft, network drops, or local device environment compromises.

---

## 2. User Personas

SecureVault targets four primary roles, each derived from the Master Product Blueprint:

### 1. Students
* **Who They Are**: High-frequency mobile users managing dozens of school portals, personal emails, student discount memberships, and social accounts.
* **Job-to-be-Done (JTBD)**: Easily organize, store, and partition their school-related logins separately from personal logins so they can access them quickly during study sessions.
* **Pain Points**: Frequently forgetting credentials during exam log-ins, using weak password patterns to aid memory, and losing credentials during device switches.
* **Success Criteria**: Instant access to school and personal credentials via custom Categories, quick search, and one-tap copy-pasting.

### 2. Professionals
* **Who They Are**: Corporate employees handling sensitive enterprise credentials, banking portals, and client communications on their personal Android devices.
* **Job-to-be-Done (JTBD)**: Isolate work credentials, generate highly complex passwords for banking portals, and securely export credential records for backup compliance.
* **Pain Points**: High security risk of work passwords leaking to personal apps, corporate data vulnerability if the phone is lost, and clipboard sniffing by other mobile applications.
* **Success Criteria**: Secure local database encryption, automatic background lockout, automatic clipboard purging, and password-protected PDF backups.

### 3. Developers
* **Who They Are**: Engineering and technical users with advanced password requirements and higher risk vectors (rooted devices, debugging settings).
* **Job-to-be-Done (JTBD)**: Generate high-entropy passwords with custom constraints (special characters, similar character exclusions) and receive explicit alerts when running the app in an insecure host environment.
* **Pain Points**: Standard password managers blocking usage entirely on development devices, weak default character generation sets, and lack of warning when local USB debugging is active.
* **Success Criteria**: Custom length, character sets, and exclusions for generated passwords; automatic root and USB debugging warnings.

### 4. General Smartphone Users
* **Who They Are**: Non-technical smartphone users seeking a simple, low-effort solution to secure their online identities.
* **Job-to-be-Done (JTBD)**: Autofill credentials directly inside standard Android applications and mobile websites, and easily restore their vaults after buying a new phone.
* **Pain Points**: Complex setup steps, copy-pasting back and forth between password vaults and target apps, and losing passwords forever when switching devices.
* **Success Criteria**: Smooth integration with the Android Autofill Framework, secure multi-device cloud synchronization, and single-click recovery using Google Sign-In.

---

## 3. Feature Specifications

### Module: Authentication & Onboarding (AUTH)

#### F-AUTH-01: Google Sign-In Authentication
* **One-Line Description**: Authenticate users uniquely via their Google Account using Android's Credential Manager API.
* **User Story**: As a General smartphone user, I want to sign in with my Google Account so that I can securely and uniquely access my vault.
* **Acceptance Criteria**:
  1. On first launch, the app must bypass the dashboard and display onboarding screens followed by a "Sign in with Google" button.
  2. Tapping the button must trigger the Android `Credential Manager` API, displaying the account selection overlay.
  3. Upon successful Google OAuth verification, the client must request and receive the user's encrypted Vault Master Key (VMK) from the backend over a secure TLS channel.
  4. If Google Sign-In fails or is cancelled, the app must remain on the login screen and show a standard Android Toast error message.
* **Priority**: P0
* **Blueprint Reference**: Section 7: `Only Google Sign-In is supported.` and Section 51: `Google Sign-In: Implementation uses the modern Android Credential Manager API for Google Auth integration.`
* **Dependencies**: None

#### F-AUTH-02: Security Question Setup & Challenge
* **One-Line Description**: Set up and verify a predefined security question to protect sensitive actions and new logins.
* **User Story**: As a Professional, I want to set up a security question and answer so that my identity is validated when logging in on a new device or performing sensitive actions.
* **Acceptance Criteria**:
  1. During first-time onboarding, the user must select 1 of 15 predefined security questions from a dropdown menu and input a plaintext answer.
  2. The plaintext answer must be salted and hashed (using PBKDF2 with SHA-256) on the client before being sent to the backend; the plaintext answer must never be stored on the device or backend.
  3. The security question challenge must block the user and require verification for: new device logins, re-authentication after logout, switching accounts, password exports (CSV/PDF), PIN changes, security question changes, and backup code regeneration.
* **Priority**: P0
* **Blueprint Reference**: Section 9: `User selects one predefined question... Salted -> Hashed -> Stored. Required For: Password export, PIN change, Security question change, Backup code regeneration, New device logins, Re-authentication after logout, Switching accounts.`
* **Dependencies**: F-AUTH-01

#### F-AUTH-03: PIN Creation & Verification
* **One-Line Description**: Create and verify a device-specific 6-digit PIN for daily local access.
* **User Story**: As a General smartphone user, I want to set up and verify a 6-digit numeric PIN so that I can quickly unlock my local app session.
* **Acceptance Criteria**:
  1. During first-time onboarding (Step 7), the user must enter and confirm a 6-digit numeric PIN.
  2. The PIN must be stored locally in the encrypted SQLCipher database and is unique to the device.
  3. On daily app launch, the app must display a PIN entry screen. Entering the correct PIN must launch the Dashboard in <100ms.
* **Priority**: P0
* **Blueprint Reference**: Section 10: `Numeric only. Exactly 6 digits. Device specific. Different devices may have different PINs.` and Section 8: `Daily App Launch: Launch App -> PIN Verification / Biometric Authentication -> Dashboard`.
* **Dependencies**: F-AUTH-01

#### F-AUTH-04: PIN Rate Limiting & Lockout
* **One-Line Description**: Lock out users after multiple failed PIN entry attempts to block brute-force attacks.
* **User Story**: As a Professional, I want the app to rate-limit PIN attempts so that brute-force attacks are blocked.
* **Acceptance Criteria**:
  1. Failed PIN attempts from 1 to 5 must allow immediate reentry.
  2. Failed PIN attempts from 6 to 10 must introduce progressive entry delays: 6th (30s), 7th (1m), 8th (2m), 9th (5m), 10th (15m), during which the PIN entry controls are disabled.
  3. The 11th consecutive failed attempt must place the app into a "lockout" state for exactly 2 hours.
  4. The lockout state and failed attempt counter must be stored in the local SQLCipher database and synced to the backend. Reinstalling the app or clearing cache must not bypass the lockout; signing in again with Google must pull the lock status from the server and restore the lockout.
* **Priority**: P0
* **Blueprint Reference**: Section 10: `Rate Limiting... Progressive delays... lockout of 2 hours. Enforcement: Lockout state and failed attempt counter are stored in SQLCipher and synchronized with the backend. Reinstalling app... pulls sync state.`
* **Dependencies**: F-AUTH-03

#### F-AUTH-05: Backup Code System
* **One-Line Description**: Generate and validate single-use backup codes to restore access if a PIN is forgotten.
* **User Story**: As a General smartphone user, I want backup codes generated during onboarding so that I can recover vault access if I forget my PIN.
* **Acceptance Criteria**:
  1. During onboarding (Step 8), the app must generate and display exactly two unique, alphanumeric backup codes formatted as `XXXX-XXXX` (e.g., `AB7K-XP92`).
  2. Backup codes must be single-use. Validating a correct backup code must allow the user to reset their device PIN.
  3. Regenerating backup codes in the security settings must invalidate all previously generated codes.
* **Priority**: P1
* **Blueprint Reference**: Section 11: `Recover access if PIN is forgotten. Format: AB7K-XP92. Two codes generated. Single-use. Regeneration invalidates old codes.`
* **Dependencies**: F-AUTH-03

#### F-AUTH-06: Biometric Authentication
* **One-Line Description**: Optional biometric integration to bypass daily PIN entry.
* **User Story**: As a General smartphone user, I want to unlock my vault using fingerprint or face unlock so that I can bypass typing my PIN.
* **Acceptance Criteria**:
  1. The user can toggle Biometric settings on/off in the Security Settings (requires PIN confirmation).
  2. When active, app launch displays the system `BiometricPrompt` supporting fingerprint and face unlock (if hardware compatible).
  3. Registering any new fingerprint or face profile in Android system settings must immediately invalidate the app's biometric encryption key, forcing the user to authenticate via PIN and Security Question to re-enable biometrics.
* **Priority**: P1
* **Blueprint Reference**: Section 12: `Optional... Fingerprint... Face Unlock. Biometric may replace daily PIN entry.` and Section 5: `Automatically invalidate biometric keys in the Android Keystore if a new fingerprint or face is enrolled in the Android system settings. If invalidated, the user must fallback to PIN and Security Question...`
* **Dependencies**: F-AUTH-03

---

### Module: Password Vault (VAULT)

#### F-VAULT-01: Dashboard Password Listing
* **One-Line Description**: Display the main password dashboard containing the listing, search, and navigation controls.
* **User Story**: As a Student, I want to see a list of all my passwords on the dashboard so that I can easily browse them.
* **Acceptance Criteria**:
  1. The Dashboard must display a Search Bar, the Password List, a Floating Action Button (FAB) to add passwords, and a Bottom Navigation bar.
  2. Each password list entry must display: Website Favicon (or letter fallback avatar), Website/Entry Name, Username/Email, and a Star (Favorite) indicator.
  3. Tapping a password entry card must navigate to the Password Details Screen.
* **Priority**: P0
* **Blueprint Reference**: Section 18: `Dashboard... Search Bar, All Password List, Floating Action Button, Bottom Navigation. Password Card Displays: Website Icon, Name, Username/Email, Favorite Indicator.`
* **Dependencies**: F-AUTH-03

#### F-VAULT-02: Password CRUD Operations
* **One-Line Description**: Create, read, update, and soft-delete password entries.
* **User Story**: As a Student, I want to create, read, update, and soft-delete passwords so that I can manage my credentials.
* **Acceptance Criteria**:
  1. Add Screen must contain inputs for Name, Username/Email, Password, Website URL, Category (dropdown), a Generate Password button, and a Save button.
  2. Saving an entry must encrypt the Password value via AES-256 using the cached VMK and write it to the local Room database.
  3. Details Screen must display: Website Favicon, Entry Name, Username, decrypted Password (hidden by default), Website URL, Created Date, and Updated Date.
  4. Editing an entry must write a new local state with an incremented version counter and updated timestamp.
  5. Deleting an entry must write a `deletedDate` timestamp and move the entry to the Trash.
* **Priority**: P0
* **Blueprint Reference**: Section 20: `Fields: Entry ID, Name, Username/Email, Password, Website URL, Category, Favorite, Created Date, Updated Date, Deleted Date...` and Section 21: `Add Password Screen...` and Section 22: `Password Details Screen... Displays: Website Icon, Name, Username, Password, Website, Created Date, Updated Date.`
* **Dependencies**: F-VAULT-01

#### F-VAULT-03: Password History
* **One-Line Description**: Store and reveal the last 3 prior passwords used for a given entry.
* **User Story**: As a Professional, I want to view my previous passwords for a website so that I can find old credentials if needed.
* **Acceptance Criteria**:
  1. Upon editing the password field of an entry, the previous password must be added to a local encrypted history collection (maximum of 3 prior passwords retained).
  2. The history passwords must be displayed on the Password Details Screen, hidden by default.
  3. Tapping the Eye Icon next to a history entry must decrypt and display it in plaintext.
* **Priority**: P1
* **Blueprint Reference**: Section 23: `Stores last 3 passwords. Encrypted. Hidden by default. User may reveal using Eye Icon.` and Section 22: `Password History (Last 3 prior passwords, hidden by default with an eye icon to reveal each)`.
* **Dependencies**: F-VAULT-02

#### F-VAULT-04: Password Details Actions
* **One-Line Description**: Copy credentials to clipboard and clear the clipboard after a delay.
* **User Story**: As a Student, I want to copy my username or password to the clipboard so that I can log into websites manually.
* **Acceptance Criteria**:
  1. Password Details screen must expose buttons for: Copy Username, Copy Password, Copy Website, Edit, Delete, Favorite, and Reveal Password.
  2. Tapping "Copy Password" must copy the decrypted plaintext password to the Android system clipboard.
  3. The app must automatically clear the clipboard contents exactly 30 seconds after the copy action.
* **Priority**: P0
* **Blueprint Reference**: Section 22: `Actions: Copy Username, Copy Password, Copy Website, Edit, Delete, Favorite, Reveal Password` and Section 41: `Clipboard Security: Copy Password -> Clipboard -> 30 Seconds -> Automatic Clear`.
* **Dependencies**: F-VAULT-02

#### F-VAULT-05: Favorites System
* **One-Line Description**: Star specific passwords to display them at the top of the dashboard.
* **User Story**: As a Student, I want to star my favorite passwords so that they appear at the top of my list.
* **Acceptance Criteria**:
  1. Tapping the star icon on a password card or details screen must toggle the entry's `Favorite` state.
  2. Favorite entries must always be sorted to the top of the All Password List on the dashboard, above non-starred entries.
* **Priority**: P1
* **Blueprint Reference**: Section 29: `Users may star passwords. Favorites appear at top of dashboard.`
* **Dependencies**: F-VAULT-01

#### F-VAULT-06: Category System
* **One-Line Description**: Group password entries into predefined or custom folders.
* **User Story**: As a Student, I want to organize my passwords into categories like Personal, Work, or School so that they are easy to separate.
* **Acceptance Criteria**:
  1. Bottom Navigation Tab 2 must display the Categories Screen, grouping entries by Personal, Work, Banking, Shopping, and Social.
  2. Users must be able to create, edit, or delete custom categories.
  3. Deleting a category must set the category of assigned passwords to uncategorized; it must not delete the passwords.
* **Priority**: P1
* **Blueprint Reference**: Section 19: `Tab 2: Categories` and Section 28: `User-created categories... Predefined: Personal, Work, Banking, Shopping, Social. Operations: Create, Edit, Delete, Assign Passwords.`
* **Dependencies**: F-VAULT-02

#### F-VAULT-07: Trash UI Screen
* **One-Line Description**: View and manage soft-deleted passwords.
* **User Story**: As a Professional, I want to view my deleted passwords in a Trash folder so that I can restore them or purge them permanently.
* **Acceptance Criteria**:
  1. Trash UI must be accessible via `Settings -> Danger Zone -> Trash`.
  2. Screen must list all soft-deleted password entries, displaying: Website Icon, Entry Name, Username, and Deletion Date showing a countdown of days remaining until permanent deletion.
  3. Tapping "Restore" must recover the entry to active vault status and nullify its `deletedDate`.
  4. Tapping "Delete Permanently" or "Empty Trash" must purge the records from the local database and backend.
  5. Any entry with a `deletedDate` older than 30 days must be automatically deleted permanently.
* **Priority**: P1
* **Blueprint Reference**: Section 30: `Trash System: Delete -> Trash -> 30 Days -> Permanent Deletion... Trash UI Screen... Access: Settings -> Danger Zone -> Trash... Restore, Delete Permanently, Empty Trash.`
* **Dependencies**: F-VAULT-02

---

### Module: Password Generator (GEN)

#### F-GEN-01: Password Generator Screen
* **One-Line Description**: Generate high-entropy passwords using configurable complexity constraints.
* **User Story**: As a Developer, I want to generate strong passwords with custom lengths and character types so that I can secure my accounts.
* **Acceptance Criteria**:
  1. Generator screen must be accessible via Bottom Navigation Tab 3 or via a shortcut on the Add Password screen.
  2. Controls must include a Length Slider (8 to 64 characters) and toggles for: Uppercase, Lowercase, Numbers, Symbols, and "Exclude Similar Characters" (e.g., `i, l, 1, L, o, 0, O`).
  3. Must display a real-time password Strength Meter, a Copy Button, and a "Use Password" button (only visible when opened from the Add/Edit Screen).
* **Priority**: P0
* **Blueprint Reference**: Section 24: `Password Generator: Length Slider, Uppercase, Lowercase, Numbers, Symbols, Exclude Similar Characters, Strength Meter, Copy Button, Use Password Button.`
* **Dependencies**: None

#### F-GEN-02: Password Health Dashboard
* **One-Line Description**: Display a summary dashboard evaluating vault security health metrics.
* **User Story**: As a General smartphone user, I want to see a summary of my password health so that I know which passwords need to be strengthened.
* **Acceptance Criteria**:
  1. The Health Dashboard must display total counts for: Weak Passwords, Medium Passwords, Strong Passwords, Reused Passwords, and Total Entries.
  2. Displays clear security recommendations: "Weak passwords detected", "Duplicate passwords detected", along with a list of the offending entries.
* **Priority**: P1
* **Blueprint Reference**: Section 25: `Displays: Weak, Medium, Strong, Reused Passwords, Total Entries. Security Recommendations...`
* **Dependencies**: F-VAULT-02

#### F-GEN-03: Password Reuse Detection
* **One-Line Description**: Warn users in real-time when assigning duplicate passwords to different accounts.
* **User Story**: As a General smartphone user, I want the app to warn me if I reuse a password so that I can avoid duplicate credentials.
* **Acceptance Criteria**:
  1. Upon saving, editing, or syncing a password, the app must decrypt the passwords in memory and compare them.
  2. If the password matches another active vault entry, the app must display a Warning Dialog notifying the user of duplication.
* **Priority**: P1
* **Blueprint Reference**: Section 26: `Checks occur: Creation, Editing, Sync. Alerts user when duplicates are found.`
* **Dependencies**: F-VAULT-02, F-GEN-02

---

### Module: Search System (SRCH)

#### F-SRCH-01: Real-Time Search
* **One-Line Description**: Filter password listings instantaneously as the user types.
* **User Story**: As a Student, I want to search my passwords in real-time so that I can find credentials instantly.
* **Acceptance Criteria**:
  1. Typing in the search bar on the Dashboard must filter the password list in real-time.
  2. Matches must be evaluated against the Name, Username/Email, and Website URL fields.
  3. Filtering list updates must display in <100ms.
* **Priority**: P0
* **Blueprint Reference**: Section 27: `Search By Name, Search By Email, Search By Website. Real-time filtering.` and Section 48: `Search: Under 100ms`.
* **Dependencies**: F-VAULT-01

---

### Module: Autofill System (AUTO)

#### F-AUTO-01: Android Autofill Service Integration
* **One-Line Description**: Integrate with the Android Autofill framework to fill credentials in external apps.
* **User Story**: As a General smartphone user, I want SecureVault to autofill my credentials in other apps so that I don't have to copy-paste.
* **Acceptance Criteria**:
  1. The app must implement the Android system `AutofillService`.
  2. When a user focuses on a username or password field in a native Android app or standard WebView, SecureVault must display a dropdown overlay with credential suggestions.
  3. Selecting a credential must autofill the username and password fields instantly.
* **Priority**: P0
* **Blueprint Reference**: Section 32: `Android Autofill Framework. Supports: Username Filling, Password Filling, Credential Suggestions... native Android input fields and standardized WebView containers.`
* **Dependencies**: F-VAULT-02

---

### Module: Data Export (EXP)

#### F-EXP-01: PDF Export
* **One-Line Description**: Generate a password-protected PDF file containing the user's vault credentials.
* **User Story**: As a Professional, I want to export my passwords as a PDF file so that I can print or store a hardcopy of my credentials.
* **Acceptance Criteria**:
  1. Tapping `Settings -> Export -> Export PDF` must challenge the user with their Security Question.
  2. Upon correct entry, the user must input a PDF encryption password.
  3. The app must generate a password-protected PDF containing Name, Username, Password, and Website URL fields using Android's native `PdfDocument` API.
  4. Opens the system Share Sheet to permit saving or sending.
* **Priority**: P1
* **Blueprint Reference**: Section 33: `Flow: Settings -> Export PDF -> Security Question -> Generate PDF -> Protect PDF Using PIN -> Share Sheet` and Section 51: `PDF Generation: Native Android PdfDocument API is used...`
* **Dependencies**: F-AUTH-02, F-VAULT-02

#### F-EXP-02: CSV Export
* **One-Line Description**: Export vault credentials to an unencrypted plaintext CSV file.
* **User Story**: As a Professional, I want to export my passwords as a CSV file so that I can migrate them to another utility.
* **Acceptance Criteria**:
  1. Tapping `Settings -> Export -> Export CSV` must challenge the user with their Security Question.
  2. Displays a Warning Screen stating that the exported CSV will store passwords in unencrypted plaintext.
  3. Upon user confirmation, generates the CSV file and triggers the Android system Share Sheet.
* **Priority**: P1
* **Blueprint Reference**: Section 34: `Flow: Settings -> Export CSV -> Security Question -> Warning Screen -> Confirmation -> Generate CSV -> Share Sheet`.
* **Dependencies**: F-AUTH-02, F-VAULT-02

---

### Module: Synchronization & Offline Support (SYNC)

#### F-SYNC-01: Local SQLite/Room Cache
* **One-Line Description**: Secure offline credential storage using Room and SQLCipher.
* **User Story**: As a Professional, I want my edits to save when I am offline so that I don't lose data.
* **Acceptance Criteria**:
  1. All vault modifications while offline must be written to the local Room database encrypted with SQLCipher.
  2. Offline writes (creates, updates, deletes) must be logged as `pending` transactions in a local `Sync Queue` table.
* **Priority**: P0
* **Blueprint Reference**: Section 17: `Local Database: Room Database... SQLCipher... Sync Queue... local Sync Queue table with state flag of pending`.
* **Dependencies**: F-VAULT-02

#### F-SYNC-02: Background Sync Worker
* **One-Line Description**: Sync offline data automatically using Android WorkManager when internet returns.
* **User Story**: As a General smartphone user, I want my offline changes to sync automatically when internet returns so that my database is up to date.
* **Acceptance Criteria**:
  1. App schedules a WorkManager background sync task configured with `NetworkType.CONNECTED` constraints.
  2. Upon connection, the task pushes pending queue transactions to the MongoDB Atlas gateway API and pulls remote updates.
  3. Upon success, processed queue rows are deleted. Synchronization must execute in <5 seconds.
* **Priority**: P0
* **Blueprint Reference**: Section 17: `Sync Trigger: Android WorkManager is scheduled with a connectivity constraint... Pushes pending... Pulls remote... removes items...` and Section 48: `Synchronization Latency: Under 5 seconds`.
* **Dependencies**: F-SYNC-01

#### F-SYNC-03: Conflict Resolution
* **One-Line Description**: Resolve conflicts between concurrent updates using version counters and timestamps.
* **User Story**: As a Professional, I want conflicting changes to resolve logically so that I don't lose my latest edits.
* **Acceptance Criteria**:
  1. Server compares version counter of incoming client updates against the database version.
  2. If versions differ, the server compares the incoming client's `updatedDate` with the server's `updatedDate`.
  3. The version with the later timestamp wins. If timestamps match exactly, server version is retained, client is updated.
* **Priority**: P0
* **Blueprint Reference**: Section 46: `Hybrid Conflict Resolution: Password entries include a logical version counter (version integer) and a timestamp (updatedDate)... version with the later timestamp wins...`
* **Dependencies**: F-SYNC-02

---

### Module: Device Management & Security Protection (DEV)

#### F-DEV-01: Multi-Device Limits
* **One-Line Description**: Enforce a maximum of 3 active devices per Google account.
* **User Story**: As a General smartphone user, I want to manage my active devices so that I can stay within the 3-device limit.
* **Acceptance Criteria**:
  1. The backend restricts active sessions to a maximum of 3 concurrent device entries per user.
  2. If logging into a 4th device, the app displays the "Active Devices Screen" listing: Device Name, Android Version, Last Active Time, and Current Device indicator.
  3. Selecting a device and tapping "Remove" logs out the selected device and allows the login to proceed on the 4th device.
* **Priority**: P0
* **Blueprint Reference**: Section 15: `Maximum Devices: 3. Device 4 Login Flow...` and Section 16: `Active Devices Screen... Displays: Device Name, Android Version, Last Active Time, Current Device Indicator. User may: Remove Device, Logout Device`.
* **Dependencies**: F-AUTH-02

#### F-DEV-02: Insecure Environment Warnings (Root & Debugging)
* **One-Line Description**: Warn users if the app runs in a rooted or debugging-enabled environment.
* **User Story**: As a Developer, I want to be warned if the app runs in a rooted or debug-enabled environment so that I am aware of security risks.
* **Acceptance Criteria**:
  1. On app start, checks must detect superuser binaries (root access) and if USB debugging is enabled in developer settings.
  2. If root access is detected, displays a Warning Dialog with a "Continue" button.
  3. If USB debugging is active, displays a Warning Dialog with a "Continue" button.
* **Priority**: P1
* **Blueprint Reference**: Section 44: `Root Found -> Warning Dialog -> User may continue` and Section 45: `Debugging Enabled -> Warning Dialog -> User may continue`.
* **Dependencies**: F-AUTH-03

---

## 4. MVP Scope

### MVP Included (v1.0)
* **Authentication**: F-AUTH-01 (Google Auth), F-AUTH-02 (Security Question), F-AUTH-03 (PIN Unlock), F-AUTH-04 (PIN rate limit & lockouts), F-AUTH-05 (Backup codes), F-AUTH-06 (Biometric settings).
* **Vault CRUD & UX**: F-VAULT-01 (Dashboard), F-VAULT-02 (Password CRUD), F-VAULT-03 (History), F-VAULT-04 (Details & Copy action with 30s clipboard auto-clear), F-VAULT-05 (Favorites sorting), F-VAULT-06 (Categories), F-VAULT-07 (Trash UI).
* **Generator**: F-GEN-01 (Password Generator), F-GEN-02 (Health Dashboard), F-GEN-03 (Reuse Warnings).
* **Search**: F-SRCH-01 (Real-Time Search).
* **Autofill**: F-AUTO-01 (Autofill Service).
* **Export**: F-EXP-01 (PDF Export with native `PdfDocument`), F-EXP-02 (CSV Export with plaintext warning).
* **Sync & Offline**: F-SYNC-01 (Room / SQLCipher local cache), F-SYNC-02 (WorkManager background sync worker), F-SYNC-03 (Version / timestamp conflict resolution).
* **Device Limits**: F-DEV-01 (3-Device limits / session management), F-DEV-02 (Root and USB Debug warning screens).

### Deferred (Post-MVP Roadmap)
* **F-ROAD-01: Secure Notes** (Blueprint Section 49: Phase 2)
* **F-ROAD-02: Advanced Password Reports** (Blueprint Section 49: Phase 2)
* **F-ROAD-03: Shared Vaults** (Blueprint Section 49: Phase 2)
* **F-ROAD-04: Credit Card Storage** (Blueprint Section 49: Phase 3)
* **F-ROAD-05: Secure Documents** (Blueprint Section 49: Phase 3)
* **F-ROAD-06: Account Migration** (Blueprint Section 49: Phase 4)
* **F-ROAD-07: Cross-Platform (iOS/Web/Desktop)** (Blueprint Section 49: Phase 4)
* **F-ROAD-08: Browser Extensions** (Blueprint Section 49: Phase 4)
* **Autofill Advanced Features**: Custom IME keyboards and third-party credential interception/saving are explicitly excluded in Phase 1 (Blueprint Section 32 & 51).

---

## 5. Non-Functional Requirements

### Security & Compliance
* **Data Encryption**: Local passwords must be encrypted using `AES-256` keying. The database file must be encrypted at rest using `SQLCipher`.
* **Key Storage**: Local database and biometric validation keys must be stored in the hardware-backed `Android Keystore`.
* **UI Protection**: Screenshots, screen recording, and display casting must be blocked application-wide.
* **Background Security**: When backgrounded, a black screen must be shown in Android Recent Apps. The app must lock itself automatically if backgrounded for more than 5 minutes.
* **Biometric Invalidation**: System biometric modifications (new fingerprint registration) must invalidate the biometrics key, triggering fallback verification.

### Performance
* **App Launch Time**: App must render the PIN or Biometric lock prompt within < 2 seconds of launch.
* **Local Search Latency**: Filtering of passwords by query on the dashboard must execute and update the UI in < 100ms.
* **Password Decryption**: Decrypting a selected password for display or copy action must take < 100ms.
* **Synchronization Duration**: Database sync via WorkManager must transfer and reconcile the queue within < 5 seconds of network availability.

---

## 6. Assumptions and Risks

### Assumptions
1. **Google Services Availability**: The device must have Google Play Services installed to authenticate via Google Sign-In and access Firebase Cloud Functions.
2. **Android Keystore Hardware Compatibility**: The target Android devices must possess hardware-backed Keystore capabilities (Teeg/StrongBox) to achieve the defined security standard.
3. **Continuous Firebase Gateway Connectivity**: The application assumes that the Firebase Cloud Functions gateway will remain responsive and connected to the MongoDB Atlas cluster.

### Risks
* **Android OS Compatibility Gaps**: Autofill Service behavior varies significantly across vendor UI skins (Samsung OneUI, Xiaomi MIUI, etc.). Extensive device testing is needed.
* **Brute-Force Bypass**: Lockout state stored locally can be bypassed if the device is offline and the user resets/wipes local application data (since the backend cannot sync the block). To mitigate this, a wiped database is empty and requires logging in via Google and Security Question which retrieves the block from the server.

---

## 7. Open Questions

1. **PDF Styling Templates**
   * *Description*: Should the PDF report match a specific corporate brand style or be generated using a standard Material 3 color palette?
   * *Owner*: Lead UX Designer
   * *Deadline*: 2026-06-25

2. **Firebase Cloud Functions Hosting region**
   * *Description*: In which cloud host region should the Firebase gateway functions be deployed to comply with global user latency and data location laws?
   * *Owner*: Backend/Security Architect
   * *Deadline*: 2026-06-30
