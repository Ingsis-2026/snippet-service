package com.ingsis.snippet.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * El access token con el que este servicio se presenta ante permission-service y language-service,
 * que solo aceptan pedidos con un token machine-to-machine para la API interna. Es del servicio y
 * no del usuario: los workers de los streams de Redis también llaman a language-service, y ahí no
 * hay ningún usuario.
 *
 * Null si no hay aplicación machine-to-machine configurada: en el docker compose local esos dos
 * servicios corren con `AUTH_ENABLED=false` y no piden token.
 */
fun interface ServiceToken {
    fun get(): String?
}

@Component
class Auth0ServiceToken(
    private val credentials: Auth0ClientCredentials,
    @Value("\${auth0.internal-audience}") private val audience: String,
) : ServiceToken {
    override fun get(): String? = if (credentials.configured) credentials.token(audience) else null
}

/** Cada pedido del cliente lleva el token de [token] en `Authorization`, si hay uno. */
fun RestClient.Builder.authenticatedWith(token: ServiceToken): RestClient.Builder =
    requestInterceptor { request, body, execution ->
        token.get()?.let { request.headers.setBearerAuth(it) }
        execution.execute(request, body)
    }
