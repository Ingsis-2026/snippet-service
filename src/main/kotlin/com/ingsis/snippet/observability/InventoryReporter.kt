package com.ingsis.snippet.observability

import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.ValidityStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Cada minuto, cuántos snippets hay en la base, de cuántos usuarios y en qué estado, como una fila de
 * `SnippetInventory` en New Relic.
 *
 * Es lo que contesta «¿cuántos snippets había ayer?», y con [SnippetActivityReporter] avisa cuando desaparecen
 * snippets que nadie borró: si había 120, se crearon 3 y se borraron 2, tendría que haber 121.
 */
@Component
class InventoryReporter(
    private val repository: SnippetRepository,
    private val telemetry: Telemetry,
) {
    @Scheduled(initialDelayString = "\${observability.initial-delay:1m}", fixedDelayString = "\${observability.interval:1m}")
    fun report() {
        val byStatus = repository.countByStatus().associate { it.status to it.total }
        telemetry.recordEvent(
            INVENTORY_EVENT,
            mapOf(
                "total" to byStatus.values.sum(),
                "owners" to repository.countOwners(),
                "pending" to (byStatus[ValidityStatus.PENDING] ?: 0L),
                "compliant" to (byStatus[ValidityStatus.COMPLIANT] ?: 0L),
                "notCompliant" to (byStatus[ValidityStatus.NOT_COMPLIANT] ?: 0L),
                "invalid" to (byStatus[ValidityStatus.INVALID] ?: 0L),
            ),
        )
    }
}

const val INVENTORY_EVENT = "SnippetInventory"

/**
 * Prende las tareas programadas, como la de [InventoryReporter]. Los tests la apagan (`observability.enabled=false`),
 * igual que las de Redis y las del parser, y llaman a los reporters cuando quieren.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty("observability.enabled", havingValue = "true", matchIfMissing = true)
class ObservabilitySchedulingConfig
