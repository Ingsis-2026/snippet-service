package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.storage.ContentStore
import com.ingsis.snippet.tests.TestCase
import com.ingsis.snippet.tests.TestCaseRepository
import com.ingsis.snippet.tests.TestStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** US9 y US10: correr un snippet o uno de sus tests por WebSocket, viendo cada output a medida que sale. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExecutionSocketTest(
    @LocalServerPort private val port: Int,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val tests: TestCaseRepository,
    @Autowired private val contents: ContentStore,
    @Autowired private val language: ScriptedLanguageExecutor,
) {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @MockitoBean
    private lateinit var languageClient: LanguageClient

    private val json = JsonMapper.builder().build()
    private val sessions = mutableListOf<WebSocketSession>()
    private val code = "let nombre: string = readInput(\"Nombre?\");\nprintln(\"Hola \" + nombre);"
    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet = snippets.save(Snippet("Saludo", "Saluda", "printscript", "1.1", "auth0|ana"), code, contents)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
        Mockito.`when`(jwtDecoder.decode("token-de-ana")).thenReturn(jwt("auth0|ana"))
        Mockito.`when`(jwtDecoder.decode("token-de-beto")).thenReturn(jwt("auth0|beto"))
        Mockito.`when`(jwtDecoder.decode("token-de-carla")).thenReturn(jwt("auth0|carla"))
        Mockito.`when`(jwtDecoder.decode("vencido")).thenThrow(BadJwtException("Jwt expired"))
        language.reset()
    }

    @AfterEach
    fun cleanUp() {
        sessions.filter { it.isOpen }.forEach { it.close() }
        tests.deleteAll()
        snippets.deleteAll()
    }

    private fun jwt(subject: String) =
        Jwt
            .withTokenValue("t")
            .header("alg", "none")
            .subject(subject)
            .build()

    /** Lo que recibe el navegador, en orden; `closed` al final si se cerró la conexión. */
    private inner class Browser(
        origin: String? = null,
    ) {
        val received = LinkedBlockingQueue<JsonNode>()
        val session: WebSocketSession =
            StandardWebSocketClient()
                .execute(
                    object : TextWebSocketHandler() {
                        override fun handleTextMessage(
                            session: WebSocketSession,
                            message: TextMessage,
                        ) {
                            received.put(json.readTree(message.payload))
                        }

                        override fun afterConnectionClosed(
                            session: WebSocketSession,
                            status: CloseStatus,
                        ) {
                            received.put(json.readTree("""{"type": "closed", "code": ${status.code}}"""))
                        }
                    },
                    WebSocketHttpHeaders().apply { origin?.let { set(HttpHeaders.ORIGIN, it) } },
                    URI("ws://localhost:$port/execution"),
                ).get(5, TimeUnit.SECONDS)
                .also(sessions::add)

        fun send(message: Map<String, Any?>) = session.sendMessage(TextMessage(json.writeValueAsString(message)))

        fun start(
            token: String? = "token-de-ana",
            snippetId: UUID? = snippet.id,
            testId: UUID? = null,
        ) = send(mapOf("type" to "start", "token" to token, "snippetId" to snippetId, "testId" to testId))

        fun next(): JsonNode = received.poll(5, TimeUnit.SECONDS) ?: error("No llegó ningún mensaje")

        fun expectFailure(message: String) {
            val failure = next()
            assertEquals("failure", failure.type())
            assertEquals(message, failure.get("message").asString())
            assertEquals("closed", next().type())
        }
    }

    private fun JsonNode.type() = get("type").asString()

    @Test
    fun `interactiva, el navegador ve cada output y da cada input cuando se pide`() {
        val browser = Browser()
        browser.start()

        val execution = language.next()
        assertEquals(code, execution.request.code)
        assertEquals("printscript", execution.request.language)
        assertEquals("1.1", execution.request.version)
        assertTrue(execution.request.interactive)
        assertEquals(emptyList(), execution.request.inputs)

        execution.listener.inputRequest("Nombre?")
        val request = browser.next()
        assertEquals("input-request", request.type())
        assertEquals("Nombre?", request.get("message").asString())

        browser.send(mapOf("type" to "input", "value" to "Ana"))
        assertEquals("Ana", execution.inputs.poll(5, TimeUnit.SECONDS))

        execution.listener.output("Hola Ana")
        assertEquals("Hola Ana", browser.next().get("value").asString())

        execution.listener.done(null)
        val done = browser.next()
        assertEquals("done", done.type())
        assertTrue(done.get("error").isNull)
        assertTrue(done.get("passed").isNull)
        assertEquals(CloseStatus.NORMAL.code, browser.next().get("code").asInt())
    }

    @Test
    fun `alguien a quien se lo compartieron tambien lo puede correr`() {
        Browser().start(token = "token-de-beto")

        assertFalse(language.next().cancelled)
    }

    @Test
    fun `un test corre con sus inputs, sin pedir otros, y al final dice si paso y lo guarda`() {
        val test = tests.save(TestCase(snippet, "Saluda", mutableListOf("Ana"), mutableListOf("Hola Ana")))
        val browser = Browser()
        browser.start(token = "token-de-beto", testId = test.id)

        val execution = language.next()
        assertFalse(execution.request.interactive)
        assertEquals(listOf("Ana"), execution.request.inputs)

        execution.listener.output("Hola Ana")
        assertEquals("Hola Ana", browser.next().get("value").asString())
        execution.listener.done(null)

        assertTrue(browser.next().get("passed").asBoolean())
        assertEquals(TestStatus.PASSED, tests.findById(test.id!!).get().status)
    }

    @Test
    fun `un test que imprime otra cosa o termina con error no pasa`() {
        val test = tests.save(TestCase(snippet, "Saluda", mutableListOf("Ana"), mutableListOf("Hola Ana")))
        val browser = Browser()
        browser.start(testId = test.id)

        val execution = language.next()
        execution.listener.output("Hola")
        browser.next()
        execution.listener.done(CodeError("No hay más inputs", 2, 1))

        val done = browser.next()
        assertFalse(done.get("passed").asBoolean())
        assertEquals(2, done.get("error").get("line").asInt())
        assertEquals(TestStatus.FAILED, tests.findById(test.id!!).get().status)
    }

    @Test
    fun `si borran el test mientras corre igual se dice si paso`() {
        val test = tests.save(TestCase(snippet, "Nada", mutableListOf(), mutableListOf()))
        val browser = Browser()
        browser.start(testId = test.id)
        val execution = language.next()

        tests.deleteAll()
        execution.listener.done(null)

        assertTrue(browser.next().get("passed").asBoolean())
    }

    @Test
    fun `sin token, o con uno que no es valido, no se corre nada`() {
        Browser().apply { start(token = null) }.expectFailure("Falta el token")
        Browser().apply { start(token = "vencido") }.expectFailure("El token no es válido")
        assertTrue(language.noneStarted())
    }

    @Test
    fun `sin acceso al snippet no se corre`() {
        Browser().apply { start(token = "token-de-carla") }.expectFailure("No tenés acceso a este snippet")
        assertTrue(language.noneStarted())
    }

    @Test
    fun `un snippet o un test que no existen no se corren`() {
        Browser().apply { start(snippetId = null) }.expectFailure("Falta el snippet")
        val missing = UUID.randomUUID()
        Browser().apply { start(snippetId = missing) }.expectFailure("No existe el snippet $missing")
        Browser().apply { start(testId = missing) }.expectFailure("No existe el test $missing en este snippet")
        assertTrue(language.noneStarted())
    }

    @Test
    fun `si language-service no responde se avisa`() {
        language.unavailable = true

        Browser().apply { start() }.expectFailure("No se pudo conectar con language-service")
    }

    @Test
    fun `si no se puede leer el codigo se avisa sin detalles`() {
        contents.delete(snippet.id!!)

        Browser().apply { start() }.expectFailure("No se pudo empezar la ejecución")
    }

    @Test
    fun `un failure de language-service llega al navegador`() {
        val browser = Browser()
        browser.start()

        language.next().listener.failure("Se perdió la conexión con language-service")

        val failure = browser.next()
        assertEquals("failure", failure.type())
        assertEquals(CloseStatus.SERVER_ERROR.code, browser.next().get("code").asInt())
    }

    @Test
    fun `si el navegador se va, la ejecucion se corta`() {
        val browser = Browser()
        browser.start()
        val execution = language.next()

        browser.session.close()

        repeat(50) { if (!execution.cancelled) Thread.sleep(100) }
        assertTrue(execution.cancelled)
    }

    @Test
    fun `los mensajes que no son del protocolo cortan la ejecucion`() {
        Browser().apply { session.sendMessage(TextMessage("no es json")) }.expectFailure("Mensaje inválido")
        Browser().apply { send(mapOf("type" to "otro")) }.expectFailure("Tipo de mensaje desconocido: otro")
        Browser().apply { send(mapOf("type" to "input", "value" to "x")) }.expectFailure("La ejecución no empezó")

        val browser = Browser()
        browser.start()
        val execution = language.next()
        browser.send(mapOf("type" to "input"))
        browser.expectFailure("Falta el valor del input")
        assertTrue(execution.cancelled)
    }

    @Test
    fun `un segundo start corta la ejecucion`() {
        val browser = Browser()
        browser.start()
        val execution = language.next()

        browser.start()

        browser.expectFailure("La ejecución ya empezó")
        assertTrue(execution.cancelled)
    }

    @Test
    fun `una pagina de otro origin no puede abrir el WebSocket`() {
        val error = assertThrows<ExecutionException> { Browser(origin = "https://otro-sitio.example") }
        assertNull(sessions.firstOrNull { it.isOpen })
        assertTrue(error.cause.toString().contains("403"))
    }

    @Test
    fun `la UI local, que esta en los origins permitidos, si puede`() {
        Browser(origin = "http://localhost:5173").start()

        language.next()
    }
}
