package com.codex.sshterminal.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.sshterminal.AppLaunchActions
import com.codex.sshterminal.data.local.SavedAuthType
import com.codex.sshterminal.data.local.SavedConnectionEntity
import com.codex.sshterminal.data.local.SshTerminalDatabase
import com.codex.sshterminal.data.repository.ConnectionsRepository
import com.codex.sshterminal.data.security.ConnectionPasswordStore
import com.codex.sshterminal.data.ssh.HostKeyDecision
import com.codex.sshterminal.data.ssh.HostKeyPromptMode
import com.codex.sshterminal.data.ssh.HostKeyVerificationRequest
import com.codex.sshterminal.data.ssh.PrivateKeyInspection
import com.codex.sshterminal.data.ssh.SshAuthMethod
import com.codex.sshterminal.data.ssh.SshConnectRequest
import com.codex.sshterminal.data.ssh.SshSessionManager
import com.codex.sshterminal.service.SshSessionService
import com.codex.sshterminal.session.SessionRegistryState
import com.codex.sshterminal.session.SessionRepository
import com.codex.sshterminal.session.SessionSlotUiState
import com.codex.sshterminal.session.SessionStatus
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionFormState(
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val usePrivateKey: Boolean = false,
    val saveSecret: Boolean = false,
) {
    val isConnectEnabled: Boolean
        get() = host.isNotBlank() && port.toIntOrNull() != null && username.isNotBlank()

    val isSaveEnabled: Boolean
        get() = isConnectEnabled
}

data class KeyPassphrasePromptState(
    val message: String,
    val passphrase: String = "",
)

data class HostKeyPromptState(
    val mode: HostKeyPromptMode,
    val host: String,
    val port: Int,
    val keyType: String,
    val newFingerprint: String,
    val previousFingerprint: String? = null,
)

data class HomeUiState(
    val form: ConnectionFormState = ConnectionFormState(),
    val savedConnections: List<SavedConnectionEntity> = emptyList(),
    val selectedConnectionId: Long? = null,
    val isSaving: Boolean = false,
    val userMessage: String? = null,
    val sessionStatus: SessionStatus = SessionStatus.IDLE,
    val terminalTitle: String = "",
    val terminalSession: TerminalSession? = null,
    val activeSlotId: Int? = null,
    val sessionSlots: List<SessionSlotUiState> = emptyList(),
    val showConnectionScreen: Boolean = false,
    val keyPassphrasePrompt: KeyPassphrasePromptState? = null,
    val hostKeyPrompt: HostKeyPromptState? = null,
) {
    val isEditingSavedConnection: Boolean
        get() = selectedConnectionId != null
}

private data class HomeScaffoldState(
    val form: ConnectionFormState,
    val savedConnections: List<SavedConnectionEntity>,
    val selectedConnectionId: Long?,
    val isSaving: Boolean,
    val userMessage: String?,
    val keyPassphrasePrompt: KeyPassphrasePromptState?,
    val hostKeyPrompt: HostKeyPromptState?,
    val showConnectionScreen: Boolean,
    val preferredConnectSlotId: Int?,
)

private data class PendingPrivateKeyConnect(
    val host: String,
    val port: Int,
    val username: String,
    val privateKey: String,
)

