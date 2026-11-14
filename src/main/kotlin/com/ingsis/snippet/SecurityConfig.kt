package com.ingsis.snippet

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
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

/** El scope de Auth0 para ver snippets, sus tests y las reglas propias, y para correrlos. */
const val READ_SNIPPETS = "read:snippets"

/** El scope de Auth0 para crear, cambiar, borrar y compartir snippets, sus tests y las reglas propias. */
const val WRITE_SNIPPETS = "write:snippets"

/**
 * Cómo ve Spring Security a un scope: el claim `scope` del access token, que en los de Auth0 es un texto con
 * los scopes separados por espacios (`"openid read:snippets write:snippets"`), se convierte en una authority
 * `SCOPE_<scope>` por cada uno. Es lo que hace por defecto el `JwtGrantedAuthoritiesConverter`.
 */
fun scopeAuthority(scope: String) = "SCOPE_$scope"

/**
 * Todos los endpoints piden un access token de Auth0, salvo el health check y el WebSocket de
 * `/execution`, que lo pide en su primer mensaje. Spring valida la firma con las claves públicas
 * del tenant, que el issuer sea ese tenant, que no esté vencido y que sea para esta API
 * (`audiences`): un token emitido para otra API del mismo tenant no sirve.
 *
 * Además del token, cada endpoint pide un scope, según lo que hace:
 *
 * - [READ_SNIPPETS] para todo lo que solo lee: los `GET` (listar y ver snippets, sus tests, el linteo, la
 *   descarga, las reglas propias y los usuarios con quien compartir). También dos `POST` que no cambian
 *   nada de lo que cargó el usuario: previsualizar el formato (`POST /snippets/{id}/format`, no se guarda) y
 *   correr un test (`POST /snippets/{id}/tests/{testId}/run`). Correr un test sí deja guardado si pasó, pero
 *   es el resultado de correr lo que ya está, no un dato del usuario, y lo puede hacer cualquiera que vea el
 *   snippet, igual que por el WebSocket de `/execution` (US9), que también pide este scope.
 * - [WRITE_SNIPPETS] para el resto de los `POST`, `PUT` y `DELETE`: crear, actualizar, borrar y compartir
 *   snippets, crear, editar y borrar tests, y cambiar las reglas de lint y formato.
 *
 * Se decide por método y no endpoint por endpoint, así un endpoint nuevo queda protegido sin acordarse
 * de agregarlo acá: si es un `GET`, pide leer; si es otra cosa, escribir.
 *
 * Sin token es un 401. Con un token válido al que le falta el scope es un 403 (`insufficient_scope`): se
 * sabe quién es, pero no tiene permiso para eso.
 *
 * Sin CSRF ni sesión: el token viaja en cada pedido en `Authorization`, no en una cookie.
 */
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                val read = hasAuthority(scopeAuthority(READ_SNIPPETS))
                val write = hasAuthority(scopeAuthority(WRITE_SNIPPETS))
                authorize("/actuator/health", permitAll)
                // El navegador no puede mandar Authorization al abrir un WebSocket: el token llega
                // en el primer mensaje y lo valida ExecutionHandler, que también pide read:snippets.
                authorize("/execution", permitAll)
                authorize(HttpMethod.GET, "/**", read)
                authorize(HttpMethod.POST, "/snippets/{id}/format", read)
                authorize(HttpMethod.POST, "/snippets/{snippetId}/tests/{testId}/run", read)
                authorize(anyRequest, write)
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
