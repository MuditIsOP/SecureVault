\# SECUREVAULT - MASTER PRODUCT BLUEPRINT



\## 1. PROJECT OVERVIEW



\### Product Name



SecureVault (Working Name)



\### Product Type



Android Password Manager



\### Platform



Android Native Application



\### Technology Stack



\* Kotlin

\* Material 3

\* Firebase Authentication (Google Sign-In)

\* MongoDB Atlas

\* Room Database

\* SQLCipher (Local Database Encryption)

\* Android Autofill Framework

\* Android Keystore

\* AES Encryption

\* MVVM Architecture



\### Product Vision



SecureVault aims to provide users with a modern, secure, cloud-synchronized password management solution that combines ease of use with strong security practices. The application will allow users to securely store, organize, generate, autofill, export, and synchronize passwords across multiple Android devices while maintaining a simple and intuitive user experience.



The primary focus of the application is security, reliability, usability, and performance.



\---



\# 2. PROBLEM STATEMENT



Most users face the following problems:



1\. Reusing the same password across multiple websites.

2\. Forgetting passwords frequently.

3\. Storing passwords in insecure notes applications.

4\. Using weak passwords.

5\. Managing hundreds of accounts manually.

6\. Difficulty transferring passwords between devices.

7\. Lack of password health visibility.

8\. Dependence on browser-specific password managers.



SecureVault aims to solve these problems through a centralized password management solution.



\---



\# 3. PRODUCT OBJECTIVES



\### Primary Objectives



\* Secure password storage.

\* Cloud synchronization.

\* Fast password retrieval.

\* Password generation.

\* Password health analysis.

\* Android Autofill integration.

\* Multi-device support.

\* Export functionality.



\### Secondary Objectives



\* Modern Material 3 interface.

\* Smooth animations.

\* Dark mode support.

\* Fast search capabilities.

\* Category organization.



\---



\# 4. TARGET USERS



\### Primary Users



\* Students

\* Professionals

\* Developers

\* General smartphone users



\### User Characteristics



\* Uses multiple online services.

\* Requires secure password storage.

\* Owns Android devices.

\* Needs cloud synchronization.



\---



\# 5. CORE SECURITY PHILOSOPHY



Security is the highest priority.



The application must:



\* Encrypt passwords before storage.

\* Never store passwords in plain text.

\* Hide sensitive content from screenshots.

\* Hide sensitive content from screen recordings.

\* Hide sensitive content in Recent Apps.

\* Support biometric authentication.

\* Use rate limiting against brute-force attacks.

\* Lock the application immediately if backgrounded for more than 5 minutes, requiring PIN or Biometric re-authentication.

\* Automatically invalidate biometric keys in the Android Keystore if a new fingerprint or face is enrolled in the Android system settings. If invalidated, the user must fallback to PIN and Security Question authentication to re-enable biometrics.



\---



\# 6. APPLICATION STRUCTURE



The application consists of:



\### Dashboard Module



Main password listing and management.



\### Password Management Module



Create, edit, delete, restore passwords.



\### Password Generator Module



Generate secure passwords.



\### Autofill Module



Integrate with Android Autofill Framework.



\### Settings Module



Account and security management.



\### Export Module



CSV and PDF exports.



\### Device Management Module



Multi-device management.



\### Password Health Module



Security analysis and recommendations.



\### Trash Module



Management of soft-deleted entries, permitting restoration or permanent deletion.



\### Password History Module



Viewing previously used passwords for individual entries.



\---




\# 7. ACCOUNT SYSTEM



\## Authentication Method



Only Google Sign-In is supported.



No email/password registration.



No phone registration.



No guest accounts.



\---



\## Account Identity



Each Google account represents one unique vault.



Example:



\[user1@gmail.com](mailto:user1@gmail.com)

→ Vault A



\[user2@gmail.com](mailto:user2@gmail.com)

→ Vault B



Both vaults remain completely isolated.



\---



\## Account Switching



User may:



Settings

→ Logout



Then:



Sign in with another Google account



The new account receives:



\* New Security Question

\* New PIN

\* New Backup Codes

\* New Vault

\* New VMK



\---



\# 8. ONBOARDING FLOW



