package app.mono.finance

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.mono.domain.Money
import app.mono.domain.QuickAction
import app.mono.finance.data.DemoData
import app.mono.finance.security.Authenticator
import app.mono.finance.security.LocalAuthenticate
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.LockScreen
import app.mono.finance.ui.MainShell
import app.mono.finance.ui.theme.MonoTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val vm: MonoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: `adb shell am start -n app.mono.finance/.MainActivity --ez demo true` loads sample records.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra("demo", false)) {
            lifecycleScope.launch { DemoData.load((application as MonoApp).db.dao()) }
        }
        handle(intent)
        setContent {
            val money by vm.moneyContext.collectAsState()
            val lockEnabled by vm.lockEnabled.collectAsState()
            val locked by vm.locked.collectAsState()
            // Set before any screen reads Money.format, and provided as a local so screens redraw when it changes.
            Money.context = money
            // While the lock is on, the app's picture is hidden from recent apps and screenshots.
            LaunchedEffect(lockEnabled) {
                if (lockEnabled == true) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            CompositionLocalProvider(
                LocalMoneyContext provides money,
                LocalAuthenticate provides { title, done -> Authenticator.prompt(this@MainActivity, title, done) },
            ) {
                MonoTheme {
                    if (locked) {
                        LockScreen(onUnlock = ::unlock)
                        LaunchedEffect(Unit) { unlock() }
                    } else MainShell(vm)
                }
            }
        }
    }

    private fun unlock() {
        if (vm.unlocking) return
        vm.unlocking = true
        Authenticator.prompt(this, "Unlock MONO") { ok -> vm.unlocking = false; if (ok) vm.unlock() }
    }

    override fun onStop() {
        super.onStop()
        vm.lockIfEnabled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** A launcher shortcut asks for an action; it waits in the ViewModel until the app is unlocked. */
    private fun handle(intent: Intent?) {
        val action = intent?.action ?: return
        QuickAction.of(action.removePrefix(ACTION_PREFIX))?.let { vm.request(it) }
    }

    companion object { const val ACTION_PREFIX = "app.mono.finance.action." }
}
