package com.geoffchan.glucosewidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * "New in the chat" for messages synced from the other phone: what the
 * other person wrote, and Ray's answers to them. One notification per
 * thread; skipped while that thread is open, and for anything older than
 * [RECENT_MS] (a resend after a long time offline shouldn't buzz).
 */
object ChatNotification {
    private const val CHANNEL = "chat"
    private const val RECENT_MS = 2 * 3600_000L

    /** The thread on screen right now, if any (set by ChatActivity). */
    @Volatile var openThread: String? = null

    suspend fun newMessages(context: Context, incoming: List<ChatMessageEntity>) {
        val me = Store.chatAuthor(context)
        val now = System.currentTimeMillis()
        val show = incoming.filter { it.isMessage && it.author != me && now - it.createdAtMs < RECENT_MS && it.thread != openThread }
        if (show.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chat with Ray", NotificationManager.IMPORTANCE_DEFAULT))
        for ((thread, ms) in show.groupBy { it.thread }) {
            val last = ms.maxBy { it.createdAtMs }
            val open = Intent(context, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_THREAD, thread)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(context, thread.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(authorName(last.author))
                .setContentText(last.text.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(ms.sortedBy { it.createdAtMs }.joinToString("\n") { "${authorName(it.author)}: ${it.text.take(300)}" }))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(thread.hashCode(), n)
        }
    }
}
