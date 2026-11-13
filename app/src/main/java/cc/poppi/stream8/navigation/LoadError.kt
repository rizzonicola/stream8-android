package cc.poppi.stream8.navigation

enum class LoadErrorKind { OFFLINE, TIMEOUT, TLS, OTHER }

/** Classificazione dei codici WebViewClient.ERROR_* (valori stabili dell'API Android). */
object LoadErrors {
    private const val ERROR_HOST_LOOKUP = -2
    private const val ERROR_CONNECT = -6
    private const val ERROR_IO = -7
    private const val ERROR_TIMEOUT = -8
    private const val ERROR_FAILED_SSL_HANDSHAKE = -11

    fun classify(errorCode: Int): LoadErrorKind = when (errorCode) {
        ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_IO -> LoadErrorKind.OFFLINE
        ERROR_TIMEOUT -> LoadErrorKind.TIMEOUT
        ERROR_FAILED_SSL_HANDSHAKE -> LoadErrorKind.TLS
        else -> LoadErrorKind.OTHER
    }
}
