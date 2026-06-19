const functions = require("firebase-functions");
const admin = require("firebase-admin");
const express = require("express");
const cors = require("cors");

// Initialize Firebase Admin SDK
// On Vercel, use service account from env var; on Firebase, auto-credentials
if (process.env.FIREBASE_SERVICE_ACCOUNT) {
  const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
    projectId: serviceAccount.project_id
  });
} else {
  admin.initializeApp();
}

const app = express();

// Enable CORS
app.use(cors({ origin: true }));

// -------------------------------------------------------------------------
// MongoDB connection — lazy singleton
// -------------------------------------------------------------------------
const { MongoClient } = require('mongodb');
let cachedDb = null;

async function connectToMongo() {
  if (cachedDb) return cachedDb;
  const uri = process.env.MONGODB_URI;
  const dbName = process.env.MONGODB_DB_NAME || 'securevault';
  const client = new MongoClient(uri);
  await client.connect();
  cachedDb = client.db(dbName);
  return cachedDb;
}

// Attach db to every request
app.use(async (req, res, next) => {
  try {
    req.app.locals.db = await connectToMongo();
    next();
  } catch (err) {
    console.error('[MongoDB] Connection failed:', err.message);
    res.status(500).json({
      error: { code: 'INTERNAL', message: 'Database connection failed.', timestamp: Date.now() }
    });
  }
});

// Parse application/json
app.use(express.json());

// -------------------------------------------------------------------------
// Route registration
// API_Spec.md §2 — POST /v1/auth/login (no auth guard — initial registration)
// Security_Requirements.md §1 STRIDE Spoofing — JWT verification in authController
// -------------------------------------------------------------------------
const { login } = require('./controllers/authController');
const { setupSecurityQuestion, verifySecurityQuestion, retrieveVmk } = require('./controllers/securityController');
const { syncLockout } = require('./controllers/lockoutController');
const { verifyBackupCode, regenerateBackupCodes } = require('./controllers/backupController');
const { getCategories, createCategory, updateCategory, deleteCategory } = require('./controllers/categoryController');
const { getVault, createCredential, updateCredential, deleteCredential, emptyTrash } = require('./controllers/vaultController');
const { processSync } = require('./controllers/syncController');
const { getDevices, revokeDevice, registerDevice } = require('./controllers/deviceController');
const { verifyFirebaseToken } = require('./middleware/authMiddleware');

// POST /v1/auth/login — Google token verification & account registration
// Auth: None (API_Spec.md §2) — Rate limit: 5/min/IP (enforced by Firebase)
app.post('/v1/auth/login', login);

// POST /v1/auth/security-question/setup — register/update question + PBKDF2 hash
// Auth: JWT required (API_Spec.md §2)
app.post('/v1/auth/security-question/setup', verifyFirebaseToken, setupSecurityQuestion);

// POST /v1/auth/security-question/verify — validate answer, return challenge token
// Auth: JWT required (API_Spec.md §2)
app.post('/v1/auth/security-question/verify', verifyFirebaseToken, verifySecurityQuestion);

// POST /v1/auth/vmk — Retrieve encrypted VMK after security question verification
// Auth: JWT required (API_Spec.md §2) — Rate limit: 5/min/IP
// Permissions_Matrix.md §3 C3 — requires Google OAuth + verified security question
app.post('/v1/auth/vmk', verifyFirebaseToken, retrieveVmk);

// POST /v1/auth/lockout — Sync user PIN lockout states
// Auth: JWT required (API_Spec.md §2)
app.post('/v1/auth/lockout', verifyFirebaseToken, syncLockout);

// POST /v1/auth/backup-codes/verify — Verify a backup code to unlock local PIN reset
// Auth: JWT required (API_Spec.md §2)
app.post('/v1/auth/backup-codes/verify', verifyFirebaseToken, verifyBackupCode);

// POST /v1/auth/backup-codes/regenerate — Regenerate new backup codes
// Auth: JWT required (API_Spec.md §2)
app.post('/v1/auth/backup-codes/regenerate', verifyFirebaseToken, regenerateBackupCodes);

// GET /v1/categories — Retrieve all categories — API_Spec.md §3, IT-VAULT-06
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.get('/v1/categories', verifyFirebaseToken, getCategories);

// POST /v1/categories — Create custom category — API_Spec.md §3, IT-VAULT-07
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.post('/v1/categories', verifyFirebaseToken, createCategory);

// PUT /v1/categories/:id — Update custom category — API_Spec.md §3, IT-VAULT-08
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.put('/v1/categories/:id', verifyFirebaseToken, updateCategory);

// DELETE /v1/categories/:id — Delete custom category — API_Spec.md §3, IT-VAULT-09
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.delete('/v1/categories/:id', verifyFirebaseToken, deleteCategory);

// GET /v1/vault — Retrieve all credentials — API_Spec.md §3, IT-VAULT-01
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.get('/v1/vault', verifyFirebaseToken, getVault);

// POST /v1/vault — Create credential — API_Spec.md §3, IT-VAULT-02
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.post('/v1/vault', verifyFirebaseToken, createCredential);

// DELETE /v1/vault/trash/empty — Permanently purge all soft-deleted entries
// Auth: JWT required (API_Spec.md §3) — Rate limit: 30/min/user
// PRD F-VAULT-07 AC#5, IT-VAULT-05
// IMPORTANT: Must be registered BEFORE /v1/vault/:id to avoid route conflict
app.delete('/v1/vault/trash/empty', verifyFirebaseToken, emptyTrash);

// PUT /v1/vault/:id — Update credential — API_Spec.md §3, IT-VAULT-03
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.put('/v1/vault/:id', verifyFirebaseToken, updateCredential);

// DELETE /v1/vault/:id — Soft-delete credential — API_Spec.md §3, IT-VAULT-04
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.delete('/v1/vault/:id', verifyFirebaseToken, deleteCredential);

// POST /v1/sync — Process sync queue payloads — API_Spec.md §4, IT-SYNC-01
// Auth: JWT required — Roles: All — Rate limit: 60/min/user
// PRD F-SYNC-03 — Conflict resolution (version + timestamp)
app.post('/v1/sync', verifyFirebaseToken, processSync);

// GET /v1/devices — List active device sessions — API_Spec.md §4, IT-DEV-01
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
app.get('/v1/devices', verifyFirebaseToken, getDevices);

// DELETE /v1/devices/:id — Revoke device session — API_Spec.md §4, IT-DEV-02
// Auth: JWT required — Roles: All — Rate limit: 30/min/user
// PRD F-DEV-01 AC#3 — remote logout
app.delete('/v1/devices/:id', verifyFirebaseToken, revokeDevice);

// POST /v1/devices/register — Register device + 3-device limit check
// Auth: JWT required — PRD F-DEV-01 AC#1, SRS FR-DEV-01a
app.post('/v1/devices/register', verifyFirebaseToken, registerDevice);

// Basic health check route
app.get("/health", (req, res) => {
  res.status(200).json({ status: "healthy", timestamp: new Date().toISOString() });
});

// Export the Express App as a Firebase Cloud Function
exports.api = functions.region("asia-south1").https.onRequest(app);

// Also export raw Express app for Vercel deployment
module.exports = app;
