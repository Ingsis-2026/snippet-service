package com.ingsis.snippet.linting

import com.ingsis.snippet.snippets.SnippetRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Vuelve a encolar para lintear los snippets que llevan más de [stuckAfter] `PENDING`.
 *
 * Los snippets se encolan recién cuando quedó guardado el cambio que los dejó `PENDING` (ver
 * [LintScheduler]), fuera de esa transacción. Si en ese momento Redis no responde, o el servicio se
 * cae a mitad de camino, el cambio queda guardado pero el snippet se queda sin el mensaje que lo iba
 * a lintear, o se quedan sin el suyo los que faltaban encolar. Spring loguea la excepción de un
 * listener de `AFTER_COMMIT` y sigue: sin esto, quedarían `PENDING` para siempre.
 *
 * Un snippet que sigue `PENDING` solo porque el worker viene atrasado, o porque language-service
 * está caído y se lo está postergando, también se vuelve a encolar. Da igual que quede dos veces,
 * porque lintear es idempotente. Para no encolarlo de nuevo en cada vuelta, se vuelve a contar desde
 * ahora el tiempo que lleva `PENDING`. Los que quedaron `ERROR` no se tocan: ya se intentaron y no
 * se pudo.
 */
@Component
class StuckLintSweeper(
    private val repository: SnippetRepository,
    private val queue: LintQueue,
    @Value("\${linting.sweep.stuck-after:10m}") private val stuckAfter: Duration,
) {
    /**
     * Se encola y se reinicia el tiempo de a tandas, para no pasarse del máximo de parámetros de una
     * consulta (después de una versión nueva del parser pueden ser todos los snippets). Primero se
     * encola: si se corta antes de reiniciar el tiempo, la próxima vuelta los encola de nuevo.
     */
    fun sweep() {
        val stuck = repository.findIdsPendingSince(Instant.now() - stuckAfter)
        if (stuck.isEmpty()) return
        log.warn("Se vuelven a encolar ${stuck.size} snippets que llevaban más de $stuckAfter sin lintearse")
        try {
            for (ids in stuck.chunked(CHUNK_SIZE)) {
                queue.enqueue(ids)
                repository.restartPending(ids)
            }
        } catch (e: DataAccessException) {
            log.warn("No se pudieron volver a encolar los snippets pendientes; se reintenta en la próxima vuelta: ${e.message}")
        }
    }

    private companion object {
        const val CHUNK_SIZE = 500
        val log: Logger = LoggerFactory.getLogger(StuckLintSweeper::class.java)
    }
}

/**
 * Barre cada `linting.sweep.interval` (5 minutos). Con `linting.sweep.enabled=false` no barre solo:
 * los tests llaman a [StuckLintSweeper.sweep] cuando quieren.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty("linting.sweep.enabled", havingValue = "true", matchIfMissing = true)
class StuckLintSweepSchedule(
    private val sweeper: StuckLintSweeper,
) {
    @Scheduled(initialDelayString = "\${linting.sweep.interval:5m}", fixedDelayString = "\${linting.sweep.interval:5m}")
    fun sweep() = sweeper.sweep()
}
