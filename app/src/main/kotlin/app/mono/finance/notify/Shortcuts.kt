package app.mono.finance.notify

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.mono.domain.QuickActionSettings
import app.mono.finance.MainActivity
import app.mono.finance.R

/** The icon's long-press menu. Each shortcut only opens the app: it never shows or speaks an amount, so the lock still guards your data. */
object Shortcuts {
    fun publish(context: Context, raw: String?) {
        val shortcuts = QuickActionSettings.shown(raw).mapIndexed { index, a ->
            ShortcutInfoCompat.Builder(context, a.raw).setShortLabel(a.title).setLongLabel(a.title)
                .setRank(index).setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_PREFIX + a.raw)).build()
        }
        try { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) } catch (e: Exception) { android.util.Log.w("MONO", "shortcuts", e) }
    }

}
