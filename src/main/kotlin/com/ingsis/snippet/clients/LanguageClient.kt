package com.ingsis.snippet.clients

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/** Una regla del lenguaje que el código no cumple, como la informa language-service. */
data class CodeError(
    val rule: String,
    val line: Int?,
    val column: Int?,
)

internal data class ValidationRequest(
    val code: String,
    val language: String,
    val version: String,
)

internal data class ValidationResult(
    val valid: Boolean,
    val errors: List<CodeError>,
)

@Component
class LanguageClient(
    builder: RestClient.Builder,
    @Value("\${services.language-url}") baseUrl: String,
) {
    private val client = builder.baseUrl(baseUrl).build()

    /** Los errores del código; vacío si es válido. */
    fun validate(
        code: String,
        language: String,
        version: String,
    ): List<CodeError> =
        try {
            client
                .post()
                .uri("/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ValidationRequest(code, language, version))
                .retrieve()
                .body<ValidationResult>()!!
                .errors
        } catch (e: HttpClientErrorException.BadRequest) {
            // language-service responde 400 solo cuando no conoce el lenguaje o la versión.
            val detail = e.getResponseBodyAs(ProblemDetail::class.java)?.detail
            throw UnsupportedLanguageException(detail ?: "Lenguaje o versión no soportados")
        }
}

class UnsupportedLanguageException(
    message: String,
) : RuntimeException(message)
