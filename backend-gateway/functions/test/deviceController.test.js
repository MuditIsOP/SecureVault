'use strict';

/**
 * IT-DEV-01, IT-DEV-02: Device Controller API tests.
 *
 * Spec refs:
 *   - Testing_Strategy.md IT-DEV-01 — GET /v1/devices (200 OK, active session list)
 *   - Testing_Strategy.md IT-DEV-02 — DELETE /v1/devices/{id} (200 OK, session revoked)
 *   - API_Spec.md §4 — GET /v1/devices, DELETE /v1/devices/{id}
 *   - PRD F-DEV-01 AC#1 — 3-device limit
 *   - PRD F-DEV-01 AC#2 — Active Devices Screen data
 *   - PRD F-DEV-01 AC#3 — Remove device, allow login
 *   - SRS FR-DEV-01a — "limit concurrent devices to maximum of 3"
 *   - SRS FR-DEV-01b — "display active device lists and support remote logout"
 *   - Database_Schema.md §2.5 — device_sessions columns
 *   - Database_Schema.md §3 — hard deletion
 *   - Security_Requirements.md §3 — uid-scoped queries
 */

const request = require('supertest');
const express = require('express');
const { getDevices, revokeDevice, registerDevice } = require('../controllers/deviceController');

// Mock firebase-admin
jest.mock('firebase-admin', () => ({
    auth: () => ({ verifyIdToken: jest.fn() }),
    initializeApp: jest.fn()
}));

function buildApp(dbMock) {
    const app = express();
    app.use(express.json());
    app.locals.db = dbMock;

    // Bypass auth — inject user directly
    app.use((req, res, next) => {
        req.user = { uid: 'uid_test_user', email: 'test@securevault.com' };
        next();
    });

    app.get('/v1/devices', getDevices);
    app.delete('/v1/devices/:id', revokeDevice);
    app.post('/v1/devices/register', registerDevice);

    return app;
}

// -------------------------------------------------------------------------
// IT-DEV-01: GET /v1/devices — Fetch active device sessions
// API_Spec.md §4 — response: [{ id, deviceName, androidVersion, lastActiveTime }]
// -------------------------------------------------------------------------

describe('IT-DEV-01: GET /v1/devices', () => {

    it('should return 200 with list of active device sessions', async () => {
        const mockSessions = [
            {
                _id: 'device-1',
                userId: 'uid_test_user',
                device_name: 'Pixel 7 Pro',
                android_version: '14.0',
                last_active_time: 1718274567000,
                created_at: 1718270000000
            },
            {
                _id: 'device-2',
                userId: 'uid_test_user',
                device_name: 'Samsung Galaxy S24',
                android_version: '14.0',
                last_active_time: 1718274000000,
                created_at: 1718260000000
            }
        ];

        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue(mockSessions)
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).get('/v1/devices');

        expect(res.status).toBe(200);
        expect(res.body).toHaveLength(2);
        expect(res.body[0]).toHaveProperty('id', 'device-1');
        expect(res.body[0]).toHaveProperty('deviceName', 'Pixel 7 Pro');
        expect(res.body[0]).toHaveProperty('androidVersion', '14.0');
        expect(res.body[0]).toHaveProperty('lastActiveTime', 1718274567000);
        // Verify uid-scoped query — Security_Requirements.md §3
        expect(findMock).toHaveBeenCalledWith({ userId: 'uid_test_user' });
    });

    it('should return empty array when no sessions exist', async () => {
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).get('/v1/devices');

        expect(res.status).toBe(200);
        expect(res.body).toEqual([]);
    });
});

// -------------------------------------------------------------------------
// IT-DEV-02: DELETE /v1/devices/{id} — Revoke device session
// API_Spec.md §4 — 200 OK on success
// Database_Schema.md §3 — hard deletion
// PRD F-DEV-01 AC#3 — "logs out the selected device"
// -------------------------------------------------------------------------

