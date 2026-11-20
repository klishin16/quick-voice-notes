package app.quicknotes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val categoryIcons: List<Pair<String, String>> = listOf(
    "shopping" to "Покупки", "work" to "Работа", "home" to "Дом", "ideas" to "Идеи",
    "health" to "Здоровье", "study" to "Учёба", "travel" to "Поездки", "star" to "Важное",
    "fitness" to "Спорт", "gift" to "Подарки", "food" to "Еда", "music" to "Музыка",
)

@Composable
fun NewNoteDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая заметка") },
        text = {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text("Текст заметки") }, minLines = 3, maxLines = 8,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text.trim()) }, enabled = text.isNotBlank()) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

fun categoryIcon(key: String): ImageVector = when (key) {
    "shopping" -> Icons.Default.ShoppingCart
    "work" -> Icons.Default.Work
    "home" -> Icons.Default.Home
    "ideas" -> Icons.Default.Lightbulb
    "health" -> Icons.Default.Favorite
    "study" -> Icons.Default.School
    "travel" -> Icons.Default.Flight
    "fitness" -> Icons.Default.FitnessCenter
    "gift" -> Icons.Default.CardGiftcard
    "food" -> Icons.Default.Restaurant
    "music" -> Icons.Default.MusicNote
    else -> Icons.Default.Star
}

@Composable
fun CategoriesScreen(
    categories: List<NoteCategory>, onSave: (NoteCategory) -> Unit,
    onDelete: (NoteCategory) -> Unit, modifier: Modifier = Modifier,
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Выберите значки, которые легко узнавать в списке заметок.",
            Modifier.padding(top = 8.dp, bottom = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            if (categories.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(40.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Пока нет категорий", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Добавьте первую, чтобы распределять заметки",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(categories, key = { it.id }) { category ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(categoryIcon(category.icon), null, tint = MaterialTheme.colorScheme.primary)
                        Text(category.name, Modifier.weight(1f).padding(start = 16.dp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { editingId = category.id; editorOpen = true }) {
                            Icon(Icons.Default.Edit, "Редактировать категорию ${category.name}")
                        }
                        IconButton(onClick = { deletingId = category.id }) {
                            Icon(Icons.Default.Delete, "Удалить категорию ${category.name}")
                        }
                    }
                }
            }
        }
        Button(
            onClick = { editingId = null; editorOpen = true },
            enabled = categories.size < MAX_CATEGORIES,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).heightIn(min = 52.dp),
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(if (categories.size < MAX_CATEGORIES) "Добавить категорию" else "Созданы все $MAX_CATEGORIES категорий")
        }
    }
    if (editorOpen) {
        CategoryEditorDialog(
            category = categories.find { it.id == editingId }, categories = categories,
            onDismiss = { editorOpen = false },
            onSave = { name, icon ->
                val id = editingId ?: maxOf(System.currentTimeMillis(), (categories.maxOfOrNull { it.id } ?: 0L) + 1)
                onSave(NoteCategory(id, name, icon))
                editorOpen = false
            },
        )
    }
    categories.find { it.id == deletingId }?.let { category ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            icon = { Icon(Icons.Default.FolderOpen, null) },
            title = { Text("Удалить «${category.name}»?") },
            text = { Text("Все заметки из этой категории вернутся во входящие.") },
            confirmButton = {
                TextButton(onClick = { onDelete(category); deletingId = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun CategoryEditorDialog(
    category: NoteCategory?, categories: List<NoteCategory>,
    onDismiss: () -> Unit, onSave: (String, String) -> Unit,
) {
    var name by rememberSaveable(category?.id) { mutableStateOf(category?.name ?: "") }
    var icon by rememberSaveable(category?.id) { mutableStateOf(category?.icon ?: "shopping") }
    val duplicate = categories.any { it.id != category?.id && it.name.equals(name.trim(), true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (category == null) "Новая категория" else "Редактировать категорию") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(32) }, label = { Text("Название") },
                    singleLine = true, isError = duplicate, modifier = Modifier.fillMaxWidth(),
                    supportingText = if (duplicate) ({ Text("Такая категория уже есть") }) else null,
                )
                Text("Значок", style = MaterialTheme.typography.labelLarge)
                categoryIcons.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { (key, label) ->
                            FilledIconToggleButton(
                                checked = icon == key, onCheckedChange = { icon = key },
                                modifier = Modifier.size(48.dp),
                                colors = IconButtonDefaults.filledIconToggleButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) { Icon(categoryIcon(key), label) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(
            onClick = { onSave(name.trim(), icon) }, enabled = name.isNotBlank() && !duplicate,
        ) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun NoteEditorDialog(
    note: NoteItem, categories: List<NoteCategory>, onDismiss: () -> Unit,
    onSave: (String, Long?) -> Unit, onDelete: () -> Unit,
) {
    var text by rememberSaveable(note.id) { mutableStateOf(note.text) }
    var categoryId by rememberSaveable(note.id) { mutableStateOf(note.categoryId) }
    var menuOpen by remember { mutableStateOf(false) }
    val category = categories.find { it.id == categoryId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать заметку") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Текст") }, minLines = 2, maxLines = 5)
                Text("Категория", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(category?.let { categoryIcon(it.icon) } ?: Icons.Default.Inbox, null)
                        Text(category?.name ?: "Входящие", Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Входящие") },
                            leadingIcon = { Icon(Icons.Default.Inbox, null) },
                            onClick = { categoryId = null; menuOpen = false })
                        categories.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) },
                                leadingIcon = { Icon(categoryIcon(item.icon), null) },
                                onClick = { categoryId = item.id; menuOpen = false })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim(), categoryId) }, enabled = text.isNotBlank()) {
            Text("Сохранить")
        } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
