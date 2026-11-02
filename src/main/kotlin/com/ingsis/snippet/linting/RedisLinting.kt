package com.ingsis.snippet.linting

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

/** Cómo se usa el stream de Redis donde se encolan los snippets a lintear. */
@ConfigurationProperties("linting")
data class LintingProperties(
    val stream: String = "lint-requests",
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
    /** Tope aproximado de mensajes que guarda el stream. */
    val maxLength: Long = 100_000,
)

/**
 * La cola en Redis y su worker. Con `linting.redis.enabled=false` no se crea ninguno, y la cola la
 * tiene que dar otro bean: los tests, por ejemplo, usan una en memoria y no necesitan Redis.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(LintingProperties::class)
@ConditionalOnProperty("linting.redis.enabled", havingValue = "true", matchIfMissing = true)
class RedisLintingConfig {
    @Bean
    fun lintQueue(
        redis: StringRedisTemplate,
        properties: LintingProperties,
    ): LintQueue = RedisLintQueue(redis, properties)

    @Bean
    fun lintWorker(
        redis: StringRedisTemplate,
        linter: SnippetLinter,
        properties: LintingProperties,
    ) = RedisLintWorker(redis, linter, properties)
}

/** Cada snippet es un mensaje del stream, con su id en el campo [SNIPPET_ID]. */
class RedisLintQueue(
    private val redis: StringRedisTemplate,
    private val properties: LintingProperties,
) : LintQueue {
    override fun enqueue(snippetIds: Collection<UUID>) {
        val options = XAddOptions.maxlen(properties.maxLength).approximateTrimming(true)
        for (id in snippetIds) {
            redis.opsForStream<String, String>().add(properties.stream, mapOf(SNIPPET_ID to id.toString()), options)
        }
    }
}

/**
 * Lee el stream como parte del consumer group y lintea cada snippet (US15). Un mensaje se confirma
 * (`XACK`) recién cuando el snippet quedó linteado, así que lo que falla o queda a medias porque
 * se cayó el servicio sigue pendiente en Redis.
 *
 * En cada vuelta primero retoma los pendientes que llevan [LintingProperties.retryAfter] sin
 * confirmarse, sean de esta réplica o de otra, y después lee los nuevos. Un mensaje que ya se
 * entregó [LintingProperties.maxAttempts] veces se confirma sin lintear: se saltea el snippet que
 * causa la falla para que no frene al resto, y queda `PENDING`.
 */
class RedisLintWorker(
    private val redis: StringRedisTemplate,
    private val linter: SnippetLinter,
    private val properties: LintingProperties,
) {
    /** El grupo se crea con la primera vuelta y no al arrancar: así el servicio levanta aunque Redis no esté. */
    @Volatile
    private var groupCreated = false

    private val stream get() = redis.opsForStream<String, String>()

    @Scheduled(fixedDelayString = "\${linting.poll-delay:500ms}")
    fun poll() {
        try {
            createGroup()
            retryStuck()
            readNew()
        } catch (e: DataAccessException) {
            // Sin el stack trace: con Redis caído esto se repite en cada vuelta.
            log.warn("No se pudo leer el stream ${properties.stream} de Redis: ${e.message}")
        }
    }

    /** Desde el principio del stream: si hay mensajes de antes de que existiera el grupo, también se procesan. */
    private fun createGroup() {
        if (groupCreated) return
        try {
            stream.createGroup(properties.stream, ReadOffset.from("0"), properties.group)
        } catch (e: DataAccessException) {
            if (generateSequence<Throwable>(e) { it.cause }.none { it.message.orEmpty().contains("BUSYGROUP") }) throw e
        }
        groupCreated = true
    }

    private fun retryStuck() {
        val stuck =
            stream.pending(
                properties.stream,
                properties.group,
                Range.unbounded<String>(),
                properties.batchSize,
                properties.retryAfter,
            )
        for (message in stuck) {
            if (message.totalDeliveryCount >= properties.maxAttempts) {
                log.error("Se saltea el mensaje ${message.id}: falló las ${message.totalDeliveryCount} veces que se entregó")
                acknowledge(message.id)
            } else {
                stream.claim(properties.stream, properties.group, properties.consumer, properties.retryAfter, message.id).forEach(::process)
            }
        }
    }

    private fun readNew() {
        stream
            .read(
                Consumer.from(properties.group, properties.consumer),
                StreamReadOptions.empty().count(properties.batchSize),
                StreamOffset.create(properties.stream, ReadOffset.lastConsumed()),
            ).orEmpty()
            .forEach(::process)
    }

    private fun process(record: MapRecord<String, String, String>) {
        val id = record.value[SNIPPET_ID]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (id == null) {
            log.error("Se descarta el mensaje ${record.id}: no trae un $SNIPPET_ID válido")
            acknowledge(record.id)
            return
        }
        try {
            linter.lint(id)
            acknowledge(record.id)
        } catch (e: RuntimeException) {
            log.warn("No se pudo lintear el snippet $id; se reintenta en ${properties.retryAfter}", e)
        }
    }

    private fun acknowledge(id: RecordId) {
        stream.acknowledge(properties.stream, properties.group, id)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(RedisLintWorker::class.java)
    }
}

const val SNIPPET_ID = "snippetId"
