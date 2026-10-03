package com.ingsis.snippet.clients

import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.client.RestClient

@Configuration
class ClientsConfig {
    /** Prototype porque el builder es mutable: cada cliente arma el suyo con su propia URL base. */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()
}
