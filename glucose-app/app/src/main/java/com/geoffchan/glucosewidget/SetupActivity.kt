package com.geoffchan.glucosewidget

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val existing = Store.credentials(ctx)
        val settings = runBlocking { Store.settings(ctx) }

        setContent {
            SugarTheme {
                androidx.compose.material3.Scaffold(
                    topBar = {
                        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                        androidx.compose.material3.TopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                androidx.compose.material3.IconButton(onClick = { finish() }) {
                                    androidx.compose.material3.Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Back",
                                    )
                                }
                            },
                        )
                    },
                ) { insets ->
                    var username by remember { mutableStateOf(existing?.first ?: "") }
                    var password by remember { mutableStateOf(existing?.second ?: "") }
                    var low by remember { mutableStateOf(settings.lowMmol.toString()) }
                    var high by remember { mutableStateOf(settings.highMmol.toString()) }
                    var status by remember { mutableStateOf("") }
                    val scope = rememberCoroutineScope()

                    Column(
                        Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Uses the wearer's Dexcom account (the login the G7 app uses). " +
                                "Credentials stay on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(username, { username = it }, label = { Text("Dexcom username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(low, { low = it }, label = { Text("Low (mmol/L)") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(high, { high = it }, label = { Text("High (mmol/L)") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Button(
                            onClick = {
                                status = "Testing…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { ShareClient.fetchLatest(username.trim(), password, null) }
                                    }
                                    result.fold(
                                        onSuccess = { (reading, session) ->
                                            Store.saveCredentials(ctx, username.trim(), password)
                                            Store.saveSession(ctx, session)
                                            Store.saveReading(ctx, reading)
                                            val s = Settings(
                                                low.toDoubleOrNull() ?: Store.DEFAULT_LOW,
                                                high.toDoubleOrNull() ?: Store.DEFAULT_HIGH,
                                            )
                                            Store.saveSettings(ctx, s)
                                            GlucoseWidget().updateAll(ctx)
                                            Refresh.scheduleNext(ctx)
                                            status = "OK — latest: ${mmolText(reading.mgdl)} " +
                                                trendArrow(reading.trend) + " (" +
                                                ageText(reading.timestampMs, System.currentTimeMillis()) + " ago). Saved."
                                        },
                                        onFailure = { status = "Failed: ${it.message}" },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Test connection & save") }
                        Text(status, style = MaterialTheme.typography.bodyMedium)

                        Text("Reliability", style = MaterialTheme.typography.titleMedium)
                        val am = getSystemService(AlarmManager::class.java)
                        val pm = getSystemService(PowerManager::class.java)
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (am.canScheduleExactAlarms()) "Exact alarms: allowed ✓"
                                else "Allow exact alarms (needed for 5-min updates)",
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (pm.isIgnoringBatteryOptimizations(packageName)) "Battery: unrestricted ✓"
                                else "Disable battery optimization",
                            )
                        }

                        SyncSection()
                        AssistantSection()
                    }
                }
            }
        }
    }
}

/** Two-phone sync status + manual controls. Setup itself is done over ADB (`op sync-setup`). */
@androidx.compose.runtime.Composable
private fun SyncSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    androidx.compose.runtime.LaunchedEffect(tick) {
        while (true) {
            val configured = Sync.active(ctx)
            val server = Store.rayServer(ctx)
            val main = Store.isMainPhone(ctx)
            val st = Store.syncStatus(ctx)
            fun ago(t: Long?) = t?.let { ageText(it, System.currentTimeMillis()) + " ago" } ?: "never"
            lines = if (!configured) {
                listOf("Not set up — this phone keeps its journal to itself.", if (main) "Role: main phone" else "Role: second phone")
            } else {
                listOfNotNull(
                    if (server != null) "On — through the Ray server (${server.url.removePrefix("https://")})" else "On — end-to-end encrypted relay",
                    if (main) "Role: main phone (auto-logs routines)" else "Role: second phone (no auto-logging)",
                    "Last checked: ${ago(st.lastPollAt)} · last sent: ${ago(st.lastPublishAt)}",
                    "Waiting to send: ${st.outbox}",
                    st.lastError?.let { "Last error: $it" },
                )
            }
            kotlinx.coroutines.delay(5_000)
        }
    }
    Text("Sync", style = MaterialTheme.typography.titleMedium)
    for (l in lines) Text(l, style = MaterialTheme.typography.bodySmall)
    if (Sync.active(ctx)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { Sync.enqueue(ctx, now = true); tick++ }, modifier = Modifier.weight(1f)) { Text("Sync now") }
            OutlinedButton(
                onClick = {
                    scope.launch { Sync.queueRecent(ctx, Sync.RESEND_WINDOW_MS); Sync.enqueue(ctx); tick++ }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Resend 30 days") }
        }
    }
}

/** Which assistant model is set up (never the key). Configured over ADB: see IngestReceiver `ai-setup`. */
@androidx.compose.runtime.Composable
private fun AssistantSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cfg = androidx.compose.runtime.remember { Store.aiConfig(ctx) }
    val server = androidx.compose.runtime.remember { Store.rayServer(ctx) }
    val scope = rememberCoroutineScope()
    Text("Ray (assistant)", style = MaterialTheme.typography.titleMedium)
    Text(
        when {
            server != null -> "Ray runs on the Ray server (one Ray for the phones, watch, Alexa and Google). " +
                if (cfg != null) "If the server can't be reached, this phone answers itself with OpenAI ${cfg.model}." else "If the server can't be reached, entries use the on-device model."
            cfg == null -> "Not set up — typed and spoken entries use the on-device model, and the chat can't answer."
            else -> "OpenAI ${cfg.model}, effort ${cfg.effort ?: "auto (low for logging, medium for questions)"}. " +
                "Sends your question and the readings/logs it looks up to OpenAI."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    // what's been recorded for evals (this phone's own + synced from the other)
    var logged by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { logged = runCatching { GlucoseDb.get(ctx).dao().interactionCount() }.getOrNull() }
    logged?.let { Text("Interaction log: $it recorded", style = MaterialTheme.typography.bodySmall) }
    // who's chatting from this phone (Ray addresses them by name; messages are labelled)
    var author by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { author = Store.chatAuthor(ctx) }
    author?.let { a ->
        Text("In the chat this phone is ${authorName(a)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (who in listOf(AUTHOR_FRANCINE, AUTHOR_GEOFF)) {
                androidx.compose.material3.FilterChip(
                    selected = a == who,
                    onClick = { scope.launch { Store.saveChatAuthor(ctx, who); author = who } },
                    label = { Text(authorName(who)) },
                )
            }
        }
    }
}
