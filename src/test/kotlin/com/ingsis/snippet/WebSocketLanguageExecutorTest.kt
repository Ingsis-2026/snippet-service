package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.execution.ExecutionListener
import com.ingsis.snippet.execution.ExecutionRequest
import com.ingsis.snippet.execution.LanguageUnavailableException
import com.ingsis.snippet.execution.WebSocketLanguageExecutor
import jakarta.servlet.ServletContainerInitializer
import jakarta.servlet.http.HttpServlet
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.MessageHandler
import jakarta.websocket.Session
import jakarta.websocket.server.ServerContainer
import jakarta.websocket.server.ServerEndpointConfig
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.websocket.server.WsSci
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * El cliente del WebSocket `/execution` de language-service contra un language-service de mentira:
 * un Tomcat con ese endpoint, que responde según el código que le llega.
 */
class WebSocketLanguageExecutorTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val tomcat = Tomcat()

    /** Lo que le llegó al language-service de mentira, en orden. */
    private val received = LinkedBlockingQueue<Map<*, *>>()
    private val closedByClient = LinkedBlockingQueue<Boolean>()

    /** Lo que le avisó el executor al listener, en orden. */
    private val events = LinkedBlockingQueue<String>()

    private val listener =
        object : ExecutionListener {
            override fun output(value: String) = events.put("output:$value")

            override fun inputRequest(message: String) = events.put("input-request:$message")

            override fun done(error: CodeError?) = events.put("done:$error")

            override fun failure(message: String) = events.put("failure:$message")
        }

    private val endpoint =
        object : Endpoint() {
            override fun onOpen(
                session: Session,
                config: EndpointConfig,
            ) {
                session.addMessageHandler(
                    String::class.java,
                    MessageHandler.Whole { payload -> respond(session, json.readValue(payload, Map::class.java)) },
                )
            }

            override fun onClose(
                session: Session,
                closeReason: jakarta.websocket.CloseReason,
            ) {
                closedByClient.put(true)
            }
        }

    /** Responde como language-service, según el código que llega en `start`. */
    private fun respond(
        session: Session,
        message: Map<*, *>,
    ) {
        received.put(message)
        val send = { text: String -> session.basicRemote.sendText(text) }
        when (message["type"] to message["code"]) {
            "start" to "saluda" -> {
                send("""{"type": "output", "value": "Hola"}""")
                send("""{"type": "input-request", "message": "Nombre?"}""")
            }
            "start" to "falla" -> send("""{"type": "failure", "message": "Lenguaje no soportado: cobol"}""")
            "start" to "se corta" -> session.close()
            "start" to "espera" -> Unit
            else -> {
                send("""{"type": "output", "value": "Chau ${message["value"]}"}""")
                send("""{"type": "done", "error": {"rule": "Algo falló", "line": 3, "column": 1}}""")
                send("""{"type": "output", "value": "después de done no llega"}""")
                session.close()
            }
        }
    }

    private lateinit var executor: WebSocketLanguageExecutor

    @BeforeEach
    fun startFakeLanguageService(
        @TempDir baseDir: Path,
    ) {
        tomcat.setBaseDir(baseDir.toString())
        tomcat.setPort(0)
        val context = tomcat.addContext("", baseDir.toString())
        // Sin ningún servlet, Tomcat responde 404 antes de llegar al filtro que hace el upgrade a WebSocket.
        Tomcat.addServlet(context, "vacio", object : HttpServlet() {})
        context.addServletMappingDecoded("/", "vacio")
        context.addServletContainerInitializer(WsSci(), null)
        context.addServletContainerInitializer(
            ServletContainerInitializer { _, servletContext ->
                val container = servletContext.getAttribute(ServerContainer::class.java.name) as ServerContainer
                container.addEndpoint(
                    ServerEndpointConfig.Builder
                        .create(endpoint.javaClass, "/execution")
                        .configurator(
                            object : ServerEndpointConfig.Configurator() {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T> getEndpointInstance(endpointClass: Class<T>): T = endpoint as T
                            },
                        ).build(),
                )
            },
            null,
        )
        tomcat.connector
        tomcat.start()
        executor = WebSocketLanguageExecutor("http://localhost:${tomcat.connector.localPort}", json)
    }

    @AfterEach
    fun stop() {
        tomcat.stop()
        tomcat.destroy()
    }

    private fun request(code: String) = ExecutionRequest(code, "printscript", "1.1", listOf("a", "b"), interactive = true)

    private fun nextEvent() = events.poll(5, TimeUnit.SECONDS) ?: error("No llegó ningún aviso")

    @Test
    fun `manda start con todo lo de la ejecucion y le pasa al listener lo que llega`() {
        val execution = executor.start(request("saluda"), listener)

        val start = received.poll(5, TimeUnit.SECONDS)!!
        assertEquals(
            mapOf(
                "type" to "start",
                "code" to "saluda",
                "language" to "printscript",
                "version" to "1.1",
                "inputs" to listOf("a", "b"),
                "interactive" to true,
            ),
            start,
        )
        assertEquals("output:Hola", nextEvent())
        assertEquals("input-request:Nombre?", nextEvent())

        execution.input("Ana")

        assertEquals(mapOf("type" to "input", "value" to "Ana"), received.poll(5, TimeUnit.SECONDS))
        assertEquals("output:Chau Ana", nextEvent())
        assertEquals("done:${CodeError("Algo falló", 3, 1)}", nextEvent())
        // Ni lo que llega después de done ni el cierre se avisan.
        assertNull(events.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `un failure de language-service se avisa`() {
        executor.start(request("falla"), listener)

        assertEquals("failure:Lenguaje no soportado: cobol", nextEvent())
    }

    @Test
    fun `si language-service corta sin done se avisa como failure`() {
        executor.start(request("se corta"), listener)

        assertEquals("failure:Se perdió la conexión con language-service", nextEvent())
    }

    @Test
    fun `cancelar cierra la conexion sin avisar nada`() {
        val execution = executor.start(request("espera"), listener)
        received.poll(5, TimeUnit.SECONDS)

        execution.cancel()

        assertEquals(true, closedByClient.poll(5, TimeUnit.SECONDS))
        assertNull(events.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `si language-service no esta, no se puede empezar`() {
        val port = tomcat.connector.localPort
        tomcat.stop()

        assertThrows<LanguageUnavailableException> {
            WebSocketLanguageExecutor("http://localhost:$port", json).start(request("saluda"), listener)
        }
    }
}
