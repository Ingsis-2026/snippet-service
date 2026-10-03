package com.ingsis.snippet.snippets

import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * Hasta que se vea Auth0 en clase, el usuario llega en este header. Después va a salir del
 * `sub` del JWT, y este header desaparece.
 */
const val USER_HEADER = "X-User-Id"

data class SnippetResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val content: String,
    val ownerId: String,
    val status: ValidityStatus,
)

fun Snippet.toResponse() = SnippetResponse(id!!, name, description, language, version, content, ownerId, status)

@RestController
class SnippetController(
    private val service: SnippetService,
) {
    /** US1: crear un snippet subiendo un archivo, junto con sus datos. */
    @PostMapping("/snippets", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun createFromFile(
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
        @RequestParam @NotBlank name: String,
        @RequestParam @NotBlank description: String,
        @RequestParam @NotBlank language: String,
        @RequestParam @NotBlank version: String,
        @RequestParam file: MultipartFile,
    ): SnippetResponse {
        if (file.isEmpty) throw EmptyFileException()
        val content = file.bytes.toString(Charsets.UTF_8)
        return service.create(userId, NewSnippet(name, description, language, version, content)).toResponse()
    }
}

class EmptyFileException : RuntimeException("El archivo está vacío")
