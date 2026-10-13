package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** US14: ver qué reglas de lint no cumple un snippet. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LintSnippetTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val rules: UserRuleRepository,
    @Autowired private val ruleService: RuleService,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val code = "let my_var: number = 1;"
    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet = snippets.save(Snippet("Variable", "Declara una variable", "printscript", "1.0", code, "auth0|ana"))
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
        ruleService.update("auth0|ana", RuleType.LINTING, listOf(RuleChange("identifier_format", true, null)))
    }

    @AfterEach
    fun cleanUp() {
        snippets.deleteAll()
        rules.deleteAll()
    }

    private fun lint(user: String) = mockMvc.get("/snippets/${snippet.id}/lint") { header(USER_HEADER, user) }

    @Test
    fun `informa las reglas del owner que el snippet no cumple`() {
        Mockito
            .`when`(languageClient.lint(code, "printscript", "1.0", mapOf("identifier_format" to "camel case")))
            .thenReturn(listOf(CodeError("The following identifier must be in camel case", 1, 5)))

        lint("auth0|ana").andExpect {
            status { isOk() }
            jsonPath("$.valid") { value(false) }
            jsonPath("$.errors[0].rule") { value("The following identifier must be in camel case") }
            jsonPath("$.errors[0].line") { value(1) }
            jsonPath("$.errors[0].column") { value(5) }
        }
    }

    @Test
    fun `alguien con quien se compartio lo ve con las reglas del owner, no con las suyas`() {
        ruleService.update("auth0|beto", RuleType.LINTING, listOf(RuleChange("identifier_format", true, "snake case")))
        Mockito
            .`when`(languageClient.lint(code, "printscript", "1.0", mapOf("identifier_format" to "camel case")))
            .thenReturn(emptyList())

        lint("auth0|beto").andExpect {
            status { isOk() }
            jsonPath("$.valid") { value(true) }
            jsonPath("$.errors.length()") { value(0) }
        }
    }

    @Test
    fun `alguien sin permisos no lo ve`() {
        lint("auth0|carla").andExpect { status { isForbidden() } }
        verifyNoInteractions(languageClient)
    }
}
