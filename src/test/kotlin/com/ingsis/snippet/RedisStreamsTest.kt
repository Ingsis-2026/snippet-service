package com.ingsis.snippet

import com.ingsis.snippet.formatting.SnippetFormatter
import com.ingsis.snippet.linting.SnippetLinter
import com.ingsis.snippet.streams.ATTEMPTS
import com.ingsis.snippet.streams.DEFERRALS
import com.ingsis.snippet.streams.RedisSnippetQueue
import com.ingsis.snippet.streams.RedisSnippetWorker
import com.ingsis.snippet.streams.RedisStreamsConfig
import com.ingsis.snippet.streams.SNIPPET_ID
import com.ingsis.snippet.streams.StreamProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Las colas de US15 y US12 sobre streams de Redis, con Redis simulado. */
class RedisStreamsTest {
    private val redis = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val stream = mock(StreamOperations::class.java) as StreamOperations<String, String, String>
    private val properties = StreamProperties(consumer = "replica-1", maxAttempts = 3, maxDeferrals = 20)
    private val clock = MutableClock(Instant.parse("2026-11-14T12:00:00Z"))
    private val processed = mutableListOf<UUID>()
    private val skipped = mutableListOf<UUID>()

    /** Los snippets que fallan por sí mismos: aunque todo lo demás ande, fallan igual. */
    private val failing = mutableSetOf<UUID>()

