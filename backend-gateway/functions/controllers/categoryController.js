'use strict';

const Joi = require('joi');

/**
 * Category Controller — CRUD for /v1/categories.
 *
 * Spec refs:
 *   - API_Spec.md §3 — GET/POST/PUT/DELETE /v1/categories
 *   - PRD F-VAULT-06 — Category System (3 acceptance criteria)
 *   - SRS FR-VAULT-06 — "client SHALL organize credentials using category tags"
 *   - SRS FR-VAULT-06b — "database SHALL preserve credentials if categories deleted"
 *   - Database_Schema.md §2.4 — categories table schema (id, user_id, name, created_at, updated_at)
 *   - Database_Schema.md §8.1 — Predefined seed categories
 *   - Database_Schema.md §4 — Audit trail: updated_at set server-side
 *   - Security_Requirements.md §3 — uid-scoped queries [MUST]
 *   - Security_Requirements.md §5 — Joi input validation [MUST]
 *   - API_Spec.md §5 — Global error envelope format
 *   - Testing_Strategy.md IT-VAULT-06 to IT-VAULT-09
 *   - Permissions_Matrix.md — All roles allowed, owner-scoped
 *
 * Default categories (Database_Schema.md §8.1):
 *   Personal, Work, Banking, Shopping, Social
 *   These are seeded on first GET if none exist for the user.
 */

// Default category names — Database_Schema.md §8.1
const DEFAULT_CATEGORIES = ['Personal', 'Work', 'Banking', 'Shopping', 'Social'];

// -------------------------------------------------------------------------
// Input validation schemas — Security_Requirements.md §5
// -------------------------------------------------------------------------

// POST /v1/categories — API_Spec.md §3
const createCategorySchema = Joi.object({
    id: Joi.string().trim().min(1).max(128).required(),
    name: Joi.string().trim().min(1).max(64).required()
});

// PUT /v1/categories/{id} — API_Spec.md §3
const updateCategorySchema = Joi.object({
    name: Joi.string().trim().min(1).max(64).required()
});

// -------------------------------------------------------------------------
// GET /v1/categories — IT-VAULT-06
// -------------------------------------------------------------------------

/**
 * Retrieve all categories for the authenticated user.
 *
 * Response schema (IT-VAULT-06):
 *   200 OK — Array of { id: string, name: string, isDefault: boolean }
 *
 * If no categories exist for the user, seeds the 5 defaults first
 * (Database_Schema.md §8.1).
 *
 * Security_Requirements.md §3 — queries scoped to req.user.uid.
 */
