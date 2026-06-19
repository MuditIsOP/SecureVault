'use strict';

const Joi = require('joi');
const crypto = require('crypto');

/**
 * Security question controller.
 *   POST /v1/auth/security-question/setup
 *   POST /v1/auth/security-question/verify
 *
 * Spec refs:
 *   - API_Spec.md §2 — full request/response schema for both endpoints
 *   - SRS FR-AUTH-02 — register security question; hash verified server-side
 *   - PRD F-AUTH-02 — PBKDF2 on client; server stores hash, NEVER plaintext
 *   - Security_Requirements.md §5 Input Validation — Joi schemas [MUST]
 *   - Security_Requirements.md §6 Brute Force — adaptive delay on verify failures
 *   - Security_Requirements.md §6 Response Sanitisation — NEVER return hash in response
 *   - Permissions_Matrix.md §3 C2 — editing security question requires challengeToken
 *   - Permissions_Matrix.md §3 C3 — VMK retrieval scoped to uid from JWT
 *   - Database_Schema.md §2.1 — users.security_question_id, security_answer_hash columns
 *   - Testing_Strategy.md IT-AUTH-03 & IT-AUTH-04
 *
 * Both endpoints require authMiddleware (Bearer JWT) — API_Spec.md §2 Auth: JWT.
 * req.user.uid is set by authMiddleware.verifyFirebaseToken.
 *
 * Challenge token: short-lived opaque HMAC token (SHA-256, 15-minute TTL).
 * The token is returned to the client for use in admin-gated operations.
 */

// ---------------------------------------------------------------------------
// Input validation schemas — Security_Requirements.md §5
// ---------------------------------------------------------------------------

const setupSchema = Joi.object({
    securityQuestionId: Joi.string().min(1).max(64).required(),
    // securityAnswerHash: PBKDF2 hash from client — base64(salt):base64(key)
    securityAnswerHash: Joi.string().min(1).max(512).required(),
    encryptedVmk: Joi.string().min(1).max(2048).required(),
    challengeToken: Joi.string().optional()
});

const verifySchema = Joi.object({
    // Client sends normalized plaintext answer; server re-hashes with stored salt
    securityAnswer: Joi.string().min(1).max(256).required()
});

// Challenge token TTL — 15 minutes (short-lived per API_Spec.md §2)
const CHALLENGE_TOKEN_TTL_MS = 15 * 60 * 1000;
const CHALLENGE_TOKEN_SECRET = process.env.CHALLENGE_TOKEN_SECRET || 'dev_secret_replace_in_prod';

// Adaptive verification delay schedule — Security_Requirements.md §6
// 1st: instant, 2nd: 2s, 3rd: 5s, 4th+: 30s
const ADAPTIVE_DELAYS_MS = [0, 0, 2000, 5000, 30000];

// ---------------------------------------------------------------------------
// POST /v1/auth/security-question/setup
// IT-AUTH-03
// ---------------------------------------------------------------------------

/**
 * Register or update security question and hashed answer.
 *
 * On first setup: no challengeToken required.
 * On updates (existing question): challengeToken required — Permissions_Matrix.md §3 C2.
 *
 * Side effects: updates users.security_question_id, security_answer_hash, encrypted_vmk.
 */
