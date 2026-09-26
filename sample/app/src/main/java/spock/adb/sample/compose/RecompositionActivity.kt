package spock.adb.sample.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A fixture for Recomposition Tracking and `android_get_recomposition_counts`.
 *
 * While "Ticking" is on, [TickingCounter] recomposes ten times a second and nothing else does, so
 * a capture should rank it far above every other composable. [StaticLabel] never reads the tick,
 * so it should be absent or near zero. [TapCounter] recomposes once per tap: tap it five times
 * during a capture and it should report five.
 *
 * Counts need `androidx.compose.runtime:runtime-tracing` in the app, which this sample has.
 */
class RecompositionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SampleTheme { RecompositionScreen() } }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RecompositionScreen() {
    var ticking by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StaticLabel()
        Switch(checked = ticking, onCheckedChange = { ticking = it }, modifier = Modifier.testTag("recomp_ticking"))
        Text(if (ticking) "Ticking" else "Paused")
        TickingCounter(ticking)
        TapCounter()
    }
}

@Composable
private fun StaticLabel() {
    Text("Recomposition fixture", modifier = Modifier.testTag("recomp_static"))
}

@Composable
private fun TickingCounter(ticking: Boolean) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ticking) {
        while (ticking) {
            delay(100)
            tick++
        }
    }
    Text("Tick $tick", modifier = Modifier.testTag("recomp_tick"))
}

@Composable
private fun TapCounter() {
    var taps by remember { mutableIntStateOf(0) }
    Button(onClick = { taps++ }, modifier = Modifier.testTag("recomp_tap")) { Text("Tapped $taps") }
}