\## First-Time User Flow



Step 1:

Launch Application



Step 2:

Splash Screen



Step 3:

Onboarding Screens



Step 4:

Google Sign-In



Step 5:

Security Question Setup



Step 6:

Security Answer Setup



Step 7:

PIN Creation



Step 8:

Backup Code Generation



Step 9:

Vault Initialization



Step 10:

Dashboard



\---



\## Returning User Flow (Daily App Launch)



Launch App

↓

PIN Verification / Biometric Authentication

↓

Dashboard



\---



\## Re-Authentication / New Device Login Flow



Launch App

↓

Google Sign-In

↓

Security Question Challenge

↓

Retrieve VMK from Backend

↓

PIN Verification / Biometric Enrollment (if new device)

↓

Dashboard



\---



\# 9. SECURITY QUESTION SYSTEM



\## Setup



User selects one predefined question.



Example:



\* What was your first school's name?

\* What was your childhood nickname?

\* What was your favorite teacher's name?

\* What city were you born in?



Approximately 15 predefined questions.



\---



\## Storage



Answer is:



Salted



↓



Hashed



↓



Stored



Plain text answers are never stored.



\---



\## Required For



\* Password export

\* PIN change

\* Security question change

\* Backup code regeneration

\* New device logins

\* Re-authentication after logout

\* Switching accounts



\---



\# 10. PIN SYSTEM



\## Characteristics



\* Numeric only

\* Exactly 6 digits

\* Device specific



Different devices may have different PINs.



\---



\## Rate Limiting



Failed Attempts:



\* 1-5 attempts: Normal



\* 6-10 attempts: Progressive delays introduced:

  \* 6th attempt: 30 seconds delay

  \* 7th attempt: 1 minute delay

  \* 8th attempt: 2 minutes delay

  \* 9th attempt: 5 minutes delay

  \* 10th attempt: 15 minutes delay



\* 11+ attempts: Temporary lockout of 2 hours.



\## Enforcement

\* Lockout state and failed attempt counter are stored in the secure local database (SQLCipher) and synchronized with the backend.

\* Reinstalling the app clears local cache, but logging in with Google Auth pulls the sync state from the backend to prevent lockout bypass.

\---



\# 11. BACKUP CODE SYSTEM



\## Purpose



Recover access if PIN is forgotten.



\---



\## Format



Examples:



AB7K-XP92



M4QR-LN88



\---



\## Rules



\* Two codes generated.

\* Single-use.

\* Regeneration invalidates old codes.



\---



\# 12. BIOMETRIC AUTHENTICATION



Optional feature.



Supports:



\* Fingerprint

\* Face Unlock (if device supports)



Biometric may replace daily PIN entry.



PIN remains recovery method.



\---



\# 13. ENCRYPTION ARCHITECTURE



\## Vault Master Key (VMK)



Each user receives:



Randomly Generated VMK



Used for:



\* Password Encryption

\* Password History Encryption



\---



\## Password Storage Process



Password



↓



AES Encryption



↓



Ciphertext



↓



MongoDB



\---



\## VMK Storage

\* The backend (Firebase/MongoDB Atlas gateway) stores the VMK encrypted.

\* The backend encrypts the VMK at rest using a master key managed by Google Cloud KMS / AWS KMS.

\* Access is non-zero-knowledge; the backend retrieves and transmits the VMK upon successful Firebase Authentication.

\* The VMK is transmitted to the client over a secure TLS channel.

\* On the client device, the VMK is decrypted only in-memory and cached securely in the hardware-backed **Android Keystore** during the active session.



Allows:



\* Multi-device sync

\* Device recovery

\* Cloud synchronization



\---



\# 14. DATABASE ARCHITECTURE



Database Provider



MongoDB Atlas



\---



\## Collections



Users



Passwords



Categories



Backup Codes



Device Sessions



Password History



Trash



Sync Logs



\---



\# 15. MULTI-DEVICE SUPPORT



Maximum Devices:



3



\---



\## Device 4 Login Flow



User attempts login



↓



Device Limit Reached



↓



Active Devices Screen



↓



Select Device To Remove



↓



Continue Login



\---



