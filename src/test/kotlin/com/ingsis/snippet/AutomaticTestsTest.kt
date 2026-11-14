package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.clients.RunOutcome
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.storage.ContentStore
import com.ingsis.snippet.tests.TestCase
import com.ingsis.snippet.tests.TestCaseRepository
import com.ingsis.snippet.tests.TestStatus
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.web.client.ResourceAccessException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * US16: al modificar un snippet se corren todos sus tests. Corren en otro hilo, después de que el
 * PUT respondió: los tests esperan a que terminen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutomaticTestsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val tests: TestCaseRepository,
    @Autowired private val contents: ContentStore,
    @Autowired @Qualifier("applicationTaskExecutor") private val executor: ThreadPoolTaskExecutor,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    private val code = "println(\"Hola\");"

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val updated = "let n: string = readInput(\"?\");\nprintln(\"Hola \" + n);"
    private lateinit var snippet: Snippet
    private lateinit var ana: TestCase
    private lateinit var beto: TestCase

    @BeforeEach
    fun setUp() {
        snippet = snippets.save(Snippet("Saludo", "Saluda", "printscript", "1.1", "auth0|ana"), code, contents)
        ana = tests.save(TestCase(snippet, "Saluda a Ana", mutableListOf("Ana"), mutableListOf("Hola Ana")))
        beto = tests.save(TestCase(snippet, "Saluda a Beto", mutableListOf("Beto"), mutableListOf("Chau Beto")))
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito
            .`when`(languageClient.run(updated, "printscript", "1.1", listOf("Ana")))
            .thenReturn(RunOutcome(listOf("Hola Ana"), null))
        Mockito
            .`when`(languageClient.run(updated, "printscript", "1.1", listOf("Beto")))
            .thenReturn(RunOutcome(listOf("Hola Beto"), null))
    }

    @AfterEach
    fun cleanUp() {
        tests.deleteAll()
        snippets.deleteAll()
    }

    private fun update(content: String = updated) =
        mockMvc.put("/snippets/${snippet.id}") {
            with(asUser("auth0|ana"))
            contentType = MediaType.APPLICATION_JSON
            this.content =
                """{"name": "Saludo", "description": "Saluda", "language": "printscript", "version": "1.1",
                   "content": ${quote(content)}}"""
        }

    private fun statusOf(test: TestCase) = tests.findById(test.id!!).get().status

    /** Espera a que la corrida en segundo plano deje a cada test como se espera. */
    private fun eventually(vararg expected: Pair<TestCase, TestStatus>) {
        await atMost Duration.ofSeconds(5) untilAsserted {
            expected.forEach { (test, status) -> assertEquals(status, statusOf(test), test.name) }
        }
    }

    private fun recordRuns(vararg cases: TestCase) = cases.forEach { tests.save(it.apply { status = TestStatus.PASSED }) }

    @Test
    fun `un test nuevo arranca sin correr`() {
        assertEquals(TestStatus.PENDING, statusOf(ana))
    }

    @Test
    fun `al actualizar el snippet se corren todos sus tests y queda si pasaron o fallaron`() {
        update().andExpect { status { isOk() } }

        eventually(ana to TestStatus.PASSED, beto to TestStatus.FAILED)
    }

    @Test
    fun `el PUT responde sin esperar a los tests, que quedan pendientes hasta que terminan`() {
        recordRuns(ana, beto)
        val languageServiceAnswers = CountDownLatch(1)
        Mockito.`when`(languageClient.run(updated, "printscript", "1.1", listOf("Ana"))).thenAnswer {
            languageServiceAnswers.await(5, TimeUnit.SECONDS)
            RunOutcome(listOf("Hola Ana"), null)
        }

        update().andExpect { status { isOk() } }

        assertEquals(TestStatus.PENDING, statusOf(ana))
        assertEquals(TestStatus.PENDING, statusOf(beto))
        languageServiceAnswers.countDown()
        eventually(ana to TestStatus.PASSED, beto to TestStatus.FAILED)
    }

    @Test
    fun `el resultado se ve al listar los tests`() {
        update()
        eventually(ana to TestStatus.PASSED, beto to TestStatus.FAILED)

        mockMvc.get("/snippets/${snippet.id}/tests") { with(asUser("auth0|ana")) }.andExpect {
            jsonPath("$[?(@.name == 'Saluda a Ana')].status") { value("PASSED") }
            jsonPath("$[?(@.name == 'Saluda a Beto')].status") { value("FAILED") }
        }
    }

    @Test
    fun `que fallen los tests no cambia el update`() {
        update().andExpect {
            status { isOk() }
            jsonPath("$.content") { value(updated) }
        }
        assertEquals(updated, contents.load(snippet.id!!))
        eventually(beto to TestStatus.FAILED)
    }

    @Test
    fun `un error al ejecutar cuenta como test fallido`() {
        Mockito
            .`when`(languageClient.run(updated, "printscript", "1.1", listOf("Ana")))
            .thenReturn(RunOutcome(emptyList(), CodeError("No hay más inputs", 1, 17)))

        update()

        eventually(ana to TestStatus.FAILED)
    }

    @Test
    fun `si un test no se puede correr queda pendiente y los demas se corren igual`() {
        recordRuns(ana)
        Mockito
            .`when`(languageClient.run(updated, "printscript", "1.1", listOf("Ana")))
            .thenThrow(ResourceAccessException("language-service no responde"))

        update().andExpect { status { isOk() } }

        eventually(ana to TestStatus.PENDING, beto to TestStatus.FAILED)
    }

    @Test
    fun `un update invalido no corre los tests ni les cambia el resultado`() {
        recordRuns(ana)
        Mockito
            .`when`(languageClient.validate("println(", "printscript", "1.1"))
            .thenReturn(listOf(CodeError("Falta un paréntesis", 1, 9)))

        update(content = "println(").andExpect { status { isUnprocessableContent() } }

        verify(languageClient, never()).run(anyString(), anyString(), anyString(), anyList())
        assertEquals(TestStatus.PASSED, statusOf(ana))
    }

    @Test
    fun `correr un test a mano tambien guarda el resultado`() {
        Mockito
            .`when`(languageClient.run(code, "printscript", "1.1", listOf("Ana")))
            .thenReturn(RunOutcome(listOf("Hola"), null))

        mockMvc.post("/snippets/${snippet.id}/tests/${ana.id}/run") { with(asUser("auth0|ana")) }.andExpect {
            jsonPath("$.passed") { value(false) }
        }

        assertEquals(TestStatus.FAILED, statusOf(ana))
    }

    /** Espera a que no quede ninguna corrida en segundo plano, ni siquiera una cuyo resultado se descarta. */
    private fun allRunsFinished() {
        await atMost Duration.ofSeconds(5) until { executor.activeCount == 0 }
    }

    @Test
    fun `si se actualiza dos veces seguidas quedan los resultados del codigo nuevo, aunque la corrida vieja termine ultima`() {
        val first = "println(\"Chau\");"
        val firstRunStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Con el primer código, Ana falla y Beto pasa: lo contrario que con el segundo.
        for (name in listOf("Ana", "Beto")) {
            Mockito.`when`(languageClient.run(first, "printscript", "1.1", listOf(name))).thenAnswer {
                firstRunStarted.countDown()
                release.await(5, TimeUnit.SECONDS)
                RunOutcome(listOf("Chau $name"), null)
            }
        }

        update(first).andExpect { status { isOk() } }
        assertTrue(firstRunStarted.await(5, TimeUnit.SECONDS))
        update(updated).andExpect { status { isOk() } }
        eventually(ana to TestStatus.PASSED, beto to TestStatus.FAILED)
        release.countDown()
        allRunsFinished()

        // La corrida del primer código terminó, pero sus resultados no se guardaron.
        verify(languageClient).run(first, "printscript", "1.1", listOf("Ana"))
        verify(languageClient).run(first, "printscript", "1.1", listOf("Beto"))
        assertEquals(TestStatus.PASSED, statusOf(ana))
        assertEquals(TestStatus.FAILED, statusOf(beto))
    }

    @Test
    fun `si editan el test mientras corre no se guarda el resultado de sus inputs anteriores`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.`when`(languageClient.run(updated, "printscript", "1.1", listOf("Ana"))).thenAnswer {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            RunOutcome(listOf("Hola Ana"), null)
        }

        update().andExpect { status { isOk() } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        mockMvc
            .put("/snippets/${snippet.id}/tests/${ana.id}") {
                with(asUser("auth0|ana"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": "Saluda a Ana", "inputs": ["Ana"], "outputs": ["Chau Ana"]}"""
            }.andExpect { status { isOk() } }
        release.countDown()
        allRunsFinished()

        assertEquals(TestStatus.PENDING, statusOf(ana))
        assertEquals(TestStatus.FAILED, statusOf(beto))
    }
}
