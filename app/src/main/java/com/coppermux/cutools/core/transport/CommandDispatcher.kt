package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.error.CdpException
import com.coppermux.cutools.core.error.CommandException
import com.coppermux.cutools.core.error.CommandTimeoutException
import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.ProtocolException
import com.coppermux.cutools.core.error.TransportException
import com.coppermux.cutools.core.model.CdpMessageClassifier
import com.coppermux.cutools.core.model.Command
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.MessageClassification
import com.coppermux.cutools.core.model.Protocol
import com.coppermux.cutools.core.model.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Configuration for the command dispatcher's timeout behavior.
 *
 * @property defaultTimeoutMs Default timeout for commands that don't specify one.
 * @property executor Optional custom executor for timeout scheduling (mainly for testing).
 */
data class DispatcherConfig(
    val defaultTimeoutMs: Long = 60_000L,
    val executor: ScheduledExecutorService? = null,
)

/**
 * Tracks a pending command awaiting its response.
 */
internal class PendingCommand(
    val command: Command,
    val callback: (CommandResult) -> Unit,
    val scheduledFuture: ScheduledFuture<*>,
)

/**
 * Central asynchronous command dispatcher.
 *
 * Responsibilities:
 * - Allocates unique command IDs.
 * - Serializes commands and sends them through [CdpTransport].
 * - Tracks pending commands keyed by ID.
 * - Correlates responses by ID (not arrival order).
 * - Handles command timeouts.
 * - Cleans up all pending commands on connection failure/loss.
 *
 * Multiple commands may be outstanding simultaneously.
 * Does NOT block — all callbacks are delivered on the transport's callback thread
 * or the dispatcher's executor thread.
 *
 * The dispatcher implements [CdpTransport.Listener] so the ConnectionManager
 * can wire it to a transport. Incoming messages are classified:
 * - Responses are correlated by ID and resolved.
 * - Events are forwarded to the [eventRouter].
 */
