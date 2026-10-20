package com.ingsis.snippet

import com.ingsis.snippet.clients.UnsupportedLanguageException
import com.ingsis.snippet.snippets.EmptyFileException
import com.ingsis.snippet.snippets.InvalidSnippetException
import com.ingsis.snippet.snippets.NoAccessException
import com.ingsis.snippet.snippets.NotOwnerException
import com.ingsis.snippet.snippets.SnippetNotFoundException
import com.ingsis.snippet.tests.TestCaseNotFoundException
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

    @ExceptionHandler(UnsupportedLanguageException::class, EmptyFileException::class)
    fun badRequest(e: RuntimeException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(SnippetNotFoundException::class, TestCaseNotFoundException::class)
    fun notFound(e: RuntimeException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(NotOwnerException::class, NoAccessException::class)
    fun forbidden(e: RuntimeException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message)

    /** Otro servicio no respondió o respondió con un error que no esperábamos. */
    @ExceptionHandler(RestClientException::class)
    fun downstreamFailure(e: RestClientException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Falló un servicio interno")
}
