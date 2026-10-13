package com.ingsis.snippet

import com.ingsis.snippet.clients.AlreadyHasAccessException
import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.clients.RunOutcome
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
    fun `permission-service registra un permiso de lectura`() {
        val snippetId = UUID.randomUUID()
        server
            .expect(requestTo("http://permission/permissions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"snippetId": "$snippetId", "userId": "auth0|beto", "role": "READ"}"""))
            .andRespond(withStatus(HttpStatus.CREATED))

        PermissionClient(builder, "http://permission").grantRead(snippetId, "auth0|beto")

        server.verify()
    }

    @Test
    fun `un 409 de permission-service es que el usuario ya tiene acceso`() {
        server.expect(requestTo("http://permission/permissions")).andRespond(withStatus(HttpStatus.CONFLICT))

        assertThrows<AlreadyHasAccessException> {
            PermissionClient(builder, "http://permission").grantRead(UUID.randomUUID(), "auth0|beto")
        }
    }

    @Test
    fun `un error de permission-service se propaga`() {
        server.expect(requestTo("http://permission/permissions")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThrows<HttpServerErrorException> {
            PermissionClient(builder, "http://permission").grantOwner(UUID.randomUUID(), "auth0|ana")
        }
    }

    @Test
    fun `permission-service devuelve el rol del usuario`() {
        val snippetId = UUID.randomUUID()
        server
            .expect(requestTo("http://permission/permissions?snippetId=$snippetId&userId=auth0%7Cana"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"snippetId": "$snippetId", "userId": "auth0|ana", "role": "OWNER"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(Role.OWNER, PermissionClient(builder, "http://permission").roleOf(snippetId, "auth0|ana"))
        server.verify()
    }

    @Test
    fun `sin permiso, permission-service responde 404 y no hay rol`() {
        val snippetId = UUID.randomUUID()
        server
            .expect(requestTo("http://permission/permissions?snippetId=$snippetId&userId=auth0%7Cbeto"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertEquals(null, PermissionClient(builder, "http://permission").roleOf(snippetId, "auth0|beto"))
    }

    @Test
    fun `permission-service devuelve los snippets de un usuario`() {
        val own = UUID.randomUUID()
        val shared = UUID.randomUUID()
        server
            .expect(requestTo("http://permission/permissions/snippets?userId=auth0%7Cana"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """[{"snippetId": "$own", "userId": "auth0|ana", "role": "OWNER"},
                        {"snippetId": "$shared", "userId": "auth0|ana", "role": "READ"}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(listOf(own, shared), PermissionClient(builder, "http://permission").snippetsOf("auth0|ana", null))
        server.verify()
    }

    @Test
    fun `permission-service filtra los snippets de un usuario por rol`() {
        server
            .expect(requestTo("http://permission/permissions/snippets?userId=auth0%7Cana&role=READ"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        assertEquals(emptyList(), PermissionClient(builder, "http://permission").snippetsOf("auth0|ana", Role.READ))
        server.verify()
    }

    @Test
    fun `language-service lintea el codigo con las reglas`() {
        server
            .expect(requestTo("http://language/lint"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """{"code": "x", "language": "printscript", "version": "1.0", "rules": {"identifier_format": "camel case"}}""",
                ),
            ).andRespond(
                withSuccess(
                    """{"valid": false, "errors": [{"rule": "The following identifier must be in camel case", "line": 1, "column": 5}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val errors =
            LanguageClient(builder, "http://language").lint("x", "printscript", "1.0", mapOf("identifier_format" to "camel case"))

        assertEquals(listOf(CodeError("The following identifier must be in camel case", 1, 5)), errors)
        server.verify()
    }

    @Test
    fun `language-service formatea el codigo con las reglas`() {
        server
            .expect(requestTo("http://language/format"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json("""{"code": "x=1", "language": "printscript", "version": "1.0", "rules": {"indent-inside-if": 2}}"""),
            ).andRespond(withSuccess("""{"content": "x = 1"}""", MediaType.APPLICATION_JSON))

        val formatted = LanguageClient(builder, "http://language").format("x=1", "printscript", "1.0", mapOf("indent-inside-if" to 2))

        assertEquals("x = 1", formatted)
        server.verify()
    }

    @Test
    fun `language-service ejecuta el codigo con los inputs`() {
        server
            .expect(requestTo("http://language/run"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"code": "x", "language": "printscript", "version": "1.1", "inputs": ["Ana"]}"""))
            .andRespond(
                withSuccess(
                    """{"outputs": ["Hola Ana"], "error": {"rule": "No hay más inputs", "line": 2, "column": 5}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val outcome = LanguageClient(builder, "http://language").run("x", "printscript", "1.1", listOf("Ana"))

        assertEquals(RunOutcome(listOf("Hola Ana"), CodeError("No hay más inputs", 2, 5)), outcome)
        server.verify()
    }
}
