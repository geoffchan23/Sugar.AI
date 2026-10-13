package com.geoffchan.glucosewidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MD = DateTimeFormatter.ofPattern("MMM d", Locale.CANADA)
private val EMD = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.CANADA)

/**
 * The Today tab: the current reading first (the number she opens the app
 * for), then the day or week chart, then that range's log as a timeline,
 * and quick + Dose / + Food chips at the bottom (Ray is his own tab; the Ask bar went 2026-10-11).
 * Rows: tap to edit, swipe left to delete (Undo in the snackbar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(snackbar: SnackbarHostState, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { GlucoseDb.get(context).dao() }
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    var day by rememberSaveable { mutableStateOf(LocalDate.now(zone)) }
    var mode by rememberSaveable { mutableStateOf(SCOPE_DAY) }
    var showPicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JournalEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var dosing by remember { mutableStateOf(false) }
    var editingDose by remember { mutableStateOf<JournalEntity?>(null) } // set alongside dosing
    var logging by remember { mutableStateOf(false) }
    var editingLog by remember { mutableStateOf<JournalEntity?>(null) } // set alongside logging
    val pendingDelete = remember { mutableStateListOf<String>() } // swiped away, waiting out the Undo


    val today = LocalDate.now(zone)
    val isWeek = mode == SCOPE_WEEK
    val firstDay = if (isWeek) weekStartOf(day) else day
    val spanDays = if (isWeek) 7 else 1
    val entryKey = firstDay.toString()

    val (startMs, endMs) = rangeBoundsMs(firstDay, spanDays, zone)
    val readings by dao.readingsBetween(startMs, endMs).collectAsState(initial = emptyList())
    val scopedEntries by dao.journalFor(mode, entryKey).collectAsState(initial = emptyList())
    // Week view also lists that week's day entries, labeled by date.
    val dayEntriesInWeek by (
        if (isWeek) dao.dayJournalInRange(firstDay.toString(), firstDay.plusDays(6).toString())
        else dao.journalFor("none", "none")
        ).collectAsState(initial = emptyList())
    val entries = scopedEntries + dayEntriesInWeek
    val latest by dao.latestReading().collectAsState(initial = null)
    val recent by dao.dayJournalInRange(today.minusDays(3).toString(), today.toString()).collectAsState(initial = emptyList())
    val settings = remember { runBlocking { Store.settings(context) } }
    val unsynced by remember { Store.syncOutboxFlow(context) }.collectAsState(initial = emptySet())

    /** After a save: sync now and tell her whether it reached the server (Geoff, 2026-10-13). */
    fun confirmSynced(uid: String) = scope.launch {
        when (Sync.sendNow(context, listOf(uid))) {
            true -> snackbar.showSnackbar("Saved ✓ Synced")
            false -> snackbar.showSnackbar("Saved on this phone. It will sync when there's a connection.")
            null -> Unit // this phone doesn't sync
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }

    fun shift(delta: Int) {
        val next = day.plusDays(delta.toLong() * spanDays)
        if (delta > 0 && (if (isWeek) weekStartOf(next) else next) > today) return
        day = next
    }

    Column(Modifier.fillMaxSize()) {
        // ---- top bar: brand, date pill, settings ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RayAvatar(30.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                buildAnnotatedString {
                    append("Sugar")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".AI") }
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            )
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { shift(-1) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", Modifier.size(20.dp))
                    }
                    Text(
                        when {
                            isWeek && weekStartOf(today) == firstDay -> "This week"
                            isWeek -> "${firstDay.format(MD)} – ${firstDay.plusDays(6).format(MD)}"
                            day == today -> "Today, ${day.format(MD)}"
                            else -> day.format(EMD)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { showPicker = true }.padding(vertical = 6.dp),
                    )
                    IconButton(
                        onClick = { shift(1) },
                        enabled = firstDay.plusDays(spanDays.toLong()) <= today,
                        modifier = Modifier.size(34.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", Modifier.size(20.dp)) }
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- the log, as a timeline (everything above it scrolls with it; the Ask bar stays put) ----
        val rows = sortForList(entries.filterNot { isDerivedEntry(it.text) || it.uid in pendingDelete }).let { if (isWeek) it else it.asReversed() }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
          item("top") {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NowCard(latest, now, settings, latestDose(recent, zone), latestEvent(recent, zone), zone, today, onOpenSettings)

            // ---- chart card ----
            val (doseMarks, eventMarks) = markerData(entries, firstDay)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Segmented(listOf("Day", "Week"), if (isWeek) 1 else 0) { mode = if (it == 1) SCOPE_WEEK else SCOPE_DAY }
                        Spacer(Modifier.weight(1f))
                        if (readings.isNotEmpty()) {
                            val inRange = readings.count { mmolValue(it.mgdl) in settings.lowMmol..settings.highMmol }
                            Text(
                                buildAnnotatedString {
                                    append("In range ")
                                    withStyle(SpanStyle(color = Color(0xFF2FA36B), fontWeight = FontWeight.ExtraBold)) {
                                        append("${(inRange * 100 + readings.size / 2) / readings.size}%")
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                context.startActivity(
                                    Intent(context, ChartActivity::class.java)
                                        .putExtra("epochDay", firstDay.toEpochDay())
                                        .putExtra("days", spanDays),
                                )
                            },
                            modifier = Modifier.size(36.dp),
                        ) { Text("⛶", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    RangeChart(
                        readings = readings,
                        firstDay = firstDay,
                        days = spanDays,
                        zone = zone,
                        modifier = Modifier.fillMaxWidth().height(200.dp).padding(end = 6.dp),
                        lowMmol = settings.lowMmol,
                        highMmol = settings.highMmol,
                        doses = doseMarks,
                        events = eventMarks,
                        onSwipe = { shift(it) },
                    )
                }
            }
          }
          }
          item("title") {
            SectionTitle(
            when {
                isWeek -> "This week's log".takeIf { weekStartOf(today) == firstDay } ?: "Week's log"
                day == today -> "Today's log"
                else -> "${day.format(MD)} log"
            },
            Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 2.dp),
            trailing = if (rows.isEmpty()) null else "${rows.size} ${if (rows.size == 1) "entry" else "entries"}",
            )
          }
            if (rows.isEmpty()) item("empty") {
                Text(
                    "Nothing logged yet. Tell Ray (the Ray tab or your watch), or use + Dose and + Food.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
            var lastDay: String? = null
            for (entry in rows) {
                if (isWeek && entry.scope == SCOPE_DAY && entry.day != lastDay) {
                    lastDay = entry.day
                    item("h" + entry.day) {
                        Text(
                            LocalDate.parse(entry.day).format(EMD),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                }
                item(entry.id) {
                    LogRow(
                        entry,
                        unsynced = entry.uid in unsynced,
                        now = now,
                        onEdit = {
                            when {
                                parseDoseNote(entry.text) != null -> { editingDose = entry; dosing = true }
                                parseEventNote(entry.text) != null -> { editingLog = entry; logging = true }
                                else -> editing = entry
                            }
                        },
                        onKeep = {
                            scope.launch {
                                Journal.update(context, entry.copy(text = confirmGuess(entry.text), updatedAtMs = System.currentTimeMillis()))
                                GlucoseWidget().updateAll(context)
                                confirmSynced(entry.uid)
                            }
                        },
                        onDelete = {
                            pendingDelete += entry.uid
                            scope.launch {
                                val r = snackbar.showSnackbar("Deleted ${rowView(entry.text).title}", actionLabel = "Undo", withDismissAction = false)
                                if (r == SnackbarResult.ActionPerformed) {
                                    pendingDelete -= entry.uid
                                } else {
                                    Journal.delete(context, entry)
                                    GlucoseWidget().updateAll(context)
                                    pendingDelete -= entry.uid
                                }
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item("end") { Spacer(Modifier.height(8.dp)) }
        }

        // ---- quick add (talking to Ray is the Ray tab or her watch) ----
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickChip("+ Dose") { dosing = true }
                if (isWeek) QuickChip("+ Week note") { adding = true } else QuickChip("+ Food") { logging = true }
            }
        }
    }

    // ---- sheets and pickers ----
    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        day = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showPicker = false
                }) { Text("OK") }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (adding || editing != null) {
        var text by remember(adding, editing) { mutableStateOf(editing?.text ?: "") }
        SheetDialog(
            onDismissRequest = { adding = false; editing = null },
            title = {
                Text(
                    when {
                        editing != null -> "Edit note"
                        isWeek -> "Note for the week of ${firstDay.format(MD)}"
                        else -> "Note for ${day.format(MD)}"
                    },
                )
            },
            text = {
                OutlinedTextField(
                    text, { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (isWeek) "How was the week? Routine, food themes, exercise…"
                            else "What happened? Meals, activity, sleep…",
                        )
                    },
                    minLines = 3,
                )
            },
            confirmButton = {
                Button(
                    enabled = text.isNotBlank(),
                    onClick = {
                        val now2 = System.currentTimeMillis()
                        val toSave = editing?.copy(text = text.trim(), updatedAtMs = now2)
                            ?: JournalEntity(
                                day = entryKey, text = text.trim(),
                                createdAtMs = now2, updatedAtMs = now2, scope = mode,
                            )
                        scope.launch {
                            if (editing != null) Journal.update(context, toSave) else Journal.insert(context, toSave)
                            GlucoseWidget().updateAll(context)
                            adding = false; editing = null
                            confirmSynced(toSave.uid)
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }

    if (dosing) {
        val existing = editingDose?.let { parseDoseNote(it.text) }
        var insulinType by remember {
            mutableStateOf(existing?.insulinType ?: runBlocking { Store.lastMedication(context) })
        }
        var unitsText by remember {
            mutableStateOf((existing?.units ?: defaultUnits(insulinType, LocalDate.now(zone).dayOfWeek)).toString())
        }
        fun unitsOrNull() = unitsText.toIntOrNull()?.takeIf { it in 1..100 }
        fun bump(delta: Int) {
            unitsText = ((unitsText.toIntOrNull() ?: 0) + delta).coerceIn(1, 100).toString()
        }
        var doseTime by remember { mutableStateOf(existing?.time ?: java.time.LocalTime.now(zone).withSecond(0)) }
        fun closeDose() { dosing = false; editingDose = null }
        SheetDialog(
            onDismissRequest = { closeDose() },
            title = {
                Text(
                    if (editingDose != null) "Edit dose"
                    else "Log dose · ${(if (isWeek) LocalDate.now(zone) else day).format(MD)}",
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = insulinType == "short-acting",
                            onClick = {
                                insulinType = "short-acting"
                                unitsText = defaultUnits("short-acting", LocalDate.now(zone).dayOfWeek).toString()
                            },
                            label = { Text("Short-acting") },
                        )
                        FilterChip(
                            selected = insulinType == "long-acting",
                            onClick = {
                                insulinType = "long-acting"
                                unitsText = defaultUnits("long-acting", LocalDate.now(zone).dayOfWeek).toString()
                            },
                            label = { Text("Long-acting") },
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.FilledTonalIconButton(onClick = { bump(-1) }) {
                            Text("−", style = MaterialTheme.typography.titleLarge)
                        }
                        OutlinedTextField(
                            unitsText,
                            { new -> if (new.length <= 3 && new.all(Char::isDigit)) unitsText = new },
                            label = { Text("Units") },
                            singleLine = true,
                            isError = unitsOrNull() == null,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.FilledTonalIconButton(onClick = { bump(+1) }) {
                            Text("+", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    TimeField(doseTime, { doseTime = it }, pickerTitle = "Dose time")
                }
            },
            confirmButton = {
                Button(
                    enabled = unitsOrNull() != null,
                    onClick = {
                        val units = unitsOrNull() ?: return@Button
                        val now2 = System.currentTimeMillis()
                        val time = doseTime.format(DateTimeFormatter.ofPattern("HH:mm"))
                        val text = doseNoteText(insulinType, "${units}u", time)
                        val toEdit = editingDose
                        scope.launch {
                            val saved = if (toEdit != null) {
                                // Edit keeps the entry's day and creation time; only the note changes.
                                toEdit.copy(text = text, updatedAtMs = now2).also { Journal.update(context, it) }
                            } else {
                                // Like food logs: the day being viewed (day mode), so
                                // yesterday's dose can be backfilled; today in week mode.
                                JournalEntity(
                                    day = (if (isWeek) LocalDate.now(zone) else day).toString(), text = text,
                                    createdAtMs = now2, updatedAtMs = now2, scope = SCOPE_DAY,
                                ).also {
                                    Journal.insert(context, it)
                                    Store.saveLastMedication(context, insulinType)
                                }
                            }
                            GlucoseWidget().updateAll(context)
                            closeDose()
                            confirmSynced(saved.uid)
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { closeDose() }) { Text("Cancel") } },
        )
    }

    if (logging) {
        val existing = editingLog?.let { parseEventNote(it.text) }
        var what by remember { mutableStateOf(existing?.name ?: "") }
        var logTime by remember { mutableStateOf(existing?.time ?: java.time.LocalTime.now(zone).withSecond(0)) }
        fun closeLog() { logging = false; editingLog = null }
        SheetDialog(
            onDismissRequest = { closeLog() },
            title = {
                Text(
                    if (editingLog != null) "Edit food/exercise"
                    else "Log food/exercise · ${day.format(MD)}",
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        what, { what = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("lunch, 30 min walk, ice cream…") },
                        singleLine = true,
                    )
                    TimeField(logTime, { logTime = it }, pickerTitle = "Log time")
                }
            },
            confirmButton = {
                Button(
                    enabled = what.isNotBlank(),
                    onClick = {
                        val now2 = System.currentTimeMillis()
                        val text = eventNoteText(what, logTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                        val toEdit = editingLog
                        scope.launch {
                            val saved = if (toEdit != null) {
                                toEdit.copy(text = text, updatedAtMs = now2).also { Journal.update(context, it) }
                            } else {
                                // Logs go to the day being viewed, so yesterday can be backfilled.
                                JournalEntity(
                                    day = (if (isWeek) LocalDate.now(zone) else day).toString(), text = text,
                                    createdAtMs = now2, updatedAtMs = now2, scope = SCOPE_DAY,
                                ).also { Journal.insert(context, it) }
                            }
                            GlucoseWidget().updateAll(context)
                            closeLog()
                            confirmSynced(saved.uid)
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { closeLog() }) { Text("Cancel") } },
        )
    }

}

/** The big "Now" card: reading, trend, age, last dose and last meal. Red when low, amber when high. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NowCard(
    latest: ReadingEntity?,
    now: Long,
    settings: Settings,
    lastDose: LastLog?,
    lastEvent: LastLog?,
    zone: ZoneId,
    today: LocalDate,
    onSetup: () -> Unit,
) {
    val reading = latest?.let { Reading(it.mgdl, it.timestampMs, it.trend) }
    val state = reading?.let { displayState(it, now, settings.lowMmol, settings.highMmol) }
    val bg = when (state) {
        GlucoseState.LOW -> Brand.Low
        GlucoseState.HIGH -> Brand.HighLight
        GlucoseState.STALE, null -> Brand.Stale
        GlucoseState.IN_RANGE -> Brand.Sugar
    }
    Surface(
        onClick = { if (reading == null) onSetup() },
        enabled = reading == null,
        shape = RoundedCornerShape(24.dp),
        color = bg,
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Image(
                painterResource(R.drawable.sparkle), null,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-20).dp, y = 16.dp).size(22.dp),
            )
            Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
                if (reading == null) {
                    Text("NO READING YET", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                    Text("Set up Dexcom in Settings", style = MaterialTheme.typography.titleLarge)
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val age = ageText(reading.timestampMs, now)
                        Text(
                            when (state) {
                                GlucoseState.STALE -> "LAST READING · $age AGO"
                                GlucoseState.LOW -> "LOW · $age AGO"
                                GlucoseState.HIGH -> "HIGH · $age AGO"
                                else -> "NOW · $age AGO"
                            }.uppercase(Locale.CANADA),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
                            color = Color.White.copy(alpha = 0.88f),
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                mmolText(reading.mgdl),
                                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
                            )
                            Text(
                                trendArrow(reading.trend),
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                            )
                            Text(
                                "mmol/L",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(start = 6.dp, bottom = 9.dp),
                            )
                        }
                    }
                }
                val pills = listOfNotNull(lastDose, lastEvent)
                if (pills.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        for (p in pills) {
                            val onDay = Instant.ofEpochMilli(p.atMs).atZone(zone).toLocalDate()
                            val whenLabel = (if (onDay == today) "" else if (onDay == today.minusDays(1)) "yesterday " else "${onDay.format(MD)} ") + whenText(p.atMs, zone)
                            Text(
                                "${p.label.let { if (it.length > 30) it.take(29).trimEnd() + "…" else it }} · $whenLabel",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Two-way pill switch (Day / Week). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(if (on) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent, CircleShape)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

private val REVEAL_WIDTH = 96.dp

private enum class RowKind { SHORT, LONG, FOOD, NOTE }
private data class RowView(val time: String, val kind: RowKind, val title: String, val guess: Boolean)

private fun rowView(text: String): RowView {
    parseDoseNote(text)?.let { d ->
        return RowView(time12(d.time), if (d.isShort) RowKind.SHORT else RowKind.LONG, "${if (d.isShort) "Short-acting" else "Long-acting"} ${d.units}u", d.isGuess)
    }
    parseEventNote(text)?.let { e ->
        return RowView(time12(e.time), RowKind.FOOD, e.name.replaceFirstChar { it.uppercase() }, e.isGuess)
    }
    return RowView("", RowKind.NOTE, text, false)
}

/**
 * One timeline row: time · icon · what. Tap edits. Swiping left only
 * reveals a Delete button (past 60% of its width, or a firm fling); the
 * row is deleted when that's tapped — a stray swipe never deletes
 * (Geoff, 2026-10-10: the old swipe-to-delete fired by accident).
 */
@Composable
private fun LogRow(entry: JournalEntity, unsynced: Boolean, now: Long, onEdit: () -> Unit, onKeep: () -> Unit, onDelete: () -> Unit) {
    val v = remember(entry.text) { rowView(entry.text) }
    val c = LocalSugar.current
    val scope = rememberCoroutineScope()
    val revealPx = with(LocalDensity.current) { REVEAL_WIDTH.toPx() }
    val offset = remember { Animatable(0f) }
    fun close() = scope.launch { offset.animateTo(0f) }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (offset.value < 0f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    onClick = { close(); onDelete() },
                    shape = RoundedCornerShape(12.dp),
                    color = c.low,
                    contentColor = Color.White,
                    modifier = Modifier.width(REVEAL_WIDTH - 8.dp).fillMaxHeight().padding(vertical = 4.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("Delete", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        Row(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .draggable(
                    rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-revealPx, 0f)) } },
                    Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val open = offset.value < -revealPx * 0.6f || (velocity < -2500f && offset.value < -revealPx * 0.3f)
                        offset.animateTo(if (open) -revealPx else 0f)
                    },
                )
                .clickable { if (offset.value != 0f) close() else onEdit() }
                .padding(horizontal = 4.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (v.kind != RowKind.NOTE) {
                Text(
                    v.time,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(62.dp),
                )
            }
            val (glyph, bg, fg) = when (v.kind) {
                RowKind.SHORT -> Triple("▲", c.soft, c.onSoft)
                RowKind.LONG -> Triple("△", c.soft, c.onSoft)
                RowKind.FOOD -> Triple("◆", MaterialTheme.colorScheme.surfaceVariant, c.event)
                RowKind.NOTE -> Triple("✎", MaterialTheme.colorScheme.surfaceVariant, c.muted)
            }
            Box(
                Modifier.size(30.dp).let {
                    if (v.guess) it.border(1.5.dp, c.muted, RoundedCornerShape(9.dp)) else it.background(bg, RoundedCornerShape(9.dp))
                },
                contentAlignment = Alignment.Center,
            ) { Text(glyph, color = if (v.guess) c.muted else fg, style = MaterialTheme.typography.labelLarge) }
            Column(Modifier.weight(1f)) {
                Text(v.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                if (v.guess) Text("Guess from the curve", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // still only on this phone: the server (and the other phone) don't have it yet
                if (unsynced) Text(
                    if (now - entry.updatedAtMs < 60_000) "Syncing…" else "Not synced yet",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (v.guess) {
                Surface(onClick = onKeep, shape = CircleShape, color = c.soft, contentColor = c.onSoft) {
                    Text("Keep", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
        }
    }
}

/**
 * −15m / [ 12:21 PM ] / +15m. Tapping the time opens Material's clock in
 * 12-hour mode (AM/PM toggle). Storage stays 24-hour; only display is 12-hour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(time: java.time.LocalTime, onChange: (java.time.LocalTime) -> Unit, pickerTitle: String) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = { onChange(time.minusMinutes(15)) }) { Text("−15m") }
        androidx.compose.material3.OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.weight(1f),
        ) { Text(time12(time)) }
        TextButton(onClick = { onChange(time.plusMinutes(15)) }) { Text("+15m") }
    }
    if (showPicker) {
        val state = androidx.compose.material3.rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = false,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(pickerTitle) },
            text = { androidx.compose.material3.TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(java.time.LocalTime.of(state.hour, state.minute))
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        )
    }
}
