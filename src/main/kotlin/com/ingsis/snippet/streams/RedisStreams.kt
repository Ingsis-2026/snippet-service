package com.ingsis.snippet.streams

import com.ingsis.snippet.formatting.FormatQueue
import com.ingsis.snippet.formatting.SnippetFormatter
import com.ingsis.snippet.linting.LintQueue
import com.ingsis.snippet.linting.SnippetLinter
import com.ingsis.snippet.observability.Telemetry
import com.newrelic.api.agent.ConcurrentHashMapHeaders
import com.newrelic.api.agent.HeaderType
import com.newrelic.api.agent.NewRelic
import com.newrelic.api.agent.Trace
import com.newrelic.api.agent.TransportType
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
import java.time.Clock
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
    /** Después de cuántos intentos fallidos por culpa del snippet se lo saltea. */
    val maxAttempts: Long = 3,
    /** Cuánto deja de leer el worker la primera vez que falla una dependencia. Se duplica con cada falla seguida. */
    val backoff: Duration = Duration.ofSeconds(10),
    /** Hasta dónde crece la pausa del worker mientras una dependencia sigue fallando. */
    val maxBackoff: Duration = Duration.ofMinutes(5),
    /** Cuántas veces se puede postergar un mismo mensaje porque falló una dependencia antes de saltearlo. */
    val maxDeferrals: Long = 20,
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

    /** Un snippet que no se pudo lintear queda `ERROR`. */
    @Bean
    fun lintWorker(
        redis: StringRedisTemplate,
        linter: SnippetLinter,
        properties: StreamProperties,
    ) = RedisSnippetWorker(redis, properties.lintStream, properties, giveUp = linter::giveUp, process = linter::lint)

    @Bean
    fun formatQueue(
        redis: StringRedisTemplate,
        properties: StreamProperties,
    ): FormatQueue = FormatQueue(RedisSnippetQueue(redis, properties.formatStream, properties)::enqueue)

    /** Un snippet que no se pudo formatear queda con el código que tenía: no hay nada que marcar. */
    @Bean
    fun formatWorker(
        redis: StringRedisTemplate,
        formatter: SnippetFormatter,
        properties: StreamProperties,
    ) = RedisSnippetWorker(redis, properties.formatStream, properties, process = formatter::format)

    @Bean
    fun streamBacklogReporter(
        redis: StringRedisTemplate,
        properties: StreamProperties,
        telemetry: Telemetry,
    ) = StreamBacklogReporter(redis, listOf(properties.lintStream, properties.formatStream), properties, telemetry)
}

/**
 * Cada snippet es un mensaje de [stream], con su id en el campo [SNIPPET_ID]. Si corre con el agente de New
 * Relic, el mensaje lleva además los headers del trace del pedido que lo encoló (`traceparent`, `newrelic`),
 * así el linteo o el formateo en segundo plano aparece en el mismo trace que la creación del snippet.
 * Sin el agente no se agrega nada.
 */
