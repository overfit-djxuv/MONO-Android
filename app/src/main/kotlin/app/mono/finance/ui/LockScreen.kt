package app.mono.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mono.finance.ui.theme.Mono

/** Shown instead of the app while it is locked. Nothing about your money is visible here. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val c = Mono.colors
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MONO", color = c.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Locked", color = c.inkSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
        PrimaryButton("Unlock", onClick = onUnlock)
    }
}
