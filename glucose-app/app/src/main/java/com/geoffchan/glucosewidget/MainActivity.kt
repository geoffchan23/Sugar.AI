package com.geoffchan.glucosewidget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import java.io.File

/** Intent extra: an interaction-log record whose proposed changes to review. */
const val EXTRA_REVIEW_CHANGES = "reviewChanges"
/** Intent extra: which tab to show ([TAB_TODAY], [TAB_RAY], [TAB_REPORTS]). */
const val EXTRA_TAB = "tab"
const val TAB_TODAY = "today"
const val TAB_RAY = "ray"
const val TAB_REPORTS = "reports"

/**
 * The whole app: three tabs — Today ([TodayScreen]), Reports ([ReportsScreen]) and
 * Ray ([RayScreen]); Settings is the gear on Today. Notifications
 * reach a tab through intent extras (ChatActivity / ReportsActivity just
 * forward here).
 */
class MainActivity : ComponentActivity() {
    /** Set when opened from the "review changes from your watch" notification. */
    private var reviewId by mutableStateOf<String?>(null)
    private var tab by mutableStateOf(TAB_TODAY)
    private var chatThread by mutableStateOf("")
    private var openReport by mutableStateOf<File?>(null)
    private var resumed by mutableStateOf(false)

    private fun route(intent: Intent) {
        intent.getStringExtra(EXTRA_REVIEW_CHANGES)?.let { reviewId = it; tab = TAB_TODAY }
        intent.getStringExtra(ChatActivity.EXTRA_THREAD)?.let { chatThread = it; tab = TAB_RAY }
        intent.getStringExtra(ReportsActivity.EXTRA_OPEN)?.let { name ->
            openReport = File(reportsDir(this), name).takeIf { it.isFile }
            tab = TAB_REPORTS
        }
        intent.getStringExtra(EXTRA_TAB)?.let { tab = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ChatNotification.openThread = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dao = GlucoseDb.get(this).dao()
        // carry on the latest conversation if it's from the last day, else start fresh
        chatThread = runBlocking {
            chatThreads(dao.allChatNow()).firstOrNull { System.currentTimeMillis() - it.lastAtMs < 24 * 3600_000L }?.thread
        } ?: newUid()
        if (savedInstanceState == null) route(intent)
        Refresh.enqueue(this) // opening the app freshens the data
        Sync.enqueue(this) // and pulls the other phone's entries
        // Android 13+: notifications need a runtime grant (used for "report ready").
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { SugarTheme { App() } }
    }

    @Composable
    private fun App() {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(tab, chatThread, resumed) {
            val chatting = resumed && tab == TAB_RAY
            ChatNotification.openThread = if (chatting) chatThread else null
            if (chatting) Sync.enqueue(this@MainActivity) // pull anything the other phone said
        }
        BackHandler(enabled = tab == TAB_RAY || (tab == TAB_REPORTS && openReport == null)) { tab = TAB_TODAY }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        val colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        )
                        NavigationBarItem(
                            selected = tab == TAB_TODAY, onClick = { tab = TAB_TODAY },
                            icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Today") }, colors = colors,
                        )
                        NavigationBarItem(
                            selected = tab == TAB_REPORTS, onClick = { tab = TAB_REPORTS },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Reports") }, colors = colors,
                        )
                        NavigationBarItem(
                            selected = tab == TAB_RAY, onClick = { tab = TAB_RAY },
                            icon = { RayAvatar(26.dp) }, label = { Text("Ray") }, colors = colors,
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (tab) {
                    TAB_RAY -> RayScreen(chatThread) { chatThread = it }
                    TAB_REPORTS -> ReportsScreen(openReport) { openReport = it }
                    else -> TodayScreen(snackbar) {
                        startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                    }
                }
            }
        }

        reviewId?.let { id -> ChangesDialog(id) { reviewId = null } }
    }
}
