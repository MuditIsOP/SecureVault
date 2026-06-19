'use strict';

const Joi = require('joi');
const crypto = require('crypto');
const { generateChallengeToken, verifyChallengeToken } = require('./securityController');

/**
 * Backup codes controller.
 *   POST /v1/auth/backup-codes/verify
 *   POST /v1/auth/backup-codes/regenerate
 *
 * Spec refs:
 *   - API_Spec.md §2 — request/response schemas
 *   - SRS FR-AUTH-05 — bypass PIN unlock, reset PIN on valid backup code
 *   - Security_Requirements.md §4 RESTRICTED field — SHA-256 hashes, never plaintext
 *   - Security_Requirements.md §5 Input Validation — Joi validation schemas [MUST]
 *   - Security_Requirements.md §6 Response Sanitisation — never leak hashes in API response
 *   - Permissions_Matrix.md §3 C2 — regeneration requires challengeToken verification
 */

// Input validation schemas — Security_Requirements.md §5
const verifySchema = Joi.object({
    backupCode: Joi.string().pattern(/^[A-Z0-9]{4}-[A-Z0-9]{4}$/).required()
});

const regenerateSchema = Joi.object({
    challengeToken: Joi.string().optional(),
    hashedBackupCodes: Joi.array().items(Joi.string().length(64)).length(2).required()
});

const CHALLENGE_TOKEN_TTL_MS = 15 * 60 * 1000;

/**
 * POST /v1/auth/backup-codes/verify
 * IT-AUTH-06
 */
async function verifyBackupCode(req, res) {
  try {
    const { error: validationError, value: body } = verifySchema.validate(req.body);
    if (validationError) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: validationError.details[0].message,
                timestamp: Date.now()
            }
        });
    }

    const uid = req.user.uid;
    const { backupCode } = body;
    const db = req.app.locals.db;
    const usersCollection = db.collection('users');

    const user = await usersCollection.findOne({ id: uid });
    if (!user) {
        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'User not found.',
                timestamp: Date.now()
            }
        });
    }

    const storedHashesStr = user.backupCodeHashes || '';
    if (!storedHashesStr) {
        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'No backup codes configured.',
                timestamp: Date.now()
            }
        });
    }

    const storedHashes = storedHashesStr.split(',');
    const inputHash = crypto.createHash('sha256').update(backupCode).digest('hex');

    // Constant-time search to prevent timing attacks on backup codes
    let matchIndex = -1;
    for (let i = 0; i < storedHashes.length; i++) {
        const hashBuffer = Buffer.from(storedHashes[i], 'hex');
        const inputBuffer = Buffer.from(inputHash, 'hex');
        
        if (hashBuffer.length === inputBuffer.length && crypto.timingSafeEqual(hashBuffer, inputBuffer)) {
            matchIndex = i;
        }
    }

    if (matchIndex === -1) {
        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'Invalid backup code.',
                timestamp: Date.now()
            }
        });
    }

    // Single-use: remove matched code from the list
    storedHashes.splice(matchIndex, 1);
    const updatedHashesStr = storedHashes.join(',');

    await usersCollection.updateOne(
        { id: uid },
        { $set: { backupCodeHashes: updatedHashesStr, updatedAt: Date.now() } }
    );

    // Generate short-lived challenge token for PIN reset
    const expiresAt = Date.now() + CHALLENGE_TOKEN_TTL_MS;
    const challengeToken = generateChallengeToken(uid, expiresAt);

    return res.status(200).json({
        status: 'success',
        challengeToken,
        resetToken: challengeToken
    });
  } catch (err) {
    console.error('[backupController.verifyBackupCode] Error:', err.message);
    return res.status(503).json({
        error: {
            code: 'SERVICE_UNAVAILABLE',
            message: 'Backup code verification temporarily unavailable.',
            timestamp: Date.now()
        }
    });
  }
}

/**
 * POST /v1/auth/backup-codes/regenerate
 * IT-AUTH-07
 */
async function regenerateBackupCodes(req, res) {
  try {
    const { error: validationError, value: body } = regenerateSchema.validate(req.body);
    if (validationError) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: validationError.details[0].message,
                timestamp: Date.now()
            }
        });
    }

    const uid = req.user.uid;
    const { challengeToken, hashedBackupCodes } = body;
    const db = req.app.locals.db;
    const usersCollection = db.collection('users');

    const user = await usersCollection.findOne({ id: uid });
    if (!user) {
        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'User not found.',
                timestamp: Date.now()
            }
        });
    }

    // C2 Policy check: editing/regenerating backup codes requires valid challengeToken
    // ONLY if codes are already configured. Onboarding first-time setup does not require it.
    const hasExistingCodes = user.backupCodeHashes && user.backupCodeHashes.length > 0;
    if (hasExistingCodes) {
        if (!challengeToken || !verifyChallengeToken(challengeToken, uid)) {
            return res.status(401).json({
                error: {
                    code: 'UNAUTHENTICATED',
                    message: 'Challenge verification token required to regenerate backup codes.',
                    timestamp: Date.now()
                }
            });
        }
    }

    const newHashesStr = hashedBackupCodes.join(',');

    await usersCollection.updateOne(
        { id: uid },
        { $set: { backupCodeHashes: newHashesStr, updatedAt: Date.now() } }
    );

    return res.status(200).json({
        status: 'success',
        message: 'Backup codes successfully regenerated.'
    });
  } catch (err) {
    console.error('[backupController.regenerateBackupCodes] Error:', err.message);
    return res.status(503).json({
        error: {
            code: 'SERVICE_UNAVAILABLE',
            message: 'Backup code regeneration temporarily unavailable.',
            timestamp: Date.now()
        }
    });
  }
}

module.exports = { verifyBackupCode, regenerateBackupCodes };
