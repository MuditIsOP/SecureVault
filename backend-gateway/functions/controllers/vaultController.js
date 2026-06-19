'use strict';

const Joi = require('joi');
const { randomUUID } = require('crypto');

/**
 * Vault Controller — CRUD for /v1/vault.
 *
 * Spec refs:
 *   - API_Spec.md §3 — GET/POST/PUT/DELETE /v1/vault
 *   - PRD F-VAULT-02 — Password Management (5 acceptance criteria)
 *   - PRD F-VAULT-02 AC#4 — version counter increment on edit
 *   - PRD F-VAULT-02 AC#5 — soft delete via deletedDate
 *   - Database_Schema.md §2.2 — vault_passwords columns
 *   - Database_Schema.md §2.3 — password_history columns
 *   - Database_Schema.md §3 — soft-deletion only for vault_passwords
 *   - Security_Requirements.md §3 — uid-scoped queries [MUST]
 *   - Security_Requirements.md §5 — Joi input validation [MUST]
 *   - API_Spec.md §5 — Global error envelope format
 *   - Testing_Strategy.md IT-VAULT-01 to IT-VAULT-04
 *   - Permissions_Matrix.md — All roles allowed, owner-scoped
 */

// -------------------------------------------------------------------------
// Input validation schemas — Security_Requirements.md §5
// -------------------------------------------------------------------------

// POST /v1/vault — API_Spec.md §3
const createCredentialSchema = Joi.object({
    id: Joi.string().required(),
    name: Joi.string().min(1).max(256).required(),
    usernameEmail: Joi.string().min(1).max(256).required(),
    encryptedPassword: Joi.string().min(1).required(),
    websiteUrl: Joi.string().max(2048).optional().allow(''),
    categoryId: Joi.string().optional().allow(null),
    favorite: Joi.number().integer().valid(0, 1).required(),
    passwordStrength: Joi.string().valid('WEAK', 'MEDIUM', 'STRONG').required()
});

// PUT /v1/vault/{id} — API_Spec.md §3
const updateCredentialSchema = Joi.object({
    name: Joi.string().min(1).max(256).required(),
    usernameEmail: Joi.string().min(1).max(256).required(),
    encryptedPassword: Joi.string().min(1).required(),
    websiteUrl: Joi.string().max(2048).optional().allow(''),
    categoryId: Joi.string().optional().allow(null),
    favorite: Joi.number().integer().valid(0, 1).required(),
    passwordStrength: Joi.string().valid('WEAK', 'MEDIUM', 'STRONG').required()
});

// -------------------------------------------------------------------------
// GET /v1/vault — IT-VAULT-01
// -------------------------------------------------------------------------

/**
 * Retrieve all non-deleted credentials for the authenticated user.
 *
 * Response schema (IT-VAULT-01):
 *   200 OK — Array of { id, name, usernameEmail, encryptedPassword,
 *                        websiteUrl, categoryId, favorite, createdAt,
 *                        updatedAt, passwordStrength, version }
 *
 * Sort: updatedAt DESC (most recently updated first).
 *
 * Security_Requirements.md §3 — queries scoped to req.user.uid.
 * Database_Schema.md §3 — filter out soft-deleted records.
 */
