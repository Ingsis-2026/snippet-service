package com.ingsis.snippet

import com.ingsis.snippet.clients.ClientsConfig
import com.ingsis.snippet.clients.HttpTimeouts
import com.ingsis.snippet.observability.REQUEST_ID
import com.ingsis.snippet.observability.REQUEST_ID_HEADER
import com.ingsis.snippet.observability.withLogContext
import org.junit.jupiter.api.Test
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

/** El id del pedido viaja a permission-service y language-service, pero no a Auth0. */
class RequestIdForwardingTest {
    private val config = ClientsConfig()
    private val builder =
        config.restClientBuilder(
            "http://permission:8080",
            "http://language:8080",
            config.timeoutsByService(HttpTimeouts(), "http://language:8080"),
        )
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = builder.build()

    private fun get(url: String) =
        client
            .get()
            .uri(url)
            .retrieve()
            .toBodilessEntity()

    @Test
    fun `se lo pasa a los servicios internos`() {
        server
            .expect(
                requestTo("http://permission:8080/permissions"),
            ).andExpect(header(REQUEST_ID_HEADER, "abc-123"))
            .andRespond(withSuccess())
        server.expect(requestTo("http://language:8080/version")).andExpect(header(REQUEST_ID_HEADER, "abc-123")).andRespond(withSuccess())

        withLogContext(REQUEST_ID to "abc-123") {
            get("http://permission:8080/permissions")
            get("http://language:8080/version")
        }

        server.verify()
    }

    @Test
    fun `no se lo pasa a otros sitios`() {
        server
            .expect(
                requestTo("https://tenant.auth0.com/oauth/token"),
            ).andExpect(headerDoesNotExist(REQUEST_ID_HEADER))
            .andRespond(withSuccess())

        withLogContext(REQUEST_ID to "abc-123") { get("https://tenant.auth0.com/oauth/token") }

        server.verify()
    }

    @Test
    fun `fuera de un pedido no agrega nada`() {
        server.expect(requestTo("http://language:8080/version")).andExpect(headerDoesNotExist(REQUEST_ID_HEADER)).andRespond(withSuccess())

        get("http://language:8080/version")

        server.verify()
    }
}
