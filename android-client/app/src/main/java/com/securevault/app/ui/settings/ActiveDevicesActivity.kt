package com.securevault.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securevault.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Active Devices Screen — SCR-DEV-01.
 *
 * Spec refs:
 *   - PRD F-DEV-01 AC#1 — "backend restricts active sessions to a maximum
 *     of 3 concurrent device entries per user"
 *   - PRD F-DEV-01 AC#2 — "displays Active Devices Screen listing:
 *     Device Name, Android Version, Last Active Time, Current Device indicator"
 *   - PRD F-DEV-01 AC#3 — "tapping Remove logs out the selected device
 *     and allows login to proceed on the 4th device"
 *   - SRS FR-DEV-01b — "client SHALL display active device lists and
 *     support remote logout actions"
 *   - Screens.md SCR-DEV-01 — Active Devices Screen spec
 *   - API_Spec.md §4 — GET /v1/devices, DELETE /v1/devices/{id}
 *   - Design.md — background #141218, error #F2B8B5
 *   - Security_Requirements.md §1 — FLAG_SECURE
 *
 * State variations (Screens.md SCR-DEV-01):
 *   Loading — ProgressBar spinner
 *   Empty — "No active sessions found." (impossible state per spec)
 *   Error — "Could not load device list. Pull to refresh."
 *
 * Entry points:
 *   - Settings navigation
 *   - Login redirection when session limit exceeded (forced modal)
 */
class ActiveDevicesActivity : AppCompatActivity() {

    companion object {
        /**
         * If true, this screen was forced during login because session limit
         * was exceeded. The user must remove a device before proceeding.
         * Architecture.md Data Flow: login → check count → count > 3 → route to list
         */
        const val EXTRA_FORCED_MODE = "forced_mode"

        /** Current device's ANDROID_ID — used to show "Current Device" badge */
        const val EXTRA_CURRENT_DEVICE_ID = "current_device_id"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerDevices: RecyclerView
    private lateinit var progressLoading: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var tvErrorState: TextView

    private var isForcedMode = false
    private var currentDeviceId = ""
    private val deviceList = mutableListOf<DeviceSession>()
    private lateinit var adapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_active_devices)

        isForcedMode = intent.getBooleanExtra(EXTRA_FORCED_MODE, false)
        currentDeviceId = intent.getStringExtra(EXTRA_CURRENT_DEVICE_ID) ?: ""

