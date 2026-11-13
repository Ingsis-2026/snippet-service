package com.ingsis.snippet.execution

import com.ingsis.snippet.clients.CodeError
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.TimeUnit

/**
 * Una ejecución que se le pide a language-service. Con [interactive] en false (un test, US9) los
 * inputs son solo [inputs]; con true (US10) los que falten se piden con
 * [ExecutionListener.inputRequest].
 */
data class ExecutionRequest(
    val code: String,
    val language: String,
    val version: String,
    val inputs: List<String>,
    val interactive: Boolean,
)

/**
 * Lo que va pasando en una ejecución, en orden. Termina con [done] o con [failure], y después no
 * llega nada más.
 */
interface ExecutionListener {
    fun output(value: String)

    fun inputRequest(message: String)

    /** Terminó: [error] es lo que la cortó, o null si terminó bien. */
    fun done(error: CodeError?)

    /** No se pudo correr o terminar, por algo que no es del código. */
    fun failure(message: String)
}

/** Una ejecución que ya empezó. */
interface RemoteExecution {
    /** El valor para el último [ExecutionListener.inputRequest]. */
    fun input(value: String)

    /** La corta sin esperar a que termine. Después no se avisa nada más. */
    fun cancel()
}

/** Ejecuta código en language-service viendo cada output y cada pedido de input a medida que pasan. */
fun interface LanguageExecutor {
    fun start(
        request: ExecutionRequest,
        listener: ExecutionListener,
    ): RemoteExecution
}

class LanguageUnavailableException(
    cause: Throwable? = null,
) : RuntimeException("No se pudo conectar con language-service", cause)

/** Lo que manda language-service por `/execution`: un solo tipo con los campos de todos. */
internal data class LanguageMessage(
    val type: String,
    val value: String? = null,
    val message: String? = null,
    val error: CodeError? = null,
)

/**
 * [LanguageExecutor] sobre el WebSocket `/execution` de language-service: una conexión por
 * ejecución. Los mensajes son los mismos que los de [ExecutionHandler], sin el token: entre los
 * servicios no hay usuarios.
 */
@Component
class WebSocketLanguageExecutor(
    @Value("\${services.language-url}") languageUrl: String,
    private val jsonMapper: JsonMapper,
) : LanguageExecutor {
    private val url = languageUrl.replaceFirst(Regex("^http"), "ws") + "/execution"
    private val client = StandardWebSocketClient()

    override fun start(
        request: ExecutionRequest,
        listener: ExecutionListener,
    ): RemoteExecution {
        val relay = Relay(listener)
        val session =
            try {
                client.execute(relay, url).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw LanguageUnavailableException(e)
            }
        val safe = ConcurrentWebSocketSessionDecorator(session, SEND_TIMEOUT_MS, SEND_BUFFER_BYTES)
        safe.send(
            mapOf(
                "type" to "start",
                "code" to request.code,
                "language" to request.language,
                "version" to request.version,
                "inputs" to request.inputs,
                "interactive" to request.interactive,
            ),
        )
        return object : RemoteExecution {
            override fun input(value: String) = safe.send(mapOf("type" to "input", "value" to value))

            override fun cancel() {
                relay.finished = true
                if (safe.isOpen) safe.close()
            }
        }
    }

    private fun WebSocketSession.send(message: Map<String, Any>) = sendMessage(TextMessage(jsonMapper.writeValueAsString(message)))

    /** Le pasa al [listener] lo que llega de language-service. */
    private inner class Relay(
        private val listener: ExecutionListener,
    ) : TextWebSocketHandler() {
        @Volatile
        var finished = false

        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
            if (finished) return
            val received = jsonMapper.readValue(message.payload, LanguageMessage::class.java)
            when (received.type) {
                "output" -> listener.output(received.value ?: "")
                "input-request" -> listener.inputRequest(received.message ?: "")
                "done" -> finish { listener.done(received.error) }
                "failure" -> finish { listener.failure(received.message ?: "Falló la ejecución") }
            }
        }

        /** Si se corta sin `done` ni `failure`, a quien la pidió le falta el final: se lo avisa. */
        override fun afterConnectionClosed(
            session: WebSocketSession,
            status: CloseStatus,
        ) {
            if (!finished) finish { listener.failure("Se perdió la conexión con language-service") }
        }

        private fun finish(notify: () -> Unit) {
            finished = true
            notify()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val SEND_TIMEOUT_MS = 10_000
        const val SEND_BUFFER_BYTES = 1024 * 1024
    }
}
