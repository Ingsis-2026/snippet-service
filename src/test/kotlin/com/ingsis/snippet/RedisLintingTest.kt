package com.ingsis.snippet

import com.ingsis.snippet.linting.LintingProperties
import com.ingsis.snippet.linting.RedisLintQueue
import com.ingsis.snippet.linting.RedisLintWorker
import com.ingsis.snippet.linting.RedisLintingConfig
import com.ingsis.snippet.linting.SNIPPET_ID
import com.ingsis.snippet.linting.SnippetLinter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.data.domain.Range
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.PendingMessage
import org.springframework.data.redis.connection.stream.PendingMessages
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.client.ResourceAccessException
import java.time.Duration
import java.util.UUID
import kotlin.test.assertIs

/** La cola de US15 sobre un stream de Redis, con Redis simulado. */
class RedisLintingTest {
    private val redis = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val stream = mock(StreamOperations::class.java) as StreamOperations<String, String, String>
    private val linter = mock(SnippetLinter::class.java)
    private val properties = LintingProperties(consumer = "replica-1", maxAttempts = 3)
    private val worker = RedisLintWorker(redis, linter, properties)
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        Mockito.`when`(redis.opsForStream<String, String>()).thenReturn(stream)
        Mockito
            .`when`(
                stream.pending(anyString(), anyString(), any(), anyLong(), any()),
            ).thenReturn(PendingMessages("snippet-service", emptyList()))
    }

    private fun record(
        id: String,
        snippetId: String,
    ): MapRecord<String, String, String> =
        StreamRecords
            .newRecord()
            .`in`("lint-requests")
            .ofMap(mapOf(SNIPPET_ID to snippetId))
            .withId(RecordId.of(id))

    private fun newMessages(vararg records: MapRecord<String, String, String>) {
        Mockito
            .`when`(
                stream.read(
                    Consumer.from("snippet-service", "replica-1"),
                    StreamReadOptions.empty().count(50),
                    StreamOffset.create("lint-requests", ReadOffset.lastConsumed()),
                ),
            ).thenReturn(records.toList())
    }

    private fun stuck(
        id: String,
        deliveries: Long,
    ) {
        val message = PendingMessage(RecordId.of(id), Consumer.from("snippet-service", "replica-2"), Duration.ofMinutes(2), deliveries)
        Mockito
            .`when`(stream.pending("lint-requests", "snippet-service", Range.unbounded<String>(), 50, Duration.ofMinutes(1)))
            .thenReturn(PendingMessages("snippet-service", listOf(message)))
    }

    private fun acknowledged(id: String) = verify(stream).acknowledge("lint-requests", "snippet-service", RecordId.of(id))

    @Test
    fun `encola cada snippet como un mensaje del stream con tope de largo`() {
        RedisLintQueue(redis, properties).enqueue(listOf(first, second))

        verify(stream).add(eq("lint-requests"), eq(mapOf(SNIPPET_ID to first.toString())), any(XAddOptions::class.java))
        verify(stream).add(eq("lint-requests"), eq(mapOf(SNIPPET_ID to second.toString())), any(XAddOptions::class.java))
    }

    @Test
    fun `crea el consumer group desde el principio del stream una sola vez`() {
        worker.poll()
        worker.poll()

        verify(stream, times(1)).createGroup("lint-requests", ReadOffset.from("0"), "snippet-service")
    }

    @Test
    fun `si el grupo ya existia sigue igual`() {
        Mockito
            .`when`(stream.createGroup("lint-requests", ReadOffset.from("0"), "snippet-service"))
            .thenThrow(RedisSystemException("Error", RuntimeException("BUSYGROUP Consumer Group name already exists")))
        newMessages(record("1-0", first.toString()))

        worker.poll()

        verify(linter).lint(first)
    }

    @Test
    fun `lintea cada mensaje nuevo y lo confirma`() {
        newMessages(record("1-0", first.toString()), record("2-0", second.toString()))

        worker.poll()

        verify(linter).lint(first)
        verify(linter).lint(second)
        acknowledged("1-0")
        acknowledged("2-0")
    }

    @Test
    fun `si un snippet falla no lo confirma y sigue con los demas`() {
        Mockito.doThrow(ResourceAccessException("language-service no responde")).`when`(linter).lint(first)
        newMessages(record("1-0", first.toString()), record("2-0", second.toString()))

        worker.poll()

        verify(stream, never()).acknowledge("lint-requests", "snippet-service", RecordId.of("1-0"))
        acknowledged("2-0")
    }

    @Test
    fun `descarta un mensaje sin un id de snippet valido`() {
        newMessages(record("1-0", "no-es-un-uuid"))

        worker.poll()

        verifyNoInteractions(linter)
        acknowledged("1-0")
    }

    @Test
    fun `retoma un mensaje que quedo sin confirmar, aunque sea de otra replica`() {
        stuck("1-0", deliveries = 1)
        Mockito
            .`when`(stream.claim("lint-requests", "snippet-service", "replica-1", Duration.ofMinutes(1), RecordId.of("1-0")))
            .thenReturn(listOf(record("1-0", first.toString())))

        worker.poll()

        verify(linter).lint(first)
        acknowledged("1-0")
    }

    @Test
    fun `saltea un mensaje que ya fallo todas las veces permitidas`() {
        stuck("1-0", deliveries = 3)

        worker.poll()

        verify(stream, never()).claim(anyString(), anyString(), anyString(), any(Duration::class.java), any(RecordId::class.java))
        verifyNoInteractions(linter)
        acknowledged("1-0")
    }

    @Test
    fun `si Redis no responde no corta el worker y reintenta en la proxima vuelta`() {
        Mockito
            .`when`(stream.createGroup("lint-requests", ReadOffset.from("0"), "snippet-service"))
            .thenThrow(RedisConnectionFailureException("Connection refused"))
            .thenReturn("OK")

        worker.poll()
        worker.poll()

        verify(stream, times(2)).createGroup("lint-requests", ReadOffset.from("0"), "snippet-service")
    }

    @Test
    fun `un error al crear el grupo que no es porque ya existe no se toma como creado`() {
        Mockito
            .`when`(stream.createGroup("lint-requests", ReadOffset.from("0"), "snippet-service"))
            .thenThrow(RedisSystemException("Error", RuntimeException("WRONGTYPE")))

        worker.poll()

        verify(stream, never()).read(any(Consumer::class.java), any(StreamReadOptions::class.java), any())
    }

    @Test
    fun `la configuracion arma la cola y el worker sobre Redis`() {
        val config = RedisLintingConfig()

        assertIs<RedisLintQueue>(config.lintQueue(redis, properties))
        assertIs<RedisLintWorker>(config.lintWorker(redis, linter, properties))
    }
}
