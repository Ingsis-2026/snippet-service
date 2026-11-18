package com.ingsis.snippet

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

/**
 * Prende `@Async`. Lo que se anota así corre en el executor que arma Spring Boot
 * (`spring.task.execution.*`): hoy, solo los tests que se corren al actualizar un snippet (US16).
 */
@Configuration
@EnableAsync
class AsyncConfig