class RedisSnippetQueue(
    private val redis: StringRedisTemplate,
    private val stream: String,
    private val properties: StreamProperties,
) {
    fun enqueue(snippetIds: Collection<UUID>) {
        val trace = traceHeaders()
        for (id in snippetIds) {
            redis.opsForStream<String, String>().add(stream, trace + (SNIPPET_ID to id.toString()), properties.trimming())
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
 * confirmarse, sean de esta réplica o de otra, y después lee los nuevos.
 *
 * **No es lo mismo que falle el snippet que una dependencia.** La historia de usuario pide saltear
 * el snippet que causa la falla para que no frene al resto, pero si language-service, asset-service,
 * la base o Redis no responden ([isDependencyFailure]), la falla no es del snippet: saltearlo
 * perdería el trabajo de todos los que se procesen mientras dura la caída.
 *
 * - Si falla el snippet, el mensaje queda pendiente y se reintenta pasado
 *   [StreamProperties.retryAfter]. Al fallar [StreamProperties.maxAttempts] veces se lo saltea: se
 *   llama a [giveUp] (el linteo lo marca `ERROR`) y se lo confirma.
 * - Si falla una dependencia, el mensaje se posterga sin gastarle un intento ([requeue]), lo mismo
 *   que los que quedaban de la tanda, y el worker deja de leer por un rato ([Backoff]). Dejarlo
 *   pendiente no alcanza: retomarlo con `XCLAIM` suma una entrega, y en una caída de tres minutos se
 *   le gastarían todos los intentos.
 *
 * Los intentos fallidos se cuentan con las entregas de Redis más los que traiga el mensaje en
 * [ATTEMPTS], que son los de las copias anteriores que se postergaron. Un mensaje que se entregó
 * [StreamProperties.maxAttempts] veces y nunca terminó (el servicio se cayó procesándolo cada vez)
 * también se saltea.
 *
 * Un 500 que da un snippet en particular parece una falla de la dependencia, así que se lo
 * postergaría para siempre. Por eso se saltea también un mensaje que se postergó
 * [StreamProperties.maxDeferrals] veces: con las pausas cada vez más largas, para eso tiene que
 * haber sido el que encontró la dependencia caída durante más de una hora.
 */
class RedisSnippetWorker(
    private val redis: StringRedisTemplate,
    private val stream: String,
    private val properties: StreamProperties,
    clock: Clock = Clock.systemUTC(),
    private val giveUp: (UUID) -> Unit = {},
    private val process: (UUID) -> Unit,
) {
    /** El grupo se crea con la primera vuelta y no al arrancar: así el servicio levanta aunque Redis no esté. */
    @Volatile
    private var groupCreated = false

    private val backoff = Backoff(properties.backoff, properties.maxBackoff, clock)

    private val operations get() = redis.opsForStream<String, String>()

    @Scheduled(fixedDelayString = "\${streams.poll-delay:500ms}")
    fun poll() {
        if (backoff.isPaused()) return
        try {
            createGroup()
            if (retryStuck()) readNew()
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

    /** Devuelve si se puede seguir leyendo: no si falló una dependencia. */
    private fun retryStuck(): Boolean {
        val stuck = operations.pending(stream, properties.group, Range.unbounded<String>(), properties.batchSize, properties.retryAfter)
        for (message in stuck) {
            // Si otra réplica lo retomó primero, no viene nada.
            val record = operations.claim(stream, properties.group, properties.consumer, properties.retryAfter, message.id).firstOrNull()
            if (record != null && !handle(Delivery(record, message.totalDeliveryCount + 1))) return false
        }
        return true
    }

    private fun readNew() {
        val records =
            operations
                .read(
                    Consumer.from(properties.group, properties.consumer),
                    StreamReadOptions.empty().count(properties.batchSize),
                    StreamOffset.create(stream, ReadOffset.lastConsumed()),
                ).orEmpty()
        for ((index, record) in records.withIndex()) {
            if (!handle(Delivery(record, deliveries = 1))) {
                // Ya se le entregaron a esta réplica: si se quedan pendientes, retomarlos les gasta un intento.
                records.drop(index + 1).map { Delivery(it, deliveries = 1) }.forEach { requeue(it, it.deferrals) }
                return
            }
        }
    }

    /**
     * Devuelve si se puede seguir con el próximo mensaje: no si falló una dependencia.
     *
     * Cada mensaje es una transacción de New Relic (`RedisStream/<stream>`) que continúa el trace de quien lo
     * encoló. Sin el agente, la anotación y las llamadas a NewRelic no hacen nada.
     */
    @Trace(dispatcher = true)
    private fun handle(delivery: Delivery): Boolean {
        NewRelic.setTransactionName("RedisStream", stream)
        NewRelic
            .getAgent()
            .transaction
            .acceptDistributedTraceHeaders(TransportType.Queue, traceHeadersOf(delivery.record.value))
        val id = delivery.snippetId
        if (id == null) {
            log.error("Se descarta el mensaje ${delivery.record.id} de $stream: no trae un $SNIPPET_ID válido")
            acknowledge(delivery.record.id)
            return true
        }
        if (delivery.failedAttempts >= properties.maxAttempts) {
            log.error("Se saltea el snippet $id de $stream: ya se intentó ${delivery.failedAttempts} veces sin que terminara bien")
            skip(delivery, id)
            return true
        }
        return try {
            process(id)
            acknowledge(delivery.record.id)
            backoff.reset()
            true
        } catch (e: RuntimeException) {
            NewRelic.noticeError(e)
            if (e.isDependencyFailure()) {
                onDependencyFailure(delivery, id, e)
                false
            } else {
                onSnippetFailure(delivery, id, e)
                true
            }
        }
    }

    /** La dependencia respondió, aunque haya sido con un error: no hace falta seguir esperándola. */
    private fun onSnippetFailure(
        delivery: Delivery,
        id: UUID,
        e: RuntimeException,
    ) {
        backoff.reset()
        if (delivery.failedAttempts + 1 >= properties.maxAttempts) {
            log.error("Se saltea el snippet $id de $stream: falló las ${properties.maxAttempts} veces que se intentó", e)
            skip(delivery, id)
        } else {
            log.warn("No se pudo procesar el snippet $id de $stream; se reintenta en ${properties.retryAfter}", e)
        }
    }

    /** Sin el stack trace: mientras dure la caída, esto pasa una vez por pausa. */
    private fun onDependencyFailure(
        delivery: Delivery,
        id: UUID,
        e: RuntimeException,
    ) {
        val pause = backoff.pause()
        if (delivery.deferrals >= properties.maxDeferrals) {
            log.error("Se saltea el snippet $id de $stream: ya se postergó ${delivery.deferrals} veces porque falló una dependencia", e)
            skip(delivery, id)
        } else {
            log.warn("Falló una dependencia procesando el snippet $id de $stream (${e.message}); se posterga y se deja de leer por $pause")
            requeue(delivery, delivery.deferrals + 1)
        }
    }

    /**
     * Posterga el mensaje sin gastarle un intento: lo agrega de nuevo al final del stream, con los
     * intentos fallidos que lleva y las veces que se lo postergó, y confirma esta copia. Primero lo
     * agrega y después confirma: si se corta en el medio queda repetido, que no cambia nada, pero no se
     * pierde. Que cambie de lugar en el stream tampoco, porque [process] es idempotente.
     */
    private fun requeue(
        delivery: Delivery,
        deferrals: Long,
    ) {
        val fields = delivery.record.value + mapOf(ATTEMPTS to delivery.failedAttempts.toString(), DEFERRALS to deferrals.toString())
        operations.add(stream, fields, properties.trimming())
        acknowledge(delivery.record.id)
    }

    /** Primero [giveUp] y después se confirma: si se corta en el medio, se vuelve a saltear, que da lo mismo. */
    private fun skip(
        delivery: Delivery,
        id: UUID,
    ) {
        giveUp(id)
        acknowledge(delivery.record.id)
    }

    private fun acknowledge(id: RecordId) {
        operations.acknowledge(stream, properties.group, id)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(RedisSnippetWorker::class.java)
    }
}

/**
 * Un mensaje como le llegó al worker. [deliveries] es cuántas veces Redis entregó esta copia, contando
 * esta; lo que les pasó a las copias anteriores, si se lo postergó, viene en el mensaje.
 */
private class Delivery(
    val record: MapRecord<String, String, String>,
    deliveries: Long,
) {
    val snippetId: UUID? = record.value[SNIPPET_ID]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Los intentos que fallaron antes de este, sin contar las veces que se lo postergó. */
    val failedAttempts: Long = count(ATTEMPTS) + deliveries - 1

    val deferrals: Long = count(DEFERRALS)

    private fun count(field: String) = record.value[field]?.toLongOrNull() ?: 0
}

const val SNIPPET_ID = "snippetId"

/** Los intentos que fallaron por el snippet en copias anteriores del mensaje, que se postergaron. */
const val ATTEMPTS = "attempts"

/** Cuántas veces se postergó el mensaje porque falló una dependencia. */
const val DEFERRALS = "deferrals"

private fun StreamProperties.trimming() = XAddOptions.maxlen(maxLength).approximateTrimming(true)

/** Los headers con los que New Relic continúa un trace en otro proceso, para guardarlos en un mensaje. */
private fun traceHeaders(): Map<String, String> {
    val headers = ConcurrentHashMapHeaders.build(HeaderType.MESSAGE)
    NewRelic.getAgent().transaction.insertDistributedTraceHeaders(headers)
    return headers.headerNames.associateWith { headers.getHeader(it) }
}

/** Los headers de trace que trae un mensaje: todos sus campos menos los que son del worker. */
private fun traceHeadersOf(fields: Map<String, String>) =
    ConcurrentHashMapHeaders.buildFromFlatMap(HeaderType.MESSAGE, fields - setOf(SNIPPET_ID, ATTEMPTS, DEFERRALS))
