package com.ingsis.snippet

import com.ingsis.snippet.formatting.FormatQueue
import com.ingsis.snippet.formatting.SnippetFormatter
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Reemplaza al stream de formateo de Redis en los tests, igual que [RecordingLintQueue] al de
 * linteo: solo anota lo que se encola, y nada se formatea hasta que el test llama a [processAll].
 */
@Component
class RecordingFormatQueue(
    private val formatter: SnippetFormatter,
) : FormatQueue {
    private val queued = ConcurrentLinkedQueue<UUID>()

    override fun enqueue(snippetIds: Collection<UUID>) {
        queued += snippetIds
    }

    fun queued(): List<UUID> = queued.toList()

    fun clear() = queued.clear()

    /** Formatea todo lo encolado, como haría el worker. */
    fun processAll() {
        while (true) formatter.format(queued.poll() ?: return)
    }
}
