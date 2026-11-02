package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.clients.RunOutcome
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import com.ingsis.snippet.storage.ContentStore
import com.ingsis.snippet.tests.TestCaseRepository
import com.ingsis.snippet.tests.TestStatus
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals

/** US8: crear tests para un snippet propio. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestCaseTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val tests: TestCaseRepository,
    @Autowired private val contents: ContentStore,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    private val code = "let n: string = readInput(\"?\");"

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet =
            snippets.save(Snippet("Saludo", "Saluda a quien ingrese", "printscript", "1.1", "auth0|ana"), code, contents)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
    }

    @AfterEach
    fun cleanUp() {
        tests.deleteAll()
        snippets.deleteAll()
    }

    private fun create(
        body: String,
        user: String = "auth0|ana",
        snippetId: UUID = snippet.id!!,
    ) = mockMvc.post("/snippets/$snippetId/tests") {
        header(USER_HEADER, user)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun list(user: String) = mockMvc.get("/snippets/${snippet.id}/tests") { header(USER_HEADER, user) }

    private val greeting = """{"name": "Saluda a dos", "inputs": ["Ana", "Beto"], "outputs": ["Hola Ana", "Hola Beto", "Chau"]}"""

    @Test
    fun `el owner crea un test con sus inputs y outputs`() {
        create(greeting).andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.name") { value("Saluda a dos") }
            jsonPath("$.inputs") { value(contains("Ana", "Beto")) }
            jsonPath("$.outputs") { value(contains("Hola Ana", "Hola Beto", "Chau")) }
        }
        assertEquals(1, tests.count())
    }

    @Test
    fun `los inputs y outputs mantienen su orden`() {
        val inputs = (1..12).map { "input $it" }
        val outputs = listOf("z", "a", "m", "a")
        create(
            """{"name": "Orden", "inputs": [${inputs.joinToString { quote(it) }}], "outputs": [${outputs.joinToString { quote(it) }}]}""",
        )

        list("auth0|ana").andExpect {
            jsonPath("$[0].inputs") { value(contains(*inputs.toTypedArray())) }
            jsonPath("$[0].outputs") { value(contains(*outputs.toTypedArray())) }
        }
    }

    @Test
    fun `un test puede no tener inputs ni outputs`() {
        create("""{"name": "Sin nada"}""").andExpect {
            status { isCreated() }
            jsonPath("$.inputs.length()") { value(0) }
            jsonPath("$.outputs.length()") { value(0) }
        }
    }

    @Test
    fun `un test se guarda sin correr el snippet, aunque sus outputs no sean los que produce`() {
        create(greeting).andExpect { status { isCreated() } }
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `alguien con permiso de lectura no puede crear tests`() {
        create(greeting, user = "auth0|beto").andExpect { status { isForbidden() } }
        assertEquals(0, tests.count())
    }

    @Test
    fun `alguien sin permisos no puede crear tests`() {
        create(greeting, user = "auth0|carla").andExpect { status { isForbidden() } }
        assertEquals(0, tests.count())
    }

    @Test
    fun `crear un test para un snippet que no existe es un 404`() {
        create(greeting, snippetId = UUID.randomUUID()).andExpect { status { isNotFound() } }
    }

    @Test
    fun `un test sin nombre es un 400`() {
        create("""{"name": "", "inputs": [], "outputs": []}""").andExpect { status { isBadRequest() } }
        assertEquals(0, tests.count())
    }

    @Test
    fun `los tests se listan ordenados por nombre`() {
        create("""{"name": "Segundo"}""")
        create("""{"name": "Primero"}""")

        list("auth0|ana").andExpect {
            status { isOk() }
            jsonPath("$[*].name") { value(contains("Primero", "Segundo")) }
        }
    }

    @Test
    fun `alguien con quien se compartio el snippet ve sus tests`() {
        create(greeting)
        list("auth0|beto").andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("Saluda a dos") }
        }
    }

    @Test
    fun `alguien sin permisos no ve los tests`() {
        create(greeting)
        list("auth0|carla").andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("No tenés acceso a este snippet") }
        }
    }

    // US6: correr un test desde la vista del snippet.

    private fun createdTestId(body: String = greeting): String {
        val response = create(body).andReturn().response.contentAsString
        return Regex("\"id\":\"([^\"]+)\"").find(response)!!.groupValues[1]
    }

    private fun runTest(
        testId: String,
        user: String = "auth0|ana",
        snippetId: UUID = snippet.id!!,
    ) = mockMvc.post("/snippets/$snippetId/tests/$testId/run") { header(USER_HEADER, user) }

    private fun languageServiceAnswers(outcome: RunOutcome) {
        Mockito
            .`when`(languageClient.run(code, "printscript", "1.1", listOf("Ana", "Beto")))
            .thenReturn(outcome)
    }

    @Test
    fun `un test pasa si el snippet imprime exactamente los outputs esperados`() {
        val testId = createdTestId()
        languageServiceAnswers(RunOutcome(listOf("Hola Ana", "Hola Beto", "Chau"), error = null))

        runTest(testId).andExpect {
            status { isOk() }
            jsonPath("$.passed") { value(true) }
            jsonPath("$.expected") { value(contains("Hola Ana", "Hola Beto", "Chau")) }
            jsonPath("$.actual") { value(contains("Hola Ana", "Hola Beto", "Chau")) }
            jsonPath("$.error") { value(null) }
        }
    }

    @Test
    fun `un test falla si los outputs no coinciden o vienen en otro orden`() {
        val testId = createdTestId()
        languageServiceAnswers(RunOutcome(listOf("Hola Beto", "Hola Ana", "Chau"), error = null))

        runTest(testId).andExpect {
            jsonPath("$.passed") { value(false) }
            jsonPath("$.actual") { value(contains("Hola Beto", "Hola Ana", "Chau")) }
        }
    }

    @Test
    fun `un test falla si el snippet termina con error, aunque haya impreso lo esperado`() {
        val testId = createdTestId()
        languageServiceAnswers(
            RunOutcome(listOf("Hola Ana", "Hola Beto", "Chau"), CodeError("Division por cero", 4, 9)),
        )

        runTest(testId).andExpect {
            jsonPath("$.passed") { value(false) }
            jsonPath("$.error.rule") { value("Division por cero") }
            jsonPath("$.error.line") { value(4) }
        }
    }

    @Test
    fun `alguien con quien se compartio el snippet puede correr sus tests`() {
        val testId = createdTestId()
        languageServiceAnswers(RunOutcome(listOf("Hola Ana", "Hola Beto", "Chau"), error = null))

        runTest(testId, user = "auth0|beto").andExpect {
            status { isOk() }
            jsonPath("$.passed") { value(true) }
        }
    }

    @Test
    fun `alguien sin permisos no puede correr los tests`() {
        val testId = createdTestId()
        runTest(testId, user = "auth0|carla").andExpect { status { isForbidden() } }
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `correr un test que no existe es un 404`() {
        runTest(UUID.randomUUID().toString()).andExpect { status { isNotFound() } }
    }

    // Editar y borrar un test, desde la UI.

    private fun update(
        testId: String,
        body: String,
        user: String = "auth0|ana",
    ) = mockMvc.put("/snippets/${snippet.id}/tests/$testId") {
        header(USER_HEADER, user)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun delete(
        testId: String,
        user: String = "auth0|ana",
        snippetId: UUID = snippet.id!!,
    ) = mockMvc.delete("/snippets/$snippetId/tests/$testId") { header(USER_HEADER, user) }

    @Test
    fun `el owner edita un test y se reemplazan todos sus datos`() {
        val testId = createdTestId()

        update(testId, """{"name": "Saluda a uno", "inputs": ["Carla"], "outputs": ["Hola Carla"]}""").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(testId) }
            jsonPath("$.name") { value("Saluda a uno") }
            jsonPath("$.inputs") { value(contains("Carla")) }
            jsonPath("$.outputs") { value(contains("Hola Carla")) }
        }
        list("auth0|ana").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].inputs") { value(contains("Carla")) }
        }
    }

    @Test
    fun `un test editado vuelve a PENDING porque su resultado era de los datos anteriores`() {
        val testId = createdTestId()
        languageServiceAnswers(RunOutcome(listOf("Hola Ana", "Hola Beto", "Chau"), error = null))
        runTest(testId)
        assertEquals(TestStatus.PASSED, tests.findAll().single().status)

        update(testId, greeting).andExpect { jsonPath("$.status") { value("PENDING") } }
    }

    @Test
    fun `alguien con permiso de lectura no puede editar ni borrar tests`() {
        val testId = createdTestId()

        update(testId, """{"name": "Otro"}""", user = "auth0|beto").andExpect { status { isForbidden() } }
        delete(testId, user = "auth0|beto").andExpect { status { isForbidden() } }
        list("auth0|ana").andExpect { jsonPath("$[0].name") { value("Saluda a dos") } }
    }

    @Test
    fun `editar un test sin nombre es un 400`() {
        val testId = createdTestId()
        update(testId, """{"name": ""}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `editar un test que no existe es un 404`() {
        update(UUID.randomUUID().toString(), greeting).andExpect { status { isNotFound() } }
    }

    @Test
    fun `el owner borra un test y los demas quedan`() {
        val testId = createdTestId()
        create("""{"name": "Otro"}""")

        delete(testId).andExpect { status { isNoContent() } }

        list("auth0|ana").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("Otro") }
        }
    }

    @Test
    fun `borrar un test que no existe es un 404`() {
        delete(UUID.randomUUID().toString()).andExpect { status { isNotFound() } }
    }

    @Test
    fun `un test de otro snippet no se puede borrar desde este`() {
        val testId = createdTestId()
        val other = snippets.save(Snippet("Otro", "d", "printscript", "1.1", "auth0|ana"), "println(1);", contents)
        Mockito.`when`(permissionClient.roleOf(other.id!!, "auth0|ana")).thenReturn(Role.OWNER)

        delete(testId, snippetId = other.id!!).andExpect { status { isNotFound() } }
        assertEquals(1, tests.count())
    }

    @Test
    fun `un test de otro snippet no se puede correr desde este`() {
        val testId = createdTestId()
        val other = snippets.save(Snippet("Otro", "d", "printscript", "1.1", "auth0|ana"), "println(1);", contents)
        Mockito.`when`(permissionClient.roleOf(other.id!!, "auth0|ana")).thenReturn(Role.OWNER)

        runTest(testId, snippetId = other.id!!).andExpect { status { isNotFound() } }
        verifyNoInteractions(languageClient)
    }
}
