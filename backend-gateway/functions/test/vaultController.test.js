'use strict';

/**
 * IT-VAULT-01 to IT-VAULT-04: Vault Credential CRUD API tests.
 *
 * Spec refs:
 *   - Testing_Strategy.md IT-VAULT-01 — GET /v1/vault (200 OK, array schema)
 *   - Testing_Strategy.md IT-VAULT-02 — POST /v1/vault (201 Created, { id, version: 1 })
 *   - Testing_Strategy.md IT-VAULT-03 — PUT /v1/vault/{id} (200 OK, password_history, version increment)
 *   - Testing_Strategy.md IT-VAULT-04 — DELETE /v1/vault/{id} (200 OK, soft delete)
 *   - API_Spec.md §3 — full request/response contracts
 *   - Security_Requirements.md §3 — uid-scoped queries
 *   - Security_Requirements.md §5 — Joi input validation
 *   - PRD F-VAULT-02 AC#4 — version counter increment on edit
 *   - PRD F-VAULT-02 AC#5 — soft delete via deletedDate
 *   - Database_Schema.md §2.3 — password_history columns
 *   - Database_Schema.md §3 — soft-deletion only for vault_passwords
 */

const request = require('supertest');
const express = require('express');
const { getVault, createCredential, updateCredential, deleteCredential } = require('../controllers/vaultController');

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

    app.get('/v1/vault', getVault);
    app.post('/v1/vault', createCredential);
    app.put('/v1/vault/:id', updateCredential);
    app.delete('/v1/vault/:id', deleteCredential);

    return app;
}

// -------------------------------------------------------------------------
// IT-VAULT-01: GET /v1/vault — Fetch all non-deleted credentials
// -------------------------------------------------------------------------

