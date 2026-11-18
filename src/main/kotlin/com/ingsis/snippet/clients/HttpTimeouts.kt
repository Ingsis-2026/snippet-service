package com.ingsis.snippet.clients

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * Cuánto se espera a permission-service, language-service, asset-service y Auth0. Sin esto, un servicio
 * colgado (acepta la conexión y nunca responde) deja trabado para siempre al hilo que lo llamó: el de un
 * pedido de la UI o el worker de un stream, que deja de procesar todo lo demás.
 *
 * - [connect]: cuánto se espera a abrir la conexión. En la misma red de Docker es casi instantáneo.
 * - [read]: cuánto se espera la respuesta una vez mandado el pedido.
 * - [languageRead]: lo mismo, pero para language-service, que parsea, lintea, formatea y corre el código
 *   del snippet antes de responder (`/run` lo corre entero con todos los inputs, sin pedir nada en el medio).
 *   Un snippet largo tarda más que una consulta de permisos, así que se le da más margen.
 *
 * Al vencerse cualquiera de los dos, el [org.springframework.web.client.RestClient] tira una
 * [org.springframework.web.client.ResourceAccessException]. En los workers de los streams eso es una falla
 * de la dependencia (ver [com.ingsis.snippet.streams.isDependencyFailure]): el snippet se posterga y el
 * worker hace una pausa, sin gastarle un intento. En un pedido de la UI es un 502 (503 al buscar usuarios), como cualquier otra
 * caída de un servicio interno.
 *
 * El WebSocket con language-service (US9 y US10) no pasa por acá: tiene su propio timeout de conexión y
 * la ejecución dura lo que tarde el usuario en dar los inputs.
 */
@ConfigurationProperties("services.timeouts")
data class HttpTimeouts(
    val connect: Duration = Duration.ofSeconds(2),
    val read: Duration = Duration.ofSeconds(10),
    val languageRead: Duration = Duration.ofSeconds(30),
)

/**
 * Arma cada pedido con los timeouts del servicio al que va: a los de [slowOrigins] les da [slowRead] para
 * responder, y a los demás [read]. Todos comparten un mismo [HttpClient] del JDK, el mismo cliente que
 * usaba antes el [org.springframework.web.client.RestClient] por defecto, ahora con [connect].
 *
 * Se elige por el origin del pedido y no armando un [org.springframework.web.client.RestClient.Builder]
 * distinto por cliente para que siga habiendo un solo builder: los tests le cambian el request factory por
 * uno simulado, y un cliente que pusiera el suyo después lo pisaría.
 */
class TimeoutsByService(
    connect: Duration,
    read: Duration,
    slowRead: Duration,
    slowOrigins: Collection<String>,
) : ClientHttpRequestFactory {
    private val httpClient = HttpClient.newBuilder().connectTimeout(connect).build()
    private val default = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(read) }
    private val slow = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(slowRead) }
    private val slowOriginSet = slowOrigins.map { originOf(URI(it)) }.toSet()

    override fun createRequest(
        uri: URI,
        httpMethod: HttpMethod,
    ): ClientHttpRequest = factoryFor(uri).createRequest(uri, httpMethod)

    /** El factory con el que va un pedido a [uri]. */
    fun factoryFor(uri: URI): ClientHttpRequestFactory = if (originOf(uri) in slowOriginSet) slow else default
}

/** Esquema, host y puerto: alcanza para saber a qué servicio va un pedido. */
internal fun originOf(uri: URI) = Triple(uri.scheme, uri.host, uri.port)
