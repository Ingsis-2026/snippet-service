package com.ingsis.snippet

import com.ingsis.snippet.clients.AlreadyHasAccessException
import com.ingsis.snippet.clients.UnsupportedLanguageException
import com.ingsis.snippet.rules.ConflictingRulesException
import com.ingsis.snippet.rules.InvalidRuleValueException
import com.ingsis.snippet.rules.UnknownRuleException
import com.ingsis.snippet.snippets.EmptyFileException
import com.ingsis.snippet.snippets.InvalidSnippetException
import com.ingsis.snippet.snippets.InvalidSortException
import com.ingsis.snippet.snippets.NoAccessException
import com.ingsis.snippet.snippets.NotOwnerException
import com.ingsis.snippet.snippets.SnippetNotFoundException
import com.ingsis.snippet.storage.StorageException
import com.ingsis.snippet.tests.TestCaseNotFoundException
import com.ingsis.snippet.users.UsersUnavailableException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.RestClientException

@RestControllerAdvice
class ErrorHandler {
    /** US1: se le informa al usuario qué regla incumplió y en qué línea y columna. */
    @ExceptionHandler(InvalidSnippetException::class)
    fun invalidSnippet(e: InvalidSnippetException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.message).apply {
            setProperty("errors", e.errors)
        }

    @ExceptionHandler(
        UnsupportedLanguageException::class,
        EmptyFileException::class,
        InvalidSortException::class,
        UnknownRuleException::class,
        InvalidRuleValueException::class,
        ConflictingRulesException::class,
    )
    fun badRequest(e: RuntimeException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(SnippetNotFoundException::class, TestCaseNotFoundException::class)
    fun notFound(e: RuntimeException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(NotOwnerException::class, NoAccessException::class)
    fun forbidden(e: RuntimeException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message)

    @ExceptionHandler(AlreadyHasAccessException::class)
    fun conflict(e: RuntimeException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)

    /**
     * Otro servicio, o asset-service al guardar o leer el código, no respondió o respondió con un error que no
     * esperábamos. Al usuario no se le dice cuál; en el log queda el error entero, con el `requestId` del pedido.
     */
    @ExceptionHandler(RestClientException::class, StorageException::class)
    fun downstreamFailure(e: RuntimeException): ProblemDetail {
        log.error("Falló un servicio interno", e)
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Falló un servicio interno")
    }

    /** US7: Auth0 no respondió, o falta configurar la aplicación con la que se buscan usuarios. */
    @ExceptionHandler(UsersUnavailableException::class)
    fun usersUnavailable(e: UsersUnavailableException): ProblemDetail {
        log.warn("La búsqueda de usuarios no está disponible: {}", e.message, e.cause)
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.message)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ErrorHandler::class.java)
    }
}
