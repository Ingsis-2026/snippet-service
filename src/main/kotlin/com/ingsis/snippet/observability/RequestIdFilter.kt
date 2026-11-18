package com.ingsis.snippet.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** El header con el que nginx le pone un id a cada pedido, y con el que se lo pasa a los demás servicios. */
const val REQUEST_ID_HEADER = "X-Request-Id"

/**
 * Le pone a cada pedido el id que mandó nginx en [REQUEST_ID_HEADER], o uno nuevo si no vino (en local,
 * llamando directo al servicio). El id queda en el MDC mientras se atiende el pedido, así todos sus logs lo
 * llevan, y vuelve en la respuesta para poder buscarlo después.
 *
 * Va primero de todo, antes que Spring Security: un pedido rechazado por falta de token también tiene id.
 * Un id que no parece un id (muy largo, con espacios o saltos de línea) se reemplaza por uno nuevo: viene
 * de afuera y va derecho a los logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)?.takeIf(VALID_ID::matches) ?: UUID.randomUUID().toString()
        response.setHeader(REQUEST_ID_HEADER, requestId)
        withLogContext(REQUEST_ID to requestId) { chain.doFilter(request, response) }
    }

    private companion object {
        val VALID_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
