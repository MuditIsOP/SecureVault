# SECUREVAULT - TECHNICAL REQUIREMENTS DOCUMENT (TRD)

---

## 1. Platform and Runtime

### Client Application (Android)
* **Target Platforms**: Android Native Mobile Devices
* **Minimum SDK Version**: API Level 26 (Android 8.0 Oreo)
  * *Justification*: The Android Autofill Framework (`Android Autofill Framework` in Blueprint Section 1) was introduced natively in API level 26.
* **Target SDK Version**: API Level 34 (Android 14)
* **Runtime Environment**: Android Runtime (ART) executing compiled JVM bytecode.

### Backend Services (Gateway)
* **Runtime Environment**: Node.js 20 LTS (Node 20.x environment running on Firebase Cloud Functions)
  * *Justification*: Aligning with secure Node.js runtimes supported by Firebase gateway cloud deployment.
* **Database Host**: MongoDB Atlas Serverless/Cluster running MongoDB Engine 6.0+

---

## 2. Technology Stack

Every layer below matches the technologies listed in `Blueprint.md` Section 1:

| Layer | Technology | Target Version | Blueprint Constraint / Justification |
| :--- | :--- | :--- | :--- |
| **Language** | Kotlin | `1.9.22` | Core Stack Requirement (`Kotlin`) |
| **UI Framework** | Material 3 | `1.2.0` | Material Design UI Standard (`Material 3`) |
| **Local Cache DB** | Room Database | `2.6.1` | Local SQLite database abstractor (`Room Database`) |
| **Local DB Encryption** | SQLCipher | `4.5.4` | Database encryption at rest wrapper (`SQLCipher`) |
| **Autofill Hook** | Android Autofill Framework | Native (API 26+) | Auto-credential filling interface (`Android Autofill Framework`) |
| **Identity / Auth** | Firebase Authentication | `22.3.1` | Google Sign-in session validator (`Firebase Authentication`) |
| **Client Sign-In** | Android Credential Manager | `1.2.1` | Modern Google credential API helper (`Credential Manager API`) |
| **Secure Key Cache** | Android Keystore | Native | Secure storage of cryptographic keys (`Android Keystore`) |
| **Core Cryptography** | AES-GCM (AES Encryption) | 256-bit | In-memory encryption of vault entry fields (`AES Encryption`) |
| **App Architecture** | MVVM | Architecture Pattern | UI-data binding separation (`MVVM Architecture`) |
| **Background Sync** | Android WorkManager | `2.9.0` | Connectivity-constrained queue worker (`WorkManager`) |
| **Backend Gateway** | Firebase Cloud Functions | Node.js v20 | Server API endpoints processing database requests (`Firebase Cloud Functions`) |
| **Cloud Database** | MongoDB Atlas | MongoDB 6.0+ | Core cloud storage provider (`MongoDB Atlas`) |

---

## 3. Third-Party Integrations

### 1. Google Sign-In via Android Credential Manager
* **Purpose**: Performs user authentication.
* **SDK Dependency**: `androidx.credentials:credentials:1.2.1` and `androidx.credentials:credentials-play-services-auth:1.2.1`
* **Fallback Behavior**: None. Since Google Sign-In is the only authentication mechanism supported by the blueprint, if this service is down, the user cannot authenticate. If offline, the app falls back to cached local session token verification.

### 2. Firebase Authentication SDK
* **Purpose**: Exposes JWT token parsing and token lifecycle hooks.
* **SDK Dependency**: `com.google.firebase:firebase-auth-ktx:22.3.1`
* **Fallback Behavior**: In the event of a network drop, Firebase Auth local cached session values are used to authenticate local storage access.

### 3. SQLCipher Database Engine
* **Purpose**: Fully encrypts the local Room database SQLite file.
* **SDK Dependency**: `net.zetetic:android-database-sqlcipher:4.5.4@aar`
* **Fallback Behavior**: None. If SQLCipher fails to initialize or key verification fails, the database remains locked, preventing any data access.

