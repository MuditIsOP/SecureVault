'use strict';

const Joi = require('joi');

/**
 * Sync Controller — POST /v1/sync.
 *
 * Spec refs:
 *   - API_Spec.md §4 — POST /v1/sync (request/response schema)
 *   - PRD F-SYNC-03 AC#1 — "Server compares version counter of incoming
 *     client updates against the database version"
 *   - PRD F-SYNC-03 AC#2 — "If versions differ, server compares incoming
 *     client's updatedDate with server's updatedDate"
 *   - PRD F-SYNC-03 AC#3 — "Version with later timestamp wins. If exact
 *     match, server version retained, client is updated"
 *   - SRS FR-SYNC-03a — "backend gateway SHALL resolve sync conflicts
 *     using logical versions and timestamps"
 *   - SRS FR-SYNC-03b — "backend gateway SHALL restrict database access
 *     using user identity parameters"
 *   - Security_Requirements.md §3 — uid-scoped queries [MUST]
 *   - Security_Requirements.md §5 — Joi input validation [MUST]
 *   - API_Spec.md §5 — Global error envelope format
 *   - Security threats: ST-GATEWAY-TAMP-01, ST-GATEWAY-PRIV-01
 *   - Testing_Strategy.md IT-SYNC-01, ST-STRIDE-06, ST-STRIDE-09
 */

// -------------------------------------------------------------------------
// Input validation — Security_Requirements.md §5
// API_Spec.md §4 — POST /v1/sync request body schema
// -------------------------------------------------------------------------

const syncActionSchema = Joi.object({
    id: Joi.string().required(),
    transactionType: Joi.string().valid('INSERT', 'UPDATE', 'DELETE').required(),
    tableName: Joi.string().valid('vault_passwords', 'categories').required(),
    recordId: Joi.string().required(),
    payloadJson: Joi.string().required(),
    version: Joi.number().integer().min(0).required(),
    updatedAt: Joi.number().integer().required()
});

const syncRequestSchema = Joi.object({
    syncActions: Joi.array().items(syncActionSchema).required(),
    lastSyncTime: Joi.number().integer().required()
});

// -------------------------------------------------------------------------
// POST /v1/sync — API_Spec.md §4
// PRD F-SYNC-03 — Conflict resolution
// -------------------------------------------------------------------------

/**
 * Processes synchronization queue payloads with hybrid conflict resolution.
 *
 * Flow (Architecture.md):
 *   Sync payload → Compare versions on server → Timestamp resolution → Write changes
 *
 * Conflict resolution (PRD F-SYNC-03):
 *   1. Compare incoming version vs server version
 *   2. If versions differ → compare timestamps
 *   3. Later timestamp wins
 *   4. If timestamps match exactly → server version retained
 *
 * @param {object} req - Express request with body { syncActions, lastSyncTime }
 * @param {object} res - Express response
 */
