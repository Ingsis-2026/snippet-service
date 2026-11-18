package com.ingsis.snippet

import com.ingsis.snippet.clients.UnsupportedLanguageException
import com.ingsis.snippet.storage.StorageException
import com.ingsis.snippet.streams.Backoff
import com.ingsis.snippet.streams.isDependencyFailure
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cómo distingue el worker de los streams una dependencia caída de un snippet que falla, y cuánto espera. */
class StreamFailuresTest {
    @Test
    fun `una dependencia que no responde, responde 5xx o pide bajar el ritmo es una falla de la dependencia`() {
        listOf(
            ResourceAccessException("Connection refused"),
            HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE),
            HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR),
            HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "", HttpHeaders(), null, null),
            RedisConnectionFailureException("Connection refused"),
            QueryTimeoutException("Postgres no responde"),
            CannotCreateTransactionException("Sin conexiones"),
        ).forEach { assertTrue(it.isDependencyFailure(), "$it") }
    }

    @Test
    fun `asset-service caido llega envuelto y tambien cuenta`() {
        assertTrue(StorageException("No se pudo leer el código", ResourceAccessException("Connection refused")).isDependencyFailure())
    }

    @Test
    fun `lo que depende del snippet no es una falla de la dependencia`() {
        listOf(
            IllegalStateException("El snippet rompe el linter"),
            UnsupportedLanguageException("No existe la versión 9.9"),
            StorageException("No se pudo leer el código", HttpClientErrorException(HttpStatus.NOT_FOUND)),
            DataIntegrityViolationException("Duplicado"),
        ).forEach { assertFalse(it.isDependencyFailure(), "$it") }
    }

    @Test
    fun `la pausa empieza corta, se duplica con cada falla seguida hasta el tope y vuelve a empezar cuando algo anda`() {
        val clock = MutableClock(Instant.parse("2026-11-14T12:00:00Z"))
        val backoff = Backoff(Duration.ofSeconds(10), Duration.ofSeconds(30), clock)

        assertFalse(backoff.isPaused())
        assertEquals(Duration.ofSeconds(10), backoff.pause())
        assertTrue(backoff.isPaused())
        assertEquals(Duration.ofSeconds(20), backoff.pause())
        assertEquals(Duration.ofSeconds(30), backoff.pause())
        assertEquals(Duration.ofSeconds(30), backoff.pause())

        clock.advance(Duration.ofSeconds(30))
        assertFalse(backoff.isPaused())

        backoff.reset()
        assertEquals(Duration.ofSeconds(10), backoff.pause())
    }
}
