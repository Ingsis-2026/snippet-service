package com.ingsis.snippet

import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.storage.ContentStore
import com.ingsis.snippet.storage.StorageException
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reemplaza a Azurite en los tests, para que no necesiten Docker. Spring lo levanta solo porque
 * está en el paquete de la aplicación, y le gana a [com.ingsis.snippet.storage.BlobContentStore]
 * por ser `@Primary`.
 */
@Primary
@Component
class InMemoryContentStore : ContentStore {
    private val contents = ConcurrentHashMap<UUID, String>()

    override fun save(
        id: UUID,
        content: String,
    ) {
        contents[id] = content
    }

    /** Falla igual que [com.ingsis.snippet.storage.BlobContentStore] cuando el código no está. */
    override fun load(id: UUID): String =
        contents[id] ?: throw StorageException("No se pudo leer el código del snippet $id", NoSuchElementException())
}

/** Guarda un snippet como lo hace SnippetService: la metadata en la base y el código en el store. */
fun SnippetRepository.save(
    snippet: Snippet,
    content: String,
    contents: ContentStore,
): Snippet = save(snippet).also { contents.save(it.id!!, content) }