\# 16. ACTIVE DEVICES SCREEN



Displays:



\* Device Name

\* Android Version

\* Last Active Time

\* Current Device Indicator



User may:



\* Remove Device

\* Logout Device



\---



\# 17. OFFLINE SUPPORT



\## Requirements

User must access vault without internet.



\---



\## Local Data Storage & Security

\* **Local Database**: Android `Room Database` (SQLite wrapper).

\* **Encryption**: SQLite database is fully encrypted at rest using `SQLCipher`.

\* **Key Management**: The SQLCipher database encryption key is randomly generated on first run and stored securely in the hardware-backed `Android Keystore`. Key is never stored in plaintext or shared preferences.



\---



\## Offline Actions

\* View passwords

\* Search passwords

\* Copy passwords

\* Create passwords

\* Edit passwords

\* Soft-delete passwords (move to Trash)



\---



\## Sync Strategy & Queue

\* **Sync Queue**: All offline modifications (create, edit, delete) are immediately committed to the local Room database and appended to a local `Sync Queue` table with a state flag of `pending`.

\* **Sync Trigger**: Android `WorkManager` is scheduled with a connectivity constraint (`NetworkType.CONNECTED`). When internet returns, WorkManager executes a background sync task to process the queue:

  \* Pushes pending local updates to MongoDB Atlas.

  \* Pulls remote updates since last synced timestamp.

  \* Upon success, removes items from the local Sync Queue.



\---



\# 18. DASHBOARD



Main Home Screen



Contains:



Search Bar



All Password List



Floating Action Button



Bottom Navigation



\---



Password Card Displays



\* Website Icon

\* Name

\* Username/Email

\* Favorite Indicator



\---



\# 19. BOTTOM NAVIGATION



Tab 1:

Dashboard



Tab 2:

Categories



Tab 3:

Password Generator



Tab 4:

Settings



\---



\# 20. PASSWORD ENTRY STRUCTURE



Fields



Entry ID



Name



Username/Email



Password



Website URL



Category



Favorite



Created Date



Updated Date



Last Viewed



Last Autofilled



Password Strength



Deleted Date



\---



\# 21. ADD PASSWORD SCREEN



Fields:



Name



Username/Email



Password



Website URL



Category



Generate Password Button



Save Button



\---



\# 22. PASSWORD DETAILS SCREEN



Displays:



Website Icon



Name



Username



Password



Website



Created Date



Updated Date



Password History (Last 3 prior passwords, hidden by default with an eye icon to reveal each)



\---



Actions:



Copy Username



Copy Password



Copy Website



Edit



Delete



Favorite



Reveal Password



\---



\# 23. PASSWORD HISTORY



Stores last 3 passwords.



Encrypted.



Hidden by default.



User may reveal using Eye Icon.



\---



\# 24. PASSWORD GENERATOR



Features:



Length Slider



Uppercase



Lowercase



Numbers



Symbols



Exclude Similar Characters



Strength Meter



Copy Button



Use Password Button



\---



\# 25. PASSWORD HEALTH DASHBOARD



Displays:



Weak Passwords



Medium Passwords



Strong Passwords



Reused Passwords



Total Entries



\---



Security Recommendations:



Weak passwords detected.



Duplicate passwords detected.



Suggested improvements.



\---



\# 26. PASSWORD REUSE DETECTION



Checks occur:



\* Creation

\* Editing

\* Sync



Alerts user when duplicates are found.



\---



\# 27. SEARCH SYSTEM



Supports:



Search By Name



Search By Email



Search By Website



Real-time filtering.



\---



\# 28. CATEGORY SYSTEM



User-created categories.



Examples:



Personal



Work



Banking



Shopping



Social



\---



Operations:



Create



Edit



Delete



Assign Passwords



\---



\# 29. FAVORITES



Users may star passwords.



Favorites appear at top of dashboard.



\---



\# 30. TRASH SYSTEM



\## Data Flow

Delete

↓

Trash Folder

↓

30 Days Inactive

↓

Permanent Deletion



\---



\## Trash UI Screen

\* **Access**: Navigated via `Settings -> Danger Zone -> Trash`.

