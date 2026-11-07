package com.ingsis.snippet.users

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.time.Clock
import java.time.Instant

/** Un usuario con el que se puede compartir un snippet (US7). [id] es su `sub`, el que guarda permission-service. */
data class UserSummary(
    val id: String,
    val name: String,
    val email: String?,
)

/** Una página de la búsqueda. [page] arranca en 0 y [total] cuenta todas las páginas. */
data class UserPage(
    val users: List<UserSummary>,
    val page: Int,
    val size: Int,
    val total: Long,
)

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

internal data class Auth0User(
    @JsonProperty("user_id") val userId: String,
    val name: String?,
    val email: String?,
)

internal data class Auth0UserPage(
    val users: List<Auth0User>,
    val total: Long,
)

/**
 * Los usuarios de Auth0, por la Management API (`GET /api/v2/users`). Entra con una aplicación
 * machine-to-machine que tiene `read:users`: pide un token con client credentials y lo reusa hasta
 * un minuto antes de que venza, porque Auth0 limita cuántos tokens machine-to-machine se emiten
 * por mes.
 *
 * Sin client ID o secret configurados no hay búsqueda: [search] falla con [UsersUnavailableException].
 * El servicio arranca igual, así que el resto anda en local sin la aplicación machine-to-machine.
 */
@Component
class UserDirectory(
    builder: RestClient.Builder,
    @Value("\${auth0.domain}") private val domain: String,
    @Value("\${auth0.management.client-id}") private val clientId: String,
    @Value("\${auth0.management.client-secret}") private val clientSecret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val client = builder.baseUrl("https://$domain").build()

    @Volatile
    private var token: CachedToken? = null

    /**
     * Los usuarios cuyo nombre o email empiezan con [name], sin [excludedId]: el que comparte no
     * se puede compartir el snippet a sí mismo. Sin [name], todos.
     *
     * Busca por prefijo y no por "contiene": Auth0 desaconseja los wildcards al principio porque
     * son lentos, y corta con un 503 las búsquedas de más de dos segundos.
     */
    fun search(
        name: String,
        excludedId: String,
        page: Int,
        size: Int,
    ): UserPage {
        val result =
            calling {
                client
                    .get()
                    .uri {
                        it
                            .path("/api/v2/users")
                            // Como variable, para que se codifique entero: un `+` literal llegaría como espacio.
                            .queryParam("q", "{q}")
                            .queryParam("search_engine", "v3")
                            .queryParam("page", page)
                            .queryParam("per_page", size)
                            .queryParam("include_totals", true)
                            .queryParam("fields", "user_id,name,email")
                            .build(mapOf("q" to query(name, excludedId)))
                    }.header("Authorization", "Bearer ${accessToken()}")
                    .retrieve()
                    .body<Auth0UserPage>()!!
            }
        return UserPage(result.users.map { it.toSummary() }, page, size, result.total)
    }

    private fun query(
        name: String,
        excludedId: String,
    ): String {
        val others = "NOT user_id:${quoted(excludedId)}"
        val term = escaped(name.trim())
        return if (term.isEmpty()) others else "(name:$term* OR email:$term*) AND $others"
    }

    private fun accessToken(): String {
        token?.takeIf { it.validUntil.isAfter(clock.instant()) }?.let { return it.value }
        if (clientId.isBlank() ||
            clientSecret.isBlank()
        ) {
            throw UsersUnavailableException("Falta configurar la aplicación de Auth0 para buscar usuarios")
        }
        val response =
            calling {
                client
                    .post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(TokenRequest("client_credentials", clientId, clientSecret, "https://$domain/api/v2/"))
                    .retrieve()
                    .body<TokenResponse>()!!
            }
        return CachedToken(response.accessToken, clock.instant().plusSeconds(response.expiresIn - 60))
            .also { token = it }
            .value
    }

    private fun <T> calling(block: () -> T): T =
        try {
            block()
        } catch (e: RestClientException) {
            throw UsersUnavailableException("No se pudo buscar usuarios en Auth0", e)
        }

    private fun Auth0User.toSummary() = UserSummary(userId, name ?: email ?: userId, email)

    private data class CachedToken(
        val value: String,
        val validUntil: Instant,
    )

    private companion object {
        /** Lo que tiene un significado en la sintaxis de búsqueda de Auth0 (Lucene) se escapa, espacios incluidos. */
        val SPECIAL = Regex("""[+\-&|!(){}\[\]^"~*?:\\/\s]""")

        fun escaped(term: String) = term.replace(SPECIAL) { "\\${it.value}" }

        fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}

class UsersUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