describe('IT-VAULT-01: GET /v1/vault', () => {
    test('200 — returns array of 3 credentials with correct schema', async () => {
        const existingCredentials = [
            {
                _id: 'cred_1',
                name: 'GitHub',
                usernameEmail: 'dev@github.com',
                encryptedPassword: 'encrypted_abc123',
                websiteUrl: 'https://github.com',
                categoryId: 'cat_work',
                favorite: 1,
                passwordStrength: 'STRONG',
                userId: 'uid_test_user',
                createdAt: 1718700000000,
                updatedAt: 1718700003000,
                deletedAt: null,
                version: 3
            },
            {
                _id: 'cred_2',
                name: 'Netflix',
                usernameEmail: 'user@netflix.com',
                encryptedPassword: 'encrypted_xyz789',
                websiteUrl: 'https://netflix.com',
                categoryId: 'cat_personal',
                favorite: 0,
                passwordStrength: 'MEDIUM',
                userId: 'uid_test_user',
                createdAt: 1718700000000,
                updatedAt: 1718700002000,
                deletedAt: null,
                version: 1
            },
            {
                _id: 'cred_3',
                name: 'AWS Console',
                usernameEmail: 'admin@aws.com',
                encryptedPassword: 'encrypted_aws456',
                websiteUrl: 'https://console.aws.amazon.com',
                categoryId: 'cat_work',
                favorite: 1,
                passwordStrength: 'STRONG',
                userId: 'uid_test_user',
                createdAt: 1718700000000,
                updatedAt: 1718700001000,
                deletedAt: null,
                version: 2
            }
        ];

        const collectionMock = jest.fn(() => ({
            find: jest.fn(() => ({
                sort: jest.fn(() => ({
                    toArray: jest.fn().mockResolvedValue(existingCredentials)
                }))
            }))
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).get('/v1/vault');
        expect(res.status).toBe(200);
        expect(Array.isArray(res.body)).toBe(true);
        expect(res.body).toHaveLength(3);

        // IT-VAULT-01 schema: { id, name, usernameEmail, encryptedPassword,
        //   websiteUrl, categoryId, favorite, createdAt, updatedAt,
        //   passwordStrength, version }
        expect(res.body[0]).toHaveProperty('id');
        expect(res.body[0]).toHaveProperty('name');
        expect(res.body[0]).toHaveProperty('usernameEmail');
        expect(res.body[0]).toHaveProperty('encryptedPassword');
        expect(res.body[0]).toHaveProperty('websiteUrl');
        expect(res.body[0]).toHaveProperty('categoryId');
        expect(res.body[0]).toHaveProperty('favorite');
        expect(res.body[0]).toHaveProperty('passwordStrength');
        expect(res.body[0]).toHaveProperty('createdAt');
        expect(res.body[0]).toHaveProperty('updatedAt');
        expect(res.body[0]).toHaveProperty('version');

        // Verify correct values
        expect(res.body[0].id).toBe('cred_1');
        expect(res.body[0].name).toBe('GitHub');
        expect(res.body[0].usernameEmail).toBe('dev@github.com');
        expect(res.body[0].encryptedPassword).toBe('encrypted_abc123');
        expect(res.body[0].version).toBe(3);
    });

    test('200 — returns empty array when no credentials exist', async () => {
        const collectionMock = jest.fn(() => ({
            find: jest.fn(() => ({
                sort: jest.fn(() => ({
                    toArray: jest.fn().mockResolvedValue([])
                }))
            }))
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).get('/v1/vault');
        expect(res.status).toBe(200);
        expect(Array.isArray(res.body)).toBe(true);
        expect(res.body).toHaveLength(0);
    });
});

// -------------------------------------------------------------------------
// IT-VAULT-02: POST /v1/vault — Create a new credential
// -------------------------------------------------------------------------

describe('IT-VAULT-02: POST /v1/vault', () => {
    test('201 — creates credential with valid body, returns { id, version: 1 }', async () => {
        const insertOneMock = jest.fn().mockResolvedValue({ insertedId: 'cred_uuid_github' });

        const collectionMock = jest.fn(() => ({
            insertOne: insertOneMock
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/vault')
            .send({
                id: 'cred_uuid_github',
                name: 'GitHub',
                usernameEmail: 'dev@github.com',
                encryptedPassword: 'encrypted_abc123',
                websiteUrl: 'https://github.com',
                categoryId: 'cat_work',
                favorite: 1,
                passwordStrength: 'STRONG'
            });

        expect(res.status).toBe(201);
        // IT-VAULT-02 response: { id, version: 1 }
        expect(res.body).toHaveProperty('id', 'cred_uuid_github');
        expect(res.body).toHaveProperty('version', 1);

        // Verify insertOne was called with correct document shape
        expect(insertOneMock).toHaveBeenCalledTimes(1);
        const insertedDoc = insertOneMock.mock.calls[0][0];
        expect(insertedDoc.userId).toBe('uid_test_user');
        expect(insertedDoc.deletedAt).toBeNull();
        expect(insertedDoc.version).toBe(1);
        expect(insertedDoc.createdAt).toEqual(expect.any(Number));
        expect(insertedDoc.updatedAt).toEqual(expect.any(Number));
    });

    test('400 — rejects missing name', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/vault')
            .send({
                id: 'cred_uuid_1',
                usernameEmail: 'user@test.com',
                encryptedPassword: 'encrypted_pw',
                favorite: 0,
                passwordStrength: 'WEAK'
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('400 — rejects missing encryptedPassword', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/vault')
            .send({
                id: 'cred_uuid_1',
                name: 'GitHub',
                usernameEmail: 'dev@github.com',
                favorite: 0,
                passwordStrength: 'WEAK'
            });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });
});

// -------------------------------------------------------------------------
// IT-VAULT-03: PUT /v1/vault/{id} — Update credential
// -------------------------------------------------------------------------

describe('IT-VAULT-03: PUT /v1/vault/{id}', () => {
    test('200 — updates credential, archives old password, returns { id, version }', async () => {
        const updateOneMock = jest.fn().mockResolvedValue({ modifiedCount: 1 });
        const historyInsertOneMock = jest.fn().mockResolvedValue({ insertedId: 'hist_uuid' });

        const collectionMock = jest.fn((name) => {
            if (name === 'vault_passwords') {
                return {
                    findOne: jest.fn().mockResolvedValue({
                        _id: 'cred_github',
                        name: 'GitHub',
                        usernameEmail: 'dev@github.com',
                        encryptedPassword: 'old_encrypted_pw',
                        userId: 'uid_test_user',
                        deletedAt: null,
                        version: 2
                    }),
                    updateOne: updateOneMock
                };
            }
            if (name === 'password_history') {
                return {
                    insertOne: historyInsertOneMock
                };
            }
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .put('/v1/vault/cred_github')
            .send({
                name: 'GitHub Enterprise',
                usernameEmail: 'dev@github.com',
                encryptedPassword: 'new_encrypted_pw',
                favorite: 1,
                passwordStrength: 'STRONG'
            });

        expect(res.status).toBe(200);
        // IT-VAULT-03 response: { id, version: newVersion }
        expect(res.body).toHaveProperty('id', 'cred_github');
        expect(res.body).toHaveProperty('version', 3);

        // Verify password_history insertOne was called with old password
        expect(historyInsertOneMock).toHaveBeenCalledTimes(1);
        const historyDoc = historyInsertOneMock.mock.calls[0][0];
        expect(historyDoc.passwordEntryId).toBe('cred_github');
        expect(historyDoc.encryptedPassword).toBe('old_encrypted_pw');
        expect(historyDoc.createdAt).toEqual(expect.any(Number));
        expect(historyDoc._id).toEqual(expect.any(String));

        // PRD F-VAULT-02 AC#4 — verify $inc version is called
        expect(updateOneMock).toHaveBeenCalledTimes(1);
        const updateArgs = updateOneMock.mock.calls[0][1];
        expect(updateArgs.$inc).toEqual({ version: 1 });
        expect(updateArgs.$set).toHaveProperty('name', 'GitHub Enterprise');
        expect(updateArgs.$set).toHaveProperty('updatedAt');
    });

    test('404 — credential not found', async () => {
        const collectionMock = jest.fn((name) => {
            if (name === 'vault_passwords') {
                return {
                    findOne: jest.fn().mockResolvedValue(null)
                };
            }
            if (name === 'password_history') {
                return {};
            }
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .put('/v1/vault/nonexistent')
            .send({
                name: 'New Name',
                usernameEmail: 'user@test.com',
                encryptedPassword: 'encrypted_pw',
                favorite: 0,
                passwordStrength: 'WEAK'
            });

        expect(res.status).toBe(404);
        expect(res.body.error.code).toBe('NOT_FOUND');
        expect(res.body.error.message).toBe('Credential not found.');
    });

    test('400 — rejects empty body (required fields missing)', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .put('/v1/vault/cred_github')
            .send({});

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });
});

// -------------------------------------------------------------------------
// IT-VAULT-04: DELETE /v1/vault/{id} — Soft delete credential
// -------------------------------------------------------------------------

describe('IT-VAULT-04: DELETE /v1/vault/{id}', () => {
    test('200 — sets deletedAt instead of removing record (soft delete)', async () => {
        const updateOneMock = jest.fn().mockResolvedValue({ modifiedCount: 1 });

        const collectionMock = jest.fn(() => ({
            findOne: jest.fn().mockResolvedValue({
                _id: 'cred_github',
                name: 'GitHub',
                userId: 'uid_test_user',
                deletedAt: null,
                version: 1
            }),
            updateOne: updateOneMock
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/vault/cred_github');
        expect(res.status).toBe(200);
        expect(res.body).toHaveProperty('message', 'Credential moved to trash.');

        // Database_Schema.md §3 — verify soft delete: sets deletedAt, does NOT remove record
        expect(updateOneMock).toHaveBeenCalledTimes(1);
        const updateArgs = updateOneMock.mock.calls[0][1];
        expect(updateArgs.$set).toHaveProperty('deletedAt', expect.any(Number));
        expect(updateArgs.$set).toHaveProperty('updatedAt', expect.any(Number));
    });

    test('404 — credential not found', async () => {
        const collectionMock = jest.fn(() => ({
            findOne: jest.fn().mockResolvedValue(null)
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/vault/nonexistent');
        expect(res.status).toBe(404);
        expect(res.body.error.code).toBe('NOT_FOUND');
        expect(res.body.error.message).toBe('Credential not found.');
    });

    test('404 — already soft-deleted credential returns not found', async () => {
        // findOne with deletedAt: null will return null for already-deleted records
        const collectionMock = jest.fn(() => ({
            findOne: jest.fn().mockResolvedValue(null)
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/vault/cred_already_deleted');
        expect(res.status).toBe(404);
        expect(res.body.error.code).toBe('NOT_FOUND');
    });
});
