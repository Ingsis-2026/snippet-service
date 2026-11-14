package com.ingsis.snippet.auth

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal data class TokenRequest(
    @JsonProperty("grant_type") val grantType: String,
    @JsonProperty("client_id") val clientId: String,
    @JsonProperty("client_secret") val clientSecret: String,
    val audience: String,
)

internal data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("expires_in") val expiresIn: Long,
)

/**
 * Los access tokens de la aplicación machine-to-machine de Auth0 de este servicio, pedidos con
 * client credentials: uno para la Management API (buscar usuarios, US7) y otro para la API interna
 * (llamar a permission-service y language-service).
 *
 * Reusa cada token hasta un minuto antes de que venza, porque Auth0 limita cuántos tokens
 * machine-to-machine se emiten por mes. Sin client ID o secret, [configured] es false y [token]
 * falla con [MissingClientCredentialsException].
 */
@Component
class Auth0ClientCredentials(
    builder: RestClient.Builder,
    @Value("\${auth0.domain}") domain: String,
    @Value("\${auth0.management.client-id}") private val clientId: String,
    @Value("\${auth0.management.client-secret}") private val clientSecret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val client = builder.baseUrl("https://$domain").build()
    private val tokens = ConcurrentHashMap<String, CachedToken>()

    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** Un access token para [audience]. Si Auth0 no responde, la excepción del [RestClient]. */
    fun token(audience: String): String {
        tokens[audience]?.takeIf { it.validUntil.isAfter(clock.instant()) }?.let { return it.value }
        if (!configured) throw MissingClientCredentialsException()
        val response =
            client
                .post()
                .uri("/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(TokenRequest("client_credentials", clientId, clientSecret, audience))
                .retrieve()
                .body<TokenResponse>()!!
        return CachedToken(response.accessToken, clock.instant().plusSeconds(response.expiresIn - 60))
            .also { tokens[audience] = it }
            .value
    }

    private data class CachedToken(
        val value: String,
        val validUntil: Instant,
    )
}

class MissingClientCredentialsException : RuntimeException("Falta configurar la aplicación machine-to-machine de Auth0")
