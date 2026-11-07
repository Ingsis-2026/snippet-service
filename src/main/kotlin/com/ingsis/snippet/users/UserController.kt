package com.ingsis.snippet.users

import com.ingsis.snippet.CurrentUser
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class UserController(
    private val directory: UserDirectory,
) {
    /**
     * US7: los usuarios con los que se puede compartir un snippet, para elegirlos de un listado
     * que se filtra mientras se escribe. [name] filtra por el principio del nombre o del email.
     */
    @GetMapping("/users")
    fun search(
        @CurrentUser userId: String,
        @RequestParam(defaultValue = "") name: String,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) size: Int,
    ): UserPage = directory.search(name, userId, page, size)
}