async function setupSecurityQuestion(req, res) {
    // Input validation
    const { error: validationError, value: body } = setupSchema.validate(req.body);
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
    const { securityQuestionId, securityAnswerHash, encryptedVmk, challengeToken } = body;

    const db = req.app.locals.db;
    const usersCollection = db.collection('users');

    // Check if user already has a security question configured
    const existingUser = await usersCollection.findOne({ id: uid });
    const isUpdate = existingUser && existingUser.securityQuestionId;

    // If updating, require a valid challenge token — Permissions_Matrix.md §3 C2
    if (isUpdate) {
        if (!challengeToken || !verifyChallengeToken(challengeToken, uid)) {
            return res.status(401).json({
                error: {
                    code: 'UNAUTHENTICATED',
                    message: 'Challenge verification token required to update security question.',
                    timestamp: Date.now()
                }
            });
        }
    }

    // Sanitize inputs — Security_Requirements.md §5
    const cleanQuestionId = String(securityQuestionId).replace(/[^a-zA-Z0-9_-]/g, '').substring(0, 64);

    // Update users collection — Database_Schema.md §2.1
    await usersCollection.updateOne(
        { id: uid },
        {
            $set: {
                securityQuestionId: cleanQuestionId,
                securityAnswerHash: securityAnswerHash,  // PBKDF2 hash from client
                encryptedVmk: encryptedVmk,
                updatedAt: Date.now()
            }
        },
        { upsert: false }
    );

    return res.status(200).json({
        status: 'success',
        message: 'Security question successfully configured.'
    });
}

// ---------------------------------------------------------------------------
// POST /v1/auth/security-question/verify
// IT-AUTH-04
// ---------------------------------------------------------------------------

/**
 * Validate security question answer and return a short-lived challenge token.
 *
 * The server receives the normalized plaintext answer from the client.
 * It re-derives the PBKDF2 key using the stored salt and compares.
 *
 * Security_Requirements.md §6 Brute Force:
 *   Adaptive delay: 1st=0, 2nd=0, 3rd=2s, 4th=5s, 5th+=30s before processing.
 *
 * Security_Requirements.md §6 Response Sanitisation:
 *   NEVER include securityAnswerHash in response.
 */
async function verifySecurityQuestion(req, res) {
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
    const { securityAnswer } = body;

    const db = req.app.locals.db;
    const usersCollection = db.collection('users');

    const user = await usersCollection.findOne({ id: uid });
    if (!user || !user.securityAnswerHash) {
        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'Security question not configured.',
                timestamp: Date.now()
            }
        });
    }

    // Adaptive delay based on failed attempt counter
    const failedCount = user.securityVerifyFailedAttempts || 0;
    const delayIndex = Math.min(failedCount, ADAPTIVE_DELAYS_MS.length - 1);
    await sleep(ADAPTIVE_DELAYS_MS[delayIndex]);

    // Re-derive and compare PBKDF2 hash — constant-time comparison
    const normalizedAnswer = securityAnswer.trim().toLowerCase();
    const isValid = await verifyPbkdf2Hash(normalizedAnswer, user.securityAnswerHash);

    if (!isValid) {
        // Increment failure counter
        await usersCollection.updateOne(
            { id: uid },
            { $inc: { securityVerifyFailedAttempts: 1 }, $set: { updatedAt: Date.now() } }
        );

        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'Incorrect security answer.',
                timestamp: Date.now()
            }
        });
    }

    // Reset failure counter on success
    await usersCollection.updateOne(
        { id: uid },
        { $set: { securityVerifyFailedAttempts: 0, updatedAt: Date.now() } }
    );

    // Issue short-lived challenge token — Permissions_Matrix.md §3 C2/C3
    const expiresAt = Date.now() + CHALLENGE_TOKEN_TTL_MS;
    const challengeToken = generateChallengeToken(uid, expiresAt);

    return res.status(200).json({
        challengeToken,
        expiresAt
    });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Verify a PBKDF2-SHA256 hash.
 * The stored hash format is: base64(salt):base64(derivedKey)
 * Matches the format produced by SecurityQuestionHasher.kt on the client.
 */
async function verifyPbkdf2Hash(normalizedAnswer, storedHash) {
    return new Promise((resolve) => {
        try {
            const [saltB64, keyB64] = storedHash.split(':');
            if (!saltB64 || !keyB64) return resolve(false);

            const salt = Buffer.from(saltB64, 'base64');
            const storedKey = Buffer.from(keyB64, 'base64');

            crypto.pbkdf2(normalizedAnswer, salt, 100000, 32, 'sha256', (err, derivedKey) => {
                if (err) return resolve(false);
                // Buffer lengths must match for timingSafeEqual, otherwise it throws RangeError
                if (derivedKey.length !== storedKey.length) {
                    return resolve(false);
                }
                // Constant-time comparison — prevents timing attacks
                resolve(crypto.timingSafeEqual(derivedKey, storedKey));
            });
        } catch {
            resolve(false);
        }
    });
}

