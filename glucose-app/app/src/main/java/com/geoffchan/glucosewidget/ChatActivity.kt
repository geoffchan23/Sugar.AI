package com.geoffchan.glucosewidget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Old entry point (notifications, report links): forwards to the Ray tab. */
class ChatActivity : ComponentActivity() {
    companion object { const val EXTRA_THREAD = "thread" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_TAB, TAB_RAY)
                .apply { intent.getStringExtra(EXTRA_THREAD)?.let { putExtra(EXTRA_THREAD, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}

/**
 * The Ray tab (see [RayChat]): shared threads between her phone and
 * Geoff's. ChatGPT-style: ☰ opens the history (search, grouped by date,
 * long-press to rename or delete, both synced), ✎ starts a new chat (the
 * old one stays in the history). Ray's messages can carry a card of
 * proposed entries/changes, a saved report, and what he ran to get there.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RayScreen(thread: String, onThread: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { GlucoseDb.get(context).dao() }
    val me = remember { runBlocking { Store.chatAuthor(context) } }
    val other = if (me == AUTHOR_FRANCINE) AUTHOR_GEOFF else AUTHOR_FRANCINE
    val all by dao.allChat().collectAsState(initial = emptyList())
    val threads = chatThreads(all)
    val current = threads.firstOrNull { it.thread == thread }
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var renaming by remember { mutableStateOf<ChatThreadSummary?>(null) }
    var deleting by remember { mutableStateOf<ChatThreadSummary?>(null) }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                ChatHistory(
                    threads, thread,
                    onOpen = { onThread(it); scope.launch { drawer.close() } },
                    onNew = { onThread(newUid()); scope.launch { drawer.close() } },
                    onRename = { renaming = it },
                    onDelete = { deleting = it },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Chats") }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    RayAvatar(30.dp)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            current?.title ?: "New chat", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "Ray · shared with ${authorName(other)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onThread(newUid()) }, enabled = current != null) { Icon(Icons.Filled.Edit, "New chat") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Conversation(thread, all.filter { it.thread == thread && it.isMessage }, me)
        }
    }

    renaming?.let { t ->
        var name by remember(t.thread) { mutableStateOf(t.title) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = null
                    InteractionLog.recordLater(context) { ChatStore.threadEvent(it, t.thread, rename = name.trim()) }
                }) { Text("Rename") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { t ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat?") },
            text = { Text("“${t.title}” goes from both phones. Anything it logged stays in the log.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleting = null
                    if (t.thread == thread) onThread(newUid())
                    InteractionLog.recordLater(context) { ChatStore.threadEvent(it, t.thread, deleted = true) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** The history drawer: search, + New chat, threads grouped Today / Yesterday / Previous 7 days / by month. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatHistory(
    threads: List<ChatThreadSummary>,
    selected: String,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (ChatThreadSummary) -> Unit,
    onDelete: (ChatThreadSummary) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val shown = if (query.isBlank()) threads else threads.filter {
        it.title.contains(query.trim(), ignoreCase = true) || it.lastLine.contains(query.trim(), ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Text("Chats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        androidx.compose.material3.OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            var group: String? = null
            for (t in shown) {
                val g = threadGroup(t.lastAtMs, today, zone)
                if (g != group) {
                    group = g
                    item("g:$g") {
                        Text(
                            g, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                }
                item(t.thread) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Surface(
                            color = if (t.thread == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(t.thread) }, onLongClick = { menu = true }),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(
                                    t.lastLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename(t) })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete(t) })
                        }
                    }
                }
            }
            if (shown.isEmpty()) item("none") {
                Text(
                    if (query.isBlank()) "No chats yet." else "No chats match “${query.trim()}”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp),
                )
            }
        }
        androidx.compose.material3.Button(
            onClick = onNew,
            shape = CircleShape,
            modifier = Modifier.padding(16.dp).navigationBarsPadding(),
        ) {
            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp))
            Text("New chat", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.CANADA)
private val DAY_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.CANADA)

private fun whenLabel(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return if (t.toLocalDate() == LocalDate.now()) t.format(TIME) else t.format(DAY_TIME)
}

private val STARTERS = listOf(
    "How was this past week?",
    "Why did I go low last night?",
    "Compare my mornings this month with last month",
    "Make a report of my lows for my endocrinologist",
)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Conversation(thread: String, messages: List<ChatMessageEntity>, me: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val busy by RayChat.busy.collectAsState()
    val thinking = thread in busy
    var draft by remember(thread) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, thinking) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }

    fun send(text: String) {
        if (text.isBlank() || thinking) return
        RayChat.send(context, thread, me, text)
        draft = ""
    }

    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("bottom") { Spacer(Modifier.size(4.dp)) }
        if (thinking) item("thinking") {
            // the sparkle "twinkles" while he works
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "ray")
            val alpha by pulse.animateFloat(
                0.45f, 1f,
                androidx.compose.animation.core.infiniteRepeatable<Float>(
                    androidx.compose.animation.core.tween<Float>(700), androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "alpha",
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                RayAvatar(28.dp, Modifier.graphicsLayer { this.alpha = alpha })
                Text("  Ray is looking into it…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(messages.asReversed(), key = { it.uid }) { m -> Bubble(m, me) }
        if (messages.isEmpty()) item("empty") {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                RayAvatar(72.dp)
                Text("Hi, I'm Ray", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Ask me anything about Francine's glucose, or tell me what to log. I can look through all of her data, run my own analysis and make reports. Geoff and Francine both see this chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                STARTERS.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            draft, { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message Ray") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 6,
        )
        androidx.compose.material3.FilledIconButton(
            onClick = { send(draft) },
            enabled = draft.isNotBlank() && !thinking,
            modifier = Modifier.padding(start = 6.dp).size(48.dp),
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
    }
}

@Composable
private fun Bubble(m: ChatMessageEntity, me: String) {
    val mine = m.author == me
    val ray = m.author == AUTHOR_RAY
    val meta = remember(m.meta) { m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } }
    val big = 18.dp; val tail = 6.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (ray) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (ray) { RayAvatar(28.dp); Spacer(Modifier.size(8.dp)) }
        Surface(
            modifier = Modifier.widthIn(max = if (ray) 360.dp else 300.dp),
            shape = if (ray) RoundedCornerShape(big, big, big, tail) else RoundedCornerShape(big, big, tail, big),
            color = when {
                mine -> MaterialTheme.colorScheme.primary
                ray -> MaterialTheme.colorScheme.surfaceContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = when {
                mine -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            border = if (ray) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${authorName(m.author)} · ${whenLabel(m.createdAtMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f),
                )
                SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
                decodeCard(m.card)?.let { CardView(m.uid, it, me) }
                meta?.optString("report")?.takeIf { it.isNotBlank() }?.let { ReportLink(it) }
                meta?.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { ToolsFooter(it) }
            }
        }
    }
}

@Composable
private fun ReportLink(file: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val here = remember(file) { File(reportsDir(context), file).isFile }
    if (here) {
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(context, MainActivity::class.java).putExtra(ReportsActivity.EXTRA_OPEN, file)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }) { Text("Open report: ${RayTools.reportTitleOf(file) ?: file}") }
    } else {
        Text("Report saved on the other phone: ${RayTools.reportTitleOf(file) ?: file}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

/** "Used: query ×2, analysis" — tap for the SQL and code he ran. */
@Composable
private fun ToolsFooter(tools: org.json.JSONArray) {
    var open by remember { mutableStateOf(false) }
    val list = (0 until tools.length()).map { tools.getJSONObject(it) }
    val names = list.groupingBy { toolLabel(it.optString("name")) }.eachCount().entries.joinToString(", ") { (n, c) -> if (c > 1) "$n ×$c" else n }
    Text(
        (if (open) "▾ " else "▸ ") + "Looked at: $names",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.clickable { open = !open },
    )
    if (open) list.forEach { t ->
        val detail = listOfNotNull(
            t.optString("sql").takeIf { it.isNotBlank() },
            t.optString("code").takeIf { it.isNotBlank() },
            listOf("from_day", "to_day", "from", "to").mapNotNull { k -> t.optString(k).takeIf { it.isNotBlank() } }.takeIf { it.isNotEmpty() }?.joinToString(" → "),
            t.optString("error").takeIf { it.isNotBlank() }?.let { "error: $it" },
        )
        Text(toolLabel(t.optString("name")), style = MaterialTheme.typography.labelMedium)
        detail.forEach { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
    }
}

private fun toolLabel(name: String) = when (name) {
    AssistantTools.READINGS -> "readings"
    AssistantTools.STATS -> "stats"
    AssistantTools.JOURNAL -> "log"
    AssistantTools.LOWS -> "lows"
    AssistantTools.STRATEGIES -> "playbook"
    AssistantTools.PROPOSE -> "new entries"
    AssistantTools.PROPOSE_CHANGES -> "changes"
    RayTools.QUERY -> "query"
    RayTools.ANALYZE -> "analysis"
    RayTools.REPORT -> "report"
    else -> name
}

/** Ray's proposals: everything starts ticked; untick what's wrong, then Save. */
@Composable
private fun CardView(uid: String, card: ChatCard, me: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val entryTicks = remember(uid) { mutableStateListOf(*Array(card.entries.size) { true }) }
    val changeTicks = remember(uid) { mutableStateListOf(*Array(card.changes.size) { true }) }
    var saving by remember { mutableStateOf(false) }
    val existing by produceState(emptyList<JournalEntity>(), card.day, card.state) {
        value = GlucoseDb.get(context).dao().dayJournalBetween(card.day, card.day)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val open = card.state == CARD_OPEN
        if (card.entries.isNotEmpty()) {
            val dayLabel = runCatching { LocalDate.parse(card.day).format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA)) }.getOrDefault(card.day)
            Text("Log on $dayLabel:", style = MaterialTheme.typography.labelLarge)
            card.entries.forEachIndexed { i, text ->
                val status = proposedFromText(text)?.let { matchStatus(text, matchExisting(it, existing)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (open) Checkbox(checked = entryTicks.getOrElse(i) { true }, onCheckedChange = { entryTicks[i] = it })
                    Column {
                        Text(entryLabel(text), style = MaterialTheme.typography.bodyMedium)
                        when (status.takeIf { open }) {
                            WatchProtocol.STATUS_ALREADY -> "already logged"
                            WatchProtocol.STATUS_CONFIRM -> "confirms a guess"
                            WatchProtocol.STATUS_REPLACE -> "replaces the auto-logged entry"
                            else -> null
                        }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
        }
        if (card.changes.isNotEmpty()) {
            Text("Changes:", style = MaterialTheme.typography.labelLarge)
            card.changes.forEachIndexed { i, op ->
                val d = runCatching { LocalDate.parse(op.day).format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA)) }.getOrDefault(op.day)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (open) Checkbox(checked = changeTicks.getOrElse(i) { true }, onCheckedChange = { changeTicks[i] = it })
                    Text("$d: ${changeLabel(op)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (open) {
            val picked = entryTicks.count { it } + changeTicks.count { it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = picked > 0 && !saving, onClick = {
                    saving = true
                    scope.launch {
                        ChatStore.saveCard(
                            context, uid,
                            card.entries.filterIndexed { i, _ -> entryTicks.getOrElse(i) { true } },
                            card.changes.filterIndexed { i, _ -> changeTicks.getOrElse(i) { true } },
                            me,
                        )
                        saving = false
                        Sync.enqueue(context, now = true) // send it straight away; the line below turns to "synced ✓"
                    }
                }) { Text("Save $picked") }
                TextButton(enabled = !saving, onClick = { scope.launch { ChatStore.dismissCard(context, uid, me) } }) { Text("Dismiss") }
            }
        } else {
            Spacer(Modifier.size(2.dp))
            // a save made on this phone shows when the server has it (Geoff, 2026-10-13)
            val syncing by remember { Store.syncOutboxFlow(context) }.collectAsState(initial = emptySet())
            val syncs = remember { Sync.active(context) }
            Text(
                when (card.state) {
                    CARD_SAVED -> "${card.result ?: "Saved"} by ${authorName(card.by ?: "")}" + when {
                        !syncs -> ""
                        Sync.CHAT_PREFIX + uid in syncing -> " · syncing…"
                        else -> " · synced ✓"
                    }
                    else -> "Dismissed by ${authorName(card.by ?: "")}"
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
