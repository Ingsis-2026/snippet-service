package com.ingsis.snippet.streams

import com.ingsis.snippet.observability.Telemetry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled

/**
 * Cada minuto, cuánto trabajo espera en cada stream, como una fila de `SnippetStreamBacklog` en New Relic: la
 * saturación del linteo y del formateo en segundo plano.
 *
 * - `lag`: los mensajes que todavía no se le entregaron a ningún worker. Si crece, los workers no dan abasto.
 * - `pending`: los que se entregaron y no se confirmaron. Si crece, están fallando (language-service caído, por
 *   ejemplo) y se van a reintentar.
 * - `length`: todos los mensajes que guarda el stream, procesados o no, hasta [StreamProperties.maxLength].
 *
 * `lag` y `pending` salen de `XINFO GROUPS` para el grupo de [StreamProperties.group]. Si el stream o el grupo
 * todavía no existen (nadie encoló nada desde que arrancó Redis), no hay nada esperando.
 */
class StreamBacklogReporter(
    private val redis: StringRedisTemplate,
    private val streams: List<String>,
    private val properties: StreamProperties,
    private val telemetry: Telemetry,
) {
    @Scheduled(initialDelayString = "\${observability.initial-delay:1m}", fixedDelayString = "\${observability.interval:1m}")
    fun report() {
        for (stream in streams) {
            try {
                telemetry.recordEvent(BACKLOG_EVENT, backlogOf(stream))
            } catch (e: DataAccessException) {
                log.warn("No se pudo medir el stream $stream de Redis: ${e.message}")
            }
        }
    }

    private fun backlogOf(stream: String): Map<String, Any> {
        if (redis.hasKey(stream) != true) return mapOf("stream" to stream, "length" to 0L, "pending" to 0L, "lag" to 0L)
        val operations = redis.opsForStream<String, String>()
        val length = mapOf("stream" to stream, "length" to (operations.size(stream) ?: 0L))
        val group =
            operations.groups(stream).firstOrNull { it.groupName() == properties.group }
                ?: return length + mapOf("pending" to 0L, "lag" to (operations.size(stream) ?: 0L))
        // Redis no sabe el lag si se recortaron mensajes sin entregar (por el tope de largo): entonces no se informa.
        val lag = (group.raw["lag"] as? Number)?.toLong()
        return length + ("pending" to group.pendingCount()) + listOfNotNull(lag?.let { "lag" to it })
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(StreamBacklogReporter::class.java)
    }
}

const val BACKLOG_EVENT = "SnippetStreamBacklog"
