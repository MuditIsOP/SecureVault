'use strict';

/**
 * IT-AUTH-03 & IT-AUTH-04: Security Question Setup & Verification tests.
 *
 * Spec refs:
 *   - Testing_Strategy.md IT-AUTH-03 (§2.1) — setup endpoint
 *   - Testing_Strategy.md IT-AUTH-04 (§2.1) — verify endpoint
 *   - API_Spec.md §2 — request/response contract for both endpoints
 *   - Security_Requirements.md §6 — adaptive delay, no plaintext in responses
 *   - PRD F-AUTH-02 — AC#2 (no plaintext), AC#3 (challenge token returned)
 *   - Permissions_Matrix.md §3 C2 — update requires challengeToken
 *
 * Test strategy per Testing_Strategy.md §2.4:
 *   Mock Firebase Auth JWT. Mock MongoDB driver. No real PBKDF2 needed for route tests.
 */

const request = require('supertest');
const express = require('express');
const { setupSecurityQuestion, verifySecurityQuestion } = require('../controllers/securityController');

jest.setTimeout(30000); // 30s timeout for PBKDF2 operations

// Mock firebase-admin
jest.mock('firebase-admin', () => ({
    auth: () => ({ verifyIdToken: jest.fn(), createCustomToken: jest.fn() }),
    initializeApp: jest.fn()
}));

function buildApp(dbMock) {
    const app = express();
    app.use(express.json());
    app.locals.db = dbMock;
    // Inject req.user directly (simulating verified authMiddleware)
    app.use((req, res, next) => { req.user = { uid: 'uid_test', email: 'test@test.com' }; next(); });
    app.post('/v1/auth/security-question/setup', setupSecurityQuestion);
    app.post('/v1/auth/security-question/verify', verifySecurityQuestion);
    return app;
}

function buildDbMock({ userExists = true, hasQuestion = false, failedAttempts = 0, answerHash = null } = {}) {
    const user = userExists
        ? {
            id: 'uid_test',
            securityQuestionId: hasQuestion ? 'q_01' : '',
            securityAnswerHash: answerHash || '',
            securityVerifyFailedAttempts: failedAttempts
          }
        : null;

    return {
        collection: jest.fn(() => ({
            findOne: jest.fn().mockResolvedValue(user),
            updateOne: jest.fn().mockResolvedValue({ modifiedCount: 1 }),
            insertOne: jest.fn().mockResolvedValue({})
        }))
    };
}

// ---------------------------------------------------------------------------
// IT-AUTH-03: POST /v1/auth/security-question/setup
// ---------------------------------------------------------------------------

describe('IT-AUTH-03: POST /v1/auth/security-question/setup', () => {

    test('200 OK — first-time setup (no challengeToken required)', async () => {
        const app = buildApp(buildDbMock({ userExists: true, hasQuestion: false }));

        const res = await request(app)
            .post('/v1/auth/security-question/setup')
            .send({
                securityQuestionId: 'q_01',
                securityAnswerHash: 'aGFzaGVkX3NhbHQ=:aGFzaGVkX2tleQ==',
                encryptedVmk: 'ENCRYPTED_VMK_PLACEHOLDER'
            });

        expect(res.status).toBe(200);
        expect(res.body.status).toBe('success');
    });

    test('401 — update attempt without challengeToken rejected', async () => {
        // User already has a security question — update requires challenge token
        const app = buildApp(buildDbMock({ userExists: true, hasQuestion: true }));

        const res = await request(app)
            .post('/v1/auth/security-question/setup')
            .send({
                securityQuestionId: 'q_02',
                securityAnswerHash: 'aGFzaGVkX3NhbHQ=:aGFzaGVkX2tleQ==',
                encryptedVmk: 'ENCRYPTED_VMK_PLACEHOLDER'
            });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });

    test('400 — missing securityAnswerHash', async () => {
        const app = buildApp(buildDbMock());

        const res = await request(app)
            .post('/v1/auth/security-question/setup')
            .send({
                securityQuestionId: 'q_01',
                encryptedVmk: 'ENCRYPTED_VMK_PLACEHOLDER'
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('400 — missing encryptedVmk', async () => {
        const app = buildApp(buildDbMock());

        const res = await request(app)
            .post('/v1/auth/security-question/setup')
            .send({
                securityQuestionId: 'q_01',
                securityAnswerHash: 'aGFzaGVkX3NhbHQ=:aGFzaGVkX2tleQ=='
            });

        expect(res.status).toBe(400);
    });

    // Security: response must NOT include the hash — Security_Requirements.md §6
    test('Response body does NOT contain securityAnswerHash', async () => {
        const app = buildApp(buildDbMock({ userExists: true, hasQuestion: false }));
        const submittedHash = 'aGFzaGVkX3NhbHQ=:aGFzaGVkX2tleQ==';

        const res = await request(app)
            .post('/v1/auth/security-question/setup')
            .send({
                securityQuestionId: 'q_01',
                securityAnswerHash: submittedHash,
                encryptedVmk: 'ENCRYPTED_VMK_PLACEHOLDER'
            });

        expect(JSON.stringify(res.body)).not.toContain(submittedHash);
    });
});

// ---------------------------------------------------------------------------
// IT-AUTH-04: POST /v1/auth/security-question/verify
// ---------------------------------------------------------------------------

describe('IT-AUTH-04: POST /v1/auth/security-question/verify', () => {

    test('400 — missing securityAnswer', async () => {
        const app = buildApp(buildDbMock({ userExists: true }));

        const res = await request(app)
            .post('/v1/auth/security-question/verify')
            .send({});

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('401 — user has no security question configured', async () => {
        // User exists but answerHash is empty
        const app = buildApp(buildDbMock({ userExists: true, answerHash: '' }));

        const res = await request(app)
            .post('/v1/auth/security-question/verify')
            .send({ securityAnswer: 'london' });

        expect(res.status).toBe(401);
    });

    test('401 — wrong answer returns UNAUTHENTICATED (not the stored hash)', async () => {
        // answerHash is a real PBKDF2 hash of "london" — wrong answer "paris" should fail
        // For test purposes we pass an obviously wrong hash format so PBKDF2 verify fails
        const app = buildApp(buildDbMock({
            userExists: true,
            answerHash: 'wrongsalt==:d3JvbmdrZXlwbGFjZWhvbGRlcm11c3RiZTMyYnl0ZXM='
        }));

        const res = await request(app)
            .post('/v1/auth/security-question/verify')
            .send({ securityAnswer: 'paris' });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
        // Response must NOT contain any hash — Security_Requirements.md §6
        expect(JSON.stringify(res.body)).not.toContain('wrongsalt');
    });

    test('Error response includes timestamp — API_Spec.md §5', async () => {
        const app = buildApp(buildDbMock({ userExists: true, answerHash: '' }));

        const res = await request(app)
            .post('/v1/auth/security-question/verify')
            .send({ securityAnswer: 'test' });

        expect(res.body.error).toHaveProperty('timestamp');
        expect(typeof res.body.error.timestamp).toBe('number');
    });
});
