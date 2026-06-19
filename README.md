# SecureVault
**A secure, offline-first, cloud-synchronized password manager for Android.**

## About

SecureVault addresses the critical need for a password manager that prioritizes security without sacrificing usability. In an era of increasing credential theft and data breaches, users need a solution that encrypts their data locally with hardware-backed keys, works offline, and synchronizes securely across devices.

**Target Users:**
- **Students** — Managing school portal credentials alongside personal logins
- **Professionals** — Secure credential management with export capabilities
- **Developers** — Advanced features with environment security awareness
- **General Users** — Simple, secure password management with auto-sync

## Features

### P0 — Core Features
- 🔐 **Google Sign-In Authentication** (F-AUTH-01) — One-tap Google authentication with Firebase
- 🔑 **6-Digit PIN Protection** (F-AUTH-02) — PIN-based vault access with 5-attempt lockout
- ❓ **Security Question Recovery** (F-AUTH-03) — Knowledge-based recovery for Vault Master Key
- 🛡️ **Backup Codes** (F-AUTH-05) — Two single-use emergency recovery codes
- 📋 **Password Vault CRUD** (F-VAULT-02) — Create, view, edit, soft-delete credentials
- 📂 **Category Organization** (F-VAULT-03) — Organize entries with custom categories
- 🗑️ **Trash Management** (F-VAULT-04) — Soft-deleted entries recoverable before permanent deletion
- 🔄 **Background Sync** (F-SYNC-01/02) — Automatic cloud synchronization via WorkManager
- ⚡ **Conflict Resolution** (F-SYNC-03) — Version + timestamp hybrid resolution
- 📱 **Multi-Device Management** (F-DEV-01) — 3-device limit with session revocation
- 🧪 **Full Test Coverage** (task-025) — 100% IT/ST/UAT test ID coverage

### P1 — Enhanced Features
- 👆 **Biometric Unlock** (F-AUTH-06) — Fingerprint/face unlock via Android Keystore
- 🔍 **Password Strength Meter** (F-VAULT-05) — Real-time strength scoring (WEAK/MEDIUM/STRONG)
- 🎲 **Password Generator** (F-GEN-01) — Customizable length, character sets, exclusion rules
- ✍️ **Autofill Integration** (F-FILL-01) — Android Autofill Framework provider
- 📄 **PDF Export** (F-EXP-01) — AES-256 password-protected PDF export
- 📊 **CSV Export** (F-EXP-02) — RFC 4180 compliant plaintext export with security warning
- ⚠️ **Environment Warnings** (F-DEV-02) — Root detection and USB debugging alerts

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| **Client** | Kotlin + Android SDK | API 26+ (Android 8.0+) |
| **UI** | Material Design 3 (Material You) | 1.11.x |
| **Architecture** | MVVM + Repository Pattern | — |
| **Local DB** | Room + SQLCipher | Room 2.6.x, SQLCipher 4.5.x |
| **Key Storage** | Android Keystore (AES-256-GCM) | Hardware-backed |
| **Auth** | Firebase Authentication | 22.x |
| **Backend** | Firebase Cloud Functions (Node.js) | Node 18 |
| **Cloud DB** | MongoDB Atlas | 7.x |
| **API Framework** | Express.js | 4.x |
| **Validation** | Joi | 17.x |
| **Testing** | Jest + Supertest (Backend), JUnit + Espresso (Client) | — |

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK** 17+
- **Node.js** 18+ and npm 9+
- **Firebase CLI** (`npm install -g firebase-tools`)
- **Android SDK** API Level 26+ (Build Tools 34.x)
- **Google Services JSON** — Firebase project configuration

## Installation

### Backend Gateway
```bash
cd backend-gateway/functions
npm install
```

### Android Client
1. Open `android-client/` in Android Studio
2. Place `google-services.json` in `android-client/app/`
3. Sync Gradle and build

## Configuration

### Environment Variables

Create `.env` in `backend-gateway/functions/`:

```env
# Firebase Configuration
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_PRIVATE_KEY=your-private-key-placeholder
FIREBASE_CLIENT_EMAIL=your-client-email

# MongoDB Atlas
MONGODB_URI=mongodb+srv://user:password@cluster.mongodb.net/securevault
MONGODB_DB_NAME=securevault

# Security
JWT_SECRET=your-jwt-secret-placeholder
CHALLENGE_TOKEN_SECRET=your-challenge-token-secret
```

> ⚠️ **Never commit real secrets.** Use `.env.example` as a template.

