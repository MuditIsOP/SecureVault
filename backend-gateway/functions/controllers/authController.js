'use strict';

const admin = require('firebase-admin');
const Joi = require('joi');

/**
 * Authentication controller — POST /v1/auth/login.
 *
 * Spec refs:
 *   - API_Spec.md §2 POST /v1/auth/login — full request/response spec
 *   - SRS FR-AUTH-01 — "backend gateway SHALL verify Google OAuth JWT signatures
 *     before issuing session custom tokens"
 *   - Security_Requirements.md §1 STRIDE Spoofing (ST-GATEWAY-SPOOF-01):
 *       [MUST] "Enforce verification of Firebase JWT signatures on every request"
 *   - Security_Requirements.md §5 Input Validation — Joi schema validation [MUST]
 *   - Security_Requirements.md §3 Authorisation — Usecase Binding (uid scoping)
 *   - Database_Schema.md §2.1 users, §2.5 device_sessions — tables written here
 *   - Testing_Strategy.md IT-AUTH-01 — integration test for this endpoint
 *   - API_Spec.md §5 — 400/401/403 error codes, global error envelope
 *   - PRD F-DEV-01 — 3-device concurrent session limit enforcement
 *
 * Device limit: 3 concurrent sessions per user — PRD F-DEV-01, SRS FR-DEV-01.
 * If a 4th device attempts login, returns 403 SESSION_LIMIT_EXCEEDED.
 *
 * Side effects:
 *   - Inserts user record into `users` collection (first time only)
 *   - Inserts device session record into `device_sessions` collection
 */

// Input validation schema — API_Spec.md §2, Security_Requirements.md §5
const loginSchema = Joi.object({
    googleIdToken: Joi.string().required(),
    deviceId: Joi.string().min(1).required(),
    deviceName: Joi.string().min(1).max(128).required(),
    androidVersion: Joi.string().min(1).max(32).required()
});

const MAX_DEVICES = 3;  // PRD F-DEV-01 — 3-device limit

/**
 * POST /v1/auth/login
 *
 * Flow (Architecture.md §4 Data Flow 1):
 *   1. Validate request body with Joi
 *   2. Verify Google ID Token via Firebase Admin SDK
 *   3. Check device session limit
 *   4. Upsert user record in MongoDB
 *   5. Insert device session record
 *   6. Return Firebase custom token + registered flag
 */
async function login(req, res) {
    // Step 1: Input validation — Security_Requirements.md §5
    const { error: validationError, value: body } = loginSchema.validate(req.body);
    if (validationError) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: validationError.details[0].message,
                timestamp: Date.now()
            }
        });
    }

    const { googleIdToken, deviceId, deviceName, androidVersion } = body;

    let decodedToken;
    try {
        // Step 2: Verify Google ID Token — SRS FR-AUTH-01, ST-GATEWAY-SPOOF-01
        // Firebase Admin verifyIdToken() checks signature, exp, nbf, aud, iss
        decodedToken = await admin.auth().verifyIdToken(googleIdToken);
    } catch (err) {
        console.error('[authController.login] Token verification failed:', err.message);
        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'Google ID token verification failed.',
                timestamp: Date.now()
            }
        });
    }

    const uid = decodedToken.uid;
    const email = decodedToken.email || '';

    const db = req.app.locals.db;
    const usersCollection = db.collection('users');
    const sessionsCollection = db.collection('device_sessions');

    // Step 3: Device session limit check — PRD F-DEV-01, SRS FR-DEV-01
    const activeSessionCount = await sessionsCollection.countDocuments({ userId: uid });
    const existingSession = await sessionsCollection.findOne({ id: deviceId, userId: uid });

    if (activeSessionCount >= MAX_DEVICES && !existingSession) {
        return res.status(403).json({
            error: {
                code: 'SESSION_LIMIT_EXCEEDED',
                message: `Maximum of ${MAX_DEVICES} active devices allowed. Please remove a device.`,
                timestamp: Date.now()
            }
        });
    }

    // Step 4: Upsert user — inserts on first login, no-op on subsequent logins
    const now = Date.now();
    const userExists = await usersCollection.findOne({ id: uid });

    if (!userExists) {
        // New user registration
        await usersCollection.insertOne({
            id: uid,
            googleEmail: email,
            securityQuestionId: '',         // Populated in task-005
            securityAnswerHash: '',
            backupCodeHashes: '',
            encryptedVmk: '',               // Populated by KMS in task-005
            pinHash: '',                     // Populated in task-006
            pinFailedAttempts: 0,
            pinLockoutUntil: null,
            createdAt: now,
            updatedAt: now
        });
    }

    // Step 5: Upsert device session — Database_Schema.md §2.5
    await sessionsCollection.updateOne(
        { id: deviceId, userId: uid },
        {
            $set: {
                id: deviceId,
                userId: uid,
                deviceName: sanitizeString(deviceName),  // Input sanitization §5
                androidVersion: sanitizeString(androidVersion),
                lastActiveTime: now,
                createdAt: now
            }
        },
        { upsert: true }
    );

    // Step 6: Create Firebase custom token for client session
    // SRS FR-AUTH-01 — "backend SHALL issue session custom tokens"
    const customToken = await admin.auth().createCustomToken(uid);

    // User is "registered" only if they completed onboarding (security question + PIN)
    const registered = !!(userExists && userExists.securityQuestionId);
    const statusCode = registered ? 200 : 201;

    return res.status(statusCode).json({
        firebaseToken: customToken,
        refreshToken: generateRefreshToken(),   // Opaque refresh token
        registered,
        pinFailedAttempts: userExists ? (userExists.pinFailedAttempts || 0) : 0,
        pinLockoutUntil: userExists ? (userExists.pinLockoutUntil || null) : null
    });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Sanitises string inputs per Security_Requirements.md §5:
 * "Sanitise using regex ^[a-zA-Z0-9\s.\-\_]+$"
 */
function sanitizeString(input) {
    return String(input).replace(/[^a-zA-Z0-9\s.\-_]/g, '').substring(0, 128);
}

/**
 * Generates an opaque refresh token.
 * In production this should be a cryptographically random UUID stored server-side.
 * Security_Requirements.md §2.1 — Refresh Token validity 30 days.
 */
function generateRefreshToken() {
    const { randomUUID } = require('crypto');
    return randomUUID();
}

module.exports = { login };
