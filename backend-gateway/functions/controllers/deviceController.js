'use strict';

const Joi = require('joi');

/**
 * Device Controller — GET/DELETE /v1/devices.
 *
 * Spec refs:
 *   - API_Spec.md §4 — GET /v1/devices, DELETE /v1/devices/{id}
 *   - PRD F-DEV-01 AC#1 — "backend restricts active sessions to a maximum
 *     of 3 concurrent device entries per user"
 *   - PRD F-DEV-01 AC#2 — "displays Active Devices Screen listing:
 *     Device Name, Android Version, Last Active Time, Current Device"
 *   - PRD F-DEV-01 AC#3 — "tapping Remove logs out the selected device
 *     and allows login to proceed on the 4th device"
 *   - SRS FR-DEV-01a — "backend gateway SHALL limit user concurrent
 *     devices to a maximum of 3"
 *   - SRS FR-DEV-01b — "client SHALL display active device lists and
 *     support remote logout actions"
 *   - Database_Schema.md §2.5 — device_sessions columns
 *   - Database_Schema.md §3 — hard deletion for device_sessions
 *   - Security_Requirements.md §3 — uid-scoped queries [MUST]
 *   - Security_Requirements.md §5 — Joi input validation [MUST]
 *   - API_Spec.md §5 — Global error envelope format
 *   - Testing_Strategy.md IT-DEV-01, IT-DEV-02
 */

// -------------------------------------------------------------------------
// Constants — PRD F-DEV-01 AC#1
// -------------------------------------------------------------------------

/** Maximum concurrent device sessions per user — PRD F-DEV-01 AC#1 */
const MAX_DEVICES = 3;

// -------------------------------------------------------------------------
// GET /v1/devices — API_Spec.md §4
// PRD F-DEV-01 AC#2 — list active devices
// -------------------------------------------------------------------------

/**
 * Retrieves active device sessions for the authenticated user.
 *
 * API_Spec.md §4 — GET /v1/devices
 * Response: 200 OK — array of { id, deviceName, androidVersion, lastActiveTime }
 *
 * @param {object} req - Express request
 * @param {object} res - Express response
 */
async function getDevices(req, res) {
    try {
        // Security_Requirements.md §3 — uid-scoped query
        const uid = req.user.uid;
        const db = req.app.locals.db;
        const collection = db.collection('device_sessions');

        // Fetch all sessions for this user
        const sessions = await collection
            .find({ userId: uid })
            .sort({ last_active_time: -1 })
            .toArray();

        // API_Spec.md §4 — response schema mapping
        const response = sessions.map(session => ({
            id: session._id,
            deviceName: session.device_name,
            androidVersion: session.android_version,
            lastActiveTime: session.last_active_time
        }));

        return res.status(200).json(response);

    } catch (err) {
        console.error('[deviceController.getDevices] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Could not load device list.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// DELETE /v1/devices/{id} — API_Spec.md §4
// PRD F-DEV-01 AC#3 — revoke session
// Database_Schema.md §3 — hard deletion for device_sessions
// -------------------------------------------------------------------------

/**
 * Revokes (deletes) a device session.
 *
 * API_Spec.md §4 — DELETE /v1/devices/{id}
 * PRD F-DEV-01 AC#3 — "logs out the selected device"
 * Database_Schema.md §3 — hard deletion
 *
 * @param {object} req - Express request with params.id
 * @param {object} res - Express response
 */
async function revokeDevice(req, res) {
    try {
        const deviceId = req.params.id;

        if (!deviceId || deviceId.trim().length === 0) {
            return res.status(400).json({
                error: {
                    code: 'INVALID_ARGUMENT',
                    message: 'Device ID is required.',
                    timestamp: Date.now()
                }
            });
        }

        // Security_Requirements.md §3 — uid-scoped query
        const uid = req.user.uid;
        const db = req.app.locals.db;
        const collection = db.collection('device_sessions');

        // Verify ownership before deletion
        const session = await collection.findOne({
            _id: deviceId,
            userId: uid
        });

        if (!session) {
            return res.status(404).json({
                error: {
                    code: 'NOT_FOUND',
                    message: 'Device session not found.',
                    timestamp: Date.now()
                }
            });
        }

        // Hard delete — Database_Schema.md §3
        await collection.deleteOne({
            _id: deviceId,
            userId: uid
        });

        // API_Spec.md §4 — 200 OK
        return res.status(200).json({
            message: 'Device session revoked.'
        });

    } catch (err) {
        console.error('[deviceController.revokeDevice] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Could not revoke device session.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// Register device — called during login flow
// PRD F-DEV-01 AC#1 — 3-device limit check
// SRS FR-DEV-01a — "limit concurrent devices to maximum of 3"
// -------------------------------------------------------------------------

/**
 * Registers a new device session during login.
 * Returns { allowed: true } if under limit, or { allowed: false, devices }
 * if at/over 3-device limit.
 *
 * PRD F-DEV-01 AC#1 — "restricts active sessions to max 3"
 * SRS FR-DEV-01a — "limit concurrent devices to maximum of 3"
 *
 * @param {object} req - Express request with body { deviceId, deviceName, androidVersion }
 * @param {object} res - Express response
 */
async function registerDevice(req, res) {
    try {
        const registerSchema = Joi.object({
            deviceId: Joi.string().required(),
            deviceName: Joi.string().min(1).required(),
            androidVersion: Joi.string().min(1).required()
        });

        const { error, value } = registerSchema.validate(req.body);
        if (error) {
            return res.status(400).json({
                error: {
                    code: 'INVALID_ARGUMENT',
                    message: error.details[0].message,
                    timestamp: Date.now()
                }
            });
        }

        const { deviceId, deviceName, androidVersion } = value;
        const uid = req.user.uid;
        const db = req.app.locals.db;
        const collection = db.collection('device_sessions');

        // Check if this device already has a session (re-login)
        const existingSession = await collection.findOne({
            _id: deviceId,
            userId: uid
        });

        if (existingSession) {
            // Update last_active_time for existing session
            await collection.updateOne(
                { _id: deviceId, userId: uid },
                { $set: { last_active_time: Date.now() } }
            );
            return res.status(200).json({ allowed: true });
        }

        // Count active sessions — PRD F-DEV-01 AC#1
        const activeCount = await collection.countDocuments({ userId: uid });

        if (activeCount >= MAX_DEVICES) {
            // At limit — return device list for SCR-DEV-01
            // PRD F-DEV-01 AC#2 — "displays Active Devices Screen"
            const sessions = await collection
                .find({ userId: uid })
                .sort({ last_active_time: -1 })
                .toArray();

            const devices = sessions.map(s => ({
                id: s._id,
                deviceName: s.device_name,
                androidVersion: s.android_version,
                lastActiveTime: s.last_active_time
            }));

            return res.status(403).json({
                error: {
                    code: 'SESSION_LIMIT_EXCEEDED',
                    message: 'Maximum of 3 concurrent devices reached.',
                    timestamp: Date.now()
                },
                devices
            });
        }

        // Under limit — register new session
        await collection.insertOne({
            _id: deviceId,
            userId: uid,
            device_name: deviceName,
            android_version: androidVersion,
            last_active_time: Date.now(),
            created_at: Date.now()
        });

        return res.status(201).json({ allowed: true });

    } catch (err) {
        console.error('[deviceController.registerDevice] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Could not register device.',
                timestamp: Date.now()
            }
        });
    }
}

module.exports = { getDevices, revokeDevice, registerDevice, MAX_DEVICES };
