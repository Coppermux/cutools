package com.coppermux.cutools.core.error

/**
 * Base exception type for all CuTools debugging errors.
 * Every error retains diagnostic information and is never silently swallowed.
 */
sealed class CdpException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** The [Category] of this error. */
    abstract val category: Category

    /** Stable error code for programmatic handling. */
    abstract val code: String

    enum class Category {
        TRANSPORT,
        CONNECTION,
        PROTOCOL,
        COMMAND_TIMEOUT,
        TARGET,
        SESSION,
        PARSING,
        UNSUPPORTED_OPERATION,
        COMMAND,
        DOM,
        CSS,
        CONSOLE,
        NETWORK,
        STORAGE,
    }

    companion object {
        fun transport(message: String, cause: Throwable? = null, code: String = "TRANSPORT_ERROR") =
            TransportException(message, cause, code)

        fun connection(message: String, cause: Throwable? = null, code: String = "CONNECTION_ERROR") =
            ConnectionException(message, cause, code)

        fun protocol(message: String, cause: Throwable? = null, code: String = "PROTOCOL_ERROR") =
            ProtocolException(message, cause, code)

        fun timeout(message: String, cause: Throwable? = null, code: String = "COMMAND_TIMEOUT") =
            CommandTimeoutException(message, cause, code)

        fun target(message: String, cause: Throwable? = null, code: String = "TARGET_ERROR") =
            TargetException(message, cause, code)

        fun session(message: String, cause: Throwable? = null, code: String = "SESSION_ERROR") =
            SessionException(message, cause, code)

        fun parsing(message: String, cause: Throwable? = null, code: String = "PARSING_ERROR") =
            ParsingException(message, cause, code)

        fun unsupported(message: String, cause: Throwable? = null, code: String = "UNSUPPORTED_OPERATION") =
            UnsupportedOperationException(message, cause, code)

        fun command(message: String, cause: Throwable? = null, code: String = "COMMAND_ERROR") =
            CommandException(message, cause, code)

        fun dom(message: String, cause: Throwable? = null, code: String = "DOM_ERROR") =
            DomException(message, cause, code)

        fun css(message: String, cause: Throwable? = null, code: String = "CSS_ERROR") =
            CssException(message, cause, code)

        fun console(message: String, cause: Throwable? = null, code: String = "CONSOLE_ERROR") =
            ConsoleException(message, cause, code)

        fun network(message: String, cause: Throwable? = null, code: String = "NETWORK_ERROR") =
            NetworkException(message, cause, code)

        fun storage(message: String, cause: Throwable? = null, code: String = "STORAGE_ERROR") =
            StorageException(message, cause, code)
    }
}

class TransportException(message: String, cause: Throwable? = null, code: String = "TRANSPORT_ERROR") :
    CdpException(message, cause) {
    override val category = Category.TRANSPORT
    override val code: String = code
}

class ConnectionException(message: String, cause: Throwable? = null, code: String = "CONNECTION_ERROR") :
    CdpException(message, cause) {
    override val category = Category.CONNECTION
    override val code: String = code
}

class ProtocolException(message: String, cause: Throwable? = null, code: String = "PROTOCOL_ERROR") :
    CdpException(message, cause) {
    override val category = Category.PROTOCOL
    override val code: String = code
}

class CommandTimeoutException(
    message: String,
    cause: Throwable? = null,
    code: String = "COMMAND_TIMEOUT",
) : CdpException(message, cause) {
    override val category = Category.COMMAND_TIMEOUT
    override val code: String = code
}

class TargetException(message: String, cause: Throwable? = null, code: String = "TARGET_ERROR") :
    CdpException(message, cause) {
    override val category = Category.TARGET
    override val code: String = code
}

class SessionException(message: String, cause: Throwable? = null, code: String = "SESSION_ERROR") :
    CdpException(message, cause) {
    override val category = Category.SESSION
    override val code: String = code
}

class ParsingException(message: String, cause: Throwable? = null, code: String = "PARSING_ERROR") :
    CdpException(message, cause) {
    override val category = Category.PARSING
    override val code: String = code
}

class UnsupportedOperationException(
    message: String,
    cause: Throwable? = null,
    code: String = "UNSUPPORTED_OPERATION",
) : CdpException(message, cause) {
    override val category = Category.UNSUPPORTED_OPERATION
    override val code: String = code
}

class CommandException(message: String, cause: Throwable? = null, code: String = "COMMAND_ERROR") :
    CdpException(message, cause) {
    override val category = Category.COMMAND
    override val code: String = code
}

/**
 * DOM-specific error (inspection, mutation, or DOM-model operation failure).
 * Retains the protocol/terminal error and is never silently swallowed.
 */
class DomException(message: String, cause: Throwable? = null, code: String = "DOM_ERROR") :
    CdpException(message, cause) {
    override val category = Category.DOM
    override val code: String = code
}

/**
 * CSS-specific error (domain enablement, style inspection, or CSS-model parsing
 * failure). Retains the protocol/terminal error and is never silently swallowed.
 */
class CssException(message: String, cause: Throwable? = null, code: String = "CSS_ERROR") :
    CdpException(message, cause) {
    override val category = Category.CSS
    override val code: String = code
}

/**
 * Console-specific error (Console/Runtime domain enablement, console event
 * processing, or Console-model parsing failure). Retains the protocol/terminal
 * error and is never silently swallowed.
 */
class ConsoleException(message: String, cause: Throwable? = null, code: String = "CONSOLE_ERROR") :
    CdpException(message, cause) {
    override val category = Category.CONSOLE
    override val code: String = code
}

/**
 * Network-specific error (Network domain enablement, network event processing,
 * or network-model parsing failure). Retains the protocol/terminal error and is
 * never silently swallowed.
 */
class NetworkException(message: String, cause: Throwable? = null, code: String = "NETWORK_ERROR") :
    CdpException(message, cause) {
    override val category = Category.NETWORK
    override val code: String = code
}
