package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.UnsupportedLanguageException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals

/** Los clientes contra un servidor simulado: que armen bien el pedido y lean bien la respuesta. */
class ClientsTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()

    @Test
    fun `language-service devuelve los errores del codigo`() {
        server
            .expect(requestTo("http://language/validate"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"code": "x", "language": "printscript", "version": "1.0"}"""))
            .andRespond(
                withSuccess(
                    """{"valid": false, "errors": [{"rule": "Can't handle this sentence", "line": 1, "column": 1}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val errors = LanguageClient(builder, "http://language").validate("x", "printscript", "1.0")

        assertEquals(listOf(CodeError("Can't handle this sentence", 1, 1)), errors)
        server.verify()
    }

    @Test
    fun `un 400 de language-service es un lenguaje no soportado`() {
        server
            .expect(requestTo("http://language/validate"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("""{"status": 400, "detail": "Lenguaje no soportado: cobol"}"""),
            )

        val error =
            assertThrows<UnsupportedLanguageException> {
                LanguageClient(builder, "http://language").validate("x", "cobol", "1.0")
            }
        assertEquals("Lenguaje no soportado: cobol", error.message)
    }

    @Test
    fun `permission-service registra al owner`() {
        val snippetId = UUID.randomUUID()
        server
            .expect(requestTo("http://permission/permissions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"snippetId": "$snippetId", "userId": "auth0|ana", "role": "OWNER"}"""))
            .andRespond(withStatus(HttpStatus.CREATED))

        PermissionClient(builder, "http://permission").grantOwner(snippetId, "auth0|ana")

        server.verify()
    }

    @Test
    fun `un error de permission-service se propaga`() {
        server.expect(requestTo("http://permission/permissions")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThrows<HttpServerErrorException> {
            PermissionClient(builder, "http://permission").grantOwner(UUID.randomUUID(), "auth0|ana")
        }
    }
}
