'use strict';

/**
 * IT-SYNC-01, ST-STRIDE-06, ST-STRIDE-09: Sync Controller tests.
 *
 * Spec refs:
 *   - Testing_Strategy.md IT-SYNC-01 — POST /v1/sync payload transaction tests
 *   - Testing_Strategy.md ST-STRIDE-06 — Tampering: sync payload integrity
 *   - Testing_Strategy.md ST-STRIDE-09 — Privilege escalation: uid-scoped queries
 *   - API_Spec.md §4 — POST /v1/sync request/response schema
 *   - PRD F-SYNC-03 AC#1 — Version counter comparison
 *   - PRD F-SYNC-03 AC#2 — Timestamp comparison if versions differ
 *   - PRD F-SYNC-03 AC#3 — Later timestamp wins; ties → server wins
 *   - SRS FR-SYNC-03a — "resolve sync conflicts using logical versions and timestamps"
 *   - SRS FR-SYNC-03b — "restrict database access using user identity parameters"
 *   - Security_Requirements.md §3 — uid-scoped queries
 *   - Security_Requirements.md §5 — Joi input validation
 */

const request = require('supertest');
const express = require('express');
const { processSync, resolveConflict } = require('../controllers/syncController');

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

    app.post('/v1/sync', processSync);

    return app;
}

// -------------------------------------------------------------------------
// IT-SYNC-01: POST /v1/sync — Sync payload transaction tests
// API_Spec.md §4 — POST /v1/sync
// -------------------------------------------------------------------------

