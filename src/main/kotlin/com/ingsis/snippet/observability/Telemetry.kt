package com.ingsis.snippet.observability

import com.newrelic.api.agent.NewRelic
import org.springframework.stereotype.Component

/**
 * Lo que el servicio le cuenta a New Relic además de lo que mide solo el agente: eventos del negocio (se creó o se
 * borró un snippet, y de quién) y números del estado del sistema (cuántos snippets hay, cuánto espera en las colas).
 * Son los que contestan preguntas como «¿cuántos snippets había ayer?» o «¿se está atrasando el linteo?».
 *
 * Un evento llega a New Relic como una fila de la tabla [type], con sus [attributes] como columnas: se consulta con
 * NRQL (`SELECT ... FROM SnippetActivity`).
 */
interface Telemetry {
    fun recordEvent(
        type: String,
        attributes: Map<String, Any>,
    )

    /** Una columna más en la transacción del pedido que se está atendiendo (la tabla `Transaction`). */
    fun addTransactionAttribute(
        key: String,
        value: Number,
    )
}

/** Sin el agente de New Relic (en local, en los tests), las llamadas a su API no hacen nada. */
@Component
class NewRelicTelemetry : Telemetry {
    override fun recordEvent(
        type: String,
        attributes: Map<String, Any>,
    ) = NewRelic.getAgent().insights.recordCustomEvent(type, attributes)

    override fun addTransactionAttribute(
        key: String,
        value: Number,
    ) = NewRelic.addCustomParameter(key, value)
}
