'use strict';

/**
 * IT-VAULT-06 to IT-VAULT-09: Category CRUD API tests.
 *
 * Spec refs:
 *   - Testing_Strategy.md IT-VAULT-06 — GET /v1/categories (200 OK, array schema)
 *   - Testing_Strategy.md IT-VAULT-07 — POST /v1/categories (201 Created)
 *   - Testing_Strategy.md IT-VAULT-08 — PUT /v1/categories/{id} (200 OK)
 *   - Testing_Strategy.md IT-VAULT-09 — DELETE /v1/categories/{id} (200 OK, resets passwords)
 *   - API_Spec.md §3 — full request/response contracts
 *   - Security_Requirements.md §3 — uid-scoped queries
 *   - Security_Requirements.md §5 — Joi input validation
 */

const request = require('supertest');
const express = require('express');
const { getCategories, createCategory, updateCategory, deleteCategory } = require('../controllers/categoryController');

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

    app.get('/v1/categories', getCategories);
    app.post('/v1/categories', createCategory);
    app.put('/v1/categories/:id', updateCategory);
    app.delete('/v1/categories/:id', deleteCategory);

    return app;
}

// -------------------------------------------------------------------------
// IT-VAULT-06: GET /v1/categories — Fetch default and custom categories
// -------------------------------------------------------------------------