\* **Display Details**:

  \* List of soft-deleted passwords.

  \* Show: Website Icon, Entry Name, Username, and Deletion Date (with count of days remaining until permanent deletion).

\* **User Actions**:

  \* **Restore**: Recovers the password to active status in the Vault, resetting its `deletedDate`.

  \* **Delete Permanently**: Immediately purges the entry and its history from the local database and backend.

  \* **Empty Trash**: A button at the top to permanently delete all items in the Trash.



\---



\# 31. FAVICON SYSTEM



\## Source & Caching Proxy

\* **Backend Favicon Caching Proxy**: To preserve user privacy and prevent domain leakage to third parties, the client app does not fetch favicons directly from external public APIs (like Google or DuckDuckGo) or scrape HTML.

\* The client requests the website favicon from the self-hosted backend api (e.g. `https://api.securevault.com/favicon?domain=...`).

\* The backend fetches, caches, and returns the favicon image.



\---



\## Caching

\* **Client Caching**: Favicons are cached locally on the device (disk cache) to reduce network requests.



\---



\## Fallback

\* **Generated Letter Avatar**: If the favicon is unavailable or fails to load, a fallback letter avatar is generated dynamically by the app.

\* **Examples**:

  \* Google → G

  \* Netflix → N

  \* Discord → D



\---



\# 32. AUTOFILL SYSTEM



\## Android Autofill Framework

\* **Scope**: Read-only credential suggestion and auto-filling of usernames and passwords.

\* **Supported Contexts**: Native Android input fields and standardized WebView containers.

\* **Out of Scope (Phase 1)**:

  \* Custom inline keyboard integration (IME).

  \* Dynamic interception or prompts to *save* new credentials from third-party app forms (Autofill is strictly pull-based / read-only).



\---



\## Features

\* Username Filling

\* Password Filling

\* Credential Suggestions (dropdown overlay)



\---



\## Onboarding & Settings

\* Onboarding includes a visual guide instructing users how to enable the SecureVault Autofill Service in Android System Settings.

\* Settings Screen provides a toggle to enable/disable autofill suggestions and re-run the setup guide.



\---



\# 33. PDF EXPORT



Flow:



Settings



↓



Export PDF



↓



Security Question



↓



Generate PDF



↓



Protect PDF Using PIN



↓



Share Sheet



\---



\# 34. CSV EXPORT



Flow:



Settings



↓



Export CSV



↓



Security Question



↓



Warning Screen



↓



Confirmation



↓



Generate CSV



↓



Share Sheet



\---



\# 35. SETTINGS SCREEN



Sections:



Profile



Security



Export



Devices



Theme



About



Danger Zone



\---



\# 36. PROFILE SECTION



Displays:



Google Profile Image



Google Name



Google Email



\---



Actions:



\* **Logout**: Clears the local in-memory session (including the VMK in Keystore) and redirects the user to the splash/login screen. The user's offline database remains encrypted on the device.

\* **Switch Account**: Performs a full logout (clearing the local VMK from Keystore), clears Google Sign-In session cache, and prompts the user with the Google account selection dialog to log into a different Google account.



\---



\# 37. SECURITY SECTION



Options:



\* Change PIN

\* Change Security Question

\* Manage Backup Codes

\* Biometric Settings

\* Enable/Disable Autofill Service

\* Rerun Autofill Onboarding Guide



\---



\# 38. DEVICE MANAGEMENT SECTION



Displays:



Active Devices



Device Limit Usage



Remove Device



Current Device



\---



\# 39. THEME MANAGEMENT



System Default



Light



Dark



\---



Default:



Follow System Theme



\---



\# 40. DELETE ACCOUNT FLOW



Settings



↓



Danger Zone



↓



Delete Account



↓



Security Question



↓



Final Confirmation



↓



Delete:



Passwords



Categories



Backup Codes



History



Trash



VMK



Cloud Data



\---



\# 41. CLIPBOARD SECURITY



Copy Password



↓



Clipboard



↓



30 Seconds



↓



Automatic Clear



\---



\# 42. SCREENSHOT PROTECTION



Enabled Application Wide



Blocks:



\* Screenshots

\* Screen Recording

\* Casting



\---



