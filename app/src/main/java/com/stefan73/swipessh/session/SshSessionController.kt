package com.stefan73.swipessh.session

import com.stefan73.swipessh.data.ssh.ActiveSshSession
import com.stefan73.swipessh.data.ssh.HostKeyDecision
import com.stefan73.swipessh.data.ssh.HostKeyVerificationRequest
import com.stefan73.swipessh.data.ssh.SshConnectRequest
import com.stefan73.swipessh.data.ssh.SshSessionManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClientAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

enum class SessionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}

data class SessionUiState(
    val status: SessionStatus = SessionStatus.IDLE,
    val terminalTitle: String = "",
    val terminalSession: TerminalSession? = null,
)

/** Owns the live SSH session and its terminal lifecycle independently from form state. */
class SshSessionController(
    private val scope: CoroutineScope,
    private val sshSessionManager: SshSessionManager,
    private val onUserMessage: (String) -> Unit,
) {
    private val sessionState = MutableStateFlow(SessionUiState())
    private var activeSession: ActiveSshSession? = null
    private var connectJob: Job? = null
    private var terminalReaderJob: Job? = null
    private var pendingTerminalSize: TerminalSize? = null
    private var appliedTerminalSize: TerminalSize? = null

    /** Exposes terminal title, terminal session, and connection state to the UI. */
    val uiState: StateFlow<SessionUiState> = sessionState.asStateFlow()

    /** Starts a fresh SSH connection attempt and prepares the backing terminal session. */
    fun connect(
        request: SshConnectRequest,
        title: String,
        onConnected: suspend () -> Unit = {},
        onHostKeyDecision: suspend (HostKeyVerificationRequest) -> HostKeyDecision,
    ) {
        connectJob?.cancel()
        terminalReaderJob?.cancel()
        activeSession = null
        pendingTerminalSize = null
        appliedTerminalSize = null

        val terminalSession = createTerminalSession(title)
        sessionState.value = SessionUiState(
            status = SessionStatus.CONNECTING,
            terminalTitle = title,
            terminalSession = terminalSession,
        )
        appendTerminalText("Connecting to ${request.host}:${request.port}...\r\n")

        connectJob = scope.launch {
            runCatching {
                sshSessionManager.connect(
                    request = request,
                    onAttempt = { attempt ->
                        if (attempt.resolvedFromHostname) {
                            appendTerminalText("IP: ${attempt.address}\r\n")
                        }
                    },
                    onHostKeyDecision = onHostKeyDecision,
                )
            }.onSuccess { sshSession ->
                if (sessionState.value.status != SessionStatus.CONNECTING) {
                    runCatching { sshSession.disconnect() }
                    return@onSuccess
                }

                activeSession = sshSession
                sessionState.update { it.copy(status = SessionStatus.CONNECTED) }
                appendTerminalText("Connection established. Interactive shell opened.\r\n")
                syncTerminalSize(sshSession)
                onConnected()
                startTerminalReader(sshSession, terminalSession)
            }.onFailure { error ->
                if (error is CancellationException || sessionState.value.status == SessionStatus.IDLE) {
                    return@onFailure
                }

                activeSession = null
                sessionState.update { it.copy(status = SessionStatus.ERROR) }
                appendTerminalText("Connection failed: ${error.message ?: "Unknown error"}\r\n")
                onUserMessage("SSH connection failed.")
            }
        }
    }

    /** Writes keyboard or softkey input to the active SSH shell. */
    fun sendTerminalBytes(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        val session = activeSession ?: run {
            onUserMessage("No active SSH session.")
            return
        }

        scope.launch {
            runCatching {
                session.send(bytes, offset, count)
            }.onFailure {
                onUserMessage("Could not send terminal input.")
            }
        }
    }

    /** Cancels a pending connect or closes the active SSH session. */
    fun disconnect() {
        if (sessionState.value.status == SessionStatus.CONNECTING) {
            connectJob?.cancel()
            connectJob = null
            activeSession = null
            pendingTerminalSize = null
            appliedTerminalSize = null
            sessionState.update { it.copy(status = SessionStatus.IDLE) }
            appendTerminalText("Connection attempt cancelled.\r\n")
            onUserMessage("Connection cancelled.")
            return
        }

        val session = activeSession ?: run {
            sessionState.update { it.copy(status = SessionStatus.IDLE) }
            return
        }

        scope.launch {
            sessionState.update { it.copy(status = SessionStatus.DISCONNECTING) }
            terminalReaderJob?.cancel()
            runCatching { session.disconnect() }
            activeSession = null
            pendingTerminalSize = null
            appliedTerminalSize = null
            connectJob = null
            sessionState.update { it.copy(status = SessionStatus.IDLE) }
            appendTerminalText("Session closed.\r\n")
            onUserMessage("Disconnected.")
        }
    }

    /** Performs best-effort shutdown when the owning scope goes away. */
    fun dispose() {
        connectJob?.cancel()
        terminalReaderJob?.cancel()
        activeSession?.let { session ->
            scope.launch { session.disconnect() }
        }
    }

    /** Applies the latest measured terminal size to the remote PTY. */
    private fun syncTerminalSize(session: ActiveSshSession) {
        val size = pendingTerminalSize ?: return
        if (appliedTerminalSize == size) {
            return
        }

        scope.launch {
            runCatching {
                session.resize(
                    columns = size.columns,
                    rows = size.rows,
                    widthPixels = size.widthPixels,
                    heightPixels = size.heightPixels,
                )
            }.onSuccess {
                appliedTerminalSize = size
            }
        }
    }

    /** Streams shell output into the terminal emulator until the session ends. */
    private fun startTerminalReader(sshSession: ActiveSshSession, liveTerminalSession: TerminalSession) {
        terminalReaderJob?.cancel()
        terminalReaderJob = scope.launch {
            runCatching {
                sshSession.readLoop { chunk, length ->
                    liveTerminalSession.appendIncoming(chunk, length)
                }
            }.onSuccess {
                if (activeSession === sshSession && sessionState.value.status == SessionStatus.CONNECTED) {
                    activeSession = null
                    pendingTerminalSize = null
                    appliedTerminalSize = null
                    sessionState.update { it.copy(status = SessionStatus.IDLE) }
                    appendTerminalText("Connection closed by remote host.\r\n")
                    onUserMessage("SSH session closed.")
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                if (sessionState.value.status == SessionStatus.CONNECTED || sessionState.value.status == SessionStatus.CONNECTING) {
                    activeSession = null
                    pendingTerminalSize = null
                    appliedTerminalSize = null
                    sessionState.update { it.copy(status = SessionStatus.ERROR) }
                    appendTerminalText("Session ended: ${error.message ?: "Unknown error"}\r\n")
                    onUserMessage("SSH session ended unexpectedly.")
                }
            }
        }
    }

    /** Creates the terminal session and keeps PTY size in sync with the rendered grid. */
    private fun createTerminalSession(name: String): TerminalSession {
        return TerminalSession(
            name,
            10_000,
            TerminalSessionClientAdapter(),
            object : TerminalSession.SessionOutputWriter {
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    sendTerminalBytes(data, offset, count)
                }

                override fun onResize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
                    if (columns <= 0 || rows <= 0 || cellWidthPixels <= 0 || cellHeightPixels <= 0) {
                        return
                    }

                    // Keep the remote PTY aligned with the exact integer cell grid chosen by the terminal view.
                    val size = TerminalSize(
                        columns = columns,
                        rows = rows,
                        widthPixels = max(columns * cellWidthPixels, 1),
                        heightPixels = max(rows * cellHeightPixels, 1),
                    )

                    pendingTerminalSize = size

                    val session = activeSession ?: return
                    if (appliedTerminalSize == size) {
                        return
                    }

                    scope.launch {
                        runCatching {
                            session.resize(
                                columns = size.columns,
                                rows = size.rows,
                                widthPixels = size.widthPixels,
                                heightPixels = size.heightPixels,
                            )
                        }.onSuccess {
                            appliedTerminalSize = size
                        }
                    }
                }

                override fun onSessionFinished() {
                    disconnect()
                }
            },
        )
    }

    /** Appends local status text into the terminal transcript. */
    private fun appendTerminalText(text: String) {
        val session = sessionState.value.terminalSession ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.appendIncoming(bytes, bytes.size)
    }
}

private data class TerminalSize(
    val columns: Int,
    val rows: Int,
    val widthPixels: Int,
    val heightPixels: Int,
)

