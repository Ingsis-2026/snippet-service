package com.ingsis.snippet.execution

import com.ingsis.snippet.CorsProperties
import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.RunOutcome
import com.ingsis.snippet.snippets.NoAccessException
import com.ingsis.snippet.snippets.SnippetAccess
import com.ingsis.snippet.snippets.SnippetNotFoundException
import com.ingsis.snippet.storage.ContentStore
import com.ingsis.snippet.tests.TestCaseNotFoundException
import com.ingsis.snippet.tests.TestCaseService
import com.ingsis.snippet.tests.TestToRun
import com.ingsis.snippet.tests.passes
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lo que manda el navegador. El primero es `start`, con el access token de Auth0, el snippet y,
 * para correr uno de sus tests (US9), el test. Sin test es una ejecución interactiva (US10). Después
 * solo `input`, con el valor para el último `input-request`.
 */
data class BrowserMessage(
    val type: String,
    val token: String? = null,
    val snippetId: UUID? = null,
    val testId: UUID? = null,
    val value: String? = null,
)

/**
 * Lo que recibe el navegador: `output`, `input-request` y al final `done` o `failure`, como los
 * manda language-service. Al correr un test, `done` dice además si pasó.
 */
sealed interface ExecutionMessage {
    val type: String
}

data class OutputMessage(
    val value: String,
) : ExecutionMessage {
    override val type = "output"
}

data class InputRequestMessage(
    val message: String,
) : ExecutionMessage {
    override val type = "input-request"
}

/** [passed] es null en una ejecución interactiva: no hay outputs esperados contra qué comparar. */
data class DoneMessage(
    val error: CodeError?,
    val passed: Boolean?,
) : ExecutionMessage {
    override val type = "done"
}

data class FailureMessage(
    val message: String,
) : ExecutionMessage {
    override val type = "failure"
}

/**
 * US9 y US10: correr un snippet viendo los outputs a medida que se imprimen y dando los inputs
 * cuando se piden. Es un WebSocket entre el navegador y este servicio, que le pasa todo a
 * language-service por otro (ver [LanguageExecutor]): language-service no se publica ni sabe de
 * usuarios.
 *
 * El navegador no puede mandar headers al abrir un WebSocket, así que el token viaja en el primer
 * mensaje y no en `Authorization`. Tampoco en la URL: quedaría en los logs de nginx. Se valida con
 * el mismo [JwtDecoder] que usa Spring Security para el resto de la API (firma, issuer,
 * vencimiento y audience).
 */
