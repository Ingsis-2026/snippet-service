package com.ingsis.snippet

import com.ingsis.snippet.observability.REQUEST_ID
import com.ingsis.snippet.observability.withLogContext
import com.ingsis.snippet.streams.RedisSnippetQueue
import com.ingsis.snippet.streams.RedisSnippetWorker
import com.ingsis.snippet.streams.SNIPPET_ID
import com.ingsis.snippet.streams.StreamProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.slf4j.MDC
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.PendingMessages
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** El id del pedido que encoló un snippet sigue en los logs del worker que lo procesa. */
class RequestIdStreamsTest {
    private val redis = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val stream = mock(StreamOperations::class.java) as StreamOperations<String, String, String>
    private val properties = StreamProperties(consumer = "replica-1")
    private val snippetId = UUID.randomUUID()

    init {
        Mockito.`when`(redis.opsForStream<String, String>()).thenReturn(stream)
    }

    @Test
    fun `el mensaje lleva el id del pedido que lo encolo`() {
        withLogContext(REQUEST_ID to "abc-123") { RedisSnippetQueue(redis, "lint-requests", properties).enqueue(listOf(snippetId)) }

        verify(stream).add(
            eq("lint-requests"),
            eq(mapOf(REQUEST_ID to "abc-123", SNIPPET_ID to snippetId.toString())),
            any(XAddOptions::class.java),
        )
    }

    @Test
    fun `el worker procesa el mensaje con el id del pedido y el snippet en el MDC y despues los saca`() {
        Mockito
            .`when`(stream.pending(anyString(), anyString(), any(), anyLong(), any()))
            .thenReturn(PendingMessages("snippet-service", emptyList()))
        val message =
            StreamRecords
                .newRecord()
                .`in`("lint-requests")
                .ofMap(mapOf(REQUEST_ID to "abc-123", SNIPPET_ID to snippetId.toString()))
                .withId(RecordId.of("1-0"))
        Mockito
            .`when`(
                stream.read(
                    Consumer.from("snippet-service", "replica-1"),
                    StreamReadOptions.empty().count(50),
                    StreamOffset.create("lint-requests", ReadOffset.lastConsumed()),
                ),
            ).thenReturn(listOf(message))
        var seen: Map<String, String>? = null

        RedisSnippetWorker(redis, "lint-requests", properties) { seen = MDC.getCopyOfContextMap() }.poll()

        assertEquals(
            mapOf(REQUEST_ID to "abc-123", SNIPPET_ID to snippetId.toString(), "stream" to "lint-requests", "messageId" to "1-0"),
            seen,
        )
        assertNull(MDC.get(REQUEST_ID))
    }
}
