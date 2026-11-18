package com.ingsis.snippet.observability

import org.slf4j.MDC

/** El id del pedido que originó lo que se está logueando: el de nginx, o uno nuevo si no vino. */
const val REQUEST_ID = "requestId"

/** El `sub` del access token de quien hizo el pedido. */
const val USER = "user"

/**
 * Corre [block] con [context] en el MDC y después lo deja como estaba. Los valores null no se agregan.
 *
 * El contexto va en el MDC y no en el texto del mensaje: así cada dato sale como un campo aparte en el
 * JSON de los logs, y New Relic los reenvía como atributos que se pueden filtrar (`context.snippetId`).
 * Los key-values de SLF4J (`addKeyValue`) también salen en el JSON, pero el agente de New Relic no los
 * reenvía.
 */
inline fun <T> withLogContext(
    vararg context: Pair<String, Any?>,
    block: () -> T,
): T {
    val previous = context.map { (key, _) -> key to MDC.get(key) }
    context.forEach { (key, value) -> value?.let { MDC.put(key, it.toString()) } }
    try {
        return block()
    } finally {
        previous.forEach { (key, value) -> if (value == null) MDC.remove(key) else MDC.put(key, value) }
    }
}
