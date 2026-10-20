package com.ingsis.snippet.linting

import com.ingsis.snippet.rules.LintingRulesChangedEvent
import com.ingsis.snippet.snippets.SnippetCreatedEvent
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.SnippetService
import com.ingsis.snippet.snippets.SnippetUpdatedEvent
import com.ingsis.snippet.snippets.ValidityStatus
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/**
 * Los snippets que hay que lintear (US15). Encolar no lintea: de eso se encarga un worker aparte,
 * así quien cambió las reglas no espera a que se linteen todos sus snippets.
 */
fun interface LintQueue {
    fun enqueue(snippetIds: Collection<UUID>)
}

/** Lintea un snippet y guarda si cumple las reglas de su owner (US5, US15). */
@Service
class SnippetLinter(
    private val repository: SnippetRepository,
    private val snippets: SnippetService,
) {
    /**
     * Usa el código y las reglas como están al momento de lintear, no como estaban al encolarlo.
     * Por eso da igual cuántas veces o en qué orden llegue un snippet: el último linteo deja el
     * estado al día.
     *
     * Si el snippet ya no existe no hay nada que hacer. Si falla el linteo la excepción sube, para
     * que el worker no dé el mensaje por procesado y lo reintente.
     */
    fun lint(id: UUID) {
        val snippet = repository.findById(id).getOrNull() ?: return
        val status = if (snippets.errorsOf(snippet).isEmpty()) ValidityStatus.COMPLIANT else ValidityStatus.NOT_COMPLIANT
        repository.updateStatus(id, status)
    }
}

/** Decide qué snippets se encolan para lintear y cuándo. */
@Component
class LintScheduler(
    private val repository: SnippetRepository,
    private val queue: LintQueue,
) {
    /**
     * En la misma transacción que el cambio de reglas: o se guardan las reglas nuevas y los
     * snippets quedan `PENDING`, o no pasa ninguna de las dos.
     */
    @EventListener
    fun markPending(event: LintingRulesChangedEvent) {
        repository.markPendingByOwner(event.ownerId)
    }

    /** Recién cuando las reglas quedaron guardadas, para que el worker no lintee con las viejas. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun enqueueOwned(event: LintingRulesChangedEvent) {
        queue.enqueue(repository.findIdsByOwnerId(event.ownerId))
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun enqueueCreated(event: SnippetCreatedEvent) {
        queue.enqueue(listOf(event.snippet.id!!))
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun enqueueUpdated(event: SnippetUpdatedEvent) {
        queue.enqueue(listOf(event.snippet.id!!))
    }
}
