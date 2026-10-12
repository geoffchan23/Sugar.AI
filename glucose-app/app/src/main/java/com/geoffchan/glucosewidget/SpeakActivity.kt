package com.geoffchan.glucosewidget

import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * "Hey Google, open Francine's sugar" / "…open Francine's medicine": each
 * is a launcher alias of this activity (labels are the phrases) carrying a
 * fixed question in its meta-data. It asks the Ray server, says the answer
 * out loud, shows it, and closes. Without the server it answers the same
 * fixed questions from this phone's own data.
 */
class SpeakActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var text by mutableStateOf("Checking…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize().clickable { finish() }, contentAlignment = Alignment.BottomCenter) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Ray", style = MaterialTheme.typography.labelLarge)
                        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        val question = questionFor(this) ?: return finish()
        var ready = false
        var pending: String? = null
        tts = TextToSpeech(this) { status ->
            ready = status == TextToSpeech.SUCCESS
            pending?.let { speak(it) }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { runOnUiThread { window.decorView.postDelayed({ finish() }, 2_500) } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) = Unit
            })
        }
        lifecycleScope.launch {
            val answer = answer(question)
            text = answer
            if (ready) speak(answer) else pending = answer
        }
    }

    private fun speak(s: String) {
        tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "ray")
    }

    private suspend fun answer(question: String): String {
        Store.rayServer(this)?.let { cfg ->
            runCatching { withTimeout(25_000) { RayServer.voice(cfg, question) } }.onSuccess { return it }
        }
        val now = System.currentTimeMillis()
        return when (val q = quickQuestion(question)) {
            QuickQuestion.READING -> spokenReading(Store.reading(this), now)
            null -> "I can't reach Ray right now."
            else -> {
                val today = ZonedDateTime.now().toLocalDate()
                spokenLast(q, GlucoseDb.get(this).dao().dayJournalBetween(today.minusDays(3).toString(), today.toString()), LocalDate.now().toString())
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        /** The alias's fixed question (meta-data "question"). */
        fun questionFor(a: ComponentActivity): String? = runCatching {
            a.packageManager.getActivityInfo(a.componentName, PackageManager.GET_META_DATA).metaData?.getString("question")
        }.getOrNull()
    }
}
