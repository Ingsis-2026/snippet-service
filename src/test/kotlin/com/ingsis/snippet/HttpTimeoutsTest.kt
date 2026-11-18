package com.ingsis.snippet

import com.ingsis.snippet.clients.ClientsConfig
import com.ingsis.snippet.clients.HttpTimeouts
import com.ingsis.snippet.clients.TimeoutsByService
import com.ingsis.snippet.streams.isDependencyFailure
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Un servicio que tarda en responder no deja trabado para siempre al hilo que lo llamó. */
class HttpTimeoutsTest {
    /** Responde 200, pero recién después de [delay]. */
    private val delay = Duration.ofMillis(500)
    private val server =
        HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                Thread.sleep(delay.toMillis())
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
    private val port = server.address.port

    @AfterEach
    fun stop() = server.stop(0)

    private fun get(
        factory: TimeoutsByService,
        url: String,
    ) = RestClient
        .builder()
        .requestFactory(factory)
        .build()
        .get()
        .uri(url)
        .retrieve()
        .toBodilessEntity()

    @Test
    fun `si no responde a tiempo es una falla de la dependencia, y no se espera de mas`() {
        val factory = TimeoutsByService(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(5), emptyList())

        val started = System.nanoTime()
        val error = assertThrows<ResourceAccessException> { get(factory, "http://localhost:$port/permissions") }

        assertTrue(error.isDependencyFailure(), "Los workers de los streams la tienen que tomar como una caída del servicio")
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < delay, "Esperó a la respuesta en vez de cortar")
    }

    @Test
    fun `language-service tiene mas margen que los demas`() {
        // Mismo servidor, dos origins: localhost hace de language-service y 127.0.0.1 de cualquier otro.
        val factory =
            TimeoutsByService(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(5), listOf("http://localhost:$port"))

        assertEquals(200, get(factory, "http://localhost:$port/run").statusCode.value())
        assertThrows<ResourceAccessException> { get(factory, "http://127.0.0.1:$port/permissions") }
    }

    @Test
    fun `el builder de los clientes usa los timeouts, con mas margen para la URL de language-service`() {
        val factory = ClientsConfig().timeoutsByService(HttpTimeouts(), "http://language-service:8080")

        val language = factory.factoryFor(URI("http://language-service:8080/run"))
        assertSame(language, factory.factoryFor(URI("http://language-service:8080/lint")))
        assertNotSame(language, factory.factoryFor(URI("http://permission-service:8080/permissions")))
        assertSame(
            factory.factoryFor(URI("http://permission-service:8080/permissions")),
            factory.factoryFor(URI("https://tenant.us.auth0.com/oauth/token")),
        )
    }

    @Test
    fun `el builder de los clientes corta con los timeouts`() {
        val config = ClientsConfig()
        val timeouts = HttpTimeouts(read = Duration.ofMillis(100))
        val client =
            config
                .restClientBuilder(
                    "http://permission-service:8080",
                    "http://language-service:8080",
                    config.timeoutsByService(timeouts, "http://language-service:8080"),
                ).build()

        assertThrows<ResourceAccessException> {
            client
                .get()
                .uri("http://localhost:$port/asset")
                .retrieve()
                .toBodilessEntity()
        }
    }

    @Test
    fun `los timeouts se configuran por propiedades, con defaults`() {
        val properties = mapOf("services.timeouts.connect" to "1s", "services.timeouts.language-read" to "1m")

        val timeouts = Binder(MapConfigurationPropertySource(properties)).bind("services.timeouts", HttpTimeouts::class.java).get()

        assertEquals(HttpTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofMinutes(1)), timeouts)
    }
}
