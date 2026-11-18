package com.ingsis.snippet

import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * Un pedido autenticado como [userId]: un access token con ese `sub`, como los que emite Auth0.
 * No se valida contra Auth0, así que los tests no necesitan red.
 *
 * Por defecto trae los dos scopes que pide la UI ([READ_SNIPPETS] y [WRITE_SNIPPETS]); para probar
 * un token con menos, se pasan los que tenga.
 */
fun asUser(
    userId: String,
    vararg scopes: String = arrayOf(READ_SNIPPETS, WRITE_SNIPPETS),
): RequestPostProcessor =
    jwt()
        .jwt { it.subject(userId) }
        .authorities(scopes.map { SimpleGrantedAuthority(scopeAuthority(it)) })