async function getVault(req, res) {
    try {
        const db = req.app.locals.db;
        const uid = req.user.uid;
        const passwordsCollection = db.collection('vault_passwords');

        const credentials = await passwordsCollection
            .find({ userId: uid, deletedAt: null })
            .sort({ updatedAt: -1 })
            .toArray();

        // Map to response schema — IT-VAULT-01
        const response = credentials.map(cred => ({
            id: cred._id || cred.id,
            name: cred.name,
            usernameEmail: cred.usernameEmail,
            encryptedPassword: cred.encryptedPassword,
            websiteUrl: cred.websiteUrl || '',
            categoryId: cred.categoryId || null,
            favorite: cred.favorite || 0,
            passwordStrength: cred.passwordStrength || 'WEAK',
            createdAt: cred.createdAt,
            updatedAt: cred.updatedAt,
            version: cred.version || 1
        }));

        return res.status(200).json(response);
    } catch (err) {
        console.error('[vaultController.getVault] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to retrieve credentials.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// POST /v1/vault — IT-VAULT-02
// -------------------------------------------------------------------------

/**
 * Create a new credential.
 *
 * Request body: { id, name, usernameEmail, encryptedPassword, websiteUrl?,
 *                 categoryId?, favorite, passwordStrength } — API_Spec.md §3
 * Response: 201 Created — { id: string, version: 1 }
 *
 * Validates:
 *   - Joi schema (Security_Requirements.md §5)
 *
 * Database_Schema.md §2.2 — vault_passwords columns
 * Database_Schema.md §3 — deletedAt: null on creation (soft-deletion model)
 */
async function createCredential(req, res) {
    // Joi validation — Security_Requirements.md §5
    const { error, value } = createCredentialSchema.validate(req.body, { stripUnknown: true });
    if (error) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: error.details[0].message,
                timestamp: Date.now()
            }
        });
    }

    try {
        const db = req.app.locals.db;
        const uid = req.user.uid;
        const passwordsCollection = db.collection('vault_passwords');

        const now = Date.now();
        const credentialDoc = {
            _id: value.id,
            userId: uid,
            name: value.name,
            usernameEmail: value.usernameEmail,
            encryptedPassword: value.encryptedPassword,
            websiteUrl: value.websiteUrl || '',
            categoryId: value.categoryId || null,
            favorite: value.favorite,
            passwordStrength: value.passwordStrength,
            createdAt: now,
            updatedAt: now,
            deletedAt: null,
            version: 1
        };

        await passwordsCollection.insertOne(credentialDoc);

        // IT-VAULT-02 response schema: { id, version: 1 }
        return res.status(201).json({ id: value.id, version: 1 });
    } catch (err) {
        console.error('[vaultController.createCredential] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to create credential.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// PUT /v1/vault/{id} — IT-VAULT-03
// -------------------------------------------------------------------------

/**
 * Update an existing credential.
 *
 * Path param: id — credential ID
 * Request body: { name, usernameEmail, encryptedPassword, websiteUrl?,
 *                 categoryId?, favorite, passwordStrength }
 * Response: 200 OK — { id: string, version: number }
 *
 * Side effects:
 *   - Archives the OLD encryptedPassword into password_history collection
 *     before applying the update (Database_Schema.md §2.3).
 *
 * PRD F-VAULT-02 AC#4 — version counter increment on edit ($inc version by 1).
 * Database_Schema.md §4 — updates updatedAt server-side.
 * Security_Requirements.md §3 — queries scoped to req.user.uid.
 * Database_Schema.md §3 — only update non-deleted records (deletedAt: null).
 */
async function updateCredential(req, res) {
    const credentialId = req.params.id;

    if (!credentialId || credentialId.trim().length === 0) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: 'Credential ID is required.',
                timestamp: Date.now()
            }
        });
    }

    const { error, value } = updateCredentialSchema.validate(req.body, { stripUnknown: true });
    if (error) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: error.details[0].message,
                timestamp: Date.now()
            }
        });
    }

    try {
        const db = req.app.locals.db;
        const uid = req.user.uid;
        const passwordsCollection = db.collection('vault_passwords');
        const historyCollection = db.collection('password_history');

        // Verify ownership + not soft-deleted — Security_Requirements.md §3, Database_Schema.md §3
        const credential = await passwordsCollection.findOne({
            _id: credentialId,
            userId: uid,
            deletedAt: null
        });

        if (!credential) {
            return res.status(404).json({
                error: {
                    code: 'NOT_FOUND',
                    message: 'Credential not found.',
                    timestamp: Date.now()
                }
            });
        }

        // Archive old encrypted password to password_history — Database_Schema.md §2.3
        await historyCollection.insertOne({
            _id: randomUUID(),
            passwordEntryId: credentialId,
            encryptedPassword: credential.encryptedPassword,
            createdAt: Date.now()
        });

        // Database_Schema.md §4 — update server-side timestamp
        // PRD F-VAULT-02 AC#4 — $inc version by 1
        const newVersion = (credential.version || 1) + 1;

        await passwordsCollection.updateOne(
            { _id: credentialId, userId: uid, deletedAt: null },
            {
                $set: { ...value, updatedAt: Date.now() },
                $inc: { version: 1 }
            }
        );

        return res.status(200).json({ id: credentialId, version: newVersion });
    } catch (err) {
        console.error('[vaultController.updateCredential] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to update credential.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// DELETE /v1/vault/{id} — IT-VAULT-04
// -------------------------------------------------------------------------

/**
 * Soft-delete a credential (move to trash).
 *
 * Path param: id — credential ID
 * Response: 200 OK — { message: 'Credential moved to trash.' }
 *
 * PRD F-VAULT-02 AC#5 — soft delete via deletedDate.
 * Database_Schema.md §3 — soft-deletion only; do NOT actually delete the record.
 * Security_Requirements.md §3 — queries scoped to req.user.uid.
 */
async function deleteCredential(req, res) {
    const credentialId = req.params.id;

    if (!credentialId || credentialId.trim().length === 0) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: 'Credential ID is required.',
                timestamp: Date.now()
            }
        });
    }

    try {
        const db = req.app.locals.db;
        const uid = req.user.uid;
        const passwordsCollection = db.collection('vault_passwords');

        // Verify ownership + not already deleted — Security_Requirements.md §3
        const credential = await passwordsCollection.findOne({
            _id: credentialId,
            userId: uid,
            deletedAt: null
        });

        if (!credential) {
            return res.status(404).json({
                error: {
                    code: 'NOT_FOUND',
                    message: 'Credential not found.',
                    timestamp: Date.now()
                }
            });
        }

        // Soft delete — Database_Schema.md §3, PRD F-VAULT-02 AC#5
        // Set deletedAt timestamp, do NOT remove record
        const now = Date.now();
        await passwordsCollection.updateOne(
            { _id: credentialId, userId: uid, deletedAt: null },
            { $set: { deletedAt: now, updatedAt: now } }
        );

        return res.status(200).json({ message: 'Credential moved to trash.' });
    } catch (err) {
        console.error('[vaultController.deleteCredential] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to delete credential.',
                timestamp: Date.now()
            }
        });
    }
}

