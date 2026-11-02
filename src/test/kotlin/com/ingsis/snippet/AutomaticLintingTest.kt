package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.linting.SnippetLinter
import com.ingsis.snippet.rules.UserRuleRepository
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import com.ingsis.snippet.snippets.ValidityStatus
import com.ingsis.snippet.storage.ContentStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.web.client.ResourceAccessException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** US15: al modificar una regla de lint se evalúan todos los snippets del usuario, sin esperarlos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutomaticLintingTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val rules: UserRuleRepository,
    @Autowired private val contents: ContentStore,
    @Autowired private val queue: RecordingLintQueue,
    @Autowired private val linter: SnippetLinter,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val snakeCase = mapOf("identifier_format" to "snake case")
    private val camel = "let myVar: number = 1;"
    private val snake = "let my_var: number = 1;"
    private lateinit var camelSnippet: Snippet
    private lateinit var snakeSnippet: Snippet
    private lateinit var betoSnippet: Snippet

    @BeforeEach
    fun setUp() {
        camelSnippet = save("Camel", camel, "auth0|ana", ValidityStatus.COMPLIANT)
        snakeSnippet = save("Snake", snake, "auth0|ana", ValidityStatus.NOT_COMPLIANT)
        betoSnippet = save("De Beto", camel, "auth0|beto", ValidityStatus.COMPLIANT)
        queue.clear()
        Mockito
            .`when`(languageClient.lint(camel, "printscript", "1.1", snakeCase))
            .thenReturn(listOf(CodeError("The following identifier must be in snake case", 1, 5)))
        Mockito.`when`(languageClient.lint(snake, "printscript", "1.1", snakeCase)).thenReturn(emptyList())
    }

    @AfterEach
    fun cleanUp() {
        snippets.deleteAll()
        rules.deleteAll()
        queue.clear()
    }

    private fun save(
        name: String,
        code: String,
        owner: String,
        status: ValidityStatus,
    ) = snippets.save(Snippet(name, "Declara una variable", "printscript", "1.1", owner, status), code, contents)

    private fun changeLintingRules(body: String = """[{"name": "identifier_format", "isActive": true, "value": "snake case"}]""") =
        mockMvc.put("/rules/linting") {
            header(USER_HEADER, "auth0|ana")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun statusOf(snippet: Snippet) = snippets.findById(snippet.id!!).get().status

    @Test
    fun `cambiar una regla de lint encola todos los snippets del usuario y solo los suyos`() {
        changeLintingRules().andExpect { status { isOk() } }

        assertEquals(setOf(camelSnippet.id, snakeSnippet.id), queue.queued().toSet())
    }

    @Test
    fun `responde sin lintear y los snippets quedan pendientes hasta que se procesan`() {
        changeLintingRules().andExpect { status { isOk() } }

        verify(languageClient, never()).lint(anyString(), anyString(), anyString(), anyMap())
        assertEquals(ValidityStatus.PENDING, statusOf(camelSnippet))
        assertEquals(ValidityStatus.PENDING, statusOf(snakeSnippet))
        assertEquals(ValidityStatus.COMPLIANT, statusOf(betoSnippet))
    }

    @Test
    fun `al procesarlos queda si cumplen las reglas nuevas`() {
        changeLintingRules()

        queue.processAll()

        assertEquals(ValidityStatus.NOT_COMPLIANT, statusOf(camelSnippet))
        assertEquals(ValidityStatus.COMPLIANT, statusOf(snakeSnippet))
    }

    @Test
    fun `el resultado se puede filtrar en la lista`() {
        Mockito
            .`when`(permissionClient.snippetsOf("auth0|ana", null))
            .thenReturn(listOf(camelSnippet.id!!, snakeSnippet.id!!))
        changeLintingRules()
        queue.processAll()

        mockMvc
            .get("/snippets?status=NOT_COMPLIANT") { header(USER_HEADER, "auth0|ana") }
            .andExpect {
                jsonPath("$.content.length()") { value(1) }
                jsonPath("$.content[0].name") { value("Camel") }
            }
    }

    @Test
    fun `cambiar reglas de formato no relintea nada`() {
        mockMvc
            .put("/rules/formatting") {
                header(USER_HEADER, "auth0|ana")
                contentType = MediaType.APPLICATION_JSON
                content = """[{"name": "indent-inside-if", "isActive": true, "value": "2"}]"""
            }.andExpect { status { isOk() } }

        assertTrue(queue.queued().isEmpty())
        assertEquals(ValidityStatus.COMPLIANT, statusOf(camelSnippet))
    }

    @Test
    fun `un cambio invalido no deja nada pendiente ni encolado`() {
        changeLintingRules("""[{"name": "identifier_format", "isActive": true, "value": "kebab case"}]""")
            .andExpect { status { isBadRequest() } }

        assertTrue(queue.queued().isEmpty())
        assertEquals(ValidityStatus.COMPLIANT, statusOf(camelSnippet))
    }

    @Test
    fun `un pedido sin cambios no relintea`() {
        changeLintingRules("[]").andExpect { status { isOk() } }

        assertTrue(queue.queued().isEmpty())
    }

    @Test
    fun `se lintea con las reglas que hay al procesar, no con las que habia al encolar`() {
        changeLintingRules()
        changeLintingRules("""[{"name": "identifier_format", "isActive": false}]""")
        Mockito.`when`(languageClient.lint(camel, "printscript", "1.1", emptyMap())).thenReturn(emptyList())

        queue.processAll()

        assertEquals(ValidityStatus.COMPLIANT, statusOf(camelSnippet))
    }

    @Test
    fun `un snippet nuevo tambien se encola para lintear`() {
        Mockito.`when`(languageClient.validate(camel, "printscript", "1.1")).thenReturn(emptyList())

        mockMvc
            .post("/snippets") {
                header(USER_HEADER, "auth0|ana")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"name": "Nuevo", "description": "Uno nuevo", "language": "printscript", "version": "1.1",
                       "content": ${quote(camel)}}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("PENDING") }
            }

        assertEquals(1, queue.queued().size)
    }

    @Test
    fun `un snippet actualizado tambien se encola para lintear`() {
        Mockito.`when`(permissionClient.roleOf(camelSnippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(languageClient.validate(snake, "printscript", "1.1")).thenReturn(emptyList())

        mockMvc
            .put("/snippets/${camelSnippet.id}") {
                header(USER_HEADER, "auth0|ana")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"name": "Camel", "description": "Ahora snake", "language": "printscript", "version": "1.1",
                       "content": ${quote(snake)}}"""
            }.andExpect { status { isOk() } }

        assertEquals(listOf(camelSnippet.id), queue.queued())
    }

    @Test
    fun `lintear solo cambia el estado y no pisa un update que entro mientras tanto`() {
        changeLintingRules()
        Mockito.`when`(languageClient.lint(camel, "printscript", "1.1", snakeCase)).thenAnswer {
            snippets.save(snippets.findById(camelSnippet.id!!).get().apply { name = "Renombrado" })
            listOf(CodeError("The following identifier must be in snake case", 1, 5))
        }

        linter.lint(camelSnippet.id!!)

        val stored = snippets.findById(camelSnippet.id!!).get()
        assertEquals("Renombrado", stored.name)
        assertEquals(ValidityStatus.NOT_COMPLIANT, stored.status)
    }

    @Test
    fun `un snippet que ya no existe se saltea`() {
        linter.lint(UUID.randomUUID())
    }

    @Test
    fun `si no se puede lintear el error sube y el snippet sigue pendiente`() {
        changeLintingRules()
        Mockito
            .`when`(languageClient.lint(camel, "printscript", "1.1", snakeCase))
            .thenThrow(ResourceAccessException("language-service no responde"))

        assertFailsWith<ResourceAccessException> { linter.lint(camelSnippet.id!!) }

        assertEquals(ValidityStatus.PENDING, statusOf(camelSnippet))
    }
}