async function getCategories(req, res) {
    try {
        const db = req.app.locals.db;
        const uid = req.user.uid;
        const categoriesCollection = db.collection('categories');

        let categories = await categoriesCollection
            .find({ userId: uid })
            .sort({ name: 1 })
            .toArray();

        // Seed defaults if user has no categories — Database_Schema.md §8.1
        if (categories.length === 0) {
            const now = Date.now();
            const seedDocs = DEFAULT_CATEGORIES.map((name, idx) => ({
                _id: `cat_${name.toLowerCase()}_${uid}`,
                userId: uid,
                name: name,
                isDefault: true,
                createdAt: now,
                updatedAt: now
            }));

            await categoriesCollection.insertMany(seedDocs);
            categories = seedDocs;
        }

        // Map to response schema — IT-VAULT-06
        const response = categories.map(cat => ({
            id: cat._id || cat.id,
            name: cat.name,
            isDefault: cat.isDefault || false
        }));

        return res.status(200).json(response);
    } catch (err) {
        console.error('[categoryController.getCategories] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to retrieve categories.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// POST /v1/categories — IT-VAULT-07
// -------------------------------------------------------------------------

/**
 * Create a new custom category.
 *
 * Request body: { id: string, name: string } — API_Spec.md §3
 * Response: 201 Created — { id: string }
 *
 * Validates:
 *   - Joi schema (Security_Requirements.md §5)
 *   - Duplicate name check (SCR-VLT-04 Error state)
 */
async function createCategory(req, res) {
    // Joi validation — Security_Requirements.md §5
    const { error, value } = createCategorySchema.validate(req.body, { stripUnknown: true });
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
        const categoriesCollection = db.collection('categories');

        // Duplicate name check — SCR-VLT-04 Error state: "Category name already exists."
        const existing = await categoriesCollection.findOne({
            userId: uid,
            name: { $regex: new RegExp(`^${escapeRegex(value.name)}$`, 'i') }
        });

        if (existing) {
            return res.status(409).json({
                error: {
                    code: 'ALREADY_EXISTS',
                    message: 'Category name already exists.',
                    timestamp: Date.now()
                }
            });
        }

        const now = Date.now();
        const categoryDoc = {
            _id: value.id,
            userId: uid,
            name: value.name,
            isDefault: false,
            createdAt: now,
            updatedAt: now
        };

        await categoriesCollection.insertOne(categoryDoc);

        // IT-VAULT-07 response schema: { id: "cat_uuid_string" }
        return res.status(201).json({ id: value.id });
    } catch (err) {
        console.error('[categoryController.createCategory] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to create category.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// PUT /v1/categories/{id} — IT-VAULT-08
// -------------------------------------------------------------------------

/**
 * Update a custom category's name.
 *
 * Path param: id — category ID
 * Request body: { name: string } — API_Spec.md §3
 * Response: 200 OK
 *
 * Edge case: Cannot rename default categories.
 * Database_Schema.md §4 — updates updated_at server-side.
 */
async function updateCategory(req, res) {
    const categoryId = req.params.id;

    if (!categoryId || categoryId.trim().length === 0) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: 'Category ID is required.',
                timestamp: Date.now()
            }
        });
    }

    const { error, value } = updateCategorySchema.validate(req.body, { stripUnknown: true });
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
        const categoriesCollection = db.collection('categories');

        // Verify ownership — Security_Requirements.md §3
        const category = await categoriesCollection.findOne({ _id: categoryId, userId: uid });
        if (!category) {
            return res.status(404).json({
                error: {
                    code: 'NOT_FOUND',
                    message: 'Category not found.',
                    timestamp: Date.now()
                }
            });
        }

        // Edge case: Cannot rename default categories — task-010 notes
        if (category.isDefault) {
            return res.status(403).json({
                error: {
                    code: 'PERMISSION_DENIED',
                    message: 'Default categories cannot be renamed.',
                    timestamp: Date.now()
                }
            });
        }

        // Duplicate name check
        const duplicate = await categoriesCollection.findOne({
            userId: uid,
            name: { $regex: new RegExp(`^${escapeRegex(value.name)}$`, 'i') },
            _id: { $ne: categoryId }
        });

        if (duplicate) {
            return res.status(409).json({
                error: {
                    code: 'ALREADY_EXISTS',
                    message: 'Category name already exists.',
                    timestamp: Date.now()
                }
            });
        }

        // Database_Schema.md §4 — update server-side timestamp
        await categoriesCollection.updateOne(
            { _id: categoryId, userId: uid },
            { $set: { name: value.name, updatedAt: Date.now() } }
        );

        return res.status(200).json({ message: 'Category updated.' });
    } catch (err) {
        console.error('[categoryController.updateCategory] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to update category.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// DELETE /v1/categories/{id} — IT-VAULT-09
// -------------------------------------------------------------------------

/**
 * Delete a custom category.
 *
 * Path param: id — category ID
 * Response: 200 OK
 *
 * Side effects (API_Spec.md §3, PRD F-VAULT-06 AC#3):
 *   - Deletes category record
 *   - Resets associated passwords' category_id to null
 *   - Does NOT delete passwords (SRS FR-VAULT-06b)
 *
 * Edge case: Cannot delete default categories — task-010 notes.
 */
async function deleteCategory(req, res) {
    const categoryId = req.params.id;

    if (!categoryId || categoryId.trim().length === 0) {
        return res.status(400).json({
            error: {
                code: 'INVALID_ARGUMENT',
                message: 'Category ID is required.',
                timestamp: Date.now()
            }
        });
    }

    try {
        const db = req.app.locals.db;
        const uid = req.user.uid;
        const categoriesCollection = db.collection('categories');
        const passwordsCollection = db.collection('vault_passwords');

        // Verify ownership — Security_Requirements.md §3
        const category = await categoriesCollection.findOne({ _id: categoryId, userId: uid });
        if (!category) {
            return res.status(404).json({
                error: {
                    code: 'NOT_FOUND',
                    message: 'Category not found.',
                    timestamp: Date.now()
                }
            });
        }

        // Edge case: Cannot delete default categories — task-010 notes
        if (category.isDefault) {
            return res.status(403).json({
                error: {
                    code: 'PERMISSION_DENIED',
                    message: 'Default categories cannot be deleted.',
                    timestamp: Date.now()
                }
            });
        }

        // PRD F-VAULT-06 AC#3 — Reset passwords to uncategorized BEFORE deleting
        // SRS FR-VAULT-06 — "database SHALL preserve credentials if categories deleted"
        // IT-VAULT-09 — "resets assigned passwords' category_id value to null"
        await passwordsCollection.updateMany(
            { categoryId: categoryId, userId: uid },
            { $set: { categoryId: null, updatedAt: Date.now() } }
        );

        // Hard delete — Database_Schema.md §3
        await categoriesCollection.deleteOne({ _id: categoryId, userId: uid });

        return res.status(200).json({ message: 'Category deleted.' });
    } catch (err) {
        console.error('[categoryController.deleteCategory] Error:', err.message);
        return res.status(503).json({
            error: {
                code: 'SERVICE_UNAVAILABLE',
                message: 'Failed to delete category.',
                timestamp: Date.now()
            }
        });
    }
}

// -------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------

/**
 * Escapes special regex characters in a string for safe use in RegExp constructor.
 */
function escapeRegex(str) {
    return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

module.exports = {
    getCategories,
    createCategory,
    updateCategory,
    deleteCategory
};
