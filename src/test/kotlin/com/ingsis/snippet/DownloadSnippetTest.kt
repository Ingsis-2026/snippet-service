package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.formatting.SnippetFormatter
import com.ingsis.snippet.rules.RuleChange
import com.ingsis.snippet.rules.RuleService
import com.ingsis.snippet.rules.RuleType
import com.ingsis.snippet.rules.UserRuleRepository
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.storage.ContentStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals

/** US13: descargar un snippet. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DownloadSnippetTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val rules: UserRuleRepository,
    @Autowired private val ruleService: RuleService,
    @Autowired private val contents: ContentStore,
    @Autowired private val formatter: SnippetFormatter,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val code = "let x:number=5;\nprintln(x);"
    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet = snippets.save(Snippet("Saludo", "Imprime un número", "printscript", "1.0", "auth0|ana"), code, contents)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
        ruleService.update("auth0|ana", RuleType.FORMATTING, listOf(RuleChange("enforce-spacing-around-equals", true, null)))
    }

    @AfterEach
    fun cleanUp() {
        snippets.deleteAll()
        rules.deleteAll()
    }

    private fun download(
        user: String,
        formatted: Boolean? = null,
        id: UUID = snippet.id!!,
    ) = mockMvc.get("/snippets/$id/download") {
        with(asUser(user))
        formatted?.let { param("formatted", it.toString()) }
    }

    @Test
    fun `por defecto se descarga la version original como archivo`() {
        download("auth0|ana").andExpect {
            status { isOk() }
            content { contentType("text/plain;charset=UTF-8") }
            content { string(code) }
            header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Saludo.ps\"; filename*=UTF-8''Saludo.ps") }
        }
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `la version formateada usa las reglas del owner`() {
        Mockito
            .`when`(languageClient.format(code, "printscript", "1.0", mapOf("enforce-spacing-around-equals" to true)))
            .thenReturn("let x:number = 5;\nprintln(x);")

        download("auth0|beto", formatted = true).andExpect {
            status { isOk() }
            content { string("let x:number = 5;\nprintln(x);") }
        }
    }

    @Test
    fun `alguien con quien se compartio lo descarga`() {
        download("auth0|beto").andExpect {
            status { isOk() }
            content { string(code) }
        }
    }

    @Test
    fun `alguien sin permisos no lo descarga`() {
        download("auth0|carla").andExpect { status { isForbidden() } }
    }

    @Test
    fun `un snippet que no existe es un 404`() {
        download("auth0|ana", id = UUID.randomUUID()).andExpect { status { isNotFound() } }
    }

    @Test
    fun `un lenguaje que no se conoce se descarga como texto`() {
        val other = snippets.save(Snippet("Script", "Otro lenguaje", "python", "3", "auth0|ana"), "print(1)", contents)
        Mockito.`when`(permissionClient.roleOf(other.id!!, "auth0|ana")).thenReturn(Role.OWNER)

        download("auth0|ana", id = other.id!!).andExpect {
            header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Script.txt\"; filename*=UTF-8''Script.txt") }
        }
    }

    private val spacing = mapOf("enforce-spacing-around-equals" to true)
    private val tidy = "let x:number = 5;\nprintln(x);"

    private fun formatAutomatically() {
        Mockito.`when`(languageClient.format(code, "printscript", "1.0", spacing)).thenReturn(tidy)
        Mockito.`when`(languageClient.format(tidy, "printscript", "1.0", spacing)).thenReturn(tidy)
        formatter.format(snippet.id!!)
    }

    @Test
    fun `despues del formateo automatico el original sigue siendo el que escribio el usuario`() {
        formatAutomatically()
        assertEquals(tidy, contents.load(snippet.id!!))

        download("auth0|beto", formatted = false).andExpect { content { string(code) } }
        download("auth0|beto", formatted = true).andExpect { content { string(tidy) } }
    }

    @Test
    fun `actualizar el snippet despues del formateo automatico renueva el original`() {
        formatAutomatically()
        val edited = "let y:number=6;"

        mockMvc
            .put("/snippets/${snippet.id}") {
                with(asUser("auth0|ana"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"name": "Saludo", "description": "Imprime un número", "language": "printscript", "version": "1.0",
                       "content": ${quote(edited)}}"""
            }.andExpect { status { isOk() } }

        download("auth0|ana").andExpect { content { string(edited) } }
        assertEquals(edited, contents.load(snippet.id!!))
    }

    @Test
    fun `un snippet de antes de que se guardara el original se descarga con su codigo actual`() {
        val legacy = snippets.save(Snippet("Viejo", "Sin original", "printscript", "1.0", "auth0|ana"))
        contents.save(legacy.id!!, "println(1);")
        Mockito.`when`(permissionClient.roleOf(legacy.id!!, "auth0|ana")).thenReturn(Role.OWNER)

        download("auth0|ana", id = legacy.id!!).andExpect {
            status { isOk() }
            content { string("println(1);") }
        }
    }
}
