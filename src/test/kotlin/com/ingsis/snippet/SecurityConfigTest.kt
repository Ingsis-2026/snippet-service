package com.ingsis.snippet

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `el health check responde sin token`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    @Test
    fun `sin token el resto de la API responde 401`() {
        mockMvc.get("/snippets").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `con un JWT valido la request pasa el filtro de seguridad`() {
        mockMvc.get("/snippets") { with(jwt()) }.andExpect { status { isNotFound() } }
    }
}