describe('IT-VAULT-06: GET /v1/categories', () => {
    test('200 — returns seeded defaults when no categories exist', async () => {
        const insertedDocs = [];
        const collectionMock = jest.fn((name) => {
            if (name === 'categories') {
                return {
                    find: jest.fn(() => ({
                        sort: jest.fn(() => ({
                            toArray: jest.fn()
                                .mockResolvedValueOnce([]) // First call: empty
                        }))
                    })),
                    insertMany: jest.fn((docs) => {
                        insertedDocs.push(...docs);
                        return Promise.resolve({ insertedCount: docs.length });
                    })
                };
            }
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).get('/v1/categories');
        expect(res.status).toBe(200);
        expect(Array.isArray(res.body)).toBe(true);
        // Verify seed docs were inserted
        expect(insertedDocs.length).toBe(5);
        expect(insertedDocs.map(d => d.name)).toEqual(
            ['Personal', 'Work', 'Banking', 'Shopping', 'Social']
        );
    });

    test('200 — returns existing categories with correct schema', async () => {
        const existingCategories = [
            { _id: 'cat_1', name: 'Personal', isDefault: true, userId: 'uid_test_user' },
            { _id: 'cat_2', name: 'Gaming', isDefault: false, userId: 'uid_test_user' }
        ];

        const collectionMock = jest.fn(() => ({
            find: jest.fn(() => ({
                sort: jest.fn(() => ({
                    toArray: jest.fn().mockResolvedValue(existingCategories)
                }))
            }))
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).get('/v1/categories');
        expect(res.status).toBe(200);
        expect(res.body).toHaveLength(2);

        // IT-VAULT-06 schema: { id: string, name: string, isDefault: boolean }
        expect(res.body[0]).toHaveProperty('id');
        expect(res.body[0]).toHaveProperty('name');
        expect(res.body[0]).toHaveProperty('isDefault');
        expect(res.body[0].id).toBe('cat_1');
        expect(res.body[0].name).toBe('Personal');
        expect(res.body[0].isDefault).toBe(true);
    });
});

// -------------------------------------------------------------------------
// IT-VAULT-07: POST /v1/categories — Create a new custom category
// -------------------------------------------------------------------------

describe('IT-VAULT-07: POST /v1/categories', () => {
    test('201 — creates custom category with valid body', async () => {
        const collectionMock = jest.fn(() => ({
            findOne: jest.fn().mockResolvedValue(null), // No duplicate
            insertOne: jest.fn().mockResolvedValue({ insertedId: 'cat_uuid_gaming' })
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/categories')
            .send({ id: 'cat_uuid_gaming', name: 'Gaming Logins' });

        expect(res.status).toBe(201);
        // IT-VAULT-07 response: { id: "cat_uuid_string" }
        expect(res.body).toHaveProperty('id', 'cat_uuid_gaming');
    });

    test('400 — rejects missing name', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/categories')
            .send({ id: 'cat_uuid_1' });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('400 — rejects empty name', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/categories')
            .send({ id: 'cat_uuid_1', name: '' });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });

    test('409 — rejects duplicate category name', async () => {
        const collectionMock = jest.fn(() => ({
            findOne: jest.fn().mockResolvedValue({
                _id: 'existing_cat', name: 'Gaming Logins', userId: 'uid_test_user'
            })
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .post('/v1/categories')
            .send({ id: 'cat_uuid_new', name: 'Gaming Logins' });

        expect(res.status).toBe(409);
        expect(res.body.error.code).toBe('ALREADY_EXISTS');
        expect(res.body.error.message).toBe('Category name already exists.');
    });
});

// -------------------------------------------------------------------------
// IT-VAULT-08: PUT /v1/categories/{id} — Edit custom category name
// -------------------------------------------------------------------------

describe('IT-VAULT-08: PUT /v1/categories/{id}', () => {
    test('200 — updates custom category name', async () => {
        const collectionMock = jest.fn(() => ({
            findOne: jest.fn()
                .mockResolvedValueOnce({ _id: 'cat_custom', name: 'Gaming', isDefault: false, userId: 'uid_test_user' })
                .mockResolvedValueOnce(null), // No duplicate
            updateOne: jest.fn().mockResolvedValue({ modifiedCount: 1 })
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .put('/v1/categories/cat_custom')
            .send({ name: 'eSports Logins' });

        expect(res.status).toBe(200);
    });

    test('404 — category not found', async () => {
        const collectionMock = jest.fn(() => ({
            findOne: jest.fn().mockResolvedValue(null)
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .put('/v1/categories/nonexistent')
            .send({ name: 'New Name' });

        expect(res.status).toBe(404);
        expect(res.body.error.code).toBe('NOT_FOUND');
    });

    test('403 — cannot rename default category', async () => {
        const collectionMock = jest.fn(() => ({
            findOne: jest.fn().mockResolvedValue({
                _id: 'cat_personal', name: 'Personal', isDefault: true, userId: 'uid_test_user'
            })
        }));

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app)
            .put('/v1/categories/cat_personal')
            .send({ name: 'My Personal' });

        expect(res.status).toBe(403);
        expect(res.body.error.code).toBe('PERMISSION_DENIED');
    });

    test('400 — rejects empty name', async () => {
        const dbMock = { collection: jest.fn() };
        const app = buildApp(dbMock);

        const res = await request(app)
            .put('/v1/categories/cat_custom')
            .send({ name: '' });

        expect(res.status).toBe(400);
        expect(res.body.error.code).toBe('INVALID_ARGUMENT');
    });
});

// -------------------------------------------------------------------------
// IT-VAULT-09: DELETE /v1/categories/{id} — Delete custom category
// -------------------------------------------------------------------------

describe('IT-VAULT-09: DELETE /v1/categories/{id}', () => {
    test('200 — deletes category and resets passwords to null', async () => {
        const updateManyMock = jest.fn().mockResolvedValue({ modifiedCount: 3 });
        const deleteOneMock = jest.fn().mockResolvedValue({ deletedCount: 1 });

        const collectionMock = jest.fn((name) => {
            if (name === 'categories') {
                return {
                    findOne: jest.fn().mockResolvedValue({
                        _id: 'cat_gaming', name: 'Gaming', isDefault: false, userId: 'uid_test_user'
                    }),
                    deleteOne: deleteOneMock
                };
            }
            if (name === 'vault_passwords') {
                return {
                    updateMany: updateManyMock
                };
            }
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/categories/cat_gaming');
        expect(res.status).toBe(200);

        // IT-VAULT-09 — "resets assigned passwords' category_id value to null"
        expect(updateManyMock).toHaveBeenCalledWith(
            { categoryId: 'cat_gaming', userId: 'uid_test_user' },
            expect.objectContaining({
                $set: expect.objectContaining({ categoryId: null })
            })
        );

        // Verify category was deleted
        expect(deleteOneMock).toHaveBeenCalledWith({
            _id: 'cat_gaming', userId: 'uid_test_user'
        });
    });

    test('404 — category not found', async () => {
        const collectionMock = jest.fn((name) => {
            if (name === 'categories') {
                return {
                    findOne: jest.fn().mockResolvedValue(null)
                };
            }
            if (name === 'vault_passwords') {
                return {};
            }
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/categories/nonexistent');
        expect(res.status).toBe(404);
    });

    test('403 — cannot delete default category', async () => {
        const collectionMock = jest.fn((name) => {
            if (name === 'categories') {
                return {
                    findOne: jest.fn().mockResolvedValue({
                        _id: 'cat_personal', name: 'Personal', isDefault: true, userId: 'uid_test_user'
                    })
                };
            }
            if (name === 'vault_passwords') {
                return {};
            }
        });

        const dbMock = { collection: collectionMock };
        const app = buildApp(dbMock);

        const res = await request(app).delete('/v1/categories/cat_personal');
        expect(res.status).toBe(403);
        expect(res.body.error.code).toBe('PERMISSION_DENIED');
    });
});