describe('IT-DEV-02: DELETE /v1/devices/:id', () => {

    it('should revoke device session and return 200', async () => {
        const findOneMock = jest.fn().mockResolvedValue({
            _id: 'device-1',
            userId: 'uid_test_user',
            device_name: 'Pixel 7 Pro'
        });
        const deleteOneMock = jest.fn().mockResolvedValue({ deletedCount: 1 });

        const collectionMock = jest.fn().mockReturnValue({
            findOne: findOneMock,
            deleteOne: deleteOneMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/devices/device-1');

        expect(res.status).toBe(200);
        expect(res.body.message).toBe('Device session revoked.');
        // Verify hard delete — Database_Schema.md §3
        expect(deleteOneMock).toHaveBeenCalledWith({
            _id: 'device-1',
            userId: 'uid_test_user'
        });
    });

    it('should return 404 when device not found', async () => {
        const findOneMock = jest.fn().mockResolvedValue(null);

        const collectionMock = jest.fn().mockReturnValue({
            findOne: findOneMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/devices/nonexistent');

        expect(res.status).toBe(404);
        expect(res.body.error.code).toBe('NOT_FOUND');
    });

    it('should return 404 when device belongs to different user (uid-scoped)', async () => {
        // findOne with uid scope returns null even if device exists for other user
        const findOneMock = jest.fn().mockResolvedValue(null);

        const collectionMock = jest.fn().mockReturnValue({
            findOne: findOneMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/devices/other-user-device');

        expect(res.status).toBe(404);
        // Verify uid-scoped query — Security_Requirements.md §3
        expect(findOneMock).toHaveBeenCalledWith({
            _id: 'other-user-device',
            userId: 'uid_test_user'
        });
    });
});

// -------------------------------------------------------------------------
// Device registration + 3-device limit
// PRD F-DEV-01 AC#1 — "restricts active sessions to max 3"
// SRS FR-DEV-01a — "limit concurrent devices to maximum of 3"
// -------------------------------------------------------------------------

describe('Device registration and 3-device limit', () => {

    it('should register a new device when under limit (201)', async () => {
        const findOneMock = jest.fn().mockResolvedValue(null); // No existing session
        const countDocumentsMock = jest.fn().mockResolvedValue(1); // Only 1 device
        const insertOneMock = jest.fn().mockResolvedValue({ insertedId: 'new-device' });

        const collectionMock = jest.fn().mockReturnValue({
            findOne: findOneMock,
            countDocuments: countDocumentsMock,
            insertOne: insertOneMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/devices/register')
            .send({
                deviceId: 'new-device',
                deviceName: 'Pixel 8',
                androidVersion: '15.0'
            });

        expect(res.status).toBe(201);
        expect(res.body.allowed).toBe(true);
        expect(insertOneMock).toHaveBeenCalledWith(
            expect.objectContaining({
                _id: 'new-device',
                userId: 'uid_test_user',
                device_name: 'Pixel 8',
                android_version: '15.0'
            })
        );
    });

    it('should return 403 SESSION_LIMIT_EXCEEDED when at 3 devices', async () => {
        const findOneMock = jest.fn().mockResolvedValue(null); // No existing session
        const countDocumentsMock = jest.fn().mockResolvedValue(3); // Already at limit
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([
                    { _id: 'd1', userId: 'uid_test_user', device_name: 'Device 1', android_version: '14.0', last_active_time: 1718274567000 },
                    { _id: 'd2', userId: 'uid_test_user', device_name: 'Device 2', android_version: '13.0', last_active_time: 1718274000000 },
                    { _id: 'd3', userId: 'uid_test_user', device_name: 'Device 3', android_version: '14.0', last_active_time: 1718270000000 }
                ])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            findOne: findOneMock,
            countDocuments: countDocumentsMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/devices/register')
            .send({
                deviceId: 'device-4',
                deviceName: 'Fourth Device',
                androidVersion: '15.0'
            });

        expect(res.status).toBe(403);
        expect(res.body.error.code).toBe('SESSION_LIMIT_EXCEEDED');
        expect(res.body.devices).toHaveLength(3);
        expect(res.body.devices[0]).toHaveProperty('deviceName');
    });

    it('should update last_active_time for re-login on same device (200)', async () => {
        const findOneMock = jest.fn().mockResolvedValue({
            _id: 'existing-device',
            userId: 'uid_test_user',
            device_name: 'Pixel 7'
        });
        const updateOneMock = jest.fn().mockResolvedValue({ modifiedCount: 1 });

        const collectionMock = jest.fn().mockReturnValue({
            findOne: findOneMock,
            updateOne: updateOneMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/devices/register')
            .send({
                deviceId: 'existing-device',
                deviceName: 'Pixel 7',
                androidVersion: '14.0'
            });

        expect(res.status).toBe(200);
        expect(res.body.allowed).toBe(true);
        expect(updateOneMock).toHaveBeenCalledWith(
            { _id: 'existing-device', userId: 'uid_test_user' },
            { $set: { last_active_time: expect.any(Number) } }
        );
    });

    it('should reject missing deviceName (400)', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/devices/register')
            .send({
                deviceId: 'd1',
                androidVersion: '14.0'
                // Missing: deviceName
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });
});
