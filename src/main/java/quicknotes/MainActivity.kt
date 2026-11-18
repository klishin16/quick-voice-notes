package app.quicknotes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale

data class NoteItem(
    val id: Long,
    val text: String,
    val done: Boolean,
)

class MainActivity : ComponentActivity() {
    private var launchVoiceOnStart = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_ASSIST) {
            launchVoiceOnStart.value = true
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NotesScreen(
                        context = this,
                        triggerVoice = launchVoiceOnStart.value,
                        onVoiceTriggered = { launchVoiceOnStart.value = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) {
            launchVoiceOnStart.value = true
        }
    }
}

@Composable
fun NotesScreen(context: Context, triggerVoice: Boolean, onVoiceTriggered: () -> Unit) {
    var items by remember { mutableStateOf(loadItems(context)) }
    var inputText by remember { mutableStateOf("") }

    fun updateItems(newList: List<NoteItem>) {
        items = newList
        saveItems(context, newList)
    }

    fun addFromText(raw: String) {
        val parts = raw.split(Regex(",|\\s+и\\s+"))
                .map { it.trim().replaceFirstChar { c -> c.uppercase() } }
                .filter { it.isNotEmpty() }


        if (parts.isEmpty()) return

        val now = System.currentTimeMillis()
        val added = parts.mapIndexed { idx, text ->
            NoteItem(
                id = now + idx,
                text = text,
                done = false,
            )
        }
        updateItems(added + items)
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                addFromText(spoken)
            }
        }
    }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Слушаю..."
            )
        }
        voiceLauncher.launch(intent)
    }

    LaunchedEffect(triggerVoice) {
        if (triggerVoice) {
            onVoiceTriggered()
            startVoice()
        }
    }

    Scaffold(
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { startVoice() },
                modifier = Modifier.padding(bottom = 32.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Голосовой ввод"
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Новая заметка...")
                    },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    addFromText(inputText)
                    inputText = ""
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить")
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                updateItems(items.map {
                                    if (it.id == item.id) it.copy(done = !it.done) else it
                                })
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item.done,
                            onCheckedChange = { checked ->
                                updateItems(items.map {
                                    if (it.id == item.id) it.copy(done = checked) else it
                                })
                            }
                        )
                        Text(
                            text = item.text,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        IconButton(onClick = {
                            updateItems(items.filter { it.id != item.id })
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            }
        }
    }
}

fun saveItems(context: Context, list: List<NoteItem>) {
    val file = File(context.filesDir, "notes.txt")
    val serialized = list.joinToString("\n") { item ->
        val safeText = item.text.replace("\n", "\\n")
        "${item.id}|${if (item.done) 1 else 0}|1|$safeText"
    }
    file.writeText(serialized)
}

fun loadItems(context: Context): List<NoteItem> {
    val file = File(context.filesDir, "notes.txt")
    if (!file.exists()) return emptyList()
    return file.readLines().mapNotNull { line ->
        val parts = line.split("|", limit = 4)
        if (parts.size == 4) {
            NoteItem(
                id = parts[0].toLongOrNull() ?: 0L,
                done = parts[1] == "1",
                text = parts[3].replace("\\n", "\n")
            )
        } else null
    }
}
