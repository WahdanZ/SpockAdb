package spock.adb

/**
 * Tells the answer to the latest request apart from answers to requests it has replaced.
 *
 * Asynchronous reads finish in whatever order the device answers them, so the one that lands
 * last is not necessarily the one started last. Each request takes a token from [begin]; a
 * result is only worth applying while [isLatest] still holds for its token.
 *
 * Not thread-safe, and does not need to be: the tool window starts requests and receives their
 * results on the EDT.
 */
internal class LatestRequest {
    private var latest = 0L

    /** Starts a request, retiring every earlier one. */
    fun begin(): Long = ++latest

    fun isLatest(token: Long): Boolean = token == latest
}
