package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.UnsupportedLanguageException
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import com.ingsis.snippet.snippets.ValidityStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.web.client.ResourceAccessException
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CreateSnippetTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val code = "let x: number = 5;\nprintln(x);"

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    private fun upload(
        content: String = code,
        user: String? = "auth0|ana",
        params: Map<String, String> =
            mapOf("name" to "Saludo", "description" to "Imprime un número", "language" to "printscript", "version" to "1.0"),
    ) = mockMvc.multipart("/snippets") {
        file(MockMultipartFile("file", "saludo.ps", "text/plain", content.toByteArray()))
        params.forEach { (key, value) -> param(key, value) }
        user?.let { header(USER_HEADER, it) }
    }

    @Test
    fun `un snippet valido se guarda con su owner`() {
        upload().andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Saludo") }
            jsonPath("$.description") { value("Imprime un número") }
            jsonPath("$.language") { value("printscript") }
            jsonPath("$.version") { value("1.0") }
            jsonPath("$.content") { value(code) }
            jsonPath("$.ownerId") { value("auth0|ana") }
            jsonPath("$.status") { value("PENDING") }
        }

        val saved = repository.findAll().single()
        assertEquals(code, saved.content)
        assertEquals(ValidityStatus.PENDING, saved.status)
        verify(permissionClient).grantOwner(saved.id!!, "auth0|ana")
    }

    @Test
    fun `un snippet invalido no se guarda e informa regla, linea y columna`() {
        Mockito
            .`when`(languageClient.validate(code, "printscript", "1.0"))
            .thenReturn(listOf(CodeError("Carácter inválido encontrado: '@'", 2, 12)))

        upload().andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.errors[0].rule") { value("Carácter inválido encontrado: '@'") }
            jsonPath("$.errors[0].line") { value(2) }
            jsonPath("$.errors[0].column") { value(12) }
        }

        assertEquals(0, repository.count())
        verifyNoInteractions(permissionClient)
    }

    @Test
    fun `un lenguaje no soportado es un 400`() {
        Mockito
            .`when`(languageClient.validate(code, "printscript", "1.0"))
            .thenThrow(UnsupportedLanguageException("Lenguaje no soportado: printscript"))

        upload().andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("Lenguaje no soportado: printscript") }
        }
        assertEquals(0, repository.count())
    }

    @Test
    fun `si no se puede registrar el owner, el snippet no queda guardado`() {
        Mockito
            .doThrow(ResourceAccessException("permission-service caído"))
            .`when`(permissionClient)
            .grantOwner(Mockito.any(UUID::class.java) ?: UUID.randomUUID(), Mockito.anyString())

        upload().andExpect { status { isBadGateway() } }
        assertEquals(0, repository.count())
    }

    @Test
    fun `sin usuario es un 400`() {
        upload(user = null).andExpect { status { isBadRequest() } }
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `sin nombre es un 400`() {
        upload(params = mapOf("name" to "", "description" to "d", "language" to "printscript", "version" to "1.0"))
            .andExpect { status { isBadRequest() } }
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `sin version es un 400`() {
        upload(params = mapOf("name" to "n", "description" to "d", "language" to "printscript"))
            .andExpect { status { isBadRequest() } }
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `un archivo vacio es un 400`() {
        upload(content = "").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("El archivo está vacío") }
        }
        verifyNoInteractions(languageClient)
    }
}
