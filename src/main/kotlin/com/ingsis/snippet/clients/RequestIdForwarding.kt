package com.ingsis.snippet.clients

import com.ingsis.snippet.observability.REQUEST_ID
import com.ingsis.snippet.observability.REQUEST_ID_HEADER
import org.slf4j.MDC
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.net.URI

/**
 * Le pasa a permission-service y language-service el id del pedido que se está atendiendo (o del mensaje del
 * stream que se está procesando) en [REQUEST_ID_HEADER]: así sus logs llevan el mismo `requestId` que los de acá.
 *
 * Solo a los servicios de [internalUrls]. A Auth0 no: es de afuera y no tiene por qué ver nuestros ids.
 */
class RequestIdForwarding(
    internalUrls: Collection<String>,
) : ClientHttpRequestInterceptor {
    private val internalOrigins = internalUrls.map { originOf(URI(it)) }.toSet()

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val requestId = MDC.get(REQUEST_ID)
        if (requestId != null && originOf(request.uri) in internalOrigins) {
            request.headers.set(REQUEST_ID_HEADER, requestId)
        }
        return execution.execute(request, body)
    }
}
