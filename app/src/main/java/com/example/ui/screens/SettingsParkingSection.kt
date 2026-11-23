package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.preferences.UserSettings
import com.example.i18n.LocalAppStrings
import com.example.navigation.CarBluetoothReceiver

/**
 * F4.1 / F4.2 Settings card for Parked Car auto-save options:
 *  - "Don't save parking near Home (150 m)" toggle (ON by default)
 *  - Optional "Car Bluetooth device" bonded-device picker requesting BLUETOOTH_CONNECT
 *    only when tapped, and hiding cleanly if permission is denied.
 */
@Composable
fun SettingsParkingSectionCard(
    settings: UserSettings,
    onToggleSkipAtHome: (Boolean) -> Unit,
    onSelectCarBluetoothDevice: (address: String, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current

    val btAvailable = remember(context) { CarBluetoothReceiver.isBluetoothAvailable(context) }
    var btPermissionDenied by remember { mutableStateOf(false) }
    var showBtPicker by remember { mutableStateOf(false) }
    var bondedDevices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            bondedDevices = CarBluetoothReceiver.getBondedDevices(context)
            showBtPicker = true
        } else {
            btPermissionDenied = true
        }
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_parking_section_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalParking,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = strings.settingsSectionParking,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = strings.settingsSkipParkingAtHome,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                )
                Switch(
                    checked = settings.skipParkingAtHome,
                    onCheckedChange = onToggleSkipAtHome,
                    modifier = Modifier.testTag("switch_skip_parking_at_home")
                )
            }

            if (btAvailable && !btPermissionDenied) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                !CarBluetoothReceiver.hasBluetoothConnectPermission(context)
                            ) {
                                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                bondedDevices = CarBluetoothReceiver.getBondedDevices(context)
                                showBtPicker = true
                            }
                        }
                        .padding(vertical = 4.dp)
                        .testTag("car_bluetooth_picker_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = strings.settingsCarBluetoothLabel,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = settings.carBluetoothDeviceName.ifBlank { strings.settingsCarBluetoothNone },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBtPicker) {
        AlertDialog(
            onDismissRequest = { showBtPicker = false },
            title = { Text(strings.settingsCarBluetoothPickerTitle) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BtDeviceChoiceRow(
                        label = strings.settingsCarBluetoothNone,
                        selected = settings.carBluetoothDeviceAddress.isBlank(),
                        onClick = {
                            onSelectCarBluetoothDevice("", "")
                            showBtPicker = false
                        }
                    )
                    if (bondedDevices.isEmpty()) {
                        Text(
                            text = strings.settingsCarBluetoothNoDevices,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        bondedDevices.forEach { (address, name) ->
                            BtDeviceChoiceRow(
                                label = name,
                                selected = settings.carBluetoothDeviceAddress.equals(address, ignoreCase = true),
                                onClick = {
                                    onSelectCarBluetoothDevice(address, name)
                                    showBtPicker = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBtPicker = false }) {
                    Text(strings.close)
                }
            }
        )
    }
}

@Composable
private fun BtDeviceChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