@Component
class ExecutionHandler(
    private val jwtDecoder: JwtDecoder,
    private val access: SnippetAccess,
    private val contents: ContentStore,
    private val tests: TestCaseService,
    private val executor: LanguageExecutor,
    private val jsonMapper: JsonMapper,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val runs = ConcurrentHashMap<String, Run>()

    private class Run(
        val session: WebSocketSession,
    ) {
        @Volatile
        var started = false

        @Volatile
        var remote: RemoteExecution? = null
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        // Le escriben dos hilos: el de los mensajes del navegador y el de los de language-service.
        runs[session.id] = Run(ConcurrentWebSocketSessionDecorator(session, SEND_TIMEOUT_MS, SEND_BUFFER_BYTES))
    }

    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val run = runs[session.id] ?: return
        val request =
            try {
                jsonMapper.readValue(message.payload, BrowserMessage::class.java)
            } catch (e: JacksonException) {
                return fail(run, "Mensaje inválido")
            }
        when (request.type) {
            "start" -> start(run, request)
            "input" -> input(run, request)
            else -> fail(run, "Tipo de mensaje desconocido: ${request.type}")
        }
    }

    /** Si el navegador se va, la ejecución en language-service ya no tiene para quién correr. */
    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        runs.remove(session.id)?.remote?.cancel()
    }

    private fun start(
        run: Run,
        request: BrowserMessage,
    ) {
        if (run.started) return fail(run, "La ejecución ya empezó")
        run.started = true
        val token = request.token ?: return fail(run, "Falta el token")
        val userId =
            try {
                jwtDecoder.decode(token).subject
            } catch (e: JwtException) {
                null
            } ?: return fail(run, "El token no es válido")
        val snippetId = request.snippetId ?: return fail(run, "Falta el snippet")
        try {
            val snippet = access.requireReader(snippetId, userId)
            val test = request.testId?.let { tests.toRun(snippetId, it) }
            val code = contents.load(snippetId)
            val execution =
                ExecutionRequest(code, snippet.language, snippet.version, test?.inputs ?: emptyList(), interactive = test == null)
            run.remote = executor.start(execution, Relay(run, snippetId, test))
        } catch (e: SnippetNotFoundException) {
            fail(run, e.message!!)
        } catch (e: NoAccessException) {
            fail(run, e.message!!)
        } catch (e: TestCaseNotFoundException) {
            fail(run, e.message!!)
        } catch (e: LanguageUnavailableException) {
            fail(run, e.message!!)
        } catch (e: RuntimeException) {
            log.error("No se pudo empezar la ejecución del snippet $snippetId", e)
            fail(run, "No se pudo empezar la ejecución")
        }
    }

    private fun input(
        run: Run,
        request: BrowserMessage,
    ) {
        val remote = run.remote ?: return fail(run, "La ejecución no empezó")
        remote.input(request.value ?: return fail(run, "Falta el valor del input"))
    }

    private fun fail(
        run: Run,
        message: String,
    ) {
        run.remote?.cancel()
        finish(run, FailureMessage(message), CloseStatus.POLICY_VIOLATION)
    }

    private fun send(
        run: Run,
        message: ExecutionMessage,
    ) {
        if (run.session.isOpen) run.session.sendMessage(TextMessage(jsonMapper.writeValueAsString(message)))
    }

    private fun finish(
        run: Run,
        message: ExecutionMessage,
        status: CloseStatus,
    ) {
        send(run, message)
        if (run.session.isOpen) run.session.close(status)
    }

    /**
     * Le pasa al navegador lo que llega de language-service. Al correr un test junta los outputs,
     * para decir al final si pasó y guardarlo en el test como cuando se corre con `POST .../run`.
     */
    private inner class Relay(
        private val run: Run,
        private val snippetId: UUID,
        private val test: TestToRun?,
    ) : ExecutionListener {
        private val outputs = Collections.synchronizedList(mutableListOf<String>())

        override fun output(value: String) {
            outputs.add(value)
            send(run, OutputMessage(value))
        }

        override fun inputRequest(message: String) = send(run, InputRequestMessage(message))

        override fun done(error: CodeError?) {
            val passed = test?.let { record(it, RunOutcome(outputs.toList(), error)) }
            finish(run, DoneMessage(error, passed), CloseStatus.NORMAL)
        }

        override fun failure(message: String) = finish(run, FailureMessage(message), CloseStatus.SERVER_ERROR)

        /** Si no se puede guardar (por ejemplo, borraron el test mientras corría), igual se dice si pasó. */
        private fun record(
            test: TestToRun,
            outcome: RunOutcome,
        ): Boolean {
            try {
                tests.record(snippetId, test.id, outcome)
            } catch (e: RuntimeException) {
                log.warn("No se pudo guardar el resultado del test ${test.id} del snippet $snippetId", e)
            }
            return outcome.passes(test.expected)
        }
    }

    private companion object {
        const val SEND_TIMEOUT_MS = 10_000
        const val SEND_BUFFER_BYTES = 512 * 1024
    }
}

/**
 * El WebSocket queda en `/execution`; el navegador llega por `/api/execution` a través de nginx.
 * Los origins son los mismos que para el resto de la API (ver [CorsProperties]); el propio se
 * acepta siempre.
 */
@Configuration
@EnableWebSocket
class ExecutionSocketConfig(
    private val handler: ExecutionHandler,
    private val cors: CorsProperties,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/execution").setAllowedOrigins(*cors.allowedOrigins.toTypedArray())
    }
}
