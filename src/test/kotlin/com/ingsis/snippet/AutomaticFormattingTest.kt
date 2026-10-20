package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.formatting.SnippetFormatter
import com.ingsis.snippet.rules.UserRuleRepository
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import com.ingsis.snippet.snippets.ValidityStatus
import com.ingsis.snippet.storage.ContentStore
import com.ingsis.snippet.storage.StorageException
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
import org.springframework.test.web.servlet.put
import org.springframework.web.client.ResourceAccessException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** US12: al modificar una regla de formato se formatean todos los snippets del usuario, sin esperarlos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutomaticFormattingTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val rules: UserRuleRepository,
    @Autowired private val contents: ContentStore,
    @Autowired private val queue: RecordingFormatQueue,
    @Autowired private val lintQueue: RecordingLintQueue,
    @Autowired private val formatter: SnippetFormatter,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val spacing = mapOf("enforce-spacing-around-equals" to true)
    private val messy = "let x:number=5;"
    private val tidy = "let x:number = 5;"
    private lateinit var messySnippet: Snippet
    private lateinit var tidySnippet: Snippet
    private lateinit var betoSnippet: Snippet

    @BeforeEach
    fun setUp() {
        messySnippet = save("Desprolijo", messy, "auth0|ana")
        tidySnippet = save("Prolijo", tidy, "auth0|ana")
        betoSnippet = save("De Beto", messy, "auth0|beto")
        queue.clear()
        lintQueue.clear()
        Mockito.`when`(languageClient.format(messy, "printscript", "1.1", spacing)).thenReturn(tidy)
        Mockito.`when`(languageClient.format(tidy, "printscript", "1.1", spacing)).thenReturn(tidy)
    }

    @AfterEach
    fun cleanUp() {
        snippets.deleteAll()
        rules.deleteAll()
        queue.clear()
        lintQueue.clear()
    }

    private fun save(
        name: String,
        code: String,
        owner: String,
    ) = snippets.save(Snippet(name, "Declara una variable", "printscript", "1.1", owner, ValidityStatus.COMPLIANT), code, contents)

    private fun changeFormattingRules(body: String = """[{"name": "enforce-spacing-around-equals", "isActive": true}]""") =
        mockMvc.put("/rules/formatting") {
            header(USER_HEADER, "auth0|ana")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun statusOf(snippet: Snippet) = snippets.findById(snippet.id!!).get().status

    @Test
    fun `cambiar una regla de formato encola todos los snippets del usuario y solo los suyos`() {
        changeFormattingRules().andExpect { status { isOk() } }

        assertEquals(setOf(messySnippet.id, tidySnippet.id), queue.queued().toSet())
    }

    @Test
    fun `responde sin formatear y el codigo queda como estaba hasta que se procesa`() {
        changeFormattingRules().andExpect { status { isOk() } }

        verify(languageClient, never()).format(anyString(), anyString(), anyString(), anyMap())
        assertEquals(messy, contents.load(messySnippet.id!!))
    }

    @Test
    fun `al procesarlos se guarda el codigo formateado con las reglas nuevas`() {
        changeFormattingRules()

        queue.processAll()

        assertEquals(tidy, contents.load(messySnippet.id!!))
        assertEquals(tidy, contents.load(tidySnippet.id!!))
        assertEquals(messy, contents.load(betoSnippet.id!!))
    }

    @Test
    fun `un snippet cuyo codigo cambio queda pendiente y se encola para lintear`() {
        changeFormattingRules()

        queue.processAll()

        assertEquals(ValidityStatus.PENDING, statusOf(messySnippet))
        assertEquals(listOf(messySnippet.id), lintQueue.queued())
    }

    @Test
    fun `un snippet que ya estaba formateado no se toca ni se relintea`() {
        changeFormattingRules()

        formatter.format(tidySnippet.id!!)

        assertEquals(ValidityStatus.COMPLIANT, statusOf(tidySnippet))
        assertTrue(lintQueue.queued().isEmpty())
    }

    @Test
    fun `si se corto despues de guardar el codigo, el reintento igual lo encola para lintear`() {
        changeFormattingRules()
        snippets.updateStatus(tidySnippet.id!!, ValidityStatus.PENDING)

        formatter.format(tidySnippet.id!!)

        assertEquals(listOf(tidySnippet.id), lintQueue.queued())
    }

    @Test
    fun `formatear dos veces deja lo mismo que formatear una`() {
        changeFormattingRules()

        formatter.format(messySnippet.id!!)
        formatter.format(messySnippet.id!!)

        assertEquals(tidy, contents.load(messySnippet.id!!))
    }

    @Test
    fun `se formatea con las reglas que hay al procesar, no con las que habia al encolar`() {
        changeFormattingRules()
        changeFormattingRules("""[{"name": "enforce-spacing-around-equals", "isActive": false}]""")
        Mockito.`when`(languageClient.format(messy, "printscript", "1.1", emptyMap())).thenReturn(messy)
        Mockito.`when`(languageClient.format(tidy, "printscript", "1.1", emptyMap())).thenReturn(tidy)

        queue.processAll()

        assertEquals(messy, contents.load(messySnippet.id!!))
    }

    @Test
    fun `no pisa el codigo si el owner lo actualizo mientras se formateaba`() {
        changeFormattingRules()
        val edited = "let y:number=6;"
        Mockito.`when`(languageClient.format(messy, "printscript", "1.1", spacing)).thenAnswer {
            contents.save(messySnippet.id!!, edited)
            tidy
        }

        formatter.format(messySnippet.id!!)

        assertEquals(edited, contents.load(messySnippet.id!!))
        assertTrue(lintQueue.queued().isEmpty())
    }

    @Test
    fun `cambiar reglas de lint no formatea nada`() {
        mockMvc
            .put("/rules/linting") {
                header(USER_HEADER, "auth0|ana")
                contentType = MediaType.APPLICATION_JSON
                content = """[{"name": "identifier_format", "isActive": true, "value": "snake case"}]"""
            }.andExpect { status { isOk() } }

        assertTrue(queue.queued().isEmpty())
    }

    @Test
    fun `un cambio invalido no encola nada`() {
        changeFormattingRules("""[{"name": "no-existe", "isActive": true}]""").andExpect { status { is4xxClientError() } }

        assertTrue(queue.queued().isEmpty())
    }

    @Test
    fun `un pedido sin cambios no formatea`() {
        changeFormattingRules("[]").andExpect { status { isOk() } }

        assertTrue(queue.queued().isEmpty())
    }

    @Test
    fun `un snippet que ya no existe se saltea`() {
        formatter.format(UUID.randomUUID())

        assertTrue(lintQueue.queued().isEmpty())
    }

    @Test
    fun `si no se puede formatear el error sube y el codigo queda como estaba`() {
        changeFormattingRules()
        Mockito
            .`when`(languageClient.format(messy, "printscript", "1.1", spacing))
            .thenThrow(ResourceAccessException("language-service no responde"))

        assertFailsWith<ResourceAccessException> { formatter.format(messySnippet.id!!) }

        assertEquals(messy, contents.load(messySnippet.id!!))
        assertEquals(ValidityStatus.COMPLIANT, statusOf(messySnippet))
        assertTrue(lintQueue.queued().isEmpty())
    }

    @Test
    fun `si no se puede leer el codigo el error sube`() {
        val missing = snippets.save(Snippet("Sin código", "No tiene blob", "printscript", "1.1", "auth0|ana"))

        assertFailsWith<StorageException> { formatter.format(missing.id!!) }
    }
}
