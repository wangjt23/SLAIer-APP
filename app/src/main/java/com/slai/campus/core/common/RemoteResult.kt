package com.slai.campus.core.common

/**
 * Unified outcome for every remote read in the app.
 *
 * The whole degradation strategy depends on callers being able to tell the difference between
 * "the server said there is nothing" and "we could not ask the server". A nullable list or a
 * boolean would collapse those two cases into one and produce the exact bug the design plan
 * forbids: showing an empty timetable because a request failed.
 */
sealed interface RemoteResult<out T> {

    /** The server answered and the payload passed schema validation. */
    data class Success<T>(val data: T) : RemoteResult<T>

    /**
     * The school session is gone. Detected from a redirect to the login page *or* from an HTTP 200
     * whose body is actually the login page.
     */
    data object SessionExpired : RemoteResult<Nothing>

    /** No usable network, DNS failure, connect/read timeout, TLS failure. Cache stays authoritative. */
    data class NetworkUnavailable(val reason: String) : RemoteResult<Nothing>

    /** Reached the server, got a non-2xx status that is not a login redirect. */
    data class ServerError(val code: Int, val reason: String? = null) : RemoteResult<Nothing>

    /**
     * The endpoint answered with something we cannot trust: missing key fields, wrong content type,
     * unparsable JSON. Never treated as "empty".
     */
    data class SchemaChanged(val reason: String) : RemoteResult<Nothing>

    /** Anything else worth surfacing verbatim (redacted) in diagnostics. */
    data class UnknownError(val reason: String) : RemoteResult<Nothing>
}

typealias Failure = RemoteResult<Nothing>

/** Short, user-facing description of a failure, suitable for the sync status row. */
fun RemoteResult<*>?.describe(): String = when (this) {
    null -> ""
    is RemoteResult.Success -> "ok"
    RemoteResult.SessionExpired -> "session expired"
    is RemoteResult.NetworkUnavailable -> "network unavailable: $reason"
    is RemoteResult.ServerError -> "server error $code${reason?.let { ": $it" } ?: ""}"
    is RemoteResult.SchemaChanged -> "schema changed: $reason"
    is RemoteResult.UnknownError -> "error: $reason"
}

/** Convenience for `map` over the success branch without importing stdlib noise at call sites. */
inline fun <T, R> RemoteResult<T>.map(transform: (T) -> R): RemoteResult<R> = when (this) {
    is RemoteResult.Success -> RemoteResult.Success(transform(data))
    RemoteResult.SessionExpired -> RemoteResult.SessionExpired
    is RemoteResult.NetworkUnavailable -> RemoteResult.NetworkUnavailable(reason)
    is RemoteResult.ServerError -> RemoteResult.ServerError(code, reason)
    is RemoteResult.SchemaChanged -> RemoteResult.SchemaChanged(reason)
    is RemoteResult.UnknownError -> RemoteResult.UnknownError(reason)
}

/** Runs [block] only for a success payload; failures pass through unchanged. */
inline fun <T, R> RemoteResult<T>.flatMap(transform: (T) -> RemoteResult<R>): RemoteResult<R> = when (this) {
    is RemoteResult.Success -> transform(data)
    RemoteResult.SessionExpired -> RemoteResult.SessionExpired
    is RemoteResult.NetworkUnavailable -> RemoteResult.NetworkUnavailable(reason)
    is RemoteResult.ServerError -> RemoteResult.ServerError(code, reason)
    is RemoteResult.SchemaChanged -> RemoteResult.SchemaChanged(reason)
    is RemoteResult.UnknownError -> RemoteResult.UnknownError(reason)
}

fun <T> RemoteResult<T>.getOrNull(): T? = (this as? RemoteResult.Success)?.data

fun <T> RemoteResult<T>.failureOrNull(): Failure? = this as? Failure
