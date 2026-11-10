package com.ingsis.snippet

import com.ingsis.snippet.streams.BACKLOG_EVENT
import com.ingsis.snippet.streams.StreamBacklogReporter
import com.ingsis.snippet.streams.StreamProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroups
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** La saturación de las colas: cuánto espera en cada stream, con Redis simulado. */
class StreamBacklogReporterTest {
    private val redis = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val stream = mock(StreamOperations::class.java) as StreamOperations<String, String, String>
    private val telemetry = RecordingTelemetry()
    private val reporter = StreamBacklogReporter(redis, listOf("lint-requests"), StreamProperties(), telemetry)

    @BeforeEach
    fun setUp() {
        Mockito.`when`(redis.opsForStream<String, String>()).thenReturn(stream)
        Mockito.`when`(redis.hasKey("lint-requests")).thenReturn(true)
        Mockito.`when`(stream.size("lint-requests")).thenReturn(120L)
    }

    /** Lo que devuelve `XINFO GROUPS` para un grupo. */
    private fun groups(
        name: String,
        pending: Long,
        lag: Long?,
    ): XInfoGroups =
        XInfoGroups.fromList(
            listOf(
                listOf("name", name, "consumers", 1L, "pending", pending, "last-delivered-id", "0-0", "entries-read", 100L, "lag", lag),
            ),
        )

    private fun reported() = telemetry.events(BACKLOG_EVENT).single()

    @Test
    fun `informa el largo del stream y los mensajes sin entregar y sin confirmar del grupo`() {
        Mockito.`when`(stream.groups("lint-requests")).thenReturn(groups("snippet-service", pending = 4, lag = 16))

        reporter.report()

        assertEquals(mapOf("stream" to "lint-requests", "length" to 120L, "pending" to 4L, "lag" to 16L), reported())
    }

    @Test
    fun `si Redis no sabe el lag, no se informa`() {
        Mockito.`when`(stream.groups("lint-requests")).thenReturn(groups("snippet-service", pending = 0, lag = null))

        reporter.report()

        assertFalse("lag" in reported())
        assertEquals(0L, reported()["pending"])
    }

    @Test
    fun `si todavia no existe el grupo, todo el stream esta sin entregar`() {
        Mockito.`when`(stream.groups("lint-requests")).thenReturn(groups("otro-grupo", pending = 7, lag = 1))

        reporter.report()

        assertEquals(mapOf("stream" to "lint-requests", "length" to 120L, "pending" to 0L, "lag" to 120L), reported())
    }

    @Test
    fun `si todavia no existe el stream, no hay nada esperando`() {
        Mockito.`when`(redis.hasKey("lint-requests")).thenReturn(false)

        reporter.report()

        assertEquals(mapOf("stream" to "lint-requests", "length" to 0L, "pending" to 0L, "lag" to 0L), reported())
    }

    @Test
    fun `con Redis caido no reporta nada y no falla`() {
        Mockito.`when`(redis.hasKey("lint-requests")).thenThrow(RedisConnectionFailureException("Redis no responde"))

        reporter.report()

        assertEquals(emptyList(), telemetry.events(BACKLOG_EVENT))
    }
}
