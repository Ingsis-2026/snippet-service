package com.ingsis.snippet

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * Un pedido autenticado como [userId]: un access token con ese `sub`, como los que emite Auth0.
 * No se valida contra Auth0, así que los tests no necesitan red.
 */
fun asUser(userId: String): RequestPostProcessor = jwt().jwt { it.subject(userId) }