\# 43. RECENT APPS PROTECTION



App enters background



↓



Sensitive content hidden



↓



Black screen displayed



\---



\# 44. ROOT DETECTION



Root Found



↓



Warning Dialog



↓



User may continue



\---



\# 45. USB DEBUGGING DETECTION



Debugging Enabled



↓



Warning Dialog



↓



User may continue



\---



\# 46. SYNC SYSTEM



\## Sync Mechanism

\* **Real-Time Sync**: Updates are immediately pushed to the backend when online.

\* **Synchronization Protocol**: Synchronization is implemented using REST API endpoints over HTTP/TLS, rather than a persistent WebSocket, to optimize mobile battery life and resources.



\---



\## Conflict Resolution

\* **Hybrid Conflict Resolution**: Password entries include a logical version counter (`version` integer) and a timestamp (`updatedDate`).

\* **Mechanism**:

  \* When pushing changes, the server checks if the client's version matches the server's version.

  \* If versions match, the update is accepted, and the version counter is incremented.

  \* If a conflict is detected (versions mismatch), the server compares the client's `updatedDate` with the server's `updatedDate`.

  \* The version with the later timestamp wins. If timestamps are identical, the server-side version is kept, and the client is instructed to overwrite its local state.



\---



\# 47. ERROR HANDLING



\## Network Failure

\* **Behavior**: App transparently switches to Offline Mode (view, search, copy, create, edit, soft-delete are performed on the local Room SQLCipher database). User is notified with a subtle offline status indicator in the UI.



\---



\## Database / Sync Failure

\* **Definition**: A Database or Sync Failure occurs when the local database cannot reach MongoDB Atlas, or the API gateway returns a server error (5xx) or timeout.

\* **Retry Queue**: Failed write operations are persisted as `pending` transactions in the local Room SQLCipher database's `Sync Queue` table. The queue persists across app close and device restarts.

\* **Enforcement**: Android `WorkManager` retries the operations periodically using exponential backoff when a network connection is active. Users can also pull-to-refresh on the dashboard to trigger a manual sync request.



\---



\# 48. PERFORMANCE REQUIREMENTS



\* **App Launch**: Under 2 seconds from splash screen display to user authentication prompt.

\* **Search Queries**: Under 100ms response time when filtering local entries (must run off the main UI thread).

\* **Password Reveal**: Under 100ms (instantaneous local decryption in-memory using Keystore cached VMK).

\* **Synchronization Latency**: Under 5 seconds from the moment internet connectivity is detected or a manual refresh is triggered to local/remote database alignment.



\---



\# 49. FUTURE ROADMAP



Phase 2



\* Secure Notes

\* Advanced Password Reports

\* Shared Vaults



Phase 3



\* Credit Card Storage

\* Secure Documents



Phase 4



\* Account Migration

\* Cross-Platform Support

\* Desktop Application

\* Browser Extensions



\---



\# 50. SUCCESS CRITERIA



The product is considered successful when:



\* Passwords synchronize correctly.

\* Autofill works reliably.

\* Security layers function correctly.

\* Export system functions correctly.

\* Multi-device support functions correctly.

\* Offline mode functions correctly.

\* Performance remains smooth.

\* Users can manage passwords securely and efficiently.



\---



\# 51. ARCHITECTURAL CONSTRAINTS & OUT OF SCOPE



\## Backend Platform

\* The backend API gateway is built on **Firebase Cloud Functions** (Node.js environment), exposing HTTP/TLS REST endpoints that validate authentication tokens and communicate with MongoDB Atlas.



\## Third-Party & Native Libraries

\* **PDF Generation**: Native Android `PdfDocument` API is used for standard data reporting to avoid bloat from heavy third-party libraries.

\* **Google Sign-In**: Implementation uses the modern Android **Credential Manager API** for Google Auth integration.



\## Out of Scope (Phase 1)

\* **Credential Import**: No import mechanism (CSV/JSON) is provided for external vaults.

\* **Local Decrypted Backups**: Vault backups to local unencrypted/encrypted files are not supported.

\* **Self-hosted database option**: Vault storage is hosted exclusively on the centralized MongoDB Atlas backend.



