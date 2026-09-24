package spock.adb.sample.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/**
 * A Compose screen for the UI Inspector and the element-addressed MCP tools (tap_element,
 * input_text_into_element, assert_text…).
 *
 * The switch at the top turns `testTagsAsResourceId` on and off, so the inspector's "Compose test
 * tags are not exposed" banner can be seen both ways. Two elements are deliberately inaccessible
 * — an unlabelled clickable box and a tiny touch target — for the Accessibility Audit to find.
 *
 * The app bar is Compose too, so the screen stays pure Compose. New text here must not contain
 * any fixture's text, and the tiny target's is a single "x": no word with that letter in it.
 */
class ComposeInspectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Back in the app bar does what the other screens' toolbar Up arrow does.
        setContent { SampleTheme { InspectorScreen(onBack = { onBackPressedDispatcher.onBackPressed() }) } }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun InspectorScreen(onBack: () -> Unit) {
    var exposeTags by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = exposeTags },
        topBar = {
            TopAppBar(
                title = { Text("Compose inspector") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Expose test tags", modifier = Modifier.weight(1f))
                    Switch(checked = exposeTags, onCheckedChange = { exposeTags = it }, modifier = Modifier.testTag("expose_tags_switch"))
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth().testTag("name_field"),
                    )
                    Text(if (name.isEmpty()) "Hello, stranger" else "Hello, $name", modifier = Modifier.testTag("greeting"))
                    Row {
                        Checkbox(checked = agreed, onCheckedChange = { agreed = it }, modifier = Modifier.testTag("terms_checkbox"))
                        Text("I agree to the terms", modifier = Modifier.padding(top = 12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { taps++ }, enabled = agreed, modifier = Modifier.testTag("submit_button")) {
                            Text("Submit")
                        }
                        Text("Submitted $taps time(s)", modifier = Modifier.testTag("submit_count"))
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Deliberate accessibility problems:", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Clickable with no label: a screen reader announces nothing.
                        Box(Modifier.size(48.dp).background(Color(0xFF7986CB)).clickable { taps++ }.testTag("unlabelled_box"))
                        // Clickable, labelled, but far below the 48dp minimum touch target.
                        Box(Modifier.size(16.dp).background(Color(0xFFE57373)).clickable { taps++ }.testTag("tiny_target")) {
                            Text("x")
                        }
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("item_list")) {
                    items((1..30).toList()) { index ->
                        Text("Item $index", modifier = Modifier.padding(8.dp).testTag("item_$index"))
                    }
                }
            }
        }
    }
}
