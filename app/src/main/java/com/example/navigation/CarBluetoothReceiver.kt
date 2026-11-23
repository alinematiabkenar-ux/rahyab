package com.example.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Optional F4.2 Car Bluetooth ACL disconnect detector.
 * Listens for [BluetoothDevice.ACTION_ACL_DISCONNECTED] only when the user has selected
 * a car Bluetooth device and granted BLUETOOTH_CONNECT (Android 12+).
 */
class CarBluetoothReceiver(
    private val selectedAddressProvider: () -> String,
    private val onSelectedCarDisconnected: () -> Unit
) : BroadcastReceiver() {

    @Volatile
    private var isRegistered = false

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val expectedAddress = selectedAddressProvider().trim()
        if (expectedAddress.isEmpty()) return
        if (!hasBluetoothConnectPermission(context)) return

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val actualAddress = runCatching { device?.address }.getOrNull().orEmpty()
        if (actualAddress.isNotEmpty() && actualAddress.equals(expectedAddress, ignoreCase = true)) {
            onSelectedCarDisconnected()
        }
    }

    fun register(context: Context) {
        if (isRegistered) return
        if (!hasBluetoothConnectPermission(context)) return
        runCatching {
            val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            ContextCompat.registerReceiver(
                context.applicationContext,
                this,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isRegistered = true
        }
    }

    fun unregister(context: Context) {
        if (!isRegistered) return
        runCatching {
            context.applicationContext.unregisterReceiver(this)
        }
        isRegistered = false
    }

    companion object {
        fun hasBluetoothConnectPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun isBluetoothAvailable(context: Context): Boolean {
            val adapter = getBluetoothAdapter(context)
            return adapter != null
        }

        @SuppressLint("MissingPermission")
        fun getBondedDevices(context: Context): List<Pair<String, String>> {
            if (!hasBluetoothConnectPermission(context)) return emptyList()
            val adapter = getBluetoothAdapter(context) ?: return emptyList()
            return runCatching {
                adapter.bondedDevices
                    .orEmpty()
                    .mapNotNull { device ->
                        val addr = device.address?.trim().orEmpty()
                        if (addr.isEmpty()) return@mapNotNull null
                        val name = device.name?.trim().orEmpty().ifBlank { addr }
                        addr to name
                    }
                    .sortedBy { it.second.lowercase() }
            }.getOrDefault(emptyList())
        }

        private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
            return runCatching {
                val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                mgr?.adapter
            }.getOrNull()
        }
    }
}
