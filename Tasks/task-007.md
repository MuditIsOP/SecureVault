# Task 007: PIN Lockout State Sync & Delay Enforcement
**Status:** completed
**Priority:** P0
**Complexity:** medium
**Estimated Time:** 10 hours
**Tags:** security, backend, frontend

## Description
Implement progressive delays, lockout countdown overlays, local DB persistency, and cloud synchronization APIs for failed PIN attempts.

SecureVault is an Android native password manager built using Kotlin, Material 3, Firebase Authentication, and MongoDB Atlas. It leverages local encrypted storage (Room with SQLCipher) and hardware-backed device keys (Android Keystore) to deliver a secure, offline-first, and cloud-synchronized password management experience.

This task is focused on implementing the PIN Lockout State Sync & Delay Enforcement module, mapping directly to specific architectural layers and security guidelines defined in the specification stack.

## Document References

### PRD
- **Feature ID:** F-AUTH-04
- **User Story:** As a Professional, I want the app to rate-limit PIN attempts so that brute-force attacks are blocked.
- **Acceptance Criteria:**
- Failed PIN attempts from 1 to 5 must allow immediate reentry.
- Failed PIN attempts from 6 to 10 must introduce progressive entry delays: 6th (30s), 7th (1m), 8th (2m), 9th (5m), 10th (15m), during which the PIN entry controls are disabled.
- The 11th consecutive failed attempt must place the app into a 'lockout' state for exactly 2 hours.
- The lockout state and failed attempt counter must be stored in the local SQLCipher database and synced to the backend. Reinstalling the app or clearing cache must not bypass the lockout; signing in again with Google must pull the lock status from the server and restore the lockout.

### SRS
- **Requirement IDs:** The client SHALL enforce progressive delay lockouts for incorrect attempts., The lockout state SHALL be synchronized with the remote backend database.
- **SHALL statements:**
- The client SHALL enforce progressive delay lockouts for incorrect attempts.
- The lockout state SHALL be synchronized with the remote backend database.

### Architecture
- **Component(s):** SQLCipher Local Database, Firebase Functions Gateway
- **Data Flow:** Failed attempts -> Increment count in DB -> POST /v1/auth/lockout.

### Database
- **Tables:** users
- **Migrations:** N/A

### API
- **Endpoints:** POST /v1/auth/lockout
- **Auth/Role guard:** Bearer JWT authentication.

### Security
- **Threats addressed:** ST-GATEWAY-DOS-01
- **Data classifications:** INTERNAL account status configurations.

