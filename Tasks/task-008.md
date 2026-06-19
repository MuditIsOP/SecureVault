# Task 008: Backup Code Recovery & PIN Resets
**Status:** pending
**Priority:** P1
**Complexity:** medium
**Estimated Time:** 10 hours
**Tags:** security, backend, frontend

## Description
Establish backup recovery code validation, single-use logic, code regeneration settings interfaces, and server verification APIs.

SecureVault is an Android native password manager built using Kotlin, Material 3, Firebase Authentication, and MongoDB Atlas. It leverages local encrypted storage (Room with SQLCipher) and hardware-backed device keys (Android Keystore) to deliver a secure, offline-first, and cloud-synchronized password management experience.

This task is focused on implementing the Backup Code Recovery & PIN Resets module, mapping directly to specific architectural layers and security guidelines defined in the specification stack.

## Document References

### PRD
- **Feature ID:** F-AUTH-05
- **User Story:** As a General smartphone user, I want backup codes generated during onboarding so that I can recover vault access if I forget my PIN.
- **Acceptance Criteria:**
- During onboarding (Step 8), the app must generate and display exactly two unique, alphanumeric backup codes formatted as XXXX-XXXX (e.g., AB7K-XP92).
- Backup codes must be single-use. Validating a correct backup code must allow the user to reset their device PIN.
- Regenerating backup codes in the security settings must invalidate all previously generated codes.

### SRS
- **Requirement IDs:** The client SHALL generate recovery backup codes for offline account resets., The backend gateway SHALL enforce single-use execution on backup code validations.
- **SHALL statements:**
- The client SHALL generate recovery backup codes for offline account resets.
- The backend gateway SHALL enforce single-use execution on backup code validations.

### Architecture
- **Component(s):** Firebase Functions Gateway, Security Modules
- **Data Flow:** Backup Code entered -> API POST /v1/auth/backup-codes/verify -> Token return -> PIN reset allowed.

### Database
- **Tables:** users
- **Migrations:** N/A

### API
- **Endpoints:** POST /v1/auth/backup-codes/verify, POST /v1/auth/backup-codes/regenerate
- **Auth/Role guard:** Bearer JWT authorization.

### Security
- **Threats addressed:** None
- **Data classifications:** RESTRICTED backup code hashes.

### Design
- **Screen(s):** SCR-ONB-04
- **Components used:** Card, Button, Toast
- **Design tokens:** monospace font, primary color (#D0BCFF).

### Testing
- **Unit tests:** Backup code generation formats (e.g. XXXX-XXXX regex verification).
- **Integration tests:** IT-AUTH-06 and IT-AUTH-07 integration tests.
- **Security tests:** Verify that used backup codes return 401 on repeat calls.
- **UAT scenarios:** UAT-GENERAL-01 Step 4 verification.

## Acceptance Criteria
- [ ] During onboarding (Step 8), the app must generate and display exactly two unique, alphanumeric backup codes formatted as XXXX-XXXX (e.g., AB7K-XP92).
- [ ] Backup codes must be single-use. Validating a correct backup code must allow the user to reset their device PIN.
- [ ] Regenerating backup codes in the security settings must invalidate all previously generated codes.

- [ ] All relevant SRS SHALL requirements satisfied
- [ ] Security requirements from Security_Requirements.md met
- [ ] Tests from Testing_Strategy.md written and passing
- [ ] Permissions enforced per Permissions_Matrix.md

## Dependencies
- task-006: Required parent layer initialization.


## Implementation Approach
### Step-by-step Plan:
1. Build SCR-ONB-04 backup code display screen showing codes in monospace blocks.
2. Create POST /v1/auth/backup-codes/verify checking hashed parameters.
3. Create POST /v1/auth/backup-codes/regenerate replacing current arrays.
4. Implement client-side PIN reset activity triggered by successful code verification.


### Technical Considerations:
- Keep codes securely hashed using SHA-256 before remote synchronization.
- Enforce strict memory sanitization patterns to clear decrypted secrets immediately after usage.
- Standard platform packages must align with MVVM design paradigms.

### Architecture/Design Notes:
- Government decisions here are guided by components established in Architecture.md.
- Encryption relies on Room+SQLCipher standard support database factories.

## Files to Modify/Create
- `android-client/app/src/main/java/com/securevault/app/ui/onboarding/BackupCodesActivity.kt` — Create/Modify file based on specifications.
- `backend-gateway/functions/controllers/backupController.js` — Create/Modify file based on specifications.


## Testing Requirements
Reference: /documents/Testing_Strategy.md

### Unit Tests (Part 1):
- [ ] Backup code generation formats (e.g. XXXX-XXXX regex verification).

### Integration Tests (Part 2):
- [ ] IT-AUTH-06 and IT-AUTH-07 integration tests.

### Security Tests (Part 3):
- [ ] Verify that used backup codes return 401 on repeat calls.

### UAT Scenarios (Part 4):
- [ ] UAT-GENERAL-01 Step 4 verification.

### Manual Verification:
- [ ] Verify screen transitions render Loading, Empty, and Error state variations accurately.

## Edge Cases to Handle
- Generating backup codes when client has no internet connection.
- Brute forcing recovery code guesses.


## Notes & Considerations
- Reference Permissions_Matrix.md Section 2 backup rules.
- All code changes must follow conventional commits standards and maintain documentation integrity.

## Questions/Clarifications Needed
- None
