package cn.studybridge.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One caller's calls, retained until the response body has been consumed. */
internal class HttpReadScope {
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private var cancelled = false

    fun cancel() {
        val pending = synchronized(lock) { cancelled = true; calls.toList() }
        pending.forEach(Call::cancel)
    }

    fun <T> response(client: OkHttpClient, request: Request, read: (Response) -> T): T {
        val call = client.newCall(request)
        synchronized(lock) {
            if (cancelled) throw CancellationException("HTTP read cancelled")
            calls.add(call)
        }
        try { return call.execute().use(read) }
        finally { synchronized(lock) { calls.remove(call) } }
    }
}

/**
 * Suspend without blocking the owner's Job while execute/body parsing runs.
 * Cancellation resumes the owner promptly and cancels only this read's calls.
 * A transport/interceptor that returns late cannot publish a result afterward.
 * Keep [read] limited to transport/decoding; apply application state after return.
 */
internal suspend fun <T> cancellableHttpRead(read: (HttpReadScope) -> T): T = suspendCancellableCoroutine { continuation ->
    val scope = HttpReadScope()
    continuation.invokeOnCancellation { scope.cancel() }
    Dispatchers.IO.dispatch(continuation.context, Runnable {
        if (!continuation.isActive) return@Runnable
        try {
            val result = read(scope)
            if (continuation.isActive) continuation.resume(result)
        } catch (e: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
}