class CommandDispatcher(
    private val transport: CdpTransport,
    private val eventRouter: CdpEventRouter? = null,
    private val config: DispatcherConfig = DispatcherConfig(),
) : CdpTransport.Listener {

    private val idGenerator = AtomicLong(0)
    private val pendingCommands = ConcurrentHashMap<Long, PendingCommand>()
    private val classifier = CdpMessageClassifier()

    private val executor: ScheduledExecutorService =
        config.executor ?: Executors.newSingleThreadScheduledExecutor()

    /**
     * Send a CDP command asynchronously.
     *
     * @param method CDP method name (e.g., "Browser.getVersion").
     * @param params JSON params (as a Map or null).
     * @param sessionId Optional CDP session ID for commands sent within an attached target session.
     * @param timeoutMs Optional timeout override. Defaults to [DispatcherConfig.defaultTimeoutMs].
     * @param callback Called with a [CommandResult] — either Success or Failure.
     */
    fun sendCommand(
        method: String,
        params: Any? = null,
        sessionId: String? = null,
        timeoutMs: Long = config.defaultTimeoutMs,
        callback: (CommandResult) -> Unit,
    ) {
        val id = idGenerator.incrementAndGet()
        val paramsValue: Any? = if (params != null) {
            val mapParams = params as? Map<*, *> ?: throw IllegalArgumentException(
                "params must be a Map, but was ${params::class.java}"
            )
            // Keep params as a structured object (Map) so the command adapter
            // serializes it as a true JSON object — NOT a pre-serialized JSON
            // string, which would be double-encoded as "params":"{...}".
            mapParams
        } else {
            null
        }
        val command = Command(id = id, method = method, params = paramsValue, sessionId = sessionId)
        sendPreparedCommand(command, callback, timeoutMs)
    }

    /**
     * Send a pre-built [Command] with a pre-assigned ID. Used when the caller
     * wants to control the ID (e.g., for testing or when re-sending).
     */
    fun sendCommand(
        command: Command,
        callback: (CommandResult) -> Unit,
        timeoutMs: Long = config.defaultTimeoutMs,
    ) {
        sendPreparedCommand(command, callback, timeoutMs)
    }

    private fun sendPreparedCommand(
        command: Command,
        callback: (CommandResult) -> Unit,
        timeoutMs: Long,
    ) {
        if (!transport.isConnected) {
            callback(CommandResult.Failure(
                ConnectionException("Cannot send command: transport is not connected")
            ))
            return
        }

        val scheduledFuture = scheduleTimeout(command.id, timeoutMs, callback)
        pendingCommands[command.id] = PendingCommand(command, callback, scheduledFuture)

        val commandJson = Protocol.commandAdapter.toJson(command)
        try {
            transport.send(commandJson)
        } catch (e: CdpException) {
            pendingCommands.remove(command.id)?.scheduledFuture?.cancel(false)
            callback(CommandResult.Failure(e))
        } catch (e: Exception) {
            pendingCommands.remove(command.id)?.scheduledFuture?.cancel(false)
            callback(CommandResult.Failure(
                CommandException("Unexpected error sending command: ${e.message}", cause = e)
            ))
        }
    }

    private fun scheduleTimeout(
        id: Long,
        timeoutMs: Long,
        callback: (CommandResult) -> Unit,
    ): ScheduledFuture<*> {
        return if (timeoutMs > 0) {
            executor.schedule({
                val removed = pendingCommands.remove(id)
                if (removed != null) {
                    callback(CommandResult.Failure(
                        CommandTimeoutException(
                            "Command timed out after ${timeoutMs}ms (id=$id, method=${removed.command.method})"
                        )
                    ))
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            // No timeout: schedule a no-op that will be cancelled on response
            executor.schedule({
                pendingCommands.remove(id)
            }, Long.MAX_VALUE / 2, TimeUnit.MILLISECONDS)
        }
    }

    /** Called by [CdpTransport] when a message is received. */
    override fun onMessage(rawJson: String) {
        val classified = classifier.classify(rawJson)
        when (classified) {
            is MessageClassification.ResponseMessage -> {
                handleResponse(classified.response)
            }
            is MessageClassification.EventMessage -> {
                eventRouter?.handleEvent(classified.event)
            }
            is MessageClassification.Unclassified -> {
                eventRouter?.handleParsingError(classified.error)
            }
        }
    }

    /**
     * Handles an incoming CDP response. Correlates by command ID.
     */
    internal fun handleResponse(response: Response) {
        val pending = pendingCommands.remove(response.id)
        if (pending != null) {
            pending.scheduledFuture.cancel(false)
            if (response.isSuccess) {
                pending.callback(CommandResult.Success(response))
            } else {
                val error = response.error
                if (error != null) {
                    pending.callback(CommandResult.Failure(
                        ProtocolException(
                            "CDP error ${error.code}: ${error.message} for command ${pending.command.method} (id=${response.id})"
                        )
                    ))
                } else {
                    pending.callback(CommandResult.Failure(
                        ProtocolException(
                            "Protocol error for command ${pending.command.method} (id=${response.id})"
                        )
                    ))
                }
            }
        }
        // If no pending command matches, the response is orphaned — ignore
    }

    /**
     * Called by [CdpTransport] when the connection is established.
     * Clears stale pending commands.
     */
    override fun onConnected() {
        clearPendingCommands()
    }

    /**
     * Called by [CdpTransport] when the connection is closed.
     * Fails all pending commands.
     */
    override fun onClosed(reason: String) {
        failAllPending(ConnectionException("Connection closed: $reason"))
    }

    override fun onError(error: TransportException) {
        failAllPending(error)
    }

    /**
     * Cancels all scheduled timeout futures and clears pending commands
     * without invoking callbacks. Used on clean connect.
     */
    internal fun clearPendingCommands() {
        val all = pendingCommands.values.toList()
        pendingCommands.clear()
        all.forEach { it.scheduledFuture.cancel(false) }
    }

    /**
     * Fails all pending commands with the given error and clears the map.
     */
    internal fun failAllPending(error: CdpException) {
        val all = pendingCommands.values.toList()
        pendingCommands.clear()
        all.forEach {
            it.scheduledFuture.cancel(false)
            it.callback(CommandResult.Failure(error))
        }
    }

    /**
     * Cancel a specific pending command by ID (if it exists).
     */
    fun cancelCommand(id: Long) {
        pendingCommands.remove(id)?.let {
            it.scheduledFuture.cancel(false)
            it.callback(CommandResult.Failure(
                CommandException("Command cancelled (id=$id, method=${it.command.method})")
            ))
        }
    }

    /**
     * Cancels all pending commands that belong to [sessionId], failing their
     * callbacks with a [CommandException]. If [sessionId] is null, cancels
     * all session-scoped commands (commands with non-null sessionId).
     */
    fun cancelCommandsForSession(sessionId: String? = null, error: CdpException? = null) {
        val exception = error ?: CommandException("Command cancelled: session invalidated by reset")
        val matching = pendingCommands.entries.filter { (_, pending) ->
            if (sessionId != null) {
                pending.command.sessionId == sessionId
            } else {
                pending.command.sessionId != null
            }
        }
        matching.forEach { (id, pending) ->
            if (pendingCommands.remove(id) != null) {
                pending.scheduledFuture.cancel(false)
                pending.callback(CommandResult.Failure(exception))
            }
        }
    }

    /** Number of currently outstanding commands. */
    val pendingCommandCount: Int get() = pendingCommands.size

    /**
     * Shutdown the dispatcher's executor. Should be called during disconnect.
     */
    fun shutdown() {
        executor.shutdownNow()
    }
}
