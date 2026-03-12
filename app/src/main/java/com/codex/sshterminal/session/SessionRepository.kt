package com.codex.sshterminal.session

import android.content.Context
import com.codex.sshterminal.data.security.ConnectionPasswordStore
import com.codex.sshterminal.data.ssh.HostKeyDecision
import com.codex.sshterminal.data.ssh.HostKeyVerificationRequest
import com.codex.sshterminal.data.ssh.SshConnectRequest
import com.codex.sshterminal.data.ssh.SshSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private const val MAX_SESSION_SLOTS = 3

data class SessionSlotUiState(
    val slotId: Int,
    val session: SessionUiState = SessionUiState(),
) {
    val isActive: Boolean
        get() = session.status != SessionStatus.IDLE

    val buttonLabel: String
        get() = if (isActive) slotId.toString() else "+"
}

data class SessionRegistryState(
    val slots: List<SessionSlotUiState>,
    val activeSlotId: Int? = null,
) {
    val activeSlot: SessionSlotUiState?
        get() = slots.firstOrNull { it.slotId == activeSlotId }

    val hasActiveSessions: Boolean
        get() = slots.any { it.isActive }
}

/** Keeps live SSH sessions outside the UI so the terminal survives backgrounding and re-entry. */
class SessionRepository private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeSlotId = MutableStateFlow<Int?>(null)
    private val userMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val passwordStore = ConnectionPasswordStore(context.applicationContext)
    private val controllers = (1..MAX_SESSION_SLOTS).associateWith {
        SshSessionController(
            scope = scope,
            sshSessionManager = SshSessionManager(passwordStore),
            onUserMessage = { message -> userMessages.tryEmit(message) },
        )
    }

    /** Combines the slot controllers into one registry model for the UI and the foreground service. */
    val snapshot: StateFlow<SessionRegistryState> = combine(
        combine(
            controllers.getValue(1).uiState,
            controllers.getValue(2).uiState,
            controllers.getValue(3).uiState,
        ) { slot1, slot2, slot3 ->
            listOf(
                SessionSlotUiState(slotId = 1, session = slot1),
                SessionSlotUiState(slotId = 2, session = slot2),
                SessionSlotUiState(slotId = 3, session = slot3),
            )
        },
        activeSlotId,
    ) { slots, selectedSlotId ->
        val effectiveActiveSlot = selectedSlotId?.takeIf { slotId ->
            slots.firstOrNull { it.slotId == slotId }?.isActive == true
        } ?: slots.firstOrNull { it.isActive }?.slotId

        SessionRegistryState(
            slots = slots,
            activeSlotId = effectiveActiveSlot,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SessionRegistryState(
            slots = (1..MAX_SESSION_SLOTS).map { SessionSlotUiState(slotId = it) },
            activeSlotId = null,
        ),
    )

    /** Exposes controller messages so the current screen can keep using snackbars. */
    val messages = userMessages.asSharedFlow()

    /** Starts a connection in the requested slot when free, otherwise falls back to the first free slot. */
    fun connect(
        request: SshConnectRequest,
        title: String,
        preferredSlotId: Int? = null,
        onConnected: suspend (Int) -> Unit = {},
        onHostKeyDecision: suspend (HostKeyVerificationRequest) -> HostKeyDecision,
    ) {
        val slotId = resolveTargetSlot(preferredSlotId) ?: run {
            userMessages.tryEmit("Maximum of 3 open connections reached.")
            return
        }

        activeSlotId.value = slotId
        controllers.getValue(slotId).connect(
            request = request,
            title = title,
            onConnected = { onConnected(slotId) },
            onHostKeyDecision = onHostKeyDecision,
        )
    }

    /** Sends terminal input to the currently selected slot. */
    fun sendToActiveSlot(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        activeSlotId.value?.let { slotId ->
            controllers.getValue(slotId).sendTerminalBytes(bytes, offset, count)
        } ?: userMessages.tryEmit("No active SSH session.")
    }

    /** Disconnects the currently selected slot and falls back to another active slot if one exists. */
    fun disconnectActiveSlot() {
        val slotId = activeSlotId.value ?: return
        controllers.getValue(slotId).disconnect()
        activeSlotId.value = snapshot.value.slots.firstOrNull { it.slotId != slotId && it.isActive }?.slotId
    }

    /** Lets the UI or notification choose which live slot should be shown in the terminal screen. */
    fun setActiveSlot(slotId: Int) {
        if (snapshot.value.slots.firstOrNull { it.slotId == slotId }?.isActive == true) {
            activeSlotId.value = slotId
        }
    }

    /** Returns the session state for the currently selected slot. */
    fun currentActiveSession(): SessionUiState {
        return snapshot.value.activeSlot?.session ?: SessionUiState()
    }

    /** Picks a preferred empty slot first so notification + buttons can target a specific free slot. */
    private fun resolveTargetSlot(preferredSlotId: Int?): Int? {
        val slots = snapshot.value.slots
        preferredSlotId?.let { slotId ->
            if (slots.firstOrNull { it.slotId == slotId }?.isActive == false) {
                return slotId
            }
        }
        return slots.firstOrNull { !it.isActive }?.slotId
    }

    companion object {
        @Volatile
        private var instance: SessionRepository? = null

        fun getInstance(context: Context): SessionRepository {
            return instance ?: synchronized(this) {
                instance ?: SessionRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
