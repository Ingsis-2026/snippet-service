package com.ingsis.snippet

import com.ingsis.snippet.observability.Telemetry
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue

/** Reemplaza a New Relic en los tests: anota los eventos y los atributos que el servicio le reporta. */
@Primary
@Component
class RecordingTelemetry : Telemetry {
    data class Event(
        val type: String,
        val attributes: Map<String, Any>,
    )

    private val events = ConcurrentLinkedQueue<Event>()
    private val attributes = ConcurrentLinkedQueue<Pair<String, Number>>()

    override fun recordEvent(
        type: String,
        attributes: Map<String, Any>,
    ) {
        events += Event(type, attributes)
    }

    override fun addTransactionAttribute(
        key: String,
        value: Number,
    ) {
        attributes += key to value
    }

    fun events(type: String): List<Map<String, Any>> = events.filter { it.type == type }.map { it.attributes }

    fun attributes(key: String): List<Number> = attributes.filter { it.first == key }.map { it.second }

    fun clear() {
        events.clear()
        attributes.clear()
    }
}
