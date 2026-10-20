package com.ingsis.snippet.formatting

import com.ingsis.snippet.linting.LintQueue
import com.ingsis.snippet.rules.FormattingRulesChangedEvent
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.SnippetService
import com.ingsis.snippet.snippets.ValidityStatus
import com.ingsis.snippet.storage.ContentStore
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/**
 * Los snippets que hay que formatear (US12). Encolar no formatea: de eso se encarga un worker
 * aparte, así quien cambió las reglas no espera a que se formateen todos sus snippets.
 */
fun interface FormatQueue {
    fun enqueue(snippetIds: Collection<UUID>)
}

/** Formatea un snippet con las reglas de su owner y guarda el código formateado (US12). */
@Service
class SnippetFormatter(
    private val repository: SnippetRepository,
    private val snippets: SnippetService,
    private val contents: ContentStore,
    private val lintQueue: LintQueue,
) {
    /**
     * Usa el código y las reglas como están al momento de formatear, no como estaban al encolarlo.
     * Formatear código ya formateado no lo cambia, así que da igual cuántas veces o en qué orden
     * llegue un snippet: el último formateo deja el código al día.
     *
     * Si el código cambió, el snippet vuelve a `PENDING` antes de guardarlo y se encola para
     * lintearlo, porque el linteo que tenía era del código anterior. Si no cambió, solo se encola
     * si ya estaba `PENDING`: así, si se cayó entre guardar el código y encolarlo, el reintento lo
     * encola igual aunque encuentre el código ya formateado.
     *
     * Si el owner actualizó el código mientras se formateaba, no se lo pisa: queda el suyo, que ya
     * se encoló para lintear al actualizarlo. Se lo vuelve a leer justo antes de guardar, después
     * de la llamada a language-service, que es lo que tarda.
     *
     * Si el snippet ya no existe no hay nada que hacer. Si falla algo la excepción sube, para que
     * el worker no dé el mensaje por procesado y lo reintente.
     */
    fun format(id: UUID) {
        val snippet = repository.findById(id).getOrNull() ?: return
        val original = contents.load(id)
        val formatted = snippets.formatted(snippet, original)
        if (formatted != original) {
            if (contents.load(id) != original) return
            repository.updateStatus(id, ValidityStatus.PENDING)
            contents.save(id, formatted)
        } else if (snippet.status != ValidityStatus.PENDING) {
            return
        }
        lintQueue.enqueue(listOf(id))
    }
}

/** Decide qué snippets se encolan para formatear y cuándo. */
@Component
class FormatScheduler(
    private val repository: SnippetRepository,
    private val queue: FormatQueue,
) {
    /** Recién cuando las reglas quedaron guardadas, para que el worker no formatee con las viejas. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun enqueueOwned(event: FormattingRulesChangedEvent) {
        queue.enqueue(repository.findIdsByOwnerId(event.ownerId))
    }
}
