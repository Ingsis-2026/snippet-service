package com.ingsis.snippet.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Agrega al MDC quién hace el pedido, el `sub` de su access token, y se lo cuenta a New Relic: la
 * transacción queda con `enduser.id`.
 *
 * Tiene que correr después de la cadena de filtros de Spring Security (que está en
 * [SecurityFilterProperties.DEFAULT_FILTER_ORDER]), porque es la que valida el token: antes todavía no se sabe
 * quién es. Los pedidos sin usuario (el health check, el WebSocket de `/execution`) pasan sin cambios.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1)
class UserLogContextFilter(
    private val telemetry: Telemetry,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val user = (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.subject
        user?.let(telemetry::setUser)
        withLogContext(USER to user) { chain.doFilter(request, response) }
    }
}
