package com.ingsis.snippet.streams

import com.ingsis.snippet.formatting.FormatQueue
import com.ingsis.snippet.formatting.SnippetFormatter
import com.ingsis.snippet.linting.LintQueue
import com.ingsis.snippet.linting.SnippetLinter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.util.UUID

/**
 * Cómo se usan los streams de Redis donde se encolan los snippets a procesar en segundo plano.
 *
 * Hay un stream por tipo de trabajo: [lintStream] para lintear (US15) y [formatStream] para
 * formatear (US12). Así una tanda grande de formateos, que es lo más pesado y además termina
 * encolando un linteo por snippet, no deja esperando detrás suyo el linteo de un snippet recién
 * creado. Cada stream tiene su propio backlog y sus propios pendientes (`XLEN`, `XPENDING`), y los
 * mensajes siguen siendo solo el id del snippet: no hay un campo de tipo que validar ni un worker
 * que decida qué hacer con cada mensaje. La forma de procesarlos, en cambio, es la misma, y por
 * eso el resto de la configuración es común.
 */
@ConfigurationProperties("streams")
data class StreamProperties(
    val lintStream: String = "lint-requests",
    val formatStream: String = "format-requests",
    /** Todas las réplicas de snippet-service comparten el grupo: cada mensaje lo procesa una sola. */
    val group: String = "snippet-service",
    /** Tiene que ser distinto en cada réplica. */
    val consumer: String = "snippet-service",
    /** Cuántos mensajes se leen por vuelta: así no se le manda a language-service todo de golpe. */
    val batchSize: Long = 50,
    /** Cuánto tiene que pasar sin confirmarse un mensaje para darlo por fallado y reintentarlo. */
    val retryAfter: Duration = Duration.ofMinutes(1),
    /** Después de cuántas entregas se saltea un mensaje que sigue fallando. */
    val maxAttempts: Long = 3,
    /** Tope aproximado de mensajes que guarda cada stream. */
    val maxLength: Long = 100_000,
)

/**
 * Las colas en Redis y sus workers. Con `streams.redis.enabled=false` no se crea ninguno, y las
 * colas las tienen que dar otros beans: los tests, por ejemplo, usan unas en memoria y no
 * necesitan Redis.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StreamProperties::class)
@ConditionalOnProperty("streams.redis.enabled", havingValue = "true", matchIfMissing = true)
class RedisStreamsConfig {
    @Bean
    fun lintQueue(
        redis: StringRedisTemplate,
        properties: StreamProperties,
    ): LintQueue = LintQueue(RedisSnippetQueue(redis, properties.lintStream, properties)::enqueue)

    @Bean
    fun lintWorker(
        redis: StringRedisTemplate,
        linter: SnippetLinter,
        properties: StreamProperties,
    ) = RedisSnippetWorker(redis, properties.lintStream, properties, linter::lint)

    @Bean
    fun formatQueue(
        redis: StringRedisTemplate,
        properties: StreamProperties,
    ): FormatQueue = FormatQueue(RedisSnippetQueue(redis, properties.formatStream, properties)::enqueue)

    @Bean
    fun formatWorker(
        redis: StringRedisTemplate,
        formatter: SnippetFormatter,
        properties: StreamProperties,
    ) = RedisSnippetWorker(redis, properties.formatStream, properties, formatter::format)
}

/** Cada snippet es un mensaje de [stream], con su id en el campo [SNIPPET_ID]. */
class RedisSnippetQueue(
    private val redis: StringRedisTemplate,
    private val stream: String,
    private val properties: StreamProperties,
) {
    fun enqueue(snippetIds: Collection<UUID>) {
        val options = XAddOptions.maxlen(properties.maxLength).approximateTrimming(true)
        for (id in snippetIds) {
            redis.opsForStream<String, String>().add(stream, mapOf(SNIPPET_ID to id.toString()), options)
        }
    }
}

/**
 * Lee [stream] como parte del consumer group y le pasa cada snippet a [process]: lintearlo (US15)
 * o formatearlo (US12). Un mensaje se confirma (`XACK`) recién cuando [process] terminó sin
 * fallar, así que lo que falla o queda a medias porque se cayó el servicio sigue pendiente en
 * Redis. Por eso [process] tiene que ser idempotente.
 *
 * En cada vuelta primero retoma los pendientes que llevan [StreamProperties.retryAfter] sin
 * confirmarse, sean de esta réplica o de otra, y después lee los nuevos. Un mensaje que ya se
 * entregó [StreamProperties.maxAttempts] veces se confirma sin procesar: se saltea el snippet que
 * causa la falla para que no frene al resto.
 */
class RedisSnippetWorker(
    private val redis: StringRedisTemplate,
    private val stream: String,
    private val properties: StreamProperties,
    private val process: (UUID) -> Unit,
) {
    /** El grupo se crea con la primera vuelta y no al arrancar: así el servicio levanta aunque Redis no esté. */
    @Volatile
    private var groupCreated = false

    private val operations get() = redis.opsForStream<String, String>()

    @Scheduled(fixedDelayString = "\${streams.poll-delay:500ms}")
    fun poll() {
        try {
            createGroup()
            retryStuck()
            readNew()
        } catch (e: DataAccessException) {
            // Sin el stack trace: con Redis caído esto se repite en cada vuelta.
            log.warn("No se pudo leer el stream $stream de Redis: ${e.message}")
        }
    }

    /** Desde el principio del stream: si hay mensajes de antes de que existiera el grupo, también se procesan. */
    private fun createGroup() {
        if (groupCreated) return
        try {
            operations.createGroup(stream, ReadOffset.from("0"), properties.group)
        } catch (e: DataAccessException) {
            if (generateSequence<Throwable>(e) { it.cause }.none { it.message.orEmpty().contains("BUSYGROUP") }) throw e
        }
        groupCreated = true
    }

    private fun retryStuck() {
        val stuck = operations.pending(stream, properties.group, Range.unbounded<String>(), properties.batchSize, properties.retryAfter)
        for (message in stuck) {
            if (message.totalDeliveryCount >= properties.maxAttempts) {
                log.error("Se saltea el mensaje ${message.id} de $stream: falló las ${message.totalDeliveryCount} veces que se entregó")
                acknowledge(message.id)
            } else {
                operations.claim(stream, properties.group, properties.consumer, properties.retryAfter, message.id).forEach(::handle)
            }
        }
    }

    private fun readNew() {
        operations
            .read(
                Consumer.from(properties.group, properties.consumer),
                StreamReadOptions.empty().count(properties.batchSize),
                StreamOffset.create(stream, ReadOffset.lastConsumed()),
            ).orEmpty()
            .forEach(::handle)
    }

    private fun handle(record: MapRecord<String, String, String>) {
        val id = record.value[SNIPPET_ID]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (id == null) {
            log.error("Se descarta el mensaje ${record.id} de $stream: no trae un $SNIPPET_ID válido")
            acknowledge(record.id)
            return
        }
        try {
            process(id)
            acknowledge(record.id)
        } catch (e: RuntimeException) {
            log.warn("No se pudo procesar el snippet $id de $stream; se reintenta en ${properties.retryAfter}", e)
        }
    }

    private fun acknowledge(id: RecordId) {
        operations.acknowledge(stream, properties.group, id)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(RedisSnippetWorker::class.java)
    }
}

const val SNIPPET_ID = "snippetId"
