package app.quicknotes

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

const val MAX_CATEGORIES = 5

data class NoteItem(
    val id: Long,
    val text: String,
    val done: Boolean = false,
    val categoryId: Long? = null,
)

data class NoteCategory(val id: Long, val name: String, val icon: String)

data class NotesData(
    val notes: List<NoteItem> = emptyList(),
    val categories: List<NoteCategory> = emptyList(),
) {
    fun visibleNotes(categoryId: Long?): List<NoteItem> = notes
        .filter { it.categoryId == categoryId }
        .sortedWith(compareBy<NoteItem> { it.done }.thenByDescending { it.id })

    fun addNotes(raw: String, now: Long = System.currentTimeMillis()): NotesData {
        val text = raw.split(Regex(",|\\s+и\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim().replaceFirstChar { char -> char.uppercase() } }
            .filter { it.isNotEmpty() }
        val firstId = maxOf(now, (notes.maxOfOrNull { it.id } ?: 0L) + 1)
        return copy(notes = text.mapIndexed { index, value -> NoteItem(firstId + index, value) } + notes)
    }

    fun editNote(id: Long, text: String, categoryId: Long?): NotesData {
        require(text.isNotBlank())
        require(categoryId == null || categories.any { it.id == categoryId })
        return copy(notes = notes.map {
            if (it.id == id) it.copy(text = text.trim(), categoryId = categoryId) else it
        })
    }

    fun moveNote(id: Long, categoryId: Long?): NotesData {
        require(categoryId == null || categories.any { it.id == categoryId })
        return copy(notes = notes.map { if (it.id == id) it.copy(categoryId = categoryId) else it })
    }

    fun saveCategory(category: NoteCategory): NotesData {
        val existing = categories.any { it.id == category.id }
        require(existing || categories.size < MAX_CATEGORIES)
        require(category.name.isNotBlank())
        require(categories.none { it.id != category.id && it.name.equals(category.name.trim(), true) })
        val trimmed = category.copy(name = category.name.trim())
        return copy(categories = if (existing) categories.map {
            if (it.id == category.id) trimmed else it
        } else categories + trimmed)
    }

    fun deleteCategory(id: Long): NotesData = copy(
        categories = categories.filterNot { it.id == id },
        notes = notes.map { if (it.categoryId == id) it.copy(categoryId = null) else it },
    )
}

/** Notes and categories share one atomic snapshot. */
class NotesStore(private val directory: File) {
    private val snapshot = File(directory, "quicknotes.properties")

    fun load(): NotesData {
        if (!snapshot.exists()) return NotesData()
        val values = Properties().apply { snapshot.inputStream().use { load(it) } }
        if (values.getProperty("version") != "2") throw IOException("Неизвестный формат заметок")
        fun required(key: String): String = values.getProperty(key)
            ?: throw IOException("Не удалось прочитать заметки")
        val categories = List(required("categories.count").toInt()) { index ->
            NoteCategory(
                required("category.$index.id").toLong(),
                required("category.$index.name"),
                required("category.$index.icon"),
            )
        }
        val notes = List(required("notes.count").toInt()) { index ->
            val categoryId = values.getProperty("note.$index.category")?.toLongOrNull()
            NoteItem(
                required("note.$index.id").toLong(),
                required("note.$index.text"),
                required("note.$index.done").toBooleanStrict(),
                categoryId.takeIf { id -> categories.any { it.id == id } },
            )
        }
        return NotesData(notes, categories)
    }

    fun save(data: NotesData) {
        val values = Properties().apply {
            setProperty("version", "2")
            setProperty("categories.count", data.categories.size.toString())
            data.categories.forEachIndexed { index, category ->
                setProperty("category.$index.id", category.id.toString())
                setProperty("category.$index.name", category.name)
                setProperty("category.$index.icon", category.icon)
            }
            setProperty("notes.count", data.notes.size.toString())
            data.notes.forEachIndexed { index, note ->
                setProperty("note.$index.id", note.id.toString())
                setProperty("note.$index.text", note.text)
                setProperty("note.$index.done", note.done.toString())
                note.categoryId?.let { setProperty("note.$index.category", it.toString()) }
            }
        }
        directory.mkdirs()
        val temporary = File(directory, "quicknotes.properties.tmp")
        temporary.outputStream().use { stream ->
            values.store(stream, "QuickNotes")
            stream.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), snapshot.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

}