/**
 * Generates a short-lived HMAC-SHA256 challenge token.
 * Format: base64(uid:expiresAt:hmac)
 */
function generateChallengeToken(uid, expiresAt) {
    const payload = `${uid}:${expiresAt}`;
    const hmac = crypto
        .createHmac('sha256', CHALLENGE_TOKEN_SECRET)
        .update(payload)
        .digest('hex');
    return Buffer.from(`${payload}:${hmac}`).toString('base64');
}

/**
 * Verifies a challenge token. Returns true if valid and not expired.
 */
function verifyChallengeToken(token, expectedUid) {
    try {
        const decoded = Buffer.from(token, 'base64').toString('utf8');
        const parts = decoded.split(':');
        if (parts.length !== 3) return false;

        const [uid, expiresAt, receivedHmac] = parts;
        if (uid !== expectedUid) return false;
        if (Date.now() > parseInt(expiresAt, 10)) return false;

        const payload = `${uid}:${expiresAt}`;
        const expectedHmac = crypto
            .createHmac('sha256', CHALLENGE_TOKEN_SECRET)
            .update(payload)
            .digest('hex');

        return crypto.timingSafeEqual(
            Buffer.from(receivedHmac, 'hex'),
            Buffer.from(expectedHmac, 'hex')
        );
    } catch {
        return false;
    }
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * POST /v1/auth/vmk — Retrieve encrypted VMK.
 *
 * Spec refs:
 *   - API_Spec.md §2 — POST /v1/auth/vmk
 *   - IT-AUTH-02 — VMK Retrieval test
 *   - Permissions_Matrix.md §3 C3 — VMK retrieval requires Google OAuth + verified security question
 *   - Security_Requirements.md §6 — "[MUST] Never return user VMKs in plaintext"
 *   - Security_Requirements.md §3 — uid-scoped queries
 *
 * The encrypted VMK was stored during security question setup.
 * This endpoint requires a valid challengeToken from security question verification.
 *
 * @param {import('express').Request} req
 * @param {import('express').Response} res
 */
async function retrieveVmk(req, res) {
    try {
        const vmkSchema = Joi.object({
            challengeToken: Joi.string().required()
        });

        const { error: validationError, value: body } = vmkSchema.validate(req.body);
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
        const { challengeToken } = body;

        // C3 — Verify challenge token from security question verification
        if (!verifyChallengeToken(challengeToken, uid)) {
            return res.status(401).json({
                error: {
                    code: 'UNAUTHENTICATED',
                    message: 'Invalid or expired challenge token.',
                    timestamp: Date.now()
                }
            });
        }

        const db = req.app.locals.db;
        const usersCollection = db.collection('users');

        // uid-scoped query — Security_Requirements.md §3
        const user = await usersCollection.findOne({ id: uid });
        if (!user || !user.encryptedVmk) {
            return res.status(404).json({
                error: {
                    code: 'NOT_FOUND',
                    message: 'VMK not found for this user.',
                    timestamp: Date.now()
                }
            });
        }

        // Return encrypted VMK — NEVER plaintext per Security_Requirements.md §6
        return res.status(200).json({
            encryptedVmk: user.encryptedVmk
        });
    } catch (err) {
        console.error('[securityController.retrieveVmk] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'VMK retrieval temporarily unavailable.',
                timestamp: Date.now()
            }
        });
    }
}

module.exports = { 
    setupSecurityQuestion, 
    verifySecurityQuestion,
    retrieveVmk,
    generateChallengeToken,
    verifyChallengeToken
};