### Design
- **Screen(s):** SCR-ATH-03
- **Components used:** Button, Toast, Dialog
- **Design tokens:** error red (#F2B8B5), Display Large typography.

### Testing
- **Unit tests:** PINLockoutManager calculations check (Happy paths, bounds).
- **Integration tests:** IT-AUTH-05 lockout sync database tests.
- **Security tests:** ST-STRIDE-08 validation testing.
- **UAT scenarios:** None

## Acceptance Criteria
- [x] Failed PIN attempts from 1 to 5 must allow immediate reentry.
- [x] Failed PIN attempts from 6 to 10 must introduce progressive entry delays: 6th (30s), 7th (1m), 8th (2m), 9th (5m), 10th (15m), during which the PIN entry controls are disabled.
- [x] The 11th consecutive failed attempt must place the app into a 'lockout' state for exactly 2 hours.
- [x] The lockout state and failed attempt counter must be stored in the local SQLCipher database and synced to the backend. Reinstalling the app or clearing cache must not bypass the lockout; signing in again with Google must pull the lock status from the server and restore the lockout.

- [x] All relevant SRS SHALL requirements satisfied
- [x] Security requirements from Security_Requirements.md met
- [x] Tests from Testing_Strategy.md written and passing
- [x] Permissions enforced per Permissions_Matrix.md

## Dependencies
- task-006: Required parent layer initialization.


## Implementation Approach
### Step-by-step Plan:
1. Write PINLockoutManager.kt checking failed count and return delay values.
2. Build SCR-ATH-03 countdown timer view displaying real-time delay (hh:mm:ss).
3. Write POST /v1/auth/lockout endpoint syncing attempts count and timestamps.
4. Implement startup check looking up user lockout record values during login authentication.


### Technical Considerations:
- Always cross-verify lockout timestamp comparisons against network provider clocks.
- Enforce strict memory sanitization patterns to clear decrypted secrets immediately after usage.
- Standard platform packages must align with MVVM design paradigms.

### Architecture/Design Notes:
- Government decisions here are guided by components established in Architecture.md.
- Encryption relies on Room+SQLCipher standard support database factories.

## Files to Modify/Create
- `android-client/app/src/main/java/com/securevault/app/ui/auth/LockoutActivity.kt` — Created.
- `android-client/app/src/main/java/com/securevault/app/security/PINLockoutManager.kt` — Created.
- `backend-gateway/functions/controllers/lockoutController.js` — Created.
- `android-client/app/src/main/res/layout/activity_lockout.xml` — Created.
- `android-client/app/src/main/res/layout/activity_pin_unlock.xml` — Created.
- `android-client/app/src/main/java/com/securevault/app/data/dao/UserDao.kt` — Created.
- `backend-gateway/functions/test/lockoutController.test.js` — Created.
- `android-client/app/src/test/java/com/securevault/app/security/PINLockoutManagerTest.kt` — Created.


## Testing Requirements
Reference: /documents/Testing_Strategy.md

### Unit Tests (Part 1):
- [x] PINLockoutManager calculations check (Happy paths, bounds).

### Integration Tests (Part 2):
- [x] IT-AUTH-05 lockout sync database tests.

### Security Tests (Part 3):
- [x] ST-STRIDE-08 validation testing.

### UAT Scenarios (Part 4):
- [x] None

### Manual Verification:
- [x] Verify screen transitions render Loading, Empty, and Error state variations accurately.

## Edge Cases to Handle
- Device network disconnects mid-lockout API sync request.
- System clock manual modifications to bypass timer bounds.


## Notes & Considerations
- Reference Security_Requirements.md Section 1 STRIDE mitigations.
- All code changes must follow conventional commits standards and maintain documentation integrity.

## Questions/Clarifications Needed
- None

---
## ✅ COMPLETION NOTES
**Completed:** 2026-06-16
**Actual Time:** 4 hours

### What Was Done
- Created client PINLockoutManager and userDao database layers to enforce progressive delays and lockouts (satisfies PRD F-AUTH-04, SRS FR-AUTH-04).
- Implemented LockoutActivity screen with real-time countdown timer layout using design tokens (satisfies SCR-ATH-03, Design tokens).
- Implemented backend lockoutController sync route and updated login controller to return lockout states preventing uninstall bypasses (satisfies PRD F-AUTH-04 AC #4).
- Added Jest integration test suite and JUnit manager calculations unit tests.

### Spec Requirements Satisfied
- PRD: F-AUTH-04 AC #1 ✅, AC #2 ✅, AC #3 ✅, AC #4 ✅
- SRS: FR-AUTH-04 ✅
- Security: ST-GATEWAY-DOS-01 ✅, ST-STRIDE-08 ✅
- Permissions: conditional lockout role checks ✅

### Spec Deviations (if any)
- None

### Tests Performed
- ✅ Unit: PINLockoutManagerTest calculations check — Passed
- ✅ Integration: IT-AUTH-05 lockout sync database tests (lockoutController.test.js) — Passed
- ✅ Security: ST-STRIDE-08 validation testing — Passed

### Files Changed
- `backend-gateway/functions/controllers/lockoutController.js`: lockout state sync controller
- `backend-gateway/functions/controllers/authController.js`: return lockout state on login
- `backend-gateway/functions/index.js`: register lockout sync API route
- `android-client/app/src/main/java/com/securevault/app/security/PINLockoutManager.kt`: client-side lockout calculator
- `android-client/app/src/main/java/com/securevault/app/ui/auth/LockoutActivity.kt`: lockout screen countdown timer UI
- `android-client/app/src/main/java/com/securevault/app/ui/auth/PinUnlockActivity.kt`: integrate client lockout manager checks
- `android-client/app/src/main/java/com/securevault/app/data/dao/UserDao.kt`: userDao Room queries
- `android-client/app/src/main/java/com/securevault/app/data/AppDatabase.kt`: register userDao accessor
- `android-client/app/src/main/java/com/securevault/app/data/api/AuthApiService.kt`: add syncLockout client network call
- `android-client/app/src/main/AndroidManifest.xml`: register LockoutActivity
- `android-client/app/src/main/res/values/colors.xml`: add color_error token
- `android-client/app/src/main/res/layout/activity_lockout.xml`: lockout countdown design Layout
- `android-client/app/src/main/res/layout/activity_pin_unlock.xml`: PIN keypad Layout
- `backend-gateway/functions/test/lockoutController.test.js`: lockout integration tests
- `android-client/app/src/test/java/com/securevault/app/security/PINLockoutManagerTest.kt`: calculations unit tests
- `backend-gateway/functions/test/authController.test.js`: fix Jest mock hoisting issues
- `backend-gateway/functions/test/securityController.test.js`: fix RangeError async timingSafeEqual crashes

### Known Issues / Technical Debt
- None