        bindViews()
        setupToolbar()
        setupRecyclerView()
        loadDevices()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar_devices)
        recyclerDevices = findViewById(R.id.recycler_devices)
        progressLoading = findViewById(R.id.progress_loading)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        tvErrorState = findViewById(R.id.tv_error_state)

        // If forced mode, disable back navigation
        if (isForcedMode) {
            toolbar.title = "Device Limit Reached"
            toolbar.subtitle = "Remove a device to continue"
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            if (!isForcedMode) {
                finish()
            }
            // In forced mode, back is disabled — user must remove a device
        }
    }

    // -------------------------------------------------------------------------
    // RecyclerView — PRD F-DEV-01 AC#2
    // -------------------------------------------------------------------------

    private fun setupRecyclerView() {
        adapter = DeviceAdapter(
            devices = deviceList,
            currentDeviceId = currentDeviceId,
            onRemoveClicked = { device -> showRemoveConfirmation(device) }
        )
        recyclerDevices.layoutManager = LinearLayoutManager(this)
        recyclerDevices.adapter = adapter
    }

    // -------------------------------------------------------------------------
    // Load devices — Screens.md SCR-DEV-01 states
    // API_Spec.md §4 — GET /v1/devices
    // -------------------------------------------------------------------------

    private fun loadDevices() {
        // Show loading state — Screens.md SCR-DEV-01 Loading
        showState(State.LOADING)

        // TODO: Replace with actual API call via Retrofit/OkHttp
        // GET /v1/devices → parse response → populate list
        // For now, simulate loading from intent extras (passed by login flow)

        // In forced mode, devices are pre-loaded from the 403 response
        val extraDevices = intent.getStringExtra("devices_json")
        if (extraDevices != null) {
            try {
                val jsonArray = org.json.JSONArray(extraDevices)
                deviceList.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    deviceList.add(DeviceSession(
                        id = obj.getString("id"),
                        deviceName = obj.getString("deviceName"),
                        androidVersion = obj.getString("androidVersion"),
                        lastActiveTime = obj.getLong("lastActiveTime")
                    ))
                }
                adapter.notifyDataSetChanged()
                showState(if (deviceList.isEmpty()) State.EMPTY else State.CONTENT)
            } catch (e: Exception) {
                showState(State.ERROR)
            }
        } else {
            // Fetch from API
            showState(State.LOADING)
            fetchDevicesFromApi()
        }
    }

    /**
     * Fetches device list from GET /v1/devices.
     * TODO: Replace with Retrofit call
     */
    private fun fetchDevicesFromApi() {
        // Placeholder — will be wired with networking layer
        // For now show empty/error based on availability
        showState(State.CONTENT)
    }

    // -------------------------------------------------------------------------
    // Remove device — PRD F-DEV-01 AC#3
    // API_Spec.md §4 — DELETE /v1/devices/{id}
    // -------------------------------------------------------------------------

    /**
     * Shows confirmation dialog before revoking a device session.
     *
     * PRD F-DEV-01 AC#3 — "Selecting a device and tapping 'Remove' logs out
     * the selected device"
     */
    private fun showRemoveConfirmation(device: DeviceSession) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove Device")
            .setMessage(
                "Are you sure you want to log out \"${device.deviceName}\"?\n\n" +
                "This device will need to sign in again to access the vault."
            )
            .setPositiveButton("Remove") { _, _ ->
                revokeDeviceSession(device)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Calls DELETE /v1/devices/{id} to revoke a session.
     *
     * PRD F-DEV-01 AC#3 — "logs out the selected device and allows
     * the login to proceed on the 4th device"
     * Database_Schema.md §3 — hard deletion
     */
    private fun revokeDeviceSession(device: DeviceSession) {
        // TODO: Replace with actual DELETE API call
        // DELETE /v1/devices/{device.id}

        // Simulate success
        deviceList.remove(device)
        adapter.notifyDataSetChanged()

        Toast.makeText(this, "${device.deviceName} has been logged out.",
            Toast.LENGTH_SHORT).show()

        // If forced mode and now under limit, allow login to proceed
        if (isForcedMode && deviceList.size < 3) {
            setResult(RESULT_OK)
            finish()
        }

        if (deviceList.isEmpty()) {
            showState(State.EMPTY)
        }
    }

    // -------------------------------------------------------------------------
    // State management — Screens.md SCR-DEV-01 state variations
    // -------------------------------------------------------------------------

    private enum class State { LOADING, CONTENT, EMPTY, ERROR }

    private fun showState(state: State) {
        progressLoading.visibility = if (state == State.LOADING) View.VISIBLE else View.GONE
        recyclerDevices.visibility = if (state == State.CONTENT) View.VISIBLE else View.GONE
        tvEmptyState.visibility = if (state == State.EMPTY) View.VISIBLE else View.GONE
        tvErrorState.visibility = if (state == State.ERROR) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------------------
    // Data model — API_Spec.md §4 response schema
    // -------------------------------------------------------------------------

    data class DeviceSession(
        val id: String,
        val deviceName: String,
        val androidVersion: String,
        val lastActiveTime: Long
    )

    // -------------------------------------------------------------------------
    // RecyclerView Adapter — PRD F-DEV-01 AC#2
    // "Device Name, Android Version, Last Active Time, Current Device indicator"
    // -------------------------------------------------------------------------

    private class DeviceAdapter(
        private val devices: List<DeviceSession>,
        private val currentDeviceId: String,
        private val onRemoveClicked: (DeviceSession) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

        class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardDevice: MaterialCardView = view.findViewById(R.id.card_device)
            val tvDeviceName: TextView = view.findViewById(R.id.tv_device_name)
            val tvAndroidVersion: TextView = view.findViewById(R.id.tv_android_version)
            val tvLastActive: TextView = view.findViewById(R.id.tv_last_active)
            val tvCurrentBadge: TextView = view.findViewById(R.id.tv_current_badge)
            val btnRemove: MaterialButton = view.findViewById(R.id.btn_remove_device)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device_session, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = devices[position]
            val isCurrentDevice = device.id == currentDeviceId

            holder.tvDeviceName.text = device.deviceName
            holder.tvAndroidVersion.text = "Android ${device.androidVersion}"
            holder.tvLastActive.text = "Last active: ${formatTimestamp(device.lastActiveTime)}"

            // PRD F-DEV-01 AC#2 — "Current Device indicator"
            holder.tvCurrentBadge.visibility =
                if (isCurrentDevice) View.VISIBLE else View.GONE

            // Edge case: Don't allow removing current device
            holder.btnRemove.visibility =
                if (isCurrentDevice) View.GONE else View.VISIBLE

            holder.btnRemove.setOnClickListener {
                onRemoveClicked(device)
            }
        }

        override fun getItemCount() = devices.size

        private fun formatTimestamp(timestamp: Long): String {
            return try {
                val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                sdf.format(Date(timestamp))
            } catch (e: Exception) {
                "Unknown"
            }
        }
    }
}
