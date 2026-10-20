package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.rules.RuleChange
import com.ingsis.snippet.rules.RuleService
import com.ingsis.snippet.rules.RuleType
import com.ingsis.snippet.rules.UserRuleRepository
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import com.ingsis.snippet.storage.ContentStore
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
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

/** Formatear el código del editor sin guardarlo, desde la UI. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FormatSnippetTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val rules: UserRuleRepository,
    @Autowired private val ruleService: RuleService,
    @Autowired private val contents: ContentStore,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val saved = "let x: number = 5;"
    private val editor = "let y:number=6;"
    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet = snippets.save(Snippet("Numero", "Un número", "printscript", "1.0", "auth0|ana"), saved, contents)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
        ruleService.update("auth0|ana", RuleType.FORMATTING, listOf(RuleChange("enforce-spacing-around-equals", true, null)))
    }

    @AfterEach
    fun cleanUp() {
        snippets.deleteAll()
        rules.deleteAll()
    }

    private fun format(
        user: String,
        id: UUID = snippet.id!!,
        body: String = """{"content": "$editor"}""",
    ) = mockMvc.post("/snippets/$id/format") {
        header(USER_HEADER, user)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun languageServiceFormats() {
        Mockito
            .`when`(languageClient.format(editor, "printscript", "1.0", mapOf("enforce-spacing-around-equals" to true)))
            .thenReturn("let y:number = 6;")
    }

    @Test
    fun `formatea el codigo que llega con las reglas del owner, sin guardarlo`() {
        languageServiceFormats()

        format("auth0|ana").andExpect {
            status { isOk() }
            jsonPath("$.content") { value("let y:number = 6;") }
        }
        assertEquals(saved, contents.load(snippet.id!!))
    }

    @Test
    fun `alguien con quien se compartio tambien formatea, con las reglas del owner`() {
        languageServiceFormats()

        format("auth0|beto").andExpect {
            status { isOk() }
            jsonPath("$.content") { value("let y:number = 6;") }
        }
    }

    @Test
    fun `alguien sin permisos no formatea`() {
        format("auth0|carla").andExpect { status { isForbidden() } }
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `formatear en un snippet que no existe es un 404`() {
        format("auth0|ana", id = UUID.randomUUID()).andExpect { status { isNotFound() } }
    }

    @Test
    fun `sin codigo es un 400`() {
        format("auth0|ana", body = "{}").andExpect { status { isBadRequest() } }
        verifyNoInteractions(languageClient)
    }
}