### Android Configuration
- `google-services.json` — Firebase project config (git-ignored)
- SQLCipher key — derived from Android Keystore (no configuration needed)

## API Documentation

### Endpoints Summary

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/v1/auth/login` | None | Google token verification & registration |
| POST | `/v1/auth/security-question/setup` | JWT | Register/update security question |
| POST | `/v1/auth/security-question/verify` | JWT | Verify security answer |
| POST | `/v1/auth/lockout` | JWT | Sync PIN lockout state |
| POST | `/v1/auth/backup-codes/verify` | JWT | Verify backup code |
| POST | `/v1/auth/backup-codes/regenerate` | JWT | Regenerate backup codes |
| GET | `/v1/vault` | JWT | List all credentials |
| POST | `/v1/vault` | JWT | Create credential |
| PUT | `/v1/vault/:id` | JWT | Update credential |
| DELETE | `/v1/vault/:id` | JWT | Soft-delete credential |
| GET | `/v1/categories` | JWT | List categories |
| POST | `/v1/categories` | JWT | Create category |
| PUT | `/v1/categories/:id` | JWT | Update category |
| DELETE | `/v1/categories/:id` | JWT | Delete category |
| GET | `/v1/devices` | JWT | List active device sessions |
| DELETE | `/v1/devices/:id` | JWT | Revoke device session |
| POST | `/v1/devices/register` | JWT | Register device (3-device limit) |
| POST | `/v1/sync` | JWT | Process sync queue payloads |

### Error Envelope
All errors follow a standard format:
```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable description.",
    "timestamp": 1718274567000
  }
}
```

## Project Structure

```
SecureVault/
├── android-client/
│   └── app/src/main/java/com/securevault/app/
│       ├── data/           # Room entities, DAOs, AppDatabase
│       ├── security/       # CryptographyHelper, PinHasher, BiometricHelper, EnvironmentChecker
│       ├── ui/
│       │   ├── auth/       # Onboarding, PIN, Security Question, Backup Codes, Env Warning
│       │   ├── vault/      # Dashboard, Credential Edit/Detail
│       │   ├── generator/  # Password Generator
│       │   ├── settings/   # Settings, Active Devices
│       │   └── export/     # PDF/CSV Export
│       └── worker/         # SyncWorker (WorkManager)
├── backend-gateway/
│   └── functions/
│       ├── controllers/    # Auth, Security, Lockout, Backup, Vault, Category, Sync, Device
│       ├── middleware/     # JWT verification (authMiddleware)
│       └── test/          # Jest + Supertest integration tests
├── Documents/             # Locked specification documents
├── Tasks/                 # Task roadmap and completion notes
├── CHANGELOG.md           # Full development history
└── README.md              # This file
```

## Testing

### Backend Tests
```bash
cd backend-gateway/functions
npm test          # Run all tests
npx jest --verbose  # Run with detailed output
```

**Coverage:** 9 test suites, 100 tests
- IT-AUTH-01 through IT-AUTH-07 (Authentication)
- IT-VAULT-01 through IT-VAULT-09 (Vault & Categories)
- IT-DEV-01, IT-DEV-02 (Device Management)
- IT-SYNC-01 (Sync & Conflict Resolution)
- ST-STRIDE-01 through ST-STRIDE-09 (Security STRIDE)
- UAT-STUDENT-01 through UAT-GENERAL-01 (User Acceptance)

### Android Tests
```bash
# Unit tests (local JVM)
./gradlew test

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

**Coverage:** PinHasher, CryptographyHelper, BiometricHelper, PINLockoutManager, SecurityQuestionHasher, AppDatabaseSchema, VaultUITest (18 Espresso tests)

## Security

SecureVault was designed with a formal STRIDE threat model covering:

- **Spoofing** — Firebase JWT verification, FLAG_SECURE on all screens
- **Tampering** — Room + SQLCipher encryption with Android Keystore keys
- **Repudiation** — Audit logging via sync queue
- **Information Disclosure** — AES-256-GCM encryption, secure memory handling
- **Denial of Service** — API rate limiting per endpoint
- **Elevation of Privilege** — UID-scoped database queries, Joi input validation

All vault data is classified as **RESTRICTED** and encrypted at rest using SQLCipher with a hardware-backed key from Android Keystore.

## License

MIT License

---

*Built with ❤️ following a specification-driven development process with 25 structured tasks, 100% test coverage, and zero spec deviations.*
