package com.ingsis.snippet.clients

import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.client.RestClient

@Configuration
class ClientsConfig {
    /**
     * Prototype porque el builder es mutable: cada cliente arma el suyo con su propia URL base. Todos le pasan
     * el `requestId` a los servicios internos ([RequestIdForwarding]).
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun restClientBuilder(
        @Value("\${services.permission-url}") permissionUrl: String,
        @Value("\${services.language-url}") languageUrl: String,
    ): RestClient.Builder = RestClient.builder().requestInterceptor(RequestIdForwarding(listOf(permissionUrl, languageUrl)))
}
