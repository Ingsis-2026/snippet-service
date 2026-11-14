package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.assertEquals

/**
 * Las tareas programadas no comparten un solo hilo: una tanda lenta de un worker no frena a las demás.
 * Se prenden los reportes, que son de las que no necesitan nada de afuera, con una demora que no llega.
 */
@SpringBootTest(properties = ["observability.enabled=true", "observability.initial-delay=1h"])
@ActiveProfiles("test")
class SchedulingTest(
    @Autowired private val scheduler: ThreadPoolTaskScheduler,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @Test
    fun `las tareas programadas corren en un pool de varios hilos`() {
        assertEquals(4, scheduler.scheduledThreadPoolExecutor.corePoolSize)
    }
}
