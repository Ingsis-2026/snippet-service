package com.ingsis.snippet.observability

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView

/**
 * Un error que nadie esperaba (ni el ErrorHandler ni Spring lo convierten en una respuesta) va al log en ERROR
 * mientras el MDC todavía tiene el `requestId` del pedido. Si se dejara seguir, lo loguearía Tomcat, ya afuera
 * de [RequestIdFilter] y sin contexto.
 *
 * Es el último en intentar resolver la excepción, y responde lo mismo que respondería Spring Boot sin él: un 500
 * con su cuerpo de error de siempre.
 */
@Component
class UnexpectedErrorLogger :
    HandlerExceptionResolver,
    Ordered {
    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView {
        log.error("Falló un pedido por un error inesperado", ex)
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        return ModelAndView()
    }

    override fun getOrder() = Ordered.LOWEST_PRECEDENCE

    private companion object {
        val log: Logger = LoggerFactory.getLogger(UnexpectedErrorLogger::class.java)
    }
}
