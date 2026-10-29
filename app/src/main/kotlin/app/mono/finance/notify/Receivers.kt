package app.mono.finance.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Raised by an alarm: shows one reminder. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler(context).post(intent.getStringExtra("id") ?: return, intent.getStringExtra("title") ?: "MONO", intent.getStringExtra("body") ?: return)
    }
}

/** Alarms don't survive a restart: put the pending ones back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderScheduler(context).rearmFromStore()
    }
}
