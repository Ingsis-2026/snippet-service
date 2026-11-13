package com.ingsis.snippet.observability

import com.ingsis.snippet.snippets.SnippetCreatedEvent
import com.ingsis.snippet.snippets.SnippetDeletedEvent
import com.ingsis.snippet.snippets.SnippetSharedEvent
import com.ingsis.snippet.snippets.SnippetUpdatedEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * Cada cambio de un snippet es una fila de `SnippetActivity` en New Relic: qué pasó (`action`), con qué snippet y de
 * quién. Con eso se ve cuántos se crean y se borran por hora, y se distingue un snippet que se borró de uno que
 * desapareció (ver [InventoryReporter]).
 *
 * Se reporta recién cuando la transacción quedó guardada: un create que falla no es actividad.
 */
@Component
class SnippetActivityReporter(
    private val telemetry: Telemetry,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun created(event: SnippetCreatedEvent) =
        report(
            "created",
            event.snippet.id!!,
            event.snippet.ownerId,
            "language" to event.snippet.language,
            "version" to event.snippet.version,
        )

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun updated(event: SnippetUpdatedEvent) =
        report(
            "updated",
            event.snippet.id!!,
            event.snippet.ownerId,
            "language" to event.snippet.language,
            "version" to event.snippet.version,
        )

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun deleted(event: SnippetDeletedEvent) = report("deleted", event.snippetId, event.ownerId)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun shared(event: SnippetSharedEvent) = report("shared", event.snippetId, event.ownerId, "sharedWith" to event.userId)

    private fun report(
        action: String,
        snippetId: UUID,
        ownerId: String,
        vararg details: Pair<String, String>,
    ) = telemetry.recordEvent(
        ACTIVITY_EVENT,
        mapOf("action" to action, "snippetId" to snippetId.toString(), "ownerId" to ownerId) + details,
    )
}

const val ACTIVITY_EVENT = "SnippetActivity"