describe('IT-SYNC-01: POST /v1/sync', () => {

    // -----------------------------------------------------------------------
    // Valid sync — INSERT action
    // -----------------------------------------------------------------------
    it('should process INSERT action and return 200 with resolvedActions', async () => {
        const insertOneMock = jest.fn().mockResolvedValue({ insertedId: 'rec-1' });
        const findOneMock = jest.fn().mockResolvedValue(null); // No existing record
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            insertOne: insertOneMock,
            findOne: findOneMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'queue-uuid-01',
                    transactionType: 'INSERT',
                    tableName: 'vault_passwords',
                    recordId: 'rec-1',
                    payloadJson: '{"name":"Github","usernameEmail":"dev@test.com","encryptedPassword":"AES_CIPHER"}',
                    version: 1,
                    updatedAt: 1718278167000
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(200);
        expect(res.body.resolvedActions).toContain('queue-uuid-01');
        expect(res.body.syncTime).toBeDefined();
        expect(res.body.remoteUpdates).toBeDefined();
        expect(insertOneMock).toHaveBeenCalled();
    });

    // -----------------------------------------------------------------------
    // Valid sync — UPDATE action with client winning conflict
    // PRD F-SYNC-03 AC#2 — client's later timestamp wins
    // -----------------------------------------------------------------------
    it('should resolve UPDATE conflict — client wins (later timestamp)', async () => {
        const updateOneMock = jest.fn().mockResolvedValue({ modifiedCount: 1 });
        const findOneMock = jest.fn().mockResolvedValue({
            _id: 'rec-1',
            userId: 'uid_test_user',
            version: 1,
            updated_at: 1718270000000 // Server has earlier timestamp
        });
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            updateOne: updateOneMock,
            findOne: findOneMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'queue-uuid-02',
                    transactionType: 'UPDATE',
                    tableName: 'vault_passwords',
                    recordId: 'rec-1',
                    payloadJson: '{"name":"Github Updated"}',
                    version: 2,
                    updatedAt: 1718278167000 // Client has later timestamp
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(200);
        expect(res.body.resolvedActions).toContain('queue-uuid-02');
        expect(updateOneMock).toHaveBeenCalled();
    });

    // -----------------------------------------------------------------------
    // Conflict — server wins (server has later timestamp)
    // PRD F-SYNC-03 AC#2 — server's later timestamp wins
    // -----------------------------------------------------------------------
    it('should resolve UPDATE conflict — server wins (later timestamp)', async () => {
        const updateOneMock = jest.fn();
        const findOneMock = jest.fn().mockResolvedValue({
            _id: 'rec-1',
            userId: 'uid_test_user',
            version: 3,
            updated_at: 1718290000000 // Server has later timestamp
        });
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            updateOne: updateOneMock,
            findOne: findOneMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'queue-uuid-03',
                    transactionType: 'UPDATE',
                    tableName: 'vault_passwords',
                    recordId: 'rec-1',
                    payloadJson: '{"name":"Old Client Data"}',
                    version: 2,
                    updatedAt: 1718278167000 // Client has earlier timestamp
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(200);
        // Server wins — action NOT in resolvedActions
        expect(res.body.resolvedActions).not.toContain('queue-uuid-03');
        // updateOne should NOT have been called (server wins)
        expect(updateOneMock).not.toHaveBeenCalled();
    });

    // -----------------------------------------------------------------------
    // Conflict — exact timestamp match → server wins
    // PRD F-SYNC-03 AC#3 — "If timestamps match exactly, server version retained"
    // -----------------------------------------------------------------------
    it('should resolve conflict — exact timestamp match, server wins', async () => {
        const updateOneMock = jest.fn();
        const findOneMock = jest.fn().mockResolvedValue({
            _id: 'rec-1',
            userId: 'uid_test_user',
            version: 2,
            updated_at: 1718278167000 // Exact same timestamp
        });
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            updateOne: updateOneMock,
            findOne: findOneMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'queue-uuid-04',
                    transactionType: 'UPDATE',
                    tableName: 'vault_passwords',
                    recordId: 'rec-1',
                    payloadJson: '{"name":"Tied Update"}',
                    version: 2,
                    updatedAt: 1718278167000 // Same timestamp as server
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(200);
        expect(res.body.resolvedActions).not.toContain('queue-uuid-04');
        expect(updateOneMock).not.toHaveBeenCalled();
    });

    // -----------------------------------------------------------------------
    // DELETE action — vault_passwords soft delete
    // Database_Schema.md §3 — soft-deletion for vault_passwords
    // -----------------------------------------------------------------------
    it('should process DELETE action (soft delete for vault_passwords)', async () => {
        const updateOneMock = jest.fn().mockResolvedValue({ modifiedCount: 1 });
        const findOneMock = jest.fn().mockResolvedValue({
            _id: 'rec-1',
            userId: 'uid_test_user'
        });
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            updateOne: updateOneMock,
            findOne: findOneMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'queue-uuid-05',
                    transactionType: 'DELETE',
                    tableName: 'vault_passwords',
                    recordId: 'rec-1',
                    payloadJson: '{}',
                    version: 1,
                    updatedAt: 1718278167000
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(200);
        expect(res.body.resolvedActions).toContain('queue-uuid-05');
        expect(updateOneMock).toHaveBeenCalledWith(
            { _id: 'rec-1', userId: 'uid_test_user' },
            { $set: { deleted_at: expect.any(Number) } }
        );
    });

    // -----------------------------------------------------------------------
    // DELETE action — categories hard delete
    // Database_Schema.md §3 — hard delete for categories
    // -----------------------------------------------------------------------
    it('should process DELETE action (hard delete for categories)', async () => {
        const deleteOneMock = jest.fn().mockResolvedValue({ deletedCount: 1 });
        const findOneMock = jest.fn().mockResolvedValue({
            _id: 'cat-1',
            userId: 'uid_test_user'
        });
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            deleteOne: deleteOneMock,
            findOne: findOneMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'queue-uuid-06',
                    transactionType: 'DELETE',
                    tableName: 'categories',
                    recordId: 'cat-1',
                    payloadJson: '{}',
                    version: 1,
                    updatedAt: 1718278167000
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(200);
        expect(res.body.resolvedActions).toContain('queue-uuid-06');
        expect(deleteOneMock).toHaveBeenCalledWith({
            _id: 'cat-1',
            userId: 'uid_test_user'
        });
    });

    // -----------------------------------------------------------------------
    // Empty syncActions array — Edge case
    // -----------------------------------------------------------------------
    it('should handle empty syncActions array gracefully', async () => {
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

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(200);
        expect(res.body.resolvedActions).toEqual([]);
        expect(res.body.remoteUpdates).toBeDefined();
    });

    // -----------------------------------------------------------------------
    // Remote updates — API_Spec.md §4 response remoteUpdates
    // -----------------------------------------------------------------------
    it('should return remote updates since lastSyncTime', async () => {
        const findOneMock = jest.fn().mockResolvedValue(null);
        const insertOneMock = jest.fn().mockResolvedValue({});

        // vault_passwords returns 1 updated record
        // categories returns 0
        const findMock = jest.fn()
            .mockReturnValueOnce({
                sort: jest.fn().mockReturnValue({
                    toArray: jest.fn().mockResolvedValue([{
                        _id: 'remote-rec-1',
                        userId: 'uid_test_user',
                        name: 'Remote Entry',
                        version: 2,
                        updated_at: 1718280000000,
                        deleted_at: null
                    }])
                })
            })
            .mockReturnValueOnce({
                sort: jest.fn().mockReturnValue({
                    toArray: jest.fn().mockResolvedValue([])
                })
            });

        const collectionMock = jest.fn().mockReturnValue({
            findOne: findOneMock,
            insertOne: insertOneMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(200);
        expect(res.body.remoteUpdates.length).toBe(1);
        expect(res.body.remoteUpdates[0].recordId).toBe('remote-rec-1');
        expect(res.body.remoteUpdates[0].tableName).toBe('vault_passwords');
        expect(res.body.remoteUpdates[0].version).toBe(2);
    });
});

// -------------------------------------------------------------------------
// ST-STRIDE-06: Tampering — Sync payload integrity
// Security_Requirements.md §5 — Joi validation
// -------------------------------------------------------------------------

describe('ST-STRIDE-06: Sync payload validation', () => {

    it('should reject invalid transactionType', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'q1',
                    transactionType: 'DROP_TABLE',
                    tableName: 'vault_passwords',
                    recordId: 'r1',
                    payloadJson: '{}',
                    version: 1,
                    updatedAt: 1718278167000
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    it('should reject invalid tableName', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'q1',
                    transactionType: 'INSERT',
                    tableName: 'admin_users',
                    recordId: 'r1',
                    payloadJson: '{}',
                    version: 1,
                    updatedAt: 1718278167000
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    it('should reject missing required fields', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'q1',
                    transactionType: 'INSERT'
                    // Missing: tableName, recordId, payloadJson, version, updatedAt
                }],
                lastSyncTime: 1718274567000
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    it('should reject missing lastSyncTime', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/sync')
            .send({
                syncActions: []
                // Missing: lastSyncTime
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });
});

// -------------------------------------------------------------------------
// ST-STRIDE-09: Privilege escalation — uid-scoped queries
// Security_Requirements.md §3 — all queries MUST include userId = uid
// SRS FR-SYNC-03b — "restrict database access using user identity parameters"
// -------------------------------------------------------------------------

describe('ST-STRIDE-09: UID-scoped query enforcement', () => {

    it('should pass uid to all collection queries', async () => {
        const findOneMock = jest.fn().mockResolvedValue(null);
        const insertOneMock = jest.fn().mockResolvedValue({ insertedId: 'rec-1' });
        const findMock = jest.fn().mockReturnValue({
            sort: jest.fn().mockReturnValue({
                toArray: jest.fn().mockResolvedValue([])
            })
        });

        const collectionMock = jest.fn().mockReturnValue({
            insertOne: insertOneMock,
            findOne: findOneMock,
            find: findMock
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        await request(app)
            .post('/v1/sync')
            .send({
                syncActions: [{
                    id: 'q1',
                    transactionType: 'INSERT',
                    tableName: 'vault_passwords',
                    recordId: 'rec-new',
                    payloadJson: '{"name":"Test"}',
                    version: 1,
                    updatedAt: 1718278167000
                }],
                lastSyncTime: 1718274567000
            });

        // Verify findOne was called with uid scope
        expect(findOneMock).toHaveBeenCalledWith({
            _id: 'rec-new',
            userId: 'uid_test_user'
        });

        // Verify insertOne includes userId
        expect(insertOneMock).toHaveBeenCalledWith(
            expect.objectContaining({
                userId: 'uid_test_user'
            })
        );

        // Verify find (remote updates) was called with uid scope
        expect(findMock).toHaveBeenCalledWith(
            expect.objectContaining({
                userId: 'uid_test_user'
            })
        );
    });
});

// -------------------------------------------------------------------------
// Conflict resolver unit tests
// PRD F-SYNC-03 AC#1/2/3
// -------------------------------------------------------------------------

describe('Conflict resolver logic', () => {

    it('should let client win when client has newer timestamp (same version)', async () => {
        const updateOneMock = jest.fn().mockResolvedValue({ modifiedCount: 1 });
        const collection = {
            updateOne: updateOneMock
        };

        const clientAction = {
            recordId: 'r1',
            tableName: 'vault_passwords',
            payloadJson: '{"name":"Client"}',
            version: 2,
            updatedAt: 1718280000000
        };

        const serverRecord = {
            _id: 'r1',
            userId: 'uid_test_user',
            version: 2,
            updated_at: 1718270000000
        };

        const result = await resolveConflict(collection, 'uid_test_user', clientAction, serverRecord);
        expect(result.resolved).toBe(true);
        expect(updateOneMock).toHaveBeenCalled();
    });

    it('should let server win when server has newer timestamp (diff version)', async () => {
        const updateOneMock = jest.fn();
        const collection = {
            updateOne: updateOneMock
        };

        const clientAction = {
            recordId: 'r1',
            tableName: 'vault_passwords',
            payloadJson: '{"name":"Client"}',
            version: 2,
            updatedAt: 1718270000000
        };

        const serverRecord = {
            _id: 'r1',
            userId: 'uid_test_user',
            version: 3,
            updated_at: 1718280000000
        };

        const result = await resolveConflict(collection, 'uid_test_user', clientAction, serverRecord);
        expect(result.resolved).toBe(false);
        expect(result.conflict.resolution).toBe('server_wins');
        expect(updateOneMock).not.toHaveBeenCalled();
    });

    it('should let server win on exact timestamp match', async () => {
        const updateOneMock = jest.fn();
        const collection = {
            updateOne: updateOneMock
        };

        const clientAction = {
            recordId: 'r1',
            tableName: 'vault_passwords',
            payloadJson: '{"name":"Client"}',
            version: 3,
            updatedAt: 1718278167000
        };

        const serverRecord = {
            _id: 'r1',
            userId: 'uid_test_user',
            version: 2,
            updated_at: 1718278167000
        };

        const result = await resolveConflict(collection, 'uid_test_user', clientAction, serverRecord);
        expect(result.resolved).toBe(false);
        expect(result.conflict.resolution).toBe('server_wins');
        expect(updateOneMock).not.toHaveBeenCalled();
    });
});