    /** Con language-service caído falla cualquier snippet. */
    private var languageServiceDown = false
    private val worker =
        RedisSnippetWorker(redis, "lint-requests", properties, clock, giveUp = { skipped += it }) { id ->
            if (languageServiceDown) throw ResourceAccessException("language-service no responde")
            if (id in failing) throw IllegalStateException("El snippet rompe el linter")
            processed += id
        }
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val third = UUID.randomUUID()

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
        streamName: String = "lint-requests",
        extra: Map<String, String> = emptyMap(),
    ): MapRecord<String, String, String> =
        StreamRecords
            .newRecord()
            .`in`(streamName)
            .ofMap(mapOf(SNIPPET_ID to snippetId) + extra)
            .withId(RecordId.of(id))

    private fun newMessages(
        vararg records: MapRecord<String, String, String>,
        streamName: String = "lint-requests",
    ) {
        Mockito
            .`when`(
                stream.read(
                    Consumer.from("snippet-service", "replica-1"),
                    StreamReadOptions.empty().count(50),
                    StreamOffset.create(streamName, ReadOffset.lastConsumed()),
                ),
            ).thenReturn(records.toList())
    }

    /** Un mensaje que lleva más de un minuto sin confirmarse, y lo que devuelve Redis al retomarlo. */
    private fun stuck(
        record: MapRecord<String, String, String>,
        deliveries: Long,
        streamName: String = "lint-requests",
    ) {
        val message = PendingMessage(record.id, Consumer.from("snippet-service", "replica-2"), Duration.ofMinutes(2), deliveries)
        Mockito
            .`when`(stream.pending(streamName, "snippet-service", Range.unbounded<String>(), 50, Duration.ofMinutes(1)))
            .thenReturn(PendingMessages("snippet-service", listOf(message)))
        Mockito
            .`when`(stream.claim(streamName, "snippet-service", "replica-1", Duration.ofMinutes(1), record.id))
            .thenReturn(listOf(record))
    }

    private fun acknowledged(
        id: String,
        streamName: String = "lint-requests",
    ) = verify(stream).acknowledge(streamName, "snippet-service", RecordId.of(id))

    private fun notAcknowledged(id: String) = verify(stream, never()).acknowledge("lint-requests", "snippet-service", RecordId.of(id))

    private fun requeued(
        snippetId: UUID,
        attempts: Long,
        deferrals: Long,
    ) = verify(stream).add(
        eq("lint-requests"),
        eq(mapOf(SNIPPET_ID to snippetId.toString(), ATTEMPTS to attempts.toString(), DEFERRALS to deferrals.toString())),
        any(XAddOptions::class.java),
    )

    private fun nothingRequeued() = verify(stream, never()).add(anyString(), anyMap<String, String>(), any(XAddOptions::class.java))

    private fun reads() = Mockito.mockingDetails(stream).invocations.count { it.method.name == "read" }

    @Test
    fun `encola cada snippet como un mensaje del stream con tope de largo`() {
        RedisSnippetQueue(redis, "lint-requests", properties).enqueue(listOf(first, second))

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

        assertEquals(listOf(first), processed)
    }

    @Test
    fun `procesa cada mensaje nuevo y lo confirma`() {
        newMessages(record("1-0", first.toString()), record("2-0", second.toString()))

        worker.poll()

        assertEquals(listOf(first, second), processed)
        acknowledged("1-0")
        acknowledged("2-0")
    }

    @Test
    fun `si un snippet falla no lo confirma y sigue con los demas`() {
        failing += first
        newMessages(record("1-0", first.toString()), record("2-0", second.toString()))

        worker.poll()

        notAcknowledged("1-0")
        acknowledged("2-0")
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `descarta un mensaje sin un id de snippet valido`() {
        newMessages(record("1-0", "no-es-un-uuid"))

        worker.poll()

        assertTrue(processed.isEmpty())
        acknowledged("1-0")
    }

    @Test
    fun `retoma un mensaje que quedo sin confirmar, aunque sea de otra replica`() {
        stuck(record("1-0", first.toString()), deliveries = 1)

        worker.poll()

        assertEquals(listOf(first), processed)
        acknowledged("1-0")
    }

    @Test
    fun `si otra replica lo retomo primero no hace nada`() {
        stuck(record("1-0", first.toString()), deliveries = 1)
        Mockito
            .`when`(stream.claim("lint-requests", "snippet-service", "replica-1", Duration.ofMinutes(1), RecordId.of("1-0")))
            .thenReturn(emptyList())

        worker.poll()

        assertTrue(processed.isEmpty())
        notAcknowledged("1-0")
    }

    @Test
    fun `si el snippet falla la ultima vez permitida lo saltea enseguida`() {
        failing += first
        stuck(record("1-0", first.toString()), deliveries = 2)

        worker.poll()

        assertEquals(listOf(first), skipped)
        acknowledged("1-0")
    }

    @Test
    fun `saltea sin procesar un mensaje que ya se entrego todas las veces permitidas`() {
        stuck(record("1-0", first.toString()), deliveries = 3)

        worker.poll()

        assertTrue(processed.isEmpty())
        assertEquals(listOf(first), skipped)
        acknowledged("1-0")
    }

    @Test
    fun `cuenta los intentos fallidos de las copias anteriores del mensaje`() {
        failing += first
        newMessages(record("1-0", first.toString(), extra = mapOf(ATTEMPTS to "2", DEFERRALS to "4")))

        worker.poll()

        assertEquals(listOf(first), skipped)
        acknowledged("1-0")
    }

    @Test
    fun `si falla una dependencia posterga el mensaje sin gastarle un intento`() {
        languageServiceDown = true
        newMessages(record("1-0", first.toString()))

        worker.poll()

        requeued(first, attempts = 0, deferrals = 1)
        acknowledged("1-0")
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `si falla una dependencia no procesa el resto de la tanda y la posterga sin contarlo`() {
        languageServiceDown = true
        newMessages(
            record("1-0", first.toString()),
            record("2-0", second.toString()),
            record("3-0", third.toString(), extra = mapOf(ATTEMPTS to "1", DEFERRALS to "2")),
        )

        worker.poll()

        requeued(first, attempts = 0, deferrals = 1)
        requeued(second, attempts = 0, deferrals = 0)
        requeued(third, attempts = 1, deferrals = 2)
        acknowledged("2-0")
        acknowledged("3-0")
    }

    @Test
    fun `un mensaje retomado que encuentra la dependencia caida conserva los intentos que ya fallaron`() {
        languageServiceDown = true
        stuck(record("1-0", first.toString()), deliveries = 1)
        newMessages(record("2-0", second.toString()))

        worker.poll()

        requeued(first, attempts = 1, deferrals = 1)
        acknowledged("1-0")
        assertEquals(0, reads())
    }

    @Test
    fun `mientras la dependencia esta caida deja de leer y despues vuelve a intentar`() {
        languageServiceDown = true
        newMessages(record("1-0", first.toString()))
        worker.poll()

        clock.advance(Duration.ofSeconds(9))
        worker.poll()
        assertEquals(1, reads())

        languageServiceDown = false
        clock.advance(Duration.ofSeconds(1))
        worker.poll()
        assertEquals(listOf(first), processed)
    }

    @Test
    fun `cada falla seguida de la dependencia alarga la pausa`() {
        languageServiceDown = true
        newMessages(record("1-0", first.toString()))
        worker.poll()
        clock.advance(Duration.ofSeconds(10))
        worker.poll()

        clock.advance(Duration.ofSeconds(19))
        worker.poll()

        assertEquals(2, reads())
    }

    @Test
    fun `saltea un mensaje que ya se postergo todas las veces permitidas`() {
        languageServiceDown = true
        newMessages(record("1-0", first.toString(), extra = mapOf(DEFERRALS to "20")))

        worker.poll()

        assertEquals(listOf(first), skipped)
        acknowledged("1-0")
        nothingRequeued()
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

        assertEquals(0, reads())
    }

    @Test
    fun `lint y formato van cada uno a su stream`() {
        val config = RedisStreamsConfig()

        config.lintQueue(redis, properties).enqueue(listOf(first))
        config.formatQueue(redis, properties).enqueue(listOf(second))

        verify(stream).add(eq("lint-requests"), eq(mapOf(SNIPPET_ID to first.toString())), any(XAddOptions::class.java))
        verify(stream).add(eq("format-requests"), eq(mapOf(SNIPPET_ID to second.toString())), any(XAddOptions::class.java))
    }

    @Test
    fun `el worker de lint lintea lo de su stream y el de formato formatea lo del suyo`() {
        val config = RedisStreamsConfig()
        val linter = mock(SnippetLinter::class.java)
        val formatter = mock(SnippetFormatter::class.java)
        newMessages(record("1-0", first.toString()))
        newMessages(record("1-0", second.toString(), "format-requests"), streamName = "format-requests")

        config.lintWorker(redis, linter, properties).poll()
        config.formatWorker(redis, formatter, properties).poll()

        verify(linter).lint(first)
        verify(formatter).format(second)
        acknowledged("1-0")
        acknowledged("1-0", "format-requests")
    }

    @Test
    fun `el worker de lint marca ERROR el snippet que saltea y el de formato solo lo confirma`() {
        val config = RedisStreamsConfig()
        val linter = mock(SnippetLinter::class.java)
        val formatter = mock(SnippetFormatter::class.java)
        stuck(record("1-0", first.toString()), deliveries = 3)
        stuck(record("1-0", second.toString(), "format-requests"), deliveries = 3, streamName = "format-requests")

        config.lintWorker(redis, linter, properties).poll()
        config.formatWorker(redis, formatter, properties).poll()

        verify(linter).giveUp(first)
        verify(linter, never()).lint(first)
        verify(formatter, never()).format(second)
        acknowledged("1-0")
        acknowledged("1-0", "format-requests")
    }
}
