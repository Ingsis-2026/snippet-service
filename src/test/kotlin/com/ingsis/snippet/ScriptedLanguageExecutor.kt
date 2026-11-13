package com.ingsis.snippet

import com.ingsis.snippet.execution.ExecutionListener
import com.ingsis.snippet.execution.ExecutionRequest
import com.ingsis.snippet.execution.LanguageExecutor
import com.ingsis.snippet.execution.LanguageUnavailableException
import com.ingsis.snippet.execution.RemoteExecution
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reemplaza a language-service en los tests de `/execution`: cada ejecución queda en [started] y el
 * test decide qué pasa en ella llamando a su [ExecutionListener]. Le gana a
 * [com.ingsis.snippet.execution.WebSocketLanguageExecutor] por ser `@Primary`.
 */
@Primary
@Component
class ScriptedLanguageExecutor : LanguageExecutor {
    class Started(
        val request: ExecutionRequest,
        val listener: ExecutionListener,
    ) {
        val inputs = LinkedBlockingQueue<String>()

        @Volatile
        var cancelled = false
    }

    private val started = LinkedBlockingQueue<Started>()

    @Volatile
    var unavailable = false

    override fun start(
        request: ExecutionRequest,
        listener: ExecutionListener,
    ): RemoteExecution {
        if (unavailable) throw LanguageUnavailableException()
        val execution = Started(request, listener)
        started.put(execution)
        return object : RemoteExecution {
            override fun input(value: String) = execution.inputs.put(value)

            override fun cancel() {
                execution.cancelled = true
            }
        }
    }

    fun next(): Started = started.poll(5, TimeUnit.SECONDS) ?: error("No empezó ninguna ejecución")

    fun noneStarted() = started.isEmpty()

    fun reset() {
        started.clear()
        unavailable = false
    }
}