/** Coordinates the start screen form with saved-connection persistence and the shared live-session repository. */
class HomeViewModel(
    private val appContext: Context,
    private val repository: ConnectionsRepository,
    private val passwordStore: ConnectionPasswordStore,
    private val sshSessionManager: SshSessionManager,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val formState = MutableStateFlow(ConnectionFormState())
    private val selectedConnectionId = MutableStateFlow<Long?>(null)
    private val isSaving = MutableStateFlow(false)
    private val userMessage = MutableStateFlow<String?>(null)
    private val keyPassphrasePrompt = MutableStateFlow<KeyPassphrasePromptState?>(null)
    private val hostKeyPrompt = MutableStateFlow<HostKeyPromptState?>(null)
    private val currentKeyPassphrase = MutableStateFlow<String?>(null)
    private val showConnectionScreen = MutableStateFlow(false)
    private val preferredConnectSlotId = MutableStateFlow<Int?>(null)
    private var pendingPrivateKeyConnect: PendingPrivateKeyConnect? = null
    private var pendingHostKeyDecision: CompletableDeferred<HostKeyDecision>? = null

    private val formAndConnections = combine(formState, repository.observeSavedConnections()) { form, savedConnections ->
        form to savedConnections
    }

    private val promptAndMessages = combine(
        selectedConnectionId,
        isSaving,
        userMessage,
        keyPassphrasePrompt,
        hostKeyPrompt,
    ) { selectedId, saving, message, keyPrompt, hostPrompt ->
        Quintuple(selectedId, saving, message, keyPrompt, hostPrompt)
    }

    private val homeFlags = combine(showConnectionScreen, preferredConnectSlotId) { forceHome, preferredSlotId ->
        forceHome to preferredSlotId
    }

    private val scaffoldState = combine(
        combine(formAndConnections, promptAndMessages) { left, right -> left to right },
        homeFlags,
    ) { leftAndRight, flags ->
        val formAndSavedConnections = leftAndRight.first
        val promptState = leftAndRight.second
        HomeScaffoldState(
            form = formAndSavedConnections.first,
            savedConnections = formAndSavedConnections.second,
            selectedConnectionId = promptState.first,
            isSaving = promptState.second,
            userMessage = promptState.third,
            keyPassphrasePrompt = promptState.fourth,
            hostKeyPrompt = promptState.fifth,
            showConnectionScreen = flags.first,
            preferredConnectSlotId = flags.second,
        )
    }

    /** Exposes the merged form, persistence, and live-session state to Compose. */
    val uiState: StateFlow<HomeUiState> = combine(
        scaffoldState,
        sessionRepository.snapshot,
    ) { homeState, sessionState ->
        homeState.toUiState(sessionState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch {
            sessionRepository.messages.collect { message ->
                userMessage.value = message
            }
        }
    }

    fun updateName(value: String) {
        formState.update { it.copy(name = value) }
    }

    fun updateHost(value: String) {
        formState.update { it.copy(host = value) }
    }

    fun updatePort(value: String) {
        if (value.all(Char::isDigit) || value.isEmpty()) {
            formState.update { it.copy(port = value) }
        }
    }

    fun updateUsername(value: String) {
        formState.update { it.copy(username = value) }
    }

    fun updatePassword(value: String) {
        formState.update { it.copy(password = value) }
    }

    /** Clears any cached passphrase when the private key changes so we do not reuse a stale secret. */
    fun updatePrivateKey(value: String) {
        formState.update { it.copy(privateKey = value) }
        currentKeyPassphrase.value = null
    }

    fun updateUsePrivateKey(value: Boolean) {
        formState.update { it.copy(usePrivateKey = value) }
    }

    fun updateSaveSecret(value: Boolean) {
        formState.update { it.copy(saveSecret = value) }
    }

    /** Updates the in-flight passphrase prompt without losing the connect attempt that triggered it. */
    fun updateKeyPassphrase(value: String) {
        keyPassphrasePrompt.update { prompt -> prompt?.copy(passphrase = value) }
    }

    /** Closes the passphrase prompt and forgets the pending connect attempt. */
    fun dismissKeyPassphrasePrompt() {
        keyPassphrasePrompt.value = null
        pendingPrivateKeyConnect = null
    }

    /** Accepts and stores the current host key before continuing the pending SSH connect. */
    fun trustHostKeyAndConnect() {
        resolveHostKeyDecision(HostKeyDecision.TRUST_AND_CONNECT)
    }

    /** Continues the current SSH connect without changing the stored trust decision. */
    fun connectOnceWithHostKey() {
        resolveHostKeyDecision(HostKeyDecision.CONNECT_ONCE)
    }

    /** Rejects the current host key prompt and aborts the pending SSH connect. */
    fun cancelHostKeyPrompt() {
        resolveHostKeyDecision(HostKeyDecision.CANCEL)
    }

    /** Handles launcher and notification intents so the correct slot or screen opens on re-entry. */
    fun handleLaunchIntent(intent: Intent) {
        val slotToOpen = intent.getIntExtra(AppLaunchActions.EXTRA_OPEN_SLOT_ID, -1)
        if (slotToOpen > 0) {
            sessionRepository.setActiveSlot(slotToOpen)
            showConnectionScreen.value = false
            preferredConnectSlotId.value = null
            return
        }

        val wantsConnectionScreen = intent.getBooleanExtra(AppLaunchActions.EXTRA_OPEN_CONNECTION_SCREEN, false)
        if (wantsConnectionScreen) {
            showConnectionScreen.value = true
            val preferredSlot = intent.getIntExtra(AppLaunchActions.EXTRA_PREFERRED_SLOT_ID, -1)
            preferredConnectSlotId.value = preferredSlot.takeIf { it > 0 }
        }
    }

    /** Lets the terminal screen switch to another live slot without leaving the app. */
    fun selectSessionSlot(slotId: Int) {
        sessionRepository.setActiveSlot(slotId)
        showConnectionScreen.value = false
        preferredConnectSlotId.value = null
    }

    /** Opens the connection form even while other sessions stay alive in the background. */
    fun openConnectionScreen(preferredSlotId: Int? = null) {
        showConnectionScreen.value = true
        this.preferredConnectSlotId.value = preferredSlotId
    }

    /** Returns from the connection form to the currently active terminal, if one exists. */
    fun closeConnectionScreen() {
        showConnectionScreen.value = false
        preferredConnectSlotId.value = null
    }

    /** Loads a saved connection into the form and restores whichever secret type belongs to it. */
    fun selectSavedConnection(connection: SavedConnectionEntity) {
        selectedConnectionId.value = connection.id
        val usesPrivateKey = connection.authType == SavedAuthType.PRIVATE_KEY.name
        formState.value = ConnectionFormState(
            name = connection.name,
            host = connection.host,
            port = connection.port.toString(),
            username = connection.username,
            password = "",
            privateKey = "",
            usePrivateKey = usesPrivateKey,
            saveSecret = false,
        )
        currentKeyPassphrase.value = null

        viewModelScope.launch {
            if (usesPrivateKey) {
                val privateKey = passwordStore.loadPrivateKey(connection.id)
                val passphrase = passwordStore.loadKeyPassphrase(connection.id)
                if (selectedConnectionId.value == connection.id) {
                    currentKeyPassphrase.value = passphrase
                    formState.update {
                        it.copy(
                            privateKey = privateKey.orEmpty(),
                            saveSecret = !privateKey.isNullOrEmpty(),
                        )
                    }
                }
            } else {
                val password = passwordStore.loadPassword(connection.id)
                if (selectedConnectionId.value == connection.id) {
                    formState.update {
                        it.copy(
                            password = password.orEmpty(),
                            saveSecret = !password.isNullOrEmpty(),
                        )
                    }
                }
            }
        }
    }

    /** Removes a saved connection and clears every encrypted secret tied to that row. */
    fun deleteSavedConnection(connection: SavedConnectionEntity) {
        viewModelScope.launch {
            runCatching {
                repository.deleteConnection(connection.id)
                passwordStore.deleteAllSecrets(connection.id)
            }.onSuccess {
                if (selectedConnectionId.value == connection.id) {
                    clearFields()
                }
                userMessage.value = "Saved connection deleted."
            }.onFailure {
                userMessage.value = "Could not delete the saved connection."
            }
        }
    }

    /** Resets the form so a new saved connection can be created from scratch. */
    fun clearFields() {
        selectedConnectionId.value = null
        currentKeyPassphrase.value = null
        keyPassphrasePrompt.value = null
        hostKeyPrompt.value = null
        pendingPrivateKeyConnect = null
        pendingHostKeyDecision?.complete(HostKeyDecision.CANCEL)
        pendingHostKeyDecision = null
        formState.value = ConnectionFormState()
    }

    /** Persists the current connection metadata and updates the encrypted secret entries if requested. */
    fun saveCurrentConnection() {
        val form = formState.value
        val port = form.port.toIntOrNull()

        if (form.host.isBlank() || form.username.isBlank() || port == null) {
            userMessage.value = "Enter host, port, and username before saving."
            return
        }

        viewModelScope.launch {
            isSaving.value = true
            runCatching {
                repository.saveConnection(
                    selectedConnectionId = selectedConnectionId.value,
                    name = form.name.trim(),
                    host = form.host.trim(),
                    port = port,
                    username = form.username.trim(),
                    authType = if (form.usePrivateKey) SavedAuthType.PRIVATE_KEY.name else SavedAuthType.PASSWORD.name,
                )
            }.onSuccess { result ->
                persistSecrets(result.id, form)

                selectedConnectionId.value = result.id
                formState.value = form.copy(
                    name = result.connection.name,
                    host = result.connection.host,
                    port = result.connection.port.toString(),
                    username = result.connection.username,
                    saveSecret = shouldKeepSecretSaved(form),
                )
                userMessage.value = if (result.wasUpdate) {
                    "Saved connection updated."
                } else {
                    "Saved connection created."
                }
            }.onFailure {
                userMessage.value = "Could not save the connection."
            }
            isSaving.value = false
        }
    }

    /** Validates the form and either connects immediately or asks for a private-key passphrase first. */
    fun onConnectRequested() {
        val form = formState.value
        if (!form.isConnectEnabled) {
            userMessage.value = "Enter host, port, and username before connecting."
            return
        }

        val port = form.port.toIntOrNull()
        if (port == null) {
            userMessage.value = "Enter a valid port before connecting."
            return
        }

        if (!form.usePrivateKey) {
            if (form.password.isEmpty()) {
                userMessage.value = "Enter a password before connecting."
                return
            }

            connectWithRequest(
                title = "${form.username.trim()}@${form.host.trim()}:$port",
                request = SshConnectRequest(
                    host = form.host.trim(),
                    port = port,
                    username = form.username.trim(),
                    authMethod = SshAuthMethod.Password(form.password),
                ),
            )
            return
        }

        if (form.privateKey.isBlank()) {
            userMessage.value = "Enter a private key before connecting."
            return
        }

        val pendingRequest = PendingPrivateKeyConnect(
            host = form.host.trim(),
            port = port,
            username = form.username.trim(),
            privateKey = form.privateKey,
        )
        pendingPrivateKeyConnect = pendingRequest

        viewModelScope.launch {
            when (
                val inspection = sshSessionManager.inspectPrivateKey(
                    privateKey = pendingRequest.privateKey,
                    passphrase = currentKeyPassphrase.value,
                )
            ) {
                PrivateKeyInspection.Valid -> {
                    connectPrivateKeyRequest(pendingRequest, currentKeyPassphrase.value)
                }

                PrivateKeyInspection.PassphraseRequired -> {
                    keyPassphrasePrompt.value = KeyPassphrasePromptState(
                        message = "Enter the passphrase for this private key.",
                    )
                }

                PrivateKeyInspection.InvalidPassphrase -> {
                    currentKeyPassphrase.value = null
                    keyPassphrasePrompt.value = KeyPassphrasePromptState(
                        message = "The saved passphrase was rejected. Enter the key passphrase.",
                    )
                }

                is PrivateKeyInspection.InvalidKey -> {
                    pendingPrivateKeyConnect = null
                    userMessage.value = inspection.message
                }
            }
        }
    }

    /** Re-validates the entered passphrase before attempting the SSH connection with the private key. */
    fun submitKeyPassphrase() {
        val prompt = keyPassphrasePrompt.value ?: return
        val pendingRequest = pendingPrivateKeyConnect ?: return

        viewModelScope.launch {
            when (
                val inspection = sshSessionManager.inspectPrivateKey(
                    privateKey = pendingRequest.privateKey,
                    passphrase = prompt.passphrase,
                )
            ) {
                PrivateKeyInspection.Valid -> {
                    currentKeyPassphrase.value = prompt.passphrase
                    keyPassphrasePrompt.value = null
                    connectPrivateKeyRequest(pendingRequest, prompt.passphrase)
                }

                PrivateKeyInspection.PassphraseRequired,
                PrivateKeyInspection.InvalidPassphrase,
                -> {
                    keyPassphrasePrompt.update {
                        it?.copy(message = "The passphrase is required or incorrect. Please try again.")
                    }
                }

                is PrivateKeyInspection.InvalidKey -> {
                    keyPassphrasePrompt.value = null
                    pendingPrivateKeyConnect = null
                    userMessage.value = inspection.message
                }
            }
        }
    }

    /** Forwards terminal keyboard and softkey bytes to the active SSH shell. */
    fun sendTerminalBytes(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        sessionRepository.sendToActiveSlot(bytes, offset, count)
    }

    /** Cancels an in-flight connect or disconnects the active terminal session. */
    fun disconnectSession() {
        sessionRepository.disconnectActiveSlot()
    }

    fun clearUserMessage() {
        userMessage.value = null
    }

    /** Creates the repository-backed ViewModel with the app's local storage dependencies. */
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            val repository = ConnectionsRepository(
                savedConnectionDao = SshTerminalDatabase.getInstance(appContext).savedConnectionDao(),
            )
            val passwordStore = ConnectionPasswordStore(appContext)
            val sshSessionManager = SshSessionManager(passwordStore)
            val sessionRepository = SessionRepository.getInstance(appContext)

            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(appContext, repository, passwordStore, sshSessionManager, sessionRepository) as T
                }
            }
        }
    }

    /** Starts the connection in the shared repository and then promotes the process to a foreground service. */
    private fun connectWithRequest(title: String, request: SshConnectRequest) {
        sessionRepository.connect(
            request = request,
            title = title,
            preferredSlotId = preferredConnectSlotId.value,
            onConnected = { slotId ->
                selectedConnectionId.value?.let { repository.markConnectionSuccessful(it) }
                sessionRepository.setActiveSlot(slotId)
            },
            onHostKeyDecision = ::requestHostKeyDecision,
        )
        showConnectionScreen.value = false
        preferredConnectSlotId.value = null
        SshSessionService.ensureStarted(appContext)
    }

    /** Reuses the normal connect path once the private key and passphrase are ready. */
    private fun connectPrivateKeyRequest(request: PendingPrivateKeyConnect, passphrase: String?) {
        pendingPrivateKeyConnect = null
        connectWithRequest(
            title = "${request.username}@${request.host}:${request.port}",
            request = SshConnectRequest(
                host = request.host,
                port = request.port,
                username = request.username,
                authMethod = SshAuthMethod.PrivateKey(
                    privateKey = request.privateKey,
                    passphrase = passphrase,
                ),
            ),
        )
    }

    /** Keeps secret persistence decisions in one place so password and key mode stay symmetric. */
    private fun persistSecrets(connectionId: Long, form: ConnectionFormState) {
        if (!form.saveSecret) {
            passwordStore.deleteAllSecrets(connectionId)
            return
        }

        if (form.usePrivateKey) {
            passwordStore.deletePassword(connectionId)
            passwordStore.savePrivateKey(connectionId, form.privateKey)
            currentKeyPassphrase.value?.let { passwordStore.saveKeyPassphrase(connectionId, it) }
                ?: passwordStore.deleteKeyPassphrase(connectionId)
        } else {
            passwordStore.savePassword(connectionId, form.password)
            passwordStore.deletePrivateKey(connectionId)
            passwordStore.deleteKeyPassphrase(connectionId)
        }
    }

    /** Mirrors the form label state so Save/Update reflects whether a usable secret is actually present. */
    private fun shouldKeepSecretSaved(form: ConnectionFormState): Boolean {
        return if (form.usePrivateKey) {
            form.saveSecret && form.privateKey.isNotBlank()
        } else {
            form.saveSecret && form.password.isNotBlank()
        }
    }

    /** Suspends the SSH verifier until the terminal UI resolves the current host-key decision. */
    private suspend fun requestHostKeyDecision(request: HostKeyVerificationRequest): HostKeyDecision {
        pendingHostKeyDecision?.complete(HostKeyDecision.CANCEL)

        val deferred = CompletableDeferred<HostKeyDecision>()
        pendingHostKeyDecision = deferred
        hostKeyPrompt.value = HostKeyPromptState(
            mode = request.mode,
            host = request.host,
            port = request.port,
            keyType = request.keyType,
            newFingerprint = request.newFingerprint,
            previousFingerprint = request.previousTrustedKey?.fingerprint,
        )

        return try {
            deferred.await()
        } finally {
            if (pendingHostKeyDecision === deferred) {
                pendingHostKeyDecision = null
                hostKeyPrompt.value = null
            }
        }
    }

    /** Clears the dialog state before resuming the SSH verifier so the prompt closes immediately. */
    private fun resolveHostKeyDecision(decision: HostKeyDecision) {
        val deferred = pendingHostKeyDecision ?: return
        pendingHostKeyDecision = null
        hostKeyPrompt.value = null
        deferred.complete(decision)
    }
}

/** Maps the locally editable form state and the active shared session into one UI model. */
private fun HomeScaffoldState.toUiState(sessionState: SessionRegistryState): HomeUiState {
    val activeSession = sessionState.activeSlot?.session ?: com.codex.sshterminal.session.SessionUiState()
    return HomeUiState(
        form = form,
        savedConnections = savedConnections,
        selectedConnectionId = selectedConnectionId,
        isSaving = isSaving,
        userMessage = userMessage,
        sessionStatus = activeSession.status,
        terminalTitle = activeSession.terminalTitle,
        terminalSession = activeSession.terminalSession,
        activeSlotId = sessionState.activeSlotId,
        sessionSlots = sessionState.slots,
        showConnectionScreen = showConnectionScreen,
        keyPassphrasePrompt = keyPassphrasePrompt,
        hostKeyPrompt = hostKeyPrompt,
    )
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)
