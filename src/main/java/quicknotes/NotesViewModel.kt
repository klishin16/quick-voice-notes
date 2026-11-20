package app.quicknotes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = NotesStore(application.filesDir)
    private val pendingSaves = Channel<NotesData>(Channel.CONFLATED)

    var data by mutableStateOf(NotesData())
        private set
    var isLoaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    init {
        load()
        viewModelScope.launch {
            for (snapshot in pendingSaves) {
                try {
                    withContext(Dispatchers.IO) { store.save(snapshot) }
                    saveError = null
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    saveError = "Не удалось сохранить изменения"
                }
            }
        }
    }

    fun load() {
        loadError = null
        viewModelScope.launch {
            try {
                data = withContext(Dispatchers.IO) { store.load() }
                isLoaded = true
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                loadError = "Не удалось загрузить заметки"
            }
        }
    }

    private fun update(next: NotesData) {
        if (!isLoaded || next == data) return
        data = next
        pendingSaves.trySend(next)
    }

    fun addNotes(raw: String) = update(data.addNotes(raw))
    fun editNote(id: Long, text: String, categoryId: Long?) = update(data.editNote(id, text, categoryId))
    fun moveNote(id: Long, categoryId: Long?) = update(data.moveNote(id, categoryId))
    fun setDone(id: Long, done: Boolean) = update(data.copy(notes = data.notes.map {
        if (it.id == id) it.copy(done = done) else it
    }))
    fun deleteNote(id: Long) = update(data.copy(notes = data.notes.filterNot { it.id == id }))
    fun restoreNote(note: NoteItem) {
        if (data.notes.any { it.id == note.id }) return
        val restored = note.copy(categoryId = note.categoryId.takeIf { id -> data.categories.any { it.id == id } })
        update(data.copy(notes = data.notes + restored))
    }
    fun saveCategory(category: NoteCategory) = update(data.saveCategory(category))
    fun deleteCategory(id: Long) = update(data.deleteCategory(id))
    fun retrySave() {
        saveError = null
        pendingSaves.trySend(data)
    }
}
