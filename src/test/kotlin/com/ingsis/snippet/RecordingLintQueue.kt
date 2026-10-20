package com.ingsis.snippet

import com.ingsis.snippet.linting.LintQueue
import com.ingsis.snippet.linting.SnippetLinter
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Reemplaza al stream de Redis en los tests, para que no necesiten Docker. Solo anota lo que se
 * encola: nada se lintea hasta que el test llama a [processAll], igual que en producción nada se
 * lintea mientras se atiende el pedido.
 */
@Component
class RecordingLintQueue(
    private val linter: SnippetLinter,
) : LintQueue {
    private val queued = ConcurrentLinkedQueue<UUID>()

    override fun enqueue(snippetIds: Collection<UUID>) {
        queued += snippetIds
    }

    fun queued(): List<UUID> = queued.toList()

    fun clear() = queued.clear()

    /** Lintea todo lo encolado, como haría el worker. */
    fun processAll() {
        while (true) linter.lint(queued.poll() ?: return)
    }
}
