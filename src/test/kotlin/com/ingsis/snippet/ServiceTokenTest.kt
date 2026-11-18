package com.ingsis.snippet

import com.ingsis.snippet.auth.Auth0ClientCredentials
import com.ingsis.snippet.auth.Auth0ServiceToken
import com.ingsis.snippet.auth.MissingClientCredentialsException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** El token machine-to-machine con el que snippet-service llama a permission-service y language-service. */
class ServiceTokenTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC)

    private fun credentials(
        clientId: String = "m2m-id",
        clientSecret: String = "m2m-secret",
    ) = Auth0ClientCredentials(builder, "tenant.auth0.com", clientId, clientSecret, clock)

    private fun expectToken(
        audience: String,
        token: String,
    ) {
        server
            .expect(once(), requestTo("https://tenant.auth0.com/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """{"grant_type": "client_credentials", "client_id": "m2m-id", "client_secret": "m2m-secret",
                        "audience": "$audience"}""",
                ),
            ).andRespond(withSuccess("""{"access_token": "$token", "expires_in": 86400}""", MediaType.APPLICATION_JSON))
    }

    @Test
    fun `pide el token para la API interna y lo reusa`() {
        expectToken("https://snippet-searcher/internal", "interno")
        val serviceToken = Auth0ServiceToken(credentials(), "https://snippet-searcher/internal")

        assertEquals("interno", serviceToken.get())
        assertEquals("interno", serviceToken.get())

        server.verify()
    }

    @Test
    fun `cada audience tiene su propio token`() {
        val credentials = credentials()
        expectToken("https://tenant.auth0.com/api/v2/", "management")
        expectToken("https://snippet-searcher/internal", "interno")

        assertEquals("management", credentials.token("https://tenant.auth0.com/api/v2/"))
        assertEquals("interno", credentials.token("https://snippet-searcher/internal"))
        assertEquals("management", credentials.token("https://tenant.auth0.com/api/v2/"))

        server.verify()
    }

    @Test
    fun `sin aplicacion machine-to-machine no hay token del servicio`() {
        val unconfigured = credentials(clientId = "", clientSecret = "")

        assertNull(Auth0ServiceToken(unconfigured, "https://snippet-searcher/internal").get())
        assertFailsWith<MissingClientCredentialsException> { unconfigured.token("https://snippet-searcher/internal") }
        server.verify()
    }
}
