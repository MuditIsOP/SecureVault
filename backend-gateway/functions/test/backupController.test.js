'use strict';

/**
 * IT-AUTH-06 & IT-AUTH-07: Backup Code Verification & Regeneration tests.
 *
 * Spec refs:
 *   - Testing_Strategy.md IT-AUTH-06 (§2.1) — verify endpoint
 *   - Testing_Strategy.md IT-AUTH-07 (§2.1) — regenerate endpoint
 *   - API_Spec.md §2 — request/response contract for both endpoints
 *   - Security_Requirements.md §4 — Zero-Knowledge SHA-256 hashed storage
 *   - Security_Requirements.md §5 — Joi schemas input validation
 *   - Permissions_Matrix.md §3 C2 — regeneration requires challengeToken verification
 */

const request = require('supertest');
const express = require('express');
const crypto = require('crypto');
const { verifyBackupCode, regenerateBackupCodes } = require('../controllers/backupController');
const { generateChallengeToken } = require('../controllers/securityController');

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
    app.post('/v1/auth/backup-codes/verify', verifyBackupCode);
    app.post('/v1/auth/backup-codes/regenerate', regenerateBackupCodes);
    return app;
}

function buildDbMock({ userExists = true, backupCodeHashes = '' } = {}) {
    const user = userExists
        ? {
            id: 'uid_test',
            backupCodeHashes: backupCodeHashes
          }
        : null;

    // Expose a single shared collectionMock so assertion spies work across calls
    const collectionMock = {
        findOne: jest.fn().mockResolvedValue(user),
        updateOne: jest.fn().mockResolvedValue({ modifiedCount: 1 }),
        insertOne: jest.fn().mockResolvedValue({})
    };

    return {
        collection: jest.fn(() => collectionMock),
        _collectionMock: collectionMock  // expose for assertion access
    };
}

describe('IT-AUTH-06: POST /v1/auth/backup-codes/verify', () => {

    test('200 OK — correct backup code resolves challenge token and filters verified hash', async () => {
        const plain = 'AB7K-XP92';
        const hash = crypto.createHash('sha256').update(plain).digest('hex');
        
        const plain2 = 'L9M2-R4T1';
        const hash2 = crypto.createHash('sha256').update(plain2).digest('hex');
        
        const dbMock = buildDbMock({ userExists: true, backupCodeHashes: `${hash},${hash2}` });
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/auth/backup-codes/verify')
            .send({ backupCode: plain });

        expect(res.status).toBe(200);
        expect(res.body.status).toBe('success');
        expect(res.body.challengeToken).toBeDefined();

        // Verify single-use: only hash2 should remain
        expect(dbMock._collectionMock.updateOne).toHaveBeenCalled();
        expect(dbMock._collectionMock.updateOne.mock.calls[0][1].$set.backupCodeHashes).toBe(hash2);
    });

    test('401 — incorrect backup code rejected', async () => {
        const plain = 'AB7K-XP92';
        const hash = crypto.createHash('sha256').update(plain).digest('hex');
        
        const dbMock = buildDbMock({ userExists: true, backupCodeHashes: hash });
        const app = buildApp(dbMock);

        // AAAA-BBBB matches pattern format but does not match the stored hash
        const res = await request(app)
            .post('/v1/auth/backup-codes/verify')
            .send({ backupCode: 'AAAA-BBBB' });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });

    test('400 — malformed format (missing hyphen)', async () => {
        const dbMock = buildDbMock();
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/auth/backup-codes/verify')
            .send({ backupCode: 'AB7KXP92' });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('401 — user has no backup codes configured', async () => {
        const dbMock = buildDbMock({ userExists: true, backupCodeHashes: '' });
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/auth/backup-codes/verify')
            .send({ backupCode: 'AB7K-XP92' });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });
});

describe('IT-AUTH-07: POST /v1/auth/backup-codes/regenerate', () => {
    const newHashes = [
        crypto.createHash('sha256').update('NEW1-CODE').digest('hex'),
        crypto.createHash('sha256').update('NEW2-CODE').digest('hex')
    ];

    test('200 OK — first-time setup (no challenge token required)', async () => {
        const dbMock = buildDbMock({ userExists: true, backupCodeHashes: '' });
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/auth/backup-codes/regenerate')
            .send({
                hashedBackupCodes: newHashes
            });

        expect(res.status).toBe(200);
        expect(res.body.status).toBe('success');
        expect(dbMock._collectionMock.updateOne).toHaveBeenCalled();
        expect(dbMock._collectionMock.updateOne.mock.calls[0][1].$set.backupCodeHashes)
            .toBe(newHashes.join(','));
    });

    test('200 OK — regeneration with valid challenge token when codes exist', async () => {
        const dbMock = buildDbMock({ userExists: true, backupCodeHashes: 'some_old_hash_1,some_old_hash_2' });
        const app = buildApp(dbMock);

        const expiresAt = Date.now() + 5000;
        const validToken = generateChallengeToken('uid_test', expiresAt);

        const res = await request(app)
            .post('/v1/auth/backup-codes/regenerate')
            .send({
                challengeToken: validToken,
                hashedBackupCodes: newHashes
            });

        expect(res.status).toBe(200);
        expect(res.body.status).toBe('success');
    });

    test('401 — regeneration rejected if codes exist and challenge token missing', async () => {
        const dbMock = buildDbMock({ userExists: true, backupCodeHashes: 'some_old_hash_1,some_old_hash_2' });
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/auth/backup-codes/regenerate')
            .send({
                hashedBackupCodes: newHashes
            });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });

    test('401 — regeneration rejected if codes exist and challenge token expired', async () => {
        const dbMock = buildDbMock({ userExists: true, backupCodeHashes: 'some_old_hash_1,some_old_hash_2' });
        const app = buildApp(dbMock);

        // Expired 5 seconds ago
        const expiredToken = generateChallengeToken('uid_test', Date.now() - 5000);

        const res = await request(app)
            .post('/v1/auth/backup-codes/regenerate')
            .send({
                challengeToken: expiredToken,
                hashedBackupCodes: newHashes
            });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });

    test('400 — malformed request (wrong hash length)', async () => {
        const dbMock = buildDbMock();
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/auth/backup-codes/regenerate')
            .send({
                hashedBackupCodes: ['short_hash', 'another_short_hash']
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });
});
