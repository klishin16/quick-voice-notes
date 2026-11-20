package app.quicknotes

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var launchVoiceOnStart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchVoiceOnStart = savedInstanceState == null && intent?.action == Intent.ACTION_ASSIST
        val model = ViewModelProvider(this)[NotesViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NotesScreen(model, launchVoiceOnStart) { launchVoiceOnStart = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) launchVoiceOnStart = true
    }
}

private data class NoteDrag(val note: NoteItem, val position: Offset)
private data class NoteSection(
    val category: NoteCategory?, val notes: List<NoteItem>, val completed: Boolean = false,
) {
    val key: String get() = if (completed) "completed" else category?.id?.toString() ?: "inbox"
    val title: String get() = if (completed) "Готово" else category?.name ?: "Нераспределённые"
}

private suspend fun SnackbarHostState.showTimedSnackbar(
    message: String, actionLabel: String? = null,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite)
}

@Composable
fun NotesScreen(model: NotesViewModel, triggerVoice: Boolean, onVoiceTriggered: () -> Unit) {
    val data = model.data
    var showCategories by rememberSaveable { mutableStateOf(false) }
    var showNewNote by rememberSaveable { mutableStateOf(false) }
    var editingNoteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var drag by remember { mutableStateOf<NoteDrag?>(null) }
    val targets = remember { mutableStateMapOf<String, Rect>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val hoveredTarget = drag?.let { current ->
        targets.entries.firstOrNull { it.value.contains(current.position) }?.key
    }

    fun addNotes(raw: String) {
        model.addNotes(raw)
        showCategories = false
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun deleteNote(note: NoteItem) {
        model.deleteNote(note.id)
        scope.launch {
            if (snackbar.showTimedSnackbar("Заметка удалена", "Отменить") == SnackbarResult.ActionPerformed) {
                model.restoreNote(note)
            }
        }
    }

    fun finishDrag() {
        val current = drag
        // Read live geometry on pointer-up: a remembered callback can retain an old hover value.
        val target = current?.let { value ->
            targets.entries.firstOrNull { it.value.contains(value.position) }?.key
        }
        drag = null
        if (current == null || target == null) return
        val categoryId = target.toLongOrNull()
        if (categoryId == current.note.categoryId) return
        model.moveNote(current.note.id, categoryId)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val destination = model.data.categories.find { it.id == categoryId }?.name ?: "Нераспределённые"
            if (snackbar.showTimedSnackbar("Перемещено: $destination", "Отменить") == SnackbarResult.ActionPerformed) {
                val previous = current.note.categoryId.takeIf { id -> model.data.categories.any { it.id == id } }
                model.moveNote(current.note.id, previous)
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(::addNotes)
        }
    }
    fun startVoice() {
        try {
            voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Слушаю...")
            })
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showTimedSnackbar("На устройстве нет приложения для голосового ввода") }
        }
    }

    LaunchedEffect(triggerVoice, model.isLoaded) {
        if (triggerVoice && model.isLoaded) {
            onVoiceTriggered()
            startVoice()
        }
    }
    LaunchedEffect(model.saveError) {
        model.saveError?.let {
            if (snackbar.showTimedSnackbar(it, "Повторить") == SnackbarResult.ActionPerformed) {
                model.retrySave()
            }
        }
    }
    val notification = snackbar.currentSnackbarData
    LaunchedEffect(notification) {
        if (notification != null) {
            delay(3_000)
            notification.dismiss()
        }
    }
    BackHandler(showCategories || drag != null) {
        when {
            drag != null -> drag = null
            showCategories -> showCategories = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showCategories) {
                        IconButton(onClick = { showCategories = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            if (showCategories) "Категории" else "Заметки",
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (showCategories) {
                            Text(
                                "${data.categories.size} из $MAX_CATEGORIES",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!showCategories) {
                        IconButton(onClick = { showNewNote = true }, enabled = model.isLoaded) {
                            Icon(Icons.Default.Add, "Добавить заметку")
                        }
                        IconButton(onClick = { drag = null; showCategories = true }, enabled = model.isLoaded) {
                            Icon(Icons.Default.Settings, "Управление категориями")
                        }
                    }
                }
            },
        ) { padding ->
            when {
                !model.isLoaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    if (model.loadError == null) CircularProgressIndicator() else Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(model.loadError.orEmpty())
                        Button(onClick = model::load) { Text("Повторить") }
                    }
                }
                showCategories -> CategoriesScreen(
                    data.categories,
                    onSave = model::saveCategory,
                    onDelete = { category -> model.deleteCategory(category.id) },
                    modifier = Modifier.padding(padding),
                )
                else -> {
                    val sections = remember(data) {
                        buildList {
                            add(NoteSection(null, data.visibleNotes(null).filterNot { it.done }))
                            data.categories.forEach { category ->
                                add(NoteSection(category, data.visibleNotes(category.id).filterNot { it.done }))
                            }
                            val completed = data.notes.filter { it.done }.sortedByDescending { it.id }
                            if (completed.isNotEmpty()) add(NoteSection(null, completed, completed = true))
                        }
                    }
                    val sectionPositions = remember(sections) {
                        var index = 0
                        sections.associate { section ->
                            val position = index
                            index += 1 + maxOf(1, section.notes.size)
                            section.key to position
                        }
                    }
                    val currentSectionKey by remember(sectionPositions, listState) {
                        derivedStateOf {
                            sectionPositions.entries.lastOrNull { it.value <= listState.firstVisibleItemIndex }?.key
                        }
                    }
                    val hasRail = data.categories.isNotEmpty()
                    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                        var contentOrigin by remember { mutableStateOf(Offset.Zero) }
                        BoxWithConstraints(
                            Modifier.weight(1f).fillMaxWidth()
                                .onGloballyPositioned { contentOrigin = it.positionInRoot() },
                        ) {
                            val contentWidth = maxWidth
                            val contentHeight = maxHeight
                            val density = LocalDensity.current
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = listState,
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 144.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                userScrollEnabled = drag == null,
                            ) {
                                sections.forEach { section ->
                                    item(key = "header:${section.key}") {
                                        Row(
                                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp,
                                                top = if (section.key == "inbox") 8.dp else 24.dp, bottom = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(if (section.completed) Icons.Default.DoneAll else
                                                section.category?.let { categoryIcon(it.icon) } ?: Icons.Default.Inbox,
                                                null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                            Text(section.title, Modifier.weight(1f).padding(horizontal = 10.dp),
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(section.notes.size.toString(), style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (section.notes.isEmpty()) {
                                        item(key = "empty:${section.key}") {
                                            Text(
                                                if (section.category == null) "Нажмите + или микрофон, чтобы добавить заметку"
                                                else "Пока нет заметок",
                                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    } else {
                                        items(section.notes, key = { it.id }) { note ->
                                            Column(Modifier.animateItem()) {
                                                NoteRow(
                                                    note = note,
                                                    dragging = drag?.note?.id == note.id,
                                                    canDrag = hasRail,
                                                    onDone = { model.setDone(note.id, it) },
                                                    onEdit = { editingNoteId = note.id },
                                                    onDelete = { deleteNote(note) },
                                                    onDragStart = { position ->
                                                        focus.clearFocus()
                                                        keyboard?.hide()
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        drag = NoteDrag(note, position)
                                                    },
                                                    onDragMove = { amount -> drag = drag?.let { it.copy(position = it.position + amount) } },
                                                    onDragEnd = ::finishDrag,
                                                    onDragCancel = { drag = null },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (hasRail) {
                                val dragging = drag != null
                                val targetCount = data.categories.size + if (dragging) 1 else 0
                                val targetHeight = if (dragging) {
                                    ((contentHeight - 24.dp - 8.dp * (targetCount - 1)) / targetCount)
                                        .coerceIn(48.dp, 80.dp)
                                } else 48.dp
                                Column(
                                    Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).zIndex(4f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.End,
                                ) {
                                    if (dragging) {
                                        CategoryTarget("inbox", "Нераспределённые", Icons.Default.Inbox,
                                            currentSectionKey == "inbox", true,
                                            hoveredTarget == "inbox", targetHeight, targets) {}
                                    }
                                    data.categories.forEach { category ->
                                        val key = category.id.toString()
                                        CategoryTarget(key, category.name, categoryIcon(category.icon),
                                            currentSectionKey == key, dragging,
                                            hoveredTarget == key, targetHeight, targets) {
                                            scope.launch { listState.animateScrollToItem(sectionPositions.getValue(key)) }
                                        }
                                    }
                                }
                            }
                            drag?.let { current ->
                                val ghostWidth = minOf(240.dp, contentWidth - 24.dp)
                                val widthPx = with(density) { ghostWidth.toPx() }
                                val maxX = with(density) { (contentWidth - ghostWidth).toPx() }
                                val maxY = with(density) { (contentHeight - 80.dp).toPx() }.coerceAtLeast(0f)
                                Surface(
                                    modifier = Modifier.width(ghostWidth).offset {
                                        val local = current.position - contentOrigin
                                        IntOffset((local.x - widthPx / 2).coerceIn(0f, maxX).roundToInt(),
                                            (local.y - 72.dp.toPx()).coerceIn(0f, maxY).roundToInt())
                                    }.zIndex(3f),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shadowElevation = 12.dp,
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.DragIndicator, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(current.note.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showCategories && model.isLoaded) {
                FloatingActionButton(
                    onClick = ::startVoice,
                    modifier = Modifier.padding(bottom = 12.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                ) { Icon(Icons.Default.Mic, "Голосовой ввод") }
            }
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                SnackbarHost(snackbar)
            }
        }
    }
    if (showNewNote) {
        NewNoteDialog(
            onDismiss = { showNewNote = false },
            onAdd = { text -> addNotes(text); showNewNote = false },
        )
    }
    data.notes.find { it.id == editingNoteId }?.let { note ->
        NoteEditorDialog(note, data.categories,
            onDismiss = { editingNoteId = null },
            onSave = { text, categoryId -> model.editNote(note.id, text, categoryId); editingNoteId = null },
            onDelete = { deleteNote(note); editingNoteId = null })
    }
}

@Composable
private fun NoteRow(
    note: NoteItem, dragging: Boolean, canDrag: Boolean,
    onDone: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onDragStart: (Offset) -> Unit, onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit, onDragCancel: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDragMove)
    val finish by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (note.done) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().alpha(if (dragging) 0.3f else 1f)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(note.id, canDrag) {
                if (canDrag) detectDragGesturesAfterLongPress(
                    onDragStart = { start(origin + it) },
                    onDragEnd = { finish() }, onDragCancel = { cancel() },
                    onDrag = { change, amount -> change.consume(); move(amount) },
                )
            },
    ) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = note.done, onCheckedChange = onDone)
            Text(note.text, Modifier.weight(1f).padding(vertical = 4.dp),
                textDecoration = if (note.done) TextDecoration.LineThrough else null,
                color = if (note.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = if (note.done) onDelete else onEdit) {
                Icon(if (note.done) Icons.Default.Delete else Icons.Default.Edit,
                    if (note.done) "Удалить заметку" else "Редактировать заметку")
            }
        }
    }
}

@Composable
private fun CategoryTarget(
    key: String, name: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean, dragging: Boolean, hovered: Boolean,
    height: androidx.compose.ui.unit.Dp,
    targets: MutableMap<String, Rect>, onClick: () -> Unit,
) {
    val width by animateDpAsState(if (dragging) 192.dp else 48.dp, label = "categoryWidth")
    val animatedHeight by animateDpAsState(height, label = "categoryHeight")
    DisposableEffect(key) { onDispose { targets.remove(key) } }
    Surface(
        onClick = onClick,
        enabled = !dragging,
        modifier = Modifier.width(width).height(animatedHeight)
            .onGloballyPositioned { targets[key] = it.boundsInRoot() },
        shape = RoundedCornerShape(if (dragging) 20.dp else 16.dp),
        color = when {
            hovered -> MaterialTheme.colorScheme.primary
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (hovered) MaterialTheme.colorScheme.onPrimary else
            if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shadowElevation = if (hovered) 8.dp else 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (dragging) Arrangement.SpaceBetween else Arrangement.Center) {
            if (dragging) Text(name, Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Icon(icon, name, Modifier.size(if (dragging) 32.dp else 24.dp))
        }
    }
}