### Version Pinning Policy
* All dependencies in `build.gradle.kts` and `package.json` must be pinned to exact versions (e.g., `2.6.1` instead of `2.6.+` or `^2.6.1`). Major and minor version upgrades must be executed through explicit Pull Requests after passing automated regression tests.

---

## 4. Development Standards

### Language Version
* **Kotlin**: `1.9.22` (utilizing JVM target 17)
* **Node.js (ESLint)**: ES2022

### Code Style Guide (Kotlin)
* **Indentation**: 4 spaces (no tabs).
* **Braces**: K&R style (opening brace on the same line as the header, closing brace on a new line).
* **Maximum Line Length**: 120 characters.
* **Blank Lines**: Exactly 1 blank line to separate functions; maximum of 1 blank line inside function bodies to separate logical blocks.

### Folder and Module Structure

```
SecureVault/
│
├── android-client/                  # Kotlin Android application module
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       └── main/
│   │           ├── AndroidManifest.xml
│   │           └── java/com/securevault/app/
│   │               ├── data/        # Models, Room Entities, DAOs, SyncQueue
│   │               ├── di/          # Dependency Injection (Hilt/Koin)
│   │               ├── security/    # Keystore helpers, Cryptography (AES, SQLCipher)
│   │               ├── service/     # AutofillService implementation
│   │               ├── ui/          # ViewModels, Material 3 Composable screens
│   │               └── worker/      # WorkManager Sync Workers
│   └── build.gradle.kts
│
└── backend-gateway/                 # Firebase Cloud Functions (Node.js API)
    ├── functions/
    │   ├── index.js                 # API entry point
    │   ├── package.json
    │   └── src/
    │       ├── auth/                # Firebase Token validation middleware
    │       └── database/            # MongoDB Atlas connection manager
    └── firebase.json
```

### Naming Conventions
1. **Source Files**: Must use CamelCase matching the primary class name (e.g., `AutofillService.kt`).
2. **Classes**: Must use PascalCase nouns (e.g., `PasswordRepository`).
3. **Functions**: Must use camelCase verbs (e.g., `decryptPasswordData()`).
4. **Variables**: Must use camelCase nouns (e.g., `failedAttemptCount`).
5. **Database Tables**: Must use snake_case plural nouns (e.g., `sync_queue`, `vault_passwords`).

### Git Standards
* **Branching Model**: GitFlow. Feature branches branch from `develop` and merge back to `develop` via Pull Requests. `master` represents production-ready releases.
* **Branch Naming**: `feature/[F-ID]-[short-description]` (e.g., `feature/F-AUTH-03-pin-verification`).
* **Commit Message Format**: Must follow Conventional Commits format:
  * `type(scope): message` (where type is `feat`, `fix`, `docs`, `style`, `refactor`, or `test`).
  * *Example*: `feat(auth): implement 2-hour lockout state enforcement for PIN F-AUTH-04`

### Pull Request (PR) Requirements
* **Minimum Reviewers**: At least 1 Senior Engineer approval.
* **CI Validation**: Automated build verification, static analysis (KtLint, ESLint), and all unit tests must pass.
* **PR Checklist**:
  * [ ] Traceability: PR tags specific Feature ID (F-ID).
  * [ ] Security: No plaintext passwords or keys logged or exposed.
  * [ ] Migration: Schema version increments include SQLCipher testing.

---

## 5. Build and Deployment

### Local Setup (Day One)
1. Install Java Development Kit (JDK) 17.
2. Install Android Studio (Hedgehog or later).
3. Clone the repository: `git clone https://github.com/securevault/securevault.git`
4. Open the `android-client` folder in Android Studio and wait for Gradle sync to complete.
5. Create a local debug keystore file: `keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000`
6. Run the application on a connected Android 8.0+ emulator or physical device using the `debug` build variant.

### Build Pipeline Stages (CI/CD)
1. **Trigger**: Pull Request opened to `develop` or `master`.
2. **Lint**: Execute Kotlin Linter (`./gradlew ktlintCheck`) and backend ESLint (`npm run lint`).
3. **Unit Tests**: Execute Android unit tests (`./gradlew testDebugUnitTest`) and backend Jest tests (`npm run test`).
4. **Build**: Build release APK (`./gradlew assembleRelease`) and build Functions package (`npm run build`).
5. **Artifact Storage**: Save compiled APK and source map files as pipeline build artifacts.

