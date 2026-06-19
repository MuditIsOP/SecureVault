package com.securevault.app.ui.vault

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
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

        /**
         * Extracts a clean domain from a URL for favicon fetching.
         * "https://www.instagram.com/login" -> "instagram.com"
         */
        fun extractDomain(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                var domain = url.trim()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("www.")
                    .split("/")[0]
                    .split("?")[0]
                    .lowercase()
                if (domain.contains(".")) domain else null
            } catch (e: Exception) {
                null
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
        private val ivFavicon: ImageView = itemView.findViewById(R.id.iv_favicon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_credential_name)
        private val tvUsername: TextView = itemView.findViewById(R.id.tv_credential_username)
        private val ivFavoriteStar: ImageView = itemView.findViewById(R.id.iv_favorite_star)

        fun bind(credential: PasswordEntity) {
            // Entry name
            tvName.text = credential.name

            // Username/Email
            tvUsername.text = credential.usernameEmail

            // Letter fallback avatar — first letter uppercase
            val initial = credential.name.firstOrNull()?.uppercase() ?: "?"
            tvAvatar.text = initial

            // Favicon — try to load from Google's favicon API
            val domain = extractDomain(credential.websiteUrl)
            if (domain != null) {
                ivFavicon.visibility = View.VISIBLE
                ivFavicon.load("https://www.google.com/s2/favicons?domain=$domain&sz=128") {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    listener(
                        onSuccess = { _, _ -> tvAvatar.visibility = View.INVISIBLE },
                        onError = { _, _ ->
                            ivFavicon.visibility = View.GONE
                            tvAvatar.visibility = View.VISIBLE
                        }
                    )
                }
            } else {
                ivFavicon.visibility = View.GONE
                tvAvatar.visibility = View.VISIBLE
            }

            // Favorite star
            ivFavoriteStar.visibility = if (credential.favorite == 1) {
                View.VISIBLE
            } else {
                View.GONE
            }

            // Navigate to details on tap
            itemView.setOnClickListener {
                onItemClick(credential)
            }
        }
    }
}
