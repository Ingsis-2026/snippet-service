package com.ingsis.snippet

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Los origins, además del propio, cuyo JavaScript puede leer las respuestas. La UI desplegada no
 * necesita estar acá: llama a `/api` en su mismo dominio y nginx se lo pasa a este servicio, así
 * que para el navegador es el mismo origin. Sí hace falta para la UI corriendo en local contra
 * una API desplegada, o si la API pasa a tener su propio dominio.
 *
 * Nunca `*`: con el token de Auth0 el navegador no lo acepta, y cualquier página podría leer las
 * respuestas.
 */
@ConfigurationProperties("cors")
data class CorsProperties(
    val allowedOrigins: List<String> = listOf("http://localhost:5173"),
)

/**
 * CORS con Spring MVC. Spring Security la usa con `cors { }` en [SecurityConfig]: sin eso, el
 * preflight llega sin token y responde 401.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties::class)
class CorsConfig(
    private val properties: CorsProperties,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry
            .addMapping("/**")
            .allowedOrigins(*properties.allowedOrigins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            // El access token de Auth0 va en Authorization.
            .allowedHeaders(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION)
            // Para que el JavaScript pueda leer el nombre del archivo al descargar un snippet (US13).
            .exposedHeaders(HttpHeaders.CONTENT_DISPOSITION)
            .maxAge(3600)
    }
}
