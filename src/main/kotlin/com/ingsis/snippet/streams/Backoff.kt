package com.ingsis.snippet.streams

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Cuánto deja de leer un worker cuando falla una dependencia, para no seguir pegándole a algo que
 * está caído. La primera pausa es de [initial] y cada falla seguida la duplica, hasta [max]. Cuando
 * algo vuelve a andar ([reset]), la próxima falla arranca otra vez desde [initial].
 *
 * Lo usa un solo worker, y Spring nunca corre dos vueltas del mismo worker a la vez: no hace falta
 * sincronizar. `@Volatile` es porque cada vuelta puede correr en otro hilo del scheduler.
 */
class Backoff(
    private val initial: Duration,
    private val max: Duration,
    private val clock: Clock,
) {
    @Volatile
    private var last: Duration? = null

    @Volatile
    private var until: Instant = Instant.MIN

    fun isPaused(): Boolean = clock.instant() < until

    /** Arranca una pausa y devuelve cuánto dura. */
    fun pause(): Duration {
        val next = last?.multipliedBy(2)?.coerceAtMost(max) ?: initial
        last = next
        until = clock.instant() + next
        return next
    }

    fun reset() {
        last = null
    }
}
