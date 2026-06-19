# Contributing to SecureVault

Thank you for contributing! Please follow these guidelines to maintain code quality and spec compliance.

## Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Client | Kotlin + Android SDK | API 26+ |
| UI | Material Design 3 | 1.11.x |
| Architecture | MVVM + Repository Pattern | — |
| Local DB | Room + SQLCipher | 2.6.x / 4.5.x |
| Auth | Firebase Authentication | 22.x |
| Backend | Firebase Cloud Functions (Node.js 18) | — |
| Cloud DB | MongoDB Atlas | 7.x |
| API | Express.js 4.x + Joi 17.x | — |
| Testing | Jest + Supertest (Backend), JUnit + Espresso (Client) | — |

## Setup

### Prerequisites
- Android Studio Hedgehog (2023.1.1)+
- JDK 17+
- Node.js 18+ / npm 9+
- Firebase CLI (`npm install -g firebase-tools`)

### Local Development
```bash
# Backend
cd backend-gateway/functions
cp ../../.env.example .env  # Fill in your values
npm install
npm test

# Android
# Open android-client/ in Android Studio
# Place google-services.json in app/
# Sync Gradle → Build → Run
```

## Git Workflow

### Branch Naming
```
feature/task-XXX-short-description
fix/task-XXX-bug-description
chore/task-XXX-cleanup
```

### Commit Message Format
Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

**Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

**Scopes:** `auth`, `vault`, `sync`, `device`, `export`, `security`, `ui`, `backend`

**Examples:**
```
feat(vault): add soft-delete credential endpoint (IT-VAULT-04)
fix(auth): add try-catch to backup controller (API_Spec.md §5)
test(sync): add conflict resolution tests (ST-STRIDE-06)
docs: update README with deployment instructions
```

### Pull Request Requirements
1. **Title** follows conventional commit format
2. **Description** references:
   - Task ID (e.g., task-023)
   - Spec references (PRD F-ID, SRS FR-ID)
   - Test IDs covered (IT-*, ST-*, UAT-*)
3. **Tests** pass (`npm test` for backend, `./gradlew test` for client)
4. **No spec deviations** without explicit documentation
5. **CHANGELOG.md** updated with spec references

## Code Style

### Kotlin (Android Client)
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- MVVM architecture: Activity → ViewModel → Repository → DAO
- All Activities MUST include `FLAG_SECURE` in `onCreate`
- JSDoc/KDoc comments referencing spec IDs (e.g., `// PRD F-AUTH-02 AC#1`)

### JavaScript (Backend)
- `'use strict'` at top of every file
- Async/await with try-catch (no unhandled rejections)
- Joi validation on all user inputs (Security_Requirements.md §5)
- Error envelope format per API_Spec.md §5
- UID-scoped queries (Security_Requirements.md §3)
- `console.error` for error logging only — never `console.log` in production

### Security Rules
- Never log PII, credentials, tokens, or hashes
- Never hardcode secrets — use environment variables
- All database queries must be scoped by `user_id`
- Input validation on every user-controlled field
- Error responses must never leak internal details

## Specification Documents

All implementation decisions must trace to `/documents/`:
- `PRD.md` — Feature requirements and acceptance criteria
- `SRS.md` — Functional and non-functional requirements
- `API_Spec.md` — Endpoint contracts
- `Database_Schema.md` — Table schemas and constraints
- `Security_Requirements.md` — STRIDE threat mitigations
- `Testing_Strategy.md` — Test case IDs and frameworks
- `Screens.md` — UI screen specifications
- `Design.md` — Design tokens and component specs

**Rule:** Any decision not covered by a spec document must be flagged as a `⚠️ SPEC DEVIATION` in the PR description and CHANGELOG.md.