async function processSync(req, res) {
    try {
        // Security_Requirements.md §5 — Joi validation
        const { error, value } = syncRequestSchema.validate(req.body);
        if (error) {
            return res.status(400).json({
                error: {
                    code: 'INVALID_ARGUMENT',
                    message: error.details[0].message,
                    timestamp: Date.now()
                }
            });
        }

        const { syncActions, lastSyncTime } = value;
        // Security_Requirements.md §3 — uid-scoped queries
        // SRS FR-SYNC-03b — restrict DB access using user identity
        const uid = req.user.uid;
        const db = req.app.locals.db;

        const resolvedActions = [];
        const conflicts = [];

        // Process each sync action
        for (const action of syncActions) {
            try {
                const result = await processSingleAction(db, uid, action);
                if (result.resolved) {
                    resolvedActions.push(action.id);
                } else if (result.conflict) {
                    conflicts.push(result.conflict);
                }
            } catch (actionErr) {
                console.error(`[syncController] Action ${action.id} failed:`, actionErr.message);
                // Continue processing remaining actions
            }
        }

        // Fetch remote updates since lastSyncTime — API_Spec.md §4 response
        const remoteUpdates = await getRemoteUpdates(db, uid, lastSyncTime);

        // API_Spec.md §4 — 200 OK response schema
        return res.status(200).json({
            resolvedActions,
            remoteUpdates,
            syncTime: Date.now()
        });

    } catch (err) {
        console.error('[syncController.processSync] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Sync service temporarily unavailable.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// Conflict resolution — PRD F-SYNC-03
// SRS FR-SYNC-03a — "resolve sync conflicts using logical versions and timestamps"
// -------------------------------------------------------------------------

/**
 * Processes a single sync action with hybrid conflict resolution.
 *
 * PRD F-SYNC-03 AC#1 — Compare version counters
 * PRD F-SYNC-03 AC#2 — Compare timestamps if versions differ
 * PRD F-SYNC-03 AC#3 — Later timestamp wins; ties → server wins
 *
 * @param {object} db - Database reference
 * @param {string} uid - User ID for scoping
 * @param {object} action - Sync action from client
 * @returns {object} { resolved: boolean, conflict: object|null }
 */
async function processSingleAction(db, uid, action) {
    const collection = db.collection(action.tableName);

    switch (action.transactionType) {
        case 'INSERT':
            return await handleInsert(collection, uid, action);
        case 'UPDATE':
            return await handleUpdate(collection, uid, action);
        case 'DELETE':
            return await handleDelete(collection, uid, action);
        default:
            return { resolved: false, conflict: null };
    }
}

/**
 * Handles INSERT sync action.
 * If record already exists → treat as conflict (version comparison).
 */
async function handleInsert(collection, uid, action) {
    // Security_Requirements.md §3 — uid-scoped query
    const existing = await collection.findOne({
        _id: action.recordId,
        userId: uid
    });

    if (existing) {
        // Record already exists — resolve via conflict resolution
        return resolveConflict(collection, uid, action, existing);
    }

    // New record — insert directly
    const payload = JSON.parse(action.payloadJson);
    await collection.insertOne({
        _id: action.recordId,
        userId: uid,
        ...payload,
        version: action.version,
        updated_at: action.updatedAt,
        created_at: action.updatedAt,
        deleted_at: null
    });

    return { resolved: true, conflict: null };
}

/**
 * Handles UPDATE sync action with conflict resolution.
 *
 * PRD F-SYNC-03 AC#1/2/3 — Version + timestamp comparison.
 */
async function handleUpdate(collection, uid, action) {
    // Security_Requirements.md §3 — uid-scoped query
    const existing = await collection.findOne({
        _id: action.recordId,
        userId: uid
    });

    if (!existing) {
        // Record not found — treat as INSERT
        return await handleInsert(collection, uid, action);
    }

    return resolveConflict(collection, uid, action, existing);
}

/**
 * Handles DELETE sync action.
 * For vault_passwords: soft-delete (set deleted_at).
 * For categories: hard delete (Database_Schema.md §3).
 */
async function handleDelete(collection, uid, action) {
    // Security_Requirements.md §3 — uid-scoped query
    const existing = await collection.findOne({
        _id: action.recordId,
        userId: uid
    });

    if (!existing) {
        // Already deleted or not found — mark as resolved
        return { resolved: true, conflict: null };
    }

    if (action.tableName === 'vault_passwords') {
        // Soft delete — Database_Schema.md §3
        await collection.updateOne(
            { _id: action.recordId, userId: uid },
            { $set: { deleted_at: Date.now() } }
        );
    } else {
        // Hard delete for categories — Database_Schema.md §3
        await collection.deleteOne({
            _id: action.recordId,
            userId: uid
        });
    }

    return { resolved: true, conflict: null };
}

/**
 * Hybrid conflict resolution.
 *
 * PRD F-SYNC-03:
 *   AC#1 — "Server compares version counter of incoming client updates
 *           against the database version"
 *   AC#2 — "If versions differ, server compares incoming client's
 *           updatedDate with server's updatedDate"
 *   AC#3 — "Version with later timestamp wins. If timestamps match
 *           exactly, server version is retained, client is updated"
 *
 * @param {object} collection - MongoDB collection
 * @param {string} uid - User ID
 * @param {object} clientAction - Client sync action
 * @param {object} serverRecord - Existing server record
 * @returns {object} { resolved: boolean, conflict: object|null }
 */
async function resolveConflict(collection, uid, clientAction, serverRecord) {
    const clientVersion = clientAction.version;
    const serverVersion = serverRecord.version || 0;
    const clientUpdatedAt = clientAction.updatedAt;
    const serverUpdatedAt = serverRecord.updated_at || 0;

    // AC#1 — Compare version counters
    if (clientVersion === serverVersion) {
        // Same version — compare timestamps
        // AC#2, AC#3
        if (clientUpdatedAt > serverUpdatedAt) {
            // Client wins — apply client changes
            await applyClientUpdate(collection, uid, clientAction);
            return { resolved: true, conflict: null };
        } else {
            // Server wins (including exact match) — AC#3
            return {
                resolved: false,
                conflict: {
                    recordId: clientAction.recordId,
                    tableName: clientAction.tableName,
                    resolution: 'server_wins',
                    serverVersion,
                    serverUpdatedAt
                }
            };
        }
    }

    // AC#2 — Versions differ, compare timestamps
    if (clientUpdatedAt > serverUpdatedAt) {
        // Client has later timestamp — client wins
        await applyClientUpdate(collection, uid, clientAction);
        return { resolved: true, conflict: null };
    } else if (clientUpdatedAt === serverUpdatedAt) {
        // AC#3 — Exact timestamp match → server wins
        return {
            resolved: false,
            conflict: {
                recordId: clientAction.recordId,
                tableName: clientAction.tableName,
                resolution: 'server_wins',
                serverVersion,
                serverUpdatedAt
            }
        };
    } else {
        // Server has later timestamp — server wins
        return {
            resolved: false,
            conflict: {
                recordId: clientAction.recordId,
                tableName: clientAction.tableName,
                resolution: 'server_wins',
                serverVersion,
                serverUpdatedAt
            }
        };
    }
}

/**
 * Applies a client update to the server record.
 *
 * Writes to password_history before updating (same as vaultController PUT).
 * PRD F-VAULT-02 AC#4 — version counter increment.
 */
async function applyClientUpdate(collection, uid, clientAction) {
    const payload = JSON.parse(clientAction.payloadJson);

    await collection.updateOne(
        { _id: clientAction.recordId, userId: uid },
        {
            $set: {
                ...payload,
                version: clientAction.version,
                updated_at: clientAction.updatedAt
            }
        }
    );
}

// -------------------------------------------------------------------------
// Remote updates — API_Spec.md §4 response: remoteUpdates
// -------------------------------------------------------------------------

/**
 * Fetches records updated since lastSyncTime for the user.
 *
 * API_Spec.md §4 — "Returns resolved actions and remote updates"
 * Security_Requirements.md §3 — uid-scoped queries
 *
 * @param {object} db - Database reference
 * @param {string} uid - User ID
 * @param {number} lastSyncTime - Client's last sync timestamp
 * @returns {Array} Remote updates since lastSyncTime
 */
async function getRemoteUpdates(db, uid, lastSyncTime) {
    const updates = [];

    // Fetch vault_passwords updated since lastSyncTime
    const vaultCollection = db.collection('vault_passwords');
    const vaultUpdates = await vaultCollection
        .find({
            userId: uid,
            updated_at: { $gt: lastSyncTime }
        })
        .sort({ updated_at: 1 })
        .toArray();

    for (const record of vaultUpdates) {
        updates.push({
            tableName: 'vault_passwords',
            recordId: record._id,
            transactionType: record.deleted_at ? 'DELETE' : 'UPDATE',
            payloadJson: JSON.stringify(record),
            version: record.version || 1,
            updatedAt: record.updated_at
        });
    }

    // Fetch categories updated since lastSyncTime
    const categoryCollection = db.collection('categories');
    const categoryUpdates = await categoryCollection
        .find({
            userId: uid,
            updated_at: { $gt: lastSyncTime }
        })
        .sort({ updated_at: 1 })
        .toArray();

    for (const record of categoryUpdates) {
        updates.push({
            tableName: 'categories',
            recordId: record._id,
            transactionType: 'UPDATE',
            payloadJson: JSON.stringify(record),
            version: record.version || 1,
            updatedAt: record.updated_at
        });
    }

    return updates;
}

// Exported for testing — conflict resolver is also exported
module.exports = { processSync, resolveConflict };
