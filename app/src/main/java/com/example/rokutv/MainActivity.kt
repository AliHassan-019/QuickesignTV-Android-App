@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.rokutv.ui

import android.app.TimePickerDialog
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.rokutv.data.RokuPreferences
import com.example.rokutv.network.RokuApiService
import com.example.rokutv.utils.Constants
import com.example.rokutv.work.SchedulerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = RokuPreferences(this)

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Quickesign TV Control") }
                        )
                    }
                ) { inner ->
                    Box(Modifier.padding(inner)) {
                        RokuAppUI(prefs)
                    }
                }
            }
        }
    }
}

@Composable
fun RokuAppUI(prefs: RokuPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    // Green switches
    val EnabledThumb = Color(0xFF22C55E)
    val EnabledTrack = Color(0xFFBBF7D0)
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = EnabledThumb,
        checkedTrackColor = EnabledTrack
    )

    var ipInput by remember { mutableStateOf("") }
    var savedIps by remember { mutableStateOf(prefs.getIpList().toMutableList()) }
    var selectedIp by remember { mutableStateOf(prefs.getSelectedIp().orEmpty()) }

    var expanded by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf(prefs.getSelectedApp() ?: Constants.APP_QUICKESIGN) }

    var interval by remember { mutableStateOf(prefs.getIntervalSeconds()) }

    // Global toggles/times (applied to all)
    var autoLaunchEnabled by remember { mutableStateOf(prefs.getAutoRelaunchEnabled()) }
    var scheduleOnEnabled by remember { mutableStateOf(prefs.isScheduleOnEnabled()) }
    var scheduleOffEnabled by remember { mutableStateOf(prefs.isScheduleOffEnabled()) }
    var onTime by remember { mutableStateOf(prefs.getOnTimeLabel() ?: "Not set") }
    var offTime by remember { mutableStateOf(prefs.getOffTimeLabel() ?: "Not set") }

    // Ensure some IP is “selected” for highlight
    LaunchedEffect(savedIps) {
        if (selectedIp.isEmpty() && savedIps.isNotEmpty()) {
            selectedIp = savedIps.first()
            prefs.saveSelectedIp(selectedIp)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---------- IP MANAGEMENT ----------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("IP Management", style = MaterialTheme.typography.titleMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("Enter Roku IP") },
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(onClick = {
                        val clean = ipInput.trim()
                        if (clean.isNotEmpty() && !savedIps.contains(clean)) {
                            savedIps = (savedIps + clean).toMutableList()
                            prefs.saveIpList(savedIps.toSet())
                            if (selectedIp.isEmpty()) {
                                selectedIp = clean
                                prefs.saveSelectedIp(clean)
                            }
                            ipInput = ""
                            Toast.makeText(context, "IP added", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add")
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    items(savedIps) { ip ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (ip == selectedIp) "✅ $ip" else ip,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        selectedIp = ip
                                        prefs.saveSelectedIp(ip)
                                    }
                            )
                            IconButton(onClick = {
                                val newList = savedIps.filter { it != ip }.toMutableList()
                                savedIps = newList
                                prefs.saveIpList(newList.toSet())
                                if (selectedIp == ip) {
                                    selectedIp = newList.firstOrNull().orEmpty()
                                    prefs.saveSelectedIp(selectedIp)
                                }
                                Toast.makeText(context, "IP removed", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Remove",
                                    tint = Color(0xFFEF4444)
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
        }

        // ---------- DISCOVERY ----------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Discovery", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        scope.launch {
                            Toast.makeText(context, "Scanning…", Toast.LENGTH_SHORT).show()
                            val found = withContext(Dispatchers.IO) {
                                RokuApiService.discoverDevices(2000)
                            }
                            if (found.isEmpty()) {
                                Toast.makeText(context, "No devices found", Toast.LENGTH_SHORT).show()
                            } else {
                                val merged = (savedIps + found).toSet().toMutableList()
                                savedIps = merged
                                prefs.saveIpList(merged.toSet())
                                if (selectedIp.isEmpty()) {
                                    selectedIp = merged.first()
                                    prefs.saveSelectedIp(selectedIp)
                                }
                                Toast.makeText(context, "Discovered: ${found.joinToString()}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Devices, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Devices")
                }
            }
        }

        // ---------- APP SELECTION ----------
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

        // ---------- AUTO RELAUNCH ----------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Auto Relaunch", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = interval.toString(),
                    onValueChange = { new ->
                        new.filter { it.isDigit() }.toIntOrNull()?.let {
                            interval = it.coerceAtLeast(5)
                            prefs.saveIntervalSeconds(interval)
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
                            if (savedIps.isEmpty()) {
                                Toast.makeText(context, "No devices available", Toast.LENGTH_SHORT).show()
                                return@Switch
                            }
                            autoLaunchEnabled = enabled
                            prefs.setAutoRelaunchEnabled(enabled)
                            if (enabled) {
                                SchedulerHelper.scheduleRelaunchForAll(
                                    context.applicationContext,
                                    interval,
                                    savedIps,
                                    selectedApp
                                )
                                Toast.makeText(context, "Auto re-launch enabled", Toast.LENGTH_SHORT).show()
                            } else {
                                SchedulerHelper.cancelRelaunchAll(context.applicationContext)
                                Toast.makeText(context, "Auto re-launch disabled", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = switchColors
                    )
                }
            }
        }

        // ---------- DAILY SCHEDULING ----------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Daily Scheduling", style = MaterialTheme.typography.titleMedium)

                // ON row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Power on time: $onTime")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = scheduleOnEnabled,
                            onClick = {
                                pickTime(context) { hour, minute ->
                                    val label = formatTime(hour, minute)
                                    onTime = label
                                    prefs.saveOnTimeLabel(label)
                                    SchedulerHelper.scheduleDailyForAll(
                                        context.applicationContext, "ON", hour, minute, savedIps
                                    )
                                }
                            }
                        ) { Icon(Icons.Outlined.Schedule, contentDescription = "Change time") }
                        Switch(
                            checked = scheduleOnEnabled,
                            onCheckedChange = { enabled ->
                                if (savedIps.isEmpty()) {
                                    Toast.makeText(context, "No devices available", Toast.LENGTH_SHORT).show()
                                    return@Switch
                                }
                                scheduleOnEnabled = enabled
                                prefs.setScheduleOnEnabled(enabled)
                                if (enabled) {
                                    pickTime(context) { hour, minute ->
                                        val label = formatTime(hour, minute)
                                        onTime = label
                                        prefs.saveOnTimeLabel(label)
                                        SchedulerHelper.scheduleDailyForAll(
                                            context.applicationContext, "ON", hour, minute, savedIps
                                        )
                                        Toast.makeText(context, "Power on scheduled at $label", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    onTime = "Not set"
                                    prefs.saveOnTimeLabel(null)
                                    SchedulerHelper.cancelDailyAll(context.applicationContext, "ON")
                                    Toast.makeText(context, "Power on schedule disabled", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = switchColors
                        )
                    }
                }

                Divider()

                // OFF row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Power off time: $offTime")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = scheduleOffEnabled,
                            onClick = {
                                pickTime(context) { hour, minute ->
                                    val label = formatTime(hour, minute)
                                    offTime = label
                                    prefs.saveOffTimeLabel(label)
                                    SchedulerHelper.scheduleDailyForAll(
                                        context.applicationContext, "OFF", hour, minute, savedIps
                                    )
                                }
                            }
                        ) { Icon(Icons.Outlined.Schedule, contentDescription = "Change time") }
                        Switch(
                            checked = scheduleOffEnabled,
                            onCheckedChange = { enabled ->
                                if (savedIps.isEmpty()) {
                                    Toast.makeText(context, "No devices available", Toast.LENGTH_SHORT).show()
                                    return@Switch
                                }
                                scheduleOffEnabled = enabled
                                prefs.setScheduleOffEnabled(enabled)
                                if (enabled) {
                                    pickTime(context) { hour, minute ->
                                        val label = formatTime(hour, minute)
                                        offTime = label
                                        prefs.saveOffTimeLabel(label)
                                        SchedulerHelper.scheduleDailyForAll(
                                            context.applicationContext, "OFF", hour, minute, savedIps
                                        )
                                        Toast.makeText(context, "Power off scheduled at $label", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    offTime = "Not set"
                                    prefs.saveOffTimeLabel(null)
                                    SchedulerHelper.cancelDailyAll(context.applicationContext, "OFF")
                                    Toast.makeText(context, "Power off schedule disabled", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = switchColors
                        )
                    }
                }
            }
        }

        // ---------- MANUAL CONTROLS ----------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Manual Controls", style = MaterialTheme.typography.titleMedium)

                Button(
                    onClick = { scope.sendToAll(savedIps, "keypress/PowerOn", context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Power, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Power On")
                }
                Button(
                    onClick = { scope.sendToAll(savedIps, "keypress/PowerOff", context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Power Off")
                }
                FilledTonalButton(
                    onClick = { scope.sendToAll(savedIps, "launch/$selectedApp", context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Launch App")
                }
            }
        }
    }
}

// ---- helpers ----

private fun CoroutineScope.sendToAll(ips: List<String>, command: String, context: android.content.Context) {
    if (ips.isEmpty()) {
        Toast.makeText(context, "No devices available", Toast.LENGTH_SHORT).show()
        return
    }
    launch(Dispatchers.IO) {
        val jobs = ips.map { ip ->
            async {
                val clean = ip.trim()
                if (clean.isNotBlank() && clean.contains(".")) {
                    RokuApiService.sendCommand(clean, command)
                } else false
            }
        }
        val results = jobs.awaitAll()
        val ok = results.count { it }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "$command → $ok/${ips.size} succeeded", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun pickTime(context: android.content.Context, onTimeSelected: (Int, Int) -> Unit) {
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
