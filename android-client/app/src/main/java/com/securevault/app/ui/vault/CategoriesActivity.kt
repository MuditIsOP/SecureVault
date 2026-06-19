package com.securevault.app.ui.vault

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.securevault.app.R
import com.securevault.app.data.dao.CategoryDao
import com.securevault.app.data.entities.CategoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * SCR-VLT-04 — Categories Management Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-VLT-04 — CRUD administration of organization categories
 *   - PRD F-VAULT-06 AC#1 — Bottom Nav Tab 2 displays categories grouped by
 *     Personal, Work, Banking, Shopping, Social
 *   - PRD F-VAULT-06 AC#2 — create, edit, or delete custom categories
 *   - PRD F-VAULT-06 AC#3 — deleting category sets passwords to uncategorized
 *   - SRS FR-VAULT-06 — organize credentials using category tags
 *   - Design.md §2.1 — background #141218, surface #1C1B1F, secondary #CCC2DC
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE
 *
 * Entry: Tab 2 in bottom navigation on SCR-VLT-01.
 * Exit: SCR-VLT-01 (Dashboard).
 *
 * State variations (Screens.md SCR-VLT-04):
 *   Loading — Spinner
 *   Empty — Shows default categories
 *   Error — "Category name already exists."
 */
class CategoriesActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var etCategoryName: TextInputEditText
    private lateinit var btnAddCategory: MaterialButton
    private lateinit var tvErrorMessage: TextView
    private lateinit var progressLoading: ProgressBar
    private lateinit var tvEmptyState: TextView

    private val categories = mutableListOf<CategoryDisplayItem>()
    private lateinit var adapter: CategoryAdapter

    /**
     * Display model wrapping CategoryEntity with password count.
     * SCR-VLT-04 content: "Custom list with items count display."
     */
    data class CategoryDisplayItem(
        val entity: CategoryEntity,
        val passwordCount: Int,
        val isDefault: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_categories)

        bindViews()
        setupRecyclerView()
        setupAddButton()
        loadCategories()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        rvCategories = findViewById(R.id.rv_categories)
        etCategoryName = findViewById(R.id.et_category_name)
        btnAddCategory = findViewById(R.id.btn_add_category)
        tvErrorMessage = findViewById(R.id.tv_error_message)
        progressLoading = findViewById(R.id.progress_loading)
        tvEmptyState = findViewById(R.id.tv_empty_state)
    }

    // -------------------------------------------------------------------------
    // RecyclerView setup
    // -------------------------------------------------------------------------

    private fun setupRecyclerView() {
        adapter = CategoryAdapter(
            categories = categories,
            onDeleteClick = { item -> confirmDeleteCategory(item) }
        )
        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = adapter
    }

    // -------------------------------------------------------------------------
    // Load categories — SCR-VLT-04 content inventory
    // -------------------------------------------------------------------------

    private fun loadCategories() {
        setLoadingState(true)
        tvErrorMessage.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val dao = getCategoryDao()
                val userId = getCurrentUserId()

                var dbCategories = withContext(Dispatchers.IO) {
                    dao.getCategoriesByUser(userId)
                }

                // Seed defaults if no categories exist — Database_Schema.md §8.1
                if (dbCategories.isEmpty()) {
                    val defaults = CategoryDao.createDefaultCategories(userId)
                    withContext(Dispatchers.IO) {
                        dao.insertAll(defaults)
                    }
                    dbCategories = withContext(Dispatchers.IO) {
                        dao.getCategoriesByUser(userId)
                    }
                }

                // Build display items with password counts
                val displayItems = dbCategories.map { entity ->
                    val count = withContext(Dispatchers.IO) {
                        dao.getPasswordCountForCategory(entity.id)
                    }
                    val isDefault = CategoryDao.DEFAULT_CATEGORY_NAMES.contains(entity.name)
                    CategoryDisplayItem(entity, count, isDefault)
                }

                categories.clear()
                categories.addAll(displayItems)
                adapter.notifyDataSetChanged()

                // Empty state — SCR-VLT-04
                tvEmptyState.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE

            } catch (e: Exception) {
                Toast.makeText(this@CategoriesActivity,
                    "Failed to load categories.", Toast.LENGTH_SHORT).show()
            } finally {
                setLoadingState(false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Add category — PRD F-VAULT-06 AC#2, SCR-VLT-04 "Create Category"
    // -------------------------------------------------------------------------

    private fun setupAddButton() {
        btnAddCategory.setOnClickListener {
            val name = etCategoryName.text?.toString()?.trim() ?: ""

            if (name.isEmpty()) {
                showError("Category name cannot be empty.")
                return@setOnClickListener
            }

            if (name.length > 64) {
                showError("Category name must be 64 characters or fewer.")
                return@setOnClickListener
            }

            tvErrorMessage.visibility = View.GONE

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val dao = getCategoryDao()
                    val userId = getCurrentUserId()

                    // Duplicate check — SCR-VLT-04 Error: "Category name already exists."
                    val duplicateCount = withContext(Dispatchers.IO) {
                        dao.countByUserAndName(userId, name)
                    }

                    if (duplicateCount > 0) {
                        showError("Category name already exists.")
                        return@launch
                    }

                    val newCategory = CategoryEntity(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        name = name
                    )

                    withContext(Dispatchers.IO) {
                        dao.insert(newCategory)
                    }

                    etCategoryName.text?.clear()
                    loadCategories()

                    Toast.makeText(this@CategoriesActivity,
                        "Category created.", Toast.LENGTH_SHORT).show()

                } catch (e: Exception) {
                    showError("Failed to create category.")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Delete category — PRD F-VAULT-06 AC#2, AC#3
    // -------------------------------------------------------------------------

    /**
     * Confirms deletion with a dialog, then executes.
     * Edge case: Cannot delete default categories (Personal, Work, etc.)
     */
    private fun confirmDeleteCategory(item: CategoryDisplayItem) {
        // Edge case: block default category deletion — task-010 notes
        if (item.isDefault) {
            Toast.makeText(this, "Default categories cannot be deleted.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Category")
            .setMessage(
                "Delete \"${item.entity.name}\"?\n\n" +
                "Passwords in this category will be set to uncategorized."
            )
            .setPositiveButton("Delete") { _, _ -> executeDeleteCategory(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Executes category deletion.
     * PRD F-VAULT-06 AC#3 — resets passwords to uncategorized, then deletes category.
     * SRS FR-VAULT-06 — "database SHALL preserve credentials if categories deleted"
     */
    private fun executeDeleteCategory(item: CategoryDisplayItem) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val dao = getCategoryDao()

                withContext(Dispatchers.IO) {
                    // PRD F-VAULT-06 AC#3 — set passwords to uncategorized FIRST
                    dao.resetPasswordsForCategory(item.entity.id)
                    // Then hard delete — Database_Schema.md §3
                    dao.deleteById(item.entity.id)
                }

                loadCategories()
                Toast.makeText(this@CategoriesActivity,
                    "Category deleted.", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(this@CategoriesActivity,
                    "Failed to delete category.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun setLoadingState(loading: Boolean) {
        progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        btnAddCategory.isEnabled = !loading
    }

    private fun showError(message: String) {
        tvErrorMessage.text = message
        tvErrorMessage.visibility = View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Data helpers — MVVM DAO access
    // -------------------------------------------------------------------------

    private fun getCategoryDao(): CategoryDao {
        val db = com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext)
        return db.categoryDao()
    }

    /**
     * Retrieves current user ID from local database.
     * Returns empty string if not found (should not happen after auth).
     */
    private suspend fun getCurrentUserId(): String {
        return withContext(Dispatchers.IO) {
            val db = com.securevault.app.data.DatabaseModule.provideDatabase(applicationContext)
            val cursor = db.openHelper.readableDatabase
                .rawQuery("SELECT id FROM users LIMIT 1", null)
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
        }
    }

    // -------------------------------------------------------------------------
    // RecyclerView Adapter — SCR-VLT-04 category folder list
    // -------------------------------------------------------------------------

    inner class CategoryAdapter(
        private val categories: List<CategoryDisplayItem>,
        private val onDeleteClick: (CategoryDisplayItem) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tv_category_name)
            val tvCount: TextView = itemView.findViewById(R.id.tv_password_count)
            val tvDefaultBadge: TextView = itemView.findViewById(R.id.tv_default_badge)
            val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_category)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = categories[position]
            holder.tvName.text = item.entity.name
            holder.tvCount.text = "${item.passwordCount} passwords"

            if (item.isDefault) {
                holder.tvDefaultBadge.visibility = View.VISIBLE
                holder.btnDelete.visibility = View.GONE
            } else {
                holder.tvDefaultBadge.visibility = View.GONE
                holder.btnDelete.visibility = View.VISIBLE
            }

            holder.btnDelete.setOnClickListener { onDeleteClick(item) }
        }

        override fun getItemCount() = categories.size
    }
}
