package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.rules.RuleService
import com.ingsis.snippet.rules.RuleType
import com.ingsis.snippet.rules.UserRuleRepository
import com.ingsis.snippet.snippets.USER_HEADER
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals

/** US11 y US14: habilitar y deshabilitar reglas de formato y de lint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RulesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: UserRuleRepository,
    @Autowired private val service: RuleService,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    private fun get(
        type: String,
        user: String = "auth0|ana",
    ) = mockMvc.get("/rules/$type") { header(USER_HEADER, user) }

    private fun put(
        type: String,
        body: String,
        user: String = "auth0|ana",
    ) = mockMvc.put("/rules/$type") {
        header(USER_HEADER, user)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    @Test
    fun `al principio todas las reglas predeterminadas estan apagadas`() {
        get("formatting").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(11) }
            jsonPath("$[0].name") { value("enforce-spacing-before-colon-in-declaration") }
            jsonPath("$[*].isActive") { value(everyItem(equalTo(false))) }
        }
        get("linting").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
            jsonPath("$[0].name") { value("identifier_format") }
            jsonPath("$[0].value") { value("camel case") }
        }
    }

    @Test
    fun `se habilita una regla y las demas quedan como estaban`() {
        put("formatting", """[{"name": "enforce-spacing-around-equals", "isActive": true}]""").andExpect {
            status { isOk() }
            jsonPath("$[?(@.name == 'enforce-spacing-around-equals')].isActive") { value(true) }
            jsonPath("$[?(@.name == 'enforce-spacing-before-colon-in-declaration')].isActive") { value(false) }
        }
        get("formatting").andExpect {
            jsonPath("$[?(@.name == 'enforce-spacing-around-equals')].isActive") { value(true) }
        }
    }

    @Test
    fun `se deshabilita una regla habilitada`() {
        put("linting", """[{"name": "mandatory-variable-or-literal-in-println", "isActive": true}]""")
        put("linting", """[{"name": "mandatory-variable-or-literal-in-println", "isActive": false}]""").andExpect {
            jsonPath("$[?(@.name == 'mandatory-variable-or-literal-in-println')].isActive") { value(false) }
        }
        assertEquals(emptyMap(), service.configOf("auth0|ana", RuleType.LINTING))
    }

    @Test
    fun `las reglas con valor guardan el valor elegido`() {
        put("formatting", """[{"name": "indent-inside-if", "isActive": true, "value": 2}]""").andExpect {
            jsonPath("$[?(@.name == 'indent-inside-if')].value") { value("2") }
        }
        put("linting", """[{"name": "identifier_format", "isActive": true, "value": "Snake Case"}]""").andExpect {
            jsonPath("$[?(@.name == 'identifier_format')].value") { value("snake case") }
        }
    }

    @Test
    fun `sin valor, una regla conserva el que tenia`() {
        put("formatting", """[{"name": "indent-inside-if", "isActive": true, "value": "2"}]""")
        put("formatting", """[{"name": "indent-inside-if", "isActive": false}]""")
        put("formatting", """[{"name": "indent-inside-if", "isActive": true}]""").andExpect {
            jsonPath("$[?(@.name == 'indent-inside-if')].value") { value("2") }
        }
    }

    @Test
    fun `las reglas de cada usuario son suyas`() {
        put("linting", """[{"name": "mandatory-variable-or-literal-in-println", "isActive": true}]""")
        get("linting", user = "auth0|beto").andExpect {
            jsonPath("$[?(@.name == 'mandatory-variable-or-literal-in-println')].isActive") { value(false) }
        }
    }

    @Test
    fun `la configuracion para el lenguaje tiene solo las activas, con su valor`() {
        put(
            "formatting",
            """[{"name": "enforce-spacing-around-equals", "isActive": true},
                {"name": "indent-inside-if", "isActive": true, "value": "2"},
                {"name": "line-breaks-after-println", "isActive": false, "value": "2"}]""",
        )
        put("linting", """[{"name": "identifier_format", "isActive": true}]""")

        assertEquals(
            mapOf("enforce-spacing-around-equals" to true, "indent-inside-if" to 2),
            service.configOf("auth0|ana", RuleType.FORMATTING),
        )
        assertEquals(mapOf("identifier_format" to "camel case"), service.configOf("auth0|ana", RuleType.LINTING))
    }

    @Test
    fun `una regla que no existe es un 400 y no se guarda nada`() {
        put(
            "formatting",
            """[{"name": "enforce-spacing-around-equals", "isActive": true}, {"name": "no-existe", "isActive": true}]""",
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("No existe la regla no-existe") }
        }
        assertEquals(0, repository.count())
    }

    @Test
    fun `una regla de lint no se habilita como regla de formato`() {
        put("formatting", """[{"name": "identifier_format", "isActive": true}]""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `un valor invalido es un 400`() {
        put("formatting", """[{"name": "indent-inside-if", "isActive": true, "value": "-1"}]""").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("-1 no es un valor válido para la regla indent-inside-if") }
        }
        put("linting", """[{"name": "identifier_format", "isActive": true, "value": "kebab case"}]""").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `dos reglas que se contradicen no pueden estar activas a la vez`() {
        put("formatting", """[{"name": "if-brace-same-line", "isActive": true}]""")
        put("formatting", """[{"name": "if-brace-below-line", "isActive": true}]""").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") {
                value("Estas reglas se contradicen y no pueden estar activas a la vez: if-brace-same-line, if-brace-below-line")
            }
        }
        put(
            "formatting",
            """[{"name": "if-brace-same-line", "isActive": false}, {"name": "if-brace-below-line", "isActive": true}]""",
        ).andExpect { status { isOk() } }
    }
}
