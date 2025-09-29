@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.rokutv.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.rokutv.R
import com.example.rokutv.data.Device
import com.example.rokutv.data.RokuPreferences
import com.example.rokutv.network.RokuApiService
import com.example.rokutv.utils.Constants
import com.example.rokutv.work.SchedulerHelper
import kotlinx.coroutines.*
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        val prefs = RokuPreferences(this)
        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            MaterialTheme {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(title = { Text(stringResource(id = R.string.app_name)) })
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { inner ->
                    Box(Modifier.padding(inner)) {
                        RokuAppUI(prefs, snackbarHostState)
                    }
                }
            }
        }
    }
}

@Composable
fun RokuAppUI(prefs: RokuPreferences, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    // State
    var devices by remember { mutableStateOf(prefs.getDevices().toMutableList()) }
    var selectedIp by remember { mutableStateOf(prefs.getSelectedIp().orEmpty()) }
    var ipInput by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Device?>(null) }

    var expanded by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf(prefs.getSelectedApp() ?: Constants.APP_QUICKESIGN) }
    var interval by remember { mutableStateOf(prefs.getIntervalSeconds()) }

    var autoLaunchEnabled by remember { mutableStateOf(prefs.getAutoRelaunchEnabled()) }
    var scheduleOnEnabled by remember { mutableStateOf(prefs.isScheduleOnEnabled()) }
    var scheduleOffEnabled by remember { mutableStateOf(prefs.isScheduleOffEnabled()) }
    var onTime by remember { mutableStateOf(prefs.getOnTimeLabel() ?: "Not set") }
    var offTime by remember { mutableStateOf(prefs.getOffTimeLabel() ?: "Not set") }

    LaunchedEffect(devices) {
        if (selectedIp.isEmpty() && devices.isNotEmpty()) {
            selectedIp = devices.first().ip
            prefs.saveSelectedIp(selectedIp)
        }
    }

    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = Color(0xFF22C55E),
        checkedTrackColor = Color(0xFFBBF7D0)
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- DEVICES ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Devices", style = MaterialTheme.typography.titleMedium)

                Row {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("Enter Roku IP") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val clean = ipInput.trim()
                        if (clean.isNotEmpty() && devices.none { it.ip == clean }) {
                            val d = Device(clean, "Roku Device")
                            devices = (devices + d).toMutableList()
                            prefs.saveDevices(devices)
                            if (selectedIp.isEmpty()) {
                                selectedIp = clean
                                prefs.saveSelectedIp(clean)
                            }
                            ipInput = ""
                        }
                    }) { Text("Add") }
                }

                LazyColumn(Modifier.fillMaxWidth().height(160.dp)) {
                    items(devices) { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (device.ip == selectedIp) "✅ ${device.ip} - ${device.name}" else "${device.ip} - ${device.name}",
                                modifier = Modifier.weight(1f).clickable {
                                    selectedIp = device.ip
                                    prefs.saveSelectedIp(device.ip)
                                }
                            )
                            Row {
                                IconButton(onClick = { renameTarget = device }) {
                                    Icon(Icons.Outlined.Edit, null)
                                }
                                IconButton(onClick = {
                                    devices = devices.filter { it.ip != device.ip }.toMutableList()
                                    prefs.saveDevices(devices)
                                    if (selectedIp == device.ip) {
                                        selectedIp = devices.firstOrNull()?.ip.orEmpty()
                                        prefs.saveSelectedIp(selectedIp)
                                    }
                                }) { Icon(Icons.Outlined.Delete, null, tint = Color.Red) }
                            }
                        }
                        Divider()
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            Toast.makeText(context, "Scanning…", Toast.LENGTH_SHORT).show()
                            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
                            val lock = wifi?.createMulticastLock("roku-ssdp")?.apply { acquire() }
                            try {
                                val found = withContext(Dispatchers.IO) {
                                    RokuApiService.discoverDevices(context.applicationContext, 3000)
                                }
                                if (found.isNotEmpty()) {
                                    val merged = (devices + found).distinctBy { it.ip }.toMutableList()
                                    devices = merged
                                    prefs.saveDevices(merged)
                                    if (selectedIp.isEmpty()) {
                                        selectedIp = merged.first().ip
                                        prefs.saveSelectedIp(selectedIp)
                                    }
                                    Toast.makeText(
                                        context,
                                        "Discovered: ${found.joinToString { it.name }}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(context, "No devices found", Toast.LENGTH_SHORT).show()
                                }
                            } finally { try { lock?.release() } catch (_: Exception) {} }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Devices, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Devices")
                }
            }
        }

        val ips = devices.map { it.ip }

        // ---- APPLICATION ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Application", style = MaterialTheme.typography.titleMedium)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = Constants.APP_NAMES[selectedApp] ?: selectedApp,
                        onValueChange = {},
                        label = { Text("Select App") },
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Constants.APP_NAMES.forEach { (appId, appName) ->
                            DropdownMenuItem(
                                text = { Text(appName) },
                                onClick = {
                                    selectedApp = appId
                                    prefs.saveSelectedApp(appId)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // ---- AUTO RELAUNCH ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Auto Relaunch", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = interval.toString(),
                    onValueChange = { new ->
                        new.filter { it.isDigit() }.toIntOrNull()?.let {
                            val sanitized = it.coerceAtLeast(1)
                            interval = sanitized
                            prefs.saveIntervalSeconds(sanitized)
                        }
                    },
                    label = { Text("Interval (seconds)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Auto re-launch every $interval sec")
                    Switch(
                        checked = autoLaunchEnabled,
                        onCheckedChange = { enabled ->
                            if (ips.isEmpty()) {
                                Toast.makeText(context, "No devices available", Toast.LENGTH_SHORT).show()
                                return@Switch
                            }
                            autoLaunchEnabled = enabled
                            prefs.setAutoRelaunchEnabled(enabled)
                            if (enabled) {
                                SchedulerHelper.scheduleRelaunchForAll(
                                    context.applicationContext, interval, ips, selectedApp
                                )
                                scope.launch { snackbarHostState.showSnackbar("Auto relaunch enabled (every $interval sec)") }
                            } else {
                                SchedulerHelper.cancelRelaunchAll(context.applicationContext)
                                scope.launch { snackbarHostState.showSnackbar("Auto relaunch disabled") }
                            }
                        },
                        colors = switchColors
                    )
                }
            }
        }

        // ---- DAILY SCHEDULING ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Daily Scheduling", style = MaterialTheme.typography.titleMedium)

                scheduleRow(
                    label = "Power on time",
                    time = onTime,
                    enabled = scheduleOnEnabled,
                    onEnabledChange = { enabled ->
                        if (ips.isEmpty()) {
                            Toast.makeText(context, "No devices available", Toast.LENGTH_SHORT).show()
                            return@scheduleRow
                        }
                        scheduleOnEnabled = enabled
                        prefs.setScheduleOnEnabled(enabled)
                        if (!enabled) {
                            onTime = "Not set"
                            prefs.saveOnTimeLabel(null)
                            SchedulerHelper.cancelDailyAll(context.applicationContext, "ON")
                            scope.launch { snackbarHostState.showSnackbar("Power on schedule disabled") }
                        }
                    },
                    onPickTime = { hour, minute ->
                        val label = formatTime(hour, minute)
                        onTime = label
                        prefs.saveOnTimeLabel(label)
                        SchedulerHelper.scheduleDailyForAll(context.applicationContext, "ON", hour, minute, ips)
                        scope.launch { snackbarHostState.showSnackbar("Power on scheduled at $label") }
                    }
                )

                Divider()

                scheduleRow(
                    label = "Power off time",
                    time = offTime,
                    enabled = scheduleOffEnabled,
                    onEnabledChange = { enabled ->
                        if (ips.isEmpty()) {
                            Toast.makeText(context, "No devices available", Toast.LENGTH_SHORT).show()
                            return@scheduleRow
                        }
                        scheduleOffEnabled = enabled
                        prefs.setScheduleOffEnabled(enabled)
                        if (!enabled) {
                            offTime = "Not set"
                            prefs.saveOffTimeLabel(null)
                            SchedulerHelper.cancelDailyAll(context.applicationContext, "OFF")
                            scope.launch { snackbarHostState.showSnackbar("Power off schedule disabled") }
                        }
                    },
                    onPickTime = { hour, minute ->
                        val label = formatTime(hour, minute)
                        offTime = label
                        prefs.saveOffTimeLabel(label)
                        SchedulerHelper.scheduleDailyForAll(context.applicationContext, "OFF", hour, minute, ips)
                        scope.launch { snackbarHostState.showSnackbar("Power off scheduled at $label") }
                    }
                )
            }
        }

        // ---- MANUAL CONTROLS ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Manual Controls", style = MaterialTheme.typography.titleMedium)

                Button(
                    onClick = {
                        scope.sendToAll(ips, "keypress/PowerOn", "Power On", context, prefs)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Power, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Power On")
                }
                Button(
                    onClick = {
                        scope.sendToAll(ips, "keypress/PowerOff", "Power Off", context, prefs)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Power Off")
                }
                FilledTonalButton(
                    onClick = {
                        val label = "Launch " + (Constants.APP_NAMES[selectedApp] ?: selectedApp)
                        scope.sendToAll(ips, "launch/$selectedApp", label, context, prefs)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Launch App")
                }
            }
        }
    }

    // ---- RENAME DIALOG ----
    renameTarget?.let { device ->
        var newName by remember { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Device") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Device Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val idx = devices.indexOfFirst { it.ip == device.ip }
                    if (idx >= 0 && newName.isNotBlank()) {
                        devices = devices.toMutableList().also {
                            it[idx] = device.copy(name = newName.trim())
                        }
                        prefs.saveDevices(devices)
                    }
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun scheduleRow(
    label: String,
    time: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPickTime: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) { Text("$label: $time") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = enabled, onClick = {
                pickTime(context) { h, m -> onPickTime(h, m) }
            }) { Icon(Icons.Outlined.Schedule, null) }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

// ---- helpers ----

private fun CoroutineScope.sendToAll(
    ips: List<String>,
    command: String,
    label: String,
    context: Context,
    prefs: RokuPreferences
) {
    if (ips.isEmpty()) {
        Toast.makeText(context, "No devices available", Toast.LENGTH_SHORT).show()
        return
    }
    launch(Dispatchers.IO) {
        val jobs = ips.map { ip ->
            async {
                val clean = ip.trim()
                if (clean.isNotBlank() && clean.contains(".")) {
                    val ok = RokuApiService.sendCommand(clean, command)
                    if (command.startsWith("keypress/PowerOff", true)) prefs.suppressIp(clean)
                    if (command.startsWith("keypress/PowerOn", true)) prefs.clearSuppression(clean)
                    ok
                } else false
            }
        }
        val results = jobs.awaitAll()
        val ok = results.count { it }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "$label → $ok/${ips.size} succeeded", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun pickTime(context: Context, onTimeSelected: (Int, Int) -> Unit) {
    val cal = Calendar.getInstance()
    TimePickerDialog(
        context,
        { _, hour, minute -> onTimeSelected(hour, minute) },
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
        true
    ).show()
}

private fun formatTime(h: Int, m: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", h, m)
