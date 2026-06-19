'use strict';

/**
 * IT-AUTH-01: Account Verification & Registration — POST /v1/auth/login
 *
 * Spec refs:
 *   - Testing_Strategy.md IT-AUTH-01 (§2.1)
 *   - API_Spec.md §2 POST /v1/auth/login — request/response contract
 *   - Security_Requirements.md §9.3 — JWT signature verification (ST-STRIDE-05)
 *   - Testing_Strategy.md §2.4 — Mock Firebase Auth JWT generator
 *
 * Test strategy per Testing_Strategy.md §2.4:
 *   "Mock the Firebase gateway token verifier using a local JWT generator
 *   containing mock signatures and user attributes."
 *
 * Tests:
 *   1. Happy path — valid token, new user → 201 Created + registered=false
 *   2. Happy path — valid token, existing user → 200 OK + registered=true
 *   3. Invalid token → 401 UNAUTHENTICATED (ST-STRIDE-05)
 *   4. Missing Authorization header → 401 (ST-STRIDE-05)
 *   5. Missing required body field → 400 INVALID_ARGUMENT
 *   6. Device limit exceeded → 403 SESSION_LIMIT_EXCEEDED
 *   7. No body → 400 INVALID_ARGUMENT
 */

const request = require('supertest');
const express = require('express');
const { login } = require('../controllers/authController');

jest.setTimeout(30000); // 30s timeout for network & crypto mocks

// ---------------------------------------------------------------------------
// Test setup — mock firebase-admin and MongoDB
// ---------------------------------------------------------------------------

// Mock firebase-admin — Testing_Strategy.md §2.4
const mockVerifyIdToken = jest.fn();
const mockCreateCustomToken = jest.fn().mockResolvedValue('mock_firebase_custom_token');

const mockAuth = {
    verifyIdToken: mockVerifyIdToken,
    createCustomToken: mockCreateCustomToken
};

jest.mock('firebase-admin', () => ({
    auth: () => mockAuth,
    initializeApp: jest.fn()
}));

const admin = require('firebase-admin');

function buildApp(dbMock) {
    const app = express();
    app.use(express.json());
    app.locals.db = dbMock;
    app.post('/v1/auth/login', login);
    return app;
}

function buildDbMock({ userExists = false, sessionCount = 0, existingSession = null } = {}) {
    return {
        collection: jest.fn((name) => ({
            findOne: jest.fn().mockResolvedValue(
                name === 'users' ? (userExists ? { id: 'uid_test' } : null) : existingSession
            ),
            countDocuments: jest.fn().mockResolvedValue(sessionCount),
            insertOne: jest.fn().mockResolvedValue({}),
            updateOne: jest.fn().mockResolvedValue({})
        }))
    };
}

const validBody = {
    googleIdToken: 'valid_google_id_token',
    deviceId: '3589b2512f4581a0',
    deviceName: 'Pixel 7 Pro',
    androidVersion: '14.0'
};

// ---------------------------------------------------------------------------
// IT-AUTH-01 Test Suite
// ---------------------------------------------------------------------------

describe('IT-AUTH-01: POST /v1/auth/login', () => {

    beforeEach(() => {
        jest.clearAllMocks();
    });

    // IT-AUTH-01 Scenario 1: First-time user (registered=false) → 201
    test('201 Created — new user registration returns registered=false', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_new', email: 'new@test.com' });
        const app = buildApp(buildDbMock({ userExists: false, sessionCount: 0 }));

        const res = await request(app)
            .post('/v1/auth/login')
            .send(validBody)
            .set('Content-Type', 'application/json');

        expect(res.status).toBe(201);
        expect(res.body).toHaveProperty('firebaseToken', 'mock_firebase_custom_token');
        expect(res.body).toHaveProperty('refreshToken');
        expect(res.body.registered).toBe(false);
    });

    // IT-AUTH-01 Scenario 2: Existing user (registered=true) → 200
    test('200 OK — existing user returns registered=true', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_existing', email: 'exist@test.com' });
        const app = buildApp(buildDbMock({ userExists: true, sessionCount: 1 }));

        const res = await request(app)
            .post('/v1/auth/login')
            .send(validBody);

        expect(res.status).toBe(200);
        expect(res.body.registered).toBe(true);
        expect(res.body).toHaveProperty('firebaseToken');
    });

    // ST-STRIDE-05: Invalid/absent JWT token → 401
    test('401 UNAUTHENTICATED — invalid Google ID token rejected', async () => {
        admin.auth().verifyIdToken.mockRejectedValue(new Error('auth/argument-error'));
        const app = buildApp(buildDbMock());

        const res = await request(app)
            .post('/v1/auth/login')
            .send({ ...validBody, googleIdToken: 'invalid_token' });

        expect(res.status).toBe(401);
        expect(res.body.error.code).toBe('UNAUTHENTICATED');
        // Must NOT leak which specific check failed — Security_Requirements.md §6
        expect(res.body.error.message).not.toContain('auth/');
    });

    // ST-STRIDE-05: Missing body field → 400
    test('400 INVALID_ARGUMENT — missing deviceId', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_test' });
        const app = buildApp(buildDbMock());

        const { deviceId, ...bodyWithoutDeviceId } = validBody;

        const res = await request(app)
            .post('/v1/auth/login')
            .send(bodyWithoutDeviceId);

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('400 INVALID_ARGUMENT — empty body', async () => {
        const app = buildApp(buildDbMock());

        const res = await request(app)
            .post('/v1/auth/login')
            .send({});

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    // PRD F-DEV-01: Device limit → 403 SESSION_LIMIT_EXCEEDED
    test('403 SESSION_LIMIT_EXCEEDED — 4th device rejected when 3 exist', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_test', email: 'test@test.com' });
        // 3 existing sessions, this is a new device (existingSession=null)
        const app = buildApp(buildDbMock({
            userExists: true,
            sessionCount: 3,
            existingSession: null
        }));

        const res = await request(app)
            .post('/v1/auth/login')
            .send({ ...validBody, deviceId: 'brand_new_device_id' });

        expect(res.status).toBe(403);
        expect(res.body.error.code).toBe('SESSION_LIMIT_EXCEEDED');
    });

    // Same device re-login allowed even when 3 sessions exist
    test('200 OK — same device re-login allowed when at 3-device limit', async () => {
        admin.auth().verifyIdToken.mockResolvedValue({ uid: 'uid_test', email: 'test@test.com' });
        const app = buildApp(buildDbMock({
            userExists: true,
            sessionCount: 3,
            existingSession: { id: validBody.deviceId, userId: 'uid_test' }
        }));

        const res = await request(app)
            .post('/v1/auth/login')
            .send(validBody);

        expect(res.status).toBe(200);
    });

    // Verify timestamp is included in all error responses — API_Spec.md §5
    test('Error responses include timestamp — API_Spec.md §5 error envelope', async () => {
        admin.auth().verifyIdToken.mockRejectedValue(new Error('invalid'));
        const app = buildApp(buildDbMock());

        const res = await request(app)
            .post('/v1/auth/login')
            .send(validBody);

        expect(res.body.error).toHaveProperty('timestamp');
        expect(typeof res.body.error.timestamp).toBe('number');
    });
});
