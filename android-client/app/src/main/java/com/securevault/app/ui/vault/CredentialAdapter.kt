package com.securevault.app.ui.vault

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.securevault.app.R
import com.securevault.app.data.entities.PasswordEntity

/**
 * RecyclerView adapter for the password dashboard list.
 *
 * Spec refs:
 *   - SCR-VLT-01 — Password Dashboard list entries
 *   - PRD F-VAULT-01 AC#2 — "Website Favicon (or letter fallback avatar),
 *     Website/Entry Name, Username/Email, and a Star (Favorite) indicator."
 *   - PRD F-VAULT-01 AC#3 — "Tapping a password entry card must navigate
 *     to the Password Details Screen."
 *   - SRS FR-VAULT-05b — "Starred favorites sorted to top" (handled by DAO query)
 *   - Design.md §2.1 — surface #1C1B1F, on-surface #E6E1E5, primary #D0BCFF
 *
 * Uses ListAdapter with DiffUtil for efficient list updates.
 * The onClick listener is set per-item to navigate to CredentialDetailsActivity.
 */
class CredentialAdapter(
    private val onItemClick: (PasswordEntity) -> Unit
) : ListAdapter<PasswordEntity, CredentialAdapter.CredentialViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PasswordEntity>() {
            override fun areItemsTheSame(oldItem: PasswordEntity, newItem: PasswordEntity): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: PasswordEntity, newItem: PasswordEntity): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CredentialViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_credential, parent, false)
        return CredentialViewHolder(view)
    }

    override fun onBindViewHolder(holder: CredentialViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CredentialViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvAvatar: TextView = itemView.findViewById(R.id.tv_avatar)
        private val tvName: TextView = itemView.findViewById(R.id.tv_credential_name)
        private val tvUsername: TextView = itemView.findViewById(R.id.tv_credential_username)
        private val ivFavoriteStar: ImageView = itemView.findViewById(R.id.iv_favorite_star)

        fun bind(credential: PasswordEntity) {
            // PRD F-VAULT-01 AC#2 — Website/Entry Name
            tvName.text = credential.name

            // PRD F-VAULT-01 AC#2 — Username/Email
            tvUsername.text = credential.usernameEmail

            // PRD F-VAULT-01 AC#2 — Letter fallback avatar
            // First letter of the credential name, uppercase
            val initial = credential.name.firstOrNull()?.uppercase() ?: "?"
            tvAvatar.text = initial

            // PRD F-VAULT-01 AC#2 — Star (Favorite) indicator
            ivFavoriteStar.visibility = if (credential.favorite == 1) {
                View.VISIBLE
            } else {
                View.GONE
            }

            // PRD F-VAULT-01 AC#3 — "Tapping a password entry card must navigate
            // to the Password Details Screen."
            itemView.setOnClickListener {
                onItemClick(credential)
            }
        }
    }
}