/**
 * DELETE /v1/vault/trash/empty — Permanently delete all soft-deleted credentials.
 *
 * Spec refs:
 *   - API_Spec.md §3 — DELETE /v1/vault/trash/empty
 *   - IT-VAULT-05 — Empty Trash test
 *   - PRD F-VAULT-07 AC#5 — "Empty Trash permanently purges soft-deleted entries"
 *   - SRS FR-VAULT-07 — "permanently purging from local and remote databases"
 *   - Database_Schema.md §2.2 — vault_passwords.deleted_at
 *   - Security_Requirements.md §3 — uid-scoped queries
 *
 * @param {import('express').Request} req
 * @param {import('express').Response} res
 */
async function emptyTrash(req, res) {
    try {
        const uid = req.user.uid;
        const db = req.app.locals.db;
        const collection = db.collection('vault_passwords');

        // Hard-delete all soft-deleted records for this user
        // uid-scoped query — Security_Requirements.md §3
        const result = await collection.deleteMany({
            userId: uid,
            deleted_at: { $ne: null }
        });

        return res.status(200).json({
            message: 'Trash emptied.',
            deletedCount: result.deletedCount
        });
    } catch (err) {
        console.error('[vaultController.emptyTrash] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to empty trash.',
                timestamp: Date.now()
            }
        });
    }
}

module.exports = {
    getVault,
    createCredential,
    updateCredential,
    deleteCredential,
    emptyTrash
};

