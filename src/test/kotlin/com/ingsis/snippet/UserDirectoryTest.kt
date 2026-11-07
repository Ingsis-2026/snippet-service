package com.ingsis.snippet

import com.ingsis.snippet.users.UserDirectory
import com.ingsis.snippet.users.UserSummary
import com.ingsis.snippet.users.UsersUnavailableException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** US7: buscar usuarios de Auth0 con la Management API, contra un Auth0 simulado. */
class UserDirectoryTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val clock = MutableClock(Instant.parse("2026-11-03T12:00:00Z"))
    private val directory = UserDirectory(builder, "tenant.auth0.com", "m2m-id", "m2m-secret", clock)

    private val ana = """{"user_id": "auth0|ana", "name": "Ana", "email": "ana@mail.com"}"""

    private fun expectToken(token: String = "t1") {
        server
            .expect(once(), requestTo("https://tenant.auth0.com/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """{"grant_type": "client_credentials", "client_id": "m2m-id", "client_secret": "m2m-secret",
                        "audience": "https://tenant.auth0.com/api/v2/"}""",
                ),
            ).andRespond(withSuccess("""{"access_token": "$token", "expires_in": 86400}""", MediaType.APPLICATION_JSON))
    }

    private fun expectSearch(
        query: String,
        token: String = "t1",
        users: String = ana,
        total: Int = 1,
    ) {
        server
            .expect(once(), searchWith(query))
            .andExpect(header("Authorization", "Bearer $token"))
            .andRespond(withSuccess("""{"users": [$users], "total": $total}""", MediaType.APPLICATION_JSON))
    }

    /** Compara los parámetros ya decodificados, como los lee Auth0. */
    private fun searchWith(query: String) =
        RequestMatcher { request ->
            val uri = request.uri
            assertEquals("https://tenant.auth0.com/api/v2/users", "${uri.scheme}://${uri.host}${uri.path}")
            val params =
                uri.rawQuery.split("&").associate {
                    val (key, value) = it.split("=", limit = 2)
                    key to URLDecoder.decode(value, Charsets.UTF_8)
                }
            assertEquals(query, params["q"])
            assertEquals("v3", params["search_engine"])
            assertEquals("user_id,name,email", params["fields"])
            assertEquals("true", params["include_totals"])
        }

    @Test
    fun `busca por el principio del nombre o del email, sin el usuario que comparte`() {
        expectToken()
        expectSearch("""(name:an* OR email:an*) AND NOT user_id:"auth0|beto"""")

        val page = directory.search("an", "auth0|beto", 0, 5)

        assertEquals(listOf(UserSummary("auth0|ana", "Ana", "ana@mail.com")), page.users)
        assertEquals(1, page.total)
        server.verify()
    }

    @Test
    fun `sin nombre lista a todos menos al que comparte`() {
        expectToken()
        expectSearch("""NOT user_id:"auth0|beto"""")

        directory.search("  ", "auth0|beto", 0, 5)

        server.verify()
    }

    @Test
    fun `lo que tiene significado en la busqueda de Auth0 se escapa, incluido el mas de un email`() {
        expectToken()
        expectSearch("""(name:ana\+test\ \(x\)* OR email:ana\+test\ \(x\)*) AND NOT user_id:"auth0|beto"""")

        directory.search("ana+test (x)", "auth0|beto", 0, 5)

        server.verify()
    }

    @Test
    fun `reusa el token hasta un minuto antes de que venza`() {
        expectToken("t1")
        expectSearch("""NOT user_id:"auth0|beto"""", token = "t1")
        expectSearch("""NOT user_id:"auth0|beto"""", token = "t1")
        expectToken("t2")
        expectSearch("""NOT user_id:"auth0|beto"""", token = "t2")

        directory.search("", "auth0|beto", 0, 5)
        clock.advance(Duration.ofHours(23).plusMinutes(58))
        directory.search("", "auth0|beto", 0, 5)
        clock.advance(Duration.ofMinutes(1))
        directory.search("", "auth0|beto", 0, 5)

        server.verify()
    }

    @Test
    fun `sin nombre en Auth0 se muestra el email, y si no hay, el id`() {
        expectToken()
        expectSearch(
            """NOT user_id:"auth0|beto"""",
            users = """{"user_id": "auth0|x", "email": "x@mail.com"}, {"user_id": "google-oauth2|y"}""",
            total = 2,
        )

        val names = directory.search("", "auth0|beto", 0, 5).users.map { it.name }

        assertEquals(listOf("x@mail.com", "google-oauth2|y"), names)
    }

    @Test
    fun `sin la aplicacion machine-to-machine configurada no se busca`() {
        val unconfigured = UserDirectory(builder, "tenant.auth0.com", "", "", clock)

        val error = assertFailsWith<UsersUnavailableException> { unconfigured.search("ana", "auth0|beto", 0, 5) }

        assertTrue(error.message!!.contains("Falta configurar"))
        server.verify()
    }

    @Test
    fun `si Auth0 falla no se puede buscar`() {
        expectToken()
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("https://tenant.auth0.com/api/v2/users"))).andRespond(
            withStatus(HttpStatus.TOO_MANY_REQUESTS),
        )

        assertFailsWith<UsersUnavailableException> { directory.search("ana", "auth0|beto", 0, 5) }
    }

    @Test
    fun `si Auth0 no da el token tampoco`() {
        server.expect(once(), requestTo("https://tenant.auth0.com/oauth/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertFailsWith<UsersUnavailableException> { directory.search("ana", "auth0|beto", 0, 5) }
    }

    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }
}
