package com.ingsis.snippet

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `el health check responde`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
