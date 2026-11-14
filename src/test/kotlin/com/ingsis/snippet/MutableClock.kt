package com.ingsis.snippet

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Un reloj que solo avanza cuando el test lo pide. */
class MutableClock(
    private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now += duration
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
