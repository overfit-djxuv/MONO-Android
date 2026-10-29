package app.mono.finance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.mono.finance.data.DemoData
import kotlinx.coroutines.launch
import app.mono.domain.Money
import app.mono.finance.ui.LocalMoneyContext
import app.mono.finance.ui.MainShell
import app.mono.finance.ui.theme.MonoTheme

class MainActivity : ComponentActivity() {
    private val vm: MonoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: `adb shell am start -n app.mono.finance/.MainActivity --ez demo true` loads sample records.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra("demo", false)) {
            lifecycleScope.launch { DemoData.load((application as MonoApp).db.dao()) }
        }
        setContent {
            val money by vm.moneyContext.collectAsState()
            // Set before any screen reads Money.format, and provided as a local so screens redraw when it changes.
            Money.context = money
            CompositionLocalProvider(LocalMoneyContext provides money) {
                MonoTheme { MainShell(vm) }
            }
        }
    }
}