### Environments Configuration

| Parameter | Development (dev) | Staging (staging) | Production (prod) |
| :--- | :--- | :--- | :--- |
| **Google Sign-In Client ID** | Dev Firebase Project ID | Staging Firebase Project ID | Production Firebase Project ID |
| **MongoDB Atlas URI** | `mongodb+srv://dev-vault` | `mongodb+srv://staging-vault` | `mongodb+srv://prod-vault` |
| **KMS Key ARN** | Dev Mock KMS Key | Staging KMS AWS/GCP Key | Production KMS AWS/GCP Key |
| **API Endpoints** | Localhost emulator / Cloud Dev | Staging Gateway URL | Production Gateway URL |
| **Proguard/R8** | Disabled | Enabled (debug symbols) | Enabled (fully obfuscated) |

### Release Versioning
* Semantic Versioning (`vMAJOR.MINOR.PATCH`) is enforced:
  * **MAJOR**: Architectural changes or API breaks (e.g., transitioning to multi-vaults).
  * **MINOR**: New features added (e.g., adding CSV Export).
  * **PATCH**: Security hotfixes or bug resolutions (e.g., PIN rate limit tweaks).

---

## 6. Performance Requirements (SLA Targets)

These targets map directly to the non-functional performance requirements specified in [PRD.md](file:///d:/Programming/SecureVault/Documents/PRD.md):

* **App Launch SLA**: PIN/Biometric authentication prompt must display in **< 2.0 seconds** from cold launch.
* **Search SLA**: Querying local database entries on the dashboard must filter results and update the UI in **< 100ms** (runs asynchronously on background thread).
* **Decryption SLA**: Local decryption of AES-256 encrypted fields using Android Keystore keys must take **< 100ms** (evaluated during reveal or copy action).
* **Synchronization SLA**: The processing of pending local actions in `Sync Queue` to MongoDB Atlas must reconcile and clear in **< 5.0 seconds** from the moment network connection transitions to online.

---

## 7. Dependency Policy

### Criteria for Adding Dependencies
Before adding any new dependency to `build.gradle.kts` or `package.json`, the developer must justify:
1. **Size Impact**: Must not increase the compiled release APK size by more than 1MB.
2. **Maintenance Status**: The dependency must have active maintenance (at least one release in the last 6 months).
3. **Security Auditing**: The library must not contain unresolved vulnerabilities scoring high or critical on the CVE database.

### Vulnerability Scan Cadence
* Automated dependency vulnerability scans (using OWASP Dependency-Check or Snyk) must run weekly on the `develop` branch.
* Production builds will be blocked if critical or high-level CVE vulnerabilities are detected in the active dependency tree.

---

## 8. Hard Constraints

* **NO Plaintext VMK Storage**: The Vault Master Key (VMK) must never be written to persistent storage (Shared Preferences, Datastore, SQLite, log files) in plaintext. It must remain in volatile memory and cached only in encrypted form via the Android Keystore.
  * *Reason*: Writing the VMK to disk in plaintext compromises the entire database security model, exposing all passwords if the device is rooted or storage is inspected.
* **NO Third-Party Keyboard/Autofill Interception**: Autofill suggestions must only be presented via the native Android Autofill framework service. Customized IME keyboard entries are strictly forbidden.
  * *Reason*: Standard keyboards can log keystrokes, presenting a keylogging risk to the password fields.
* **NO Local Decrypted Backups**: Plaintext exports of vault backups to local file storage are strictly out of scope for Phase 1. Exporting CSV files must trigger warning screens and share via secure system sheets only.
  * *Reason*: Creating persistent plaintext files on local public directories breaks secure storage policies.
* **NO Screenshot/Recording Allowance**: The app must set the flag `WindowManager.LayoutParams.FLAG_SECURE` application-wide on all activities.
  * *Reason*: Protects user credentials from overlay malware, casting leaks, and background screen capture.
