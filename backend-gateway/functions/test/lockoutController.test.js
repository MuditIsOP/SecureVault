'use strict';

/**
 * IT-AUTH-05: Sync Lockout State — POST /v1/auth/lockout
 *
 * Spec refs:
 *   - Testing_Strategy.md IT-AUTH-05 (§2.1) — sync lockout state
 *   - Security_Requirements.md ST-STRIDE-08 — gateway flooding DoS
 *   - API_Spec.md §2 — request/response contract
 *   - Security_Requirements.md §5 — Input validation rules
 */

const request = require('supertest');
const express = require('express');
const { syncLockout } = require('../controllers/lockoutController');
const { verifyFirebaseToken } = require('../middleware/authMiddleware');

// Mock firebase-admin
const mockVerifyIdToken = jest.fn();
const mockAuth = {
    verifyIdToken: mockVerifyIdToken
};

jest.mock('firebase-admin', () => ({
    auth: () => mockAuth,
    initializeApp: jest.fn()
}));

const admin = require('firebase-admin');

function buildApp(dbMock, bypassAuth = false) {
    const app = express();
    app.use(express.json());
    app.locals.db = dbMock;

    if (bypassAuth) {
        app.use((req, res, next) => {
            req.user = { uid: 'uid_test', email: 'test@test.com' };
            next();
        });
        app.post('/v1/auth/lockout', syncLockout);
    } else {
        app.post('/v1/auth/lockout', verifyFirebaseToken, syncLockout);
    }

    return app;
}

function buildDbMock({ matchedCount = 1, shouldThrow = false } = {}) {
    return {
        collection: jest.fn(() => ({
            updateOne: jest.fn().mockImplementation(() => {
                if (shouldThrow) {
                    throw new Error('Database error');
                }
                return Promise.resolve({ matchedCount });
            })
        }))
    };
}

describe('IT-AUTH-05: POST /v1/auth/lockout', () => {

    beforeEach(() => {
        jest.clearAllMocks();
    });

    test('200 OK — lockout state synced successfully', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_test', email: 'test@test.com' });
        const app = buildApp(buildDbMock({ matchedCount: 1 }));

        const res = await request(app)
            .post('/v1/auth/lockout')
            .set('Authorization', 'Bearer valid_mock_token')
            .send({
                pinFailedAttempts: 6,
                pinLockoutUntil: 1718281767000
            });

        expect(res.status).toBe(200);
        expect(res.body.status).toBe('success');
        expect(res.body.message).toBe('Lockout state synced.');
    });

    test('200 OK — lockout state synced with null lockout time (reset)', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_test', email: 'test@test.com' });
        const app = buildApp(buildDbMock({ matchedCount: 1 }));

        const res = await request(app)
            .post('/v1/auth/lockout')
            .set('Authorization', 'Bearer valid_mock_token')
            .send({
                pinFailedAttempts: 0,
                pinLockoutUntil: null
            });

        expect(res.status).toBe(200);
        expect(res.body.status).toBe('success');
    });

    test('400 Bad Request — missing pinFailedAttempts', async () => {
        const app = buildApp(buildDbMock(), true); // Bypass auth middleware to isolate controller validation

        const res = await request(app)
            .post('/v1/auth/lockout')
            .send({
                pinLockoutUntil: 1718281767000
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('400 Bad Request — invalid pinFailedAttempts value (negative)', async () => {
        const app = buildApp(buildDbMock(), true);

        const res = await request(app)
            .post('/v1/auth/lockout')
            .send({
                pinFailedAttempts: -1,
                pinLockoutUntil: null
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('400 Bad Request — invalid pinLockoutUntil value (string)', async () => {
        const app = buildApp(buildDbMock(), true);

        const res = await request(app)
            .post('/v1/auth/lockout')
            .send({
                pinFailedAttempts: 3,
                pinLockoutUntil: 'tomorrow'
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('401 Unauthorized — missing Authorization header', async () => {
        const app = buildApp(buildDbMock());

        const res = await request(app)
            .post('/v1/auth/lockout')
            .send({
                pinFailedAttempts: 11,
                pinLockoutUntil: 1718281767000
            });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });

    test('401 Unauthorized — invalid token signature', async () => {
        admin.auth().verifyIdToken.mockRejectedValue(new Error('Invalid token'));
        const app = buildApp(buildDbMock());

        const res = await request(app)
            .post('/v1/auth/lockout')
            .set('Authorization', 'Bearer invalid_token')
            .send({
                pinFailedAttempts: 11,
                pinLockoutUntil: 1718281767000
            });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
    });

    test('404 Not Found — user record does not exist', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_test', email: 'test@test.com' });
        const app = buildApp(buildDbMock({ matchedCount: 0 }));

        const res = await request(app)
            .post('/v1/auth/lockout')
            .set('Authorization', 'Bearer valid_mock_token')
            .send({
                pinFailedAttempts: 6,
                pinLockoutUntil: 1718281767000
            });

        expect(res.status).toBe(404);
        expect(res.body.error.code).toBe('NOT_FOUND');
    });

    test('503 Service Unavailable — database operation throws error', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_test', email: 'test@test.com' });
        const app = buildApp(buildDbMock({ shouldThrow: true }));

        const res = await request(app)
            .post('/v1/auth/lockout')
            .set('Authorization', 'Bearer valid_mock_token')
            .send({
                pinFailedAttempts: 6,
                pinLockoutUntil: 1718281767000
            });

        expect(res.status).toBe(503);
        expect(res.body.error.code).toBe('SERVICE_UNAVAILABLE');
    });

});
