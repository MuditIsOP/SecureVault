'use strict';

const Joi = require('joi');

/**
 * Lockout controller — POST /v1/auth/lockout.
 *
 * Spec refs:
 *   - API_Spec.md §2 POST /v1/auth/lockout — request/response schemas
 *   - PRD F-AUTH-04 — AC#4 (lockout state and failed attempt counter synced to backend)
 *   - Security_Requirements.md §5 Input Validation — Joi validation [MUST]
 *   - Security_Requirements.md §6 Brute Force (Cloud Enforced Block)
 *   - Security_Requirements.md §3 Authorisation — zero client-side trust, uid scoping
 */

const lockoutSchema = Joi.object({
    pinFailedAttempts: Joi.number().integer().min(0).required(),
    pinLockoutUntil: Joi.number().integer().allow(null).required()
});

/**
 * POST /v1/auth/lockout
 * Synchronizes the failed PIN attempts and lockout timestamp.
 */
async function syncLockout(req, res) {
    // Validate input schema
    const { error: validationError, value: body } = lockoutSchema.validate(req.body);
    if (validationError) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: validationError.details[0].message,
                timestamp: Date.now()
            }
        });
    }

    const uid = req.user.uid; // Attached by verifyFirebaseToken middleware
    const { pinFailedAttempts, pinLockoutUntil } = body;

    const db = req.app.locals.db;
    const usersCollection = db.collection('users');

    try {
        // Update user record in MongoDB, scoped strictly by uid
        const result = await usersCollection.updateOne(
            { id: uid },
            {
                $set: {
                    pinFailedAttempts,
                    pinLockoutUntil,
                    updatedAt: Date.now()
                }
            }
        );

        if (result.matchedCount === 0) {
            return res.status(404).json({
                error: {
                    code: 'NOT_FOUND',
                    message: 'User record not found.',
                    timestamp: Date.now()
                }
            });
        }

        return res.status(200).json({
            status: 'success',
            message: 'Lockout state synced.'
        });
    } catch (err) {
        console.error('[lockoutController.syncLockout] Database error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Database query failed.',
                timestamp: Date.now()
            }
        });
    }
}

module.exports = { syncLockout };
