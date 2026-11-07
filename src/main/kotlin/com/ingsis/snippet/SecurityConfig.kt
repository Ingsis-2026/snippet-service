package com.ingsis.snippet

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.web.SecurityFilterChain

/**
 * El usuario que hace el pedido: el `sub` del access token de Auth0 (por ejemplo `auth0|6543…`).
 * Es el id con el que se guardan el owner de cada snippet y sus permisos en permission-service.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@AuthenticationPrincipal(expression = "subject")
annotation class CurrentUser

/**
 * Todos los endpoints piden un access token de Auth0, salvo el health check y el WebSocket de
 * `/execution`, que lo pide en su primer mensaje. Spring valida la firma con las claves públicas
 * del tenant, que el issuer sea ese tenant, que no esté vencido y que sea para esta API
 * (`audiences`): un token emitido para otra API del mismo tenant no sirve.
 *
 * Sin CSRF ni sesión: el token viaja en cada pedido en `Authorization`, no en una cookie.
 */
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/actuator/health", permitAll)
                // El navegador no puede mandar Authorization al abrir un WebSocket: el token llega
                // en el primer mensaje y lo valida ExecutionHandler.
                authorize("/execution", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer { jwt { } }
            // Usa la configuración de CorsConfig. Va antes que la autenticación: el preflight
            // del navegador llega sin token y, si no, respondería 401.
            cors { }
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
        }
        return http.build()
    }
}
