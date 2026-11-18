package com.ingsis.snippet.streams

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException

/**
 * Si la falla es de algo de lo que depende el procesamiento y no del snippet: language-service o
 * asset-service que no responden (conexión rechazada, timeout), que responden 5xx o que piden que se
 * baje el ritmo (429), o la base o Redis sin conexión. Reintentar ese snippet más tarde puede andar,
 * y a los demás les va a pasar lo mismo mientras tanto.
 *
 * Se busca en toda la cadena de causas, porque a veces llega envuelta: asset-service caído, por
 * ejemplo, llega como una [com.ingsis.snippet.storage.StorageException].
 *
 * Un 500 puede ser que la dependencia se cayó o que un snippet en particular la rompe. Se lo toma
 * como de la dependencia: cuál de las dos era se ve en que los demás snippets anden, y por eso
 * [RedisSnippetWorker] no posterga un mismo mensaje para siempre (ver [StreamProperties.maxDeferrals]).
 */
fun Throwable.isDependencyFailure(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is ResourceAccessException ||
            it is HttpServerErrorException ||
            it is HttpClientErrorException.TooManyRequests ||
            it is DataAccessResourceFailureException ||
            it is TransientDataAccessException ||
            it is CannotCreateTransactionException
    }
