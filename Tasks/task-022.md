# Task 022: Serverless Gateway APIs & Conflict Resolution
**Status:** pending
**Priority:** P0
**Complexity:** high
**Estimated Time:** 16 hours
**Tags:** backend, security

## Description
Build Firebase Functions API handlers, MongoDB Atlas connection gateways, hybrid conflict resolvers, and write logs collections.

SecureVault is an Android native password manager built using Kotlin, Material 3, Firebase Authentication, and MongoDB Atlas. It leverages local encrypted storage (Room with SQLCipher) and hardware-backed device keys (Android Keystore) to deliver a secure, offline-first, and cloud-synchronized password management experience.

This task is focused on implementing the Serverless Gateway APIs & Conflict Resolution module, mapping directly to specific architectural layers and security guidelines defined in the specification stack.

## Document References

### PRD
- **Feature ID:** F-SYNC-03
- **User Story:** As a Professional, I want conflicting changes to resolve logically so that I don't lose my latest edits.
- **Acceptance Criteria:**
- Server compares version counter of incoming client updates against the database version.
- If versions differ, the server compares the incoming client's updatedDate with the server's updatedDate.
- The version with the later timestamp wins. If timestamps match exactly, server version is retained, client is updated.

### SRS
- **Requirement IDs:** The backend gateway SHALL resolve sync conflicts using logical versions and timestamps., The backend gateway SHALL restrict database access using user identity parameters.
- **SHALL statements:**
- The backend gateway SHALL resolve sync conflicts using logical versions and timestamps.
- The backend gateway SHALL restrict database access using user identity parameters.

### Architecture
- **Component(s):** Firebase Functions Gateway, MongoDB Atlas Database
- **Data Flow:** Sync payload -> Compare versions on server -> Timestamp resolution -> Write changes.

### Database
- **Tables:** vault_passwords
- **Migrations:** N/A

### API
- **Endpoints:** POST /v1/sync
- **Auth/Role guard:** Bearer JWT authorization.

### Security
- **Threats addressed:** ST-GATEWAY-TAMP-01, ST-GATEWAY-PRIV-01
- **Data classifications:** RESTRICTED vault database.

### Design
- **Screen(s):** N/A
- **Components used:** None
- **Design tokens:** N/A

### Testing
- **Unit tests:** Conflict resolver test logic blocks.
- **Integration tests:** IT-SYNC-01 payload transaction tests.
- **Security tests:** ST-STRIDE-06 and ST-STRIDE-09 security tests.
- **UAT scenarios:** None

## Acceptance Criteria
- [ ] Server compares version counter of incoming client updates against the database version.
- [ ] If versions differ, the server compares the incoming client's updatedDate with the server's updatedDate.
- [ ] The version with the later timestamp wins. If timestamps match exactly, server version is retained, client is updated.

- [ ] All relevant SRS SHALL requirements satisfied
- [ ] Security requirements from Security_Requirements.md met
- [ ] Tests from Testing_Strategy.md written and passing
- [ ] Permissions enforced per Permissions_Matrix.md

## Dependencies
- task-004: Required parent layer initialization.


## Implementation Approach
### Step-by-step Plan:
1. Set up MongoDB Atlas connection helper in db.js.
2. Create POST /v1/sync controller mapping transactions.
3. Write the hybrid conflict resolution checks comparing incoming version and updated_at properties.
4. Enforce strict query bindings where user ID resolves matching verified JWT uid properties.


### Technical Considerations:
- Express controllers must filter NoSQL parameters to prevent injections.
- Enforce strict memory sanitization patterns to clear decrypted secrets immediately after usage.
- Standard platform packages must align with MVVM design paradigms.

### Architecture/Design Notes:
- Government decisions here are guided by components established in Architecture.md.
- Encryption relies on Room+SQLCipher standard support database factories.

## Files to Modify/Create
- `backend-gateway/functions/controllers/syncController.js` — Create/Modify file based on specifications.
- `backend-gateway/functions/utils/db.js` — Create/Modify file based on specifications.


## Testing Requirements
Reference: /documents/Testing_Strategy.md

### Unit Tests (Part 1):
- [ ] Conflict resolver test logic blocks.

### Integration Tests (Part 2):
- [ ] IT-SYNC-01 payload transaction tests.

### Security Tests (Part 3):
- [ ] ST-STRIDE-06 and ST-STRIDE-09 security tests.

### UAT Scenarios (Part 4):
- [ ] None

### Manual Verification:
- [ ] Verify screen transitions render Loading, Empty, and Error state variations accurately.

## Edge Cases to Handle
- Server receives empty transaction array.
- Simultaneous sync operations from different devices.


## Notes & Considerations
- Reference Database_Schema.md Section 1 for ERD collections layout.
- All code changes must follow conventional commits standards and maintain documentation integrity.

## Questions/Clarifications Needed
- None

---
## ✅ COMPLETION NOTES
**Completed:** 2026-06-19
**Actual Time:** 5 hours

### What Was Done
- `syncController.js` — POST /v1/sync: Joi validation (syncActions array + lastSyncTime), hybrid conflict resolution (version counter + timestamp), INSERT/UPDATE/DELETE handlers, uid-scoped queries, soft-delete vault_passwords / hard-delete categories, remote updates query since lastSyncTime. (satisfies F-SYNC-03 AC#1/2/3, SRS FR-SYNC-03a/b)
- `syncController.test.js` — 16 tests: IT-SYNC-01 (INSERT/UPDATE/DELETE/empty/remoteUpdates/conflict resolution), ST-STRIDE-06 (Joi validation: invalid transactionType/tableName/missing fields/missing lastSyncTime), ST-STRIDE-09 (uid-scoped query enforcement), conflict resolver unit tests (client wins/server wins/timestamp tie)
- `index.js` — POST /v1/sync route registered with verifyFirebaseToken middleware

### Spec Requirements Satisfied
- PRD: F-SYNC-03 AC#1 ✅ (version counter comparison), AC#2 ✅ (timestamp comparison), AC#3 ✅ (later wins, ties → server)
- SRS: FR-SYNC-03a ✅ (conflict resolution via versions + timestamps), FR-SYNC-03b ✅ (uid-scoped queries)
- Security: ST-GATEWAY-TAMP-01 ✅ (Joi validation), ST-GATEWAY-PRIV-01 ✅ (uid-scoped access)
- API: POST /v1/sync matches API_Spec.md §4 schema ✅

### Spec Deviations
- None

### Tests Performed
- ✅ IT-SYNC-01: INSERT, UPDATE (client wins), UPDATE (server wins), timestamp tie, DELETE soft/hard, empty array, remote updates — 8 tests
- ✅ ST-STRIDE-06: Invalid transactionType, tableName, missing fields, missing lastSyncTime — 4 tests
- ✅ ST-STRIDE-09: UID-scoped findOne, insertOne, find verification — 1 test
- ✅ Conflict resolver: client wins (same version), server wins (diff version), timestamp tie — 3 tests
- ✅ Total: 75/75 tests passing (59 existing + 16 new)

### Files Changed
- `syncController.js`: Created — Sync controller with conflict resolution
- `syncController.test.js`: Created — 16 integration + unit tests
- `index.js`: Updated — POST /v1/sync route registered

### Known Issues / Technical Debt
- db.js utility not created (MongoDB connection handled via req.app.locals.db pattern matching existing controllers)
- Password history not written during sync UPDATE (would duplicate vaultController logic; deferred)

