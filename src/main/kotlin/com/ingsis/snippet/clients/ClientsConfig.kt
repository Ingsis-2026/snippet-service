package com.ingsis.snippet.clients

import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.client.RestClient

/**
 * El [RestClient.Builder] de todos los clientes HTTP del servicio. No se usa el de Spring Boot ni sus
 * propiedades `spring.http.clients.*`: en Boot 4 viven en `spring-boot-restclient` y `spring-boot-http-client`,
 * que este servicio no trae, así que los timeouts se configuran acá ([HttpTimeouts]).
 */
@Configuration
@EnableConfigurationProperties(HttpTimeouts::class)
class ClientsConfig {
    /** Uno solo para todo el servicio: comparte el [java.net.http.HttpClient] y sus conexiones. */
    @Bean
    fun timeoutsByService(
        timeouts: HttpTimeouts,
        @Value("\${services.language-url}") languageUrl: String,
    ): TimeoutsByService = TimeoutsByService(timeouts.connect, timeouts.read, timeouts.languageRead, listOf(languageUrl))

    /**
     * Prototype porque el builder es mutable: cada cliente arma el suyo con su propia URL base. Todos le pasan
     * el `requestId` a los servicios internos ([RequestIdForwarding]) y tienen timeouts ([TimeoutsByService]).
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun restClientBuilder(
        @Value("\${services.permission-url}") permissionUrl: String,
        @Value("\${services.language-url}") languageUrl: String,
        timeouts: TimeoutsByService,
    ): RestClient.Builder =
        RestClient
            .builder()
            .requestFactory(timeouts)
            .requestInterceptor(RequestIdForwarding(listOf(permissionUrl, languageUrl)))
}
