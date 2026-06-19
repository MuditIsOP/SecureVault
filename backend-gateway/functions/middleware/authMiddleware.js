'use strict';

const admin = require('firebase-admin');

/**
 * Firebase JWT Authentication Middleware.
 *
 * Spec refs:
 *   - Security_Requirements.md §6 API Security — JWT signature verification [MUST]
 *   - Security_Requirements.md §9.3 Firebase JWT Protection:
 *       [MUST] "Validate JWT signature keys using Google public certificates"
 *       [MUST] "Enforce JWT `nbf` and `exp` validation to reject replayed tokens"
 *   - Security_Requirements.md §3 Authorisation — Zero Client-Side Trust:
 *       [MUST] "Every MongoDB query restricts actions using Firebase JWT uid claim"
 *   - API_Spec.md §5 Global Error Envelope — UNAUTHENTICATED (401)
 *   - Testing_Strategy.md ST-STRIDE-05 — reject requests without valid JWT
 *
 * This middleware:
 *   1. Extracts the Bearer token from the Authorization header.
 *   2. Verifies the Firebase ID Token signature against Google public certificates.
 *   3. Validates nbf/exp claims (Firebase Admin SDK enforces this automatically).
 *   4. Attaches the decoded token (uid, email) to req.user for controller use.
 *   5. Returns 401 if any check fails — never leaks which check failed.
 */
async function verifyFirebaseToken(req, res, next) {
    const authHeader = req.headers['authorization'];

    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'Authorization header missing or malformed.',
                timestamp: Date.now()
            }
        });
    }

    const idToken = authHeader.split('Bearer ')[1];

    try {
        // Firebase Admin SDK verifies:
        //   1. JWT signature against Google public certificates
        //   2. exp claim (token not expired)
        //   3. nbf claim (token not used before valid time)
        //   4. aud claim (token belongs to this Firebase project)
        //   5. iss claim (issued by accounts.google.com or securetoken.google.com)
        // Security_Requirements.md §9.3 — all checks handled by verifyIdToken()
        const decodedToken = await admin.auth().verifyIdToken(idToken);

        // Attach verified uid and email to request — Security_Requirements.md §3
        req.user = {
            uid: decodedToken.uid,
            email: decodedToken.email || null
        };

        next();
    } catch (error) {
        // Do NOT leak specific error details — return generic 401
        // Security_Requirements.md §6 Response Sanitisation
        console.error('[authMiddleware] Token verification failed:', error.code, error.message);
        console.error('[authMiddleware] Token prefix:', idToken ? idToken.substring(0, 20) + '...' : 'EMPTY');
        return res.status(401).json({
            error: {
                code: 'UNAUTHENTICATED',
                message: 'Invalid or expired authentication token.',
                timestamp: Date.now()
            }
        });
    }
}

module.exports = { verifyFirebaseToken };
