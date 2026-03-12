package com.codex.sshterminal.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codex.sshterminal.session.SessionSlotUiState
import com.codex.sshterminal.session.SessionStatus
import com.codex.sshterminal.ui.home.HomeViewModel
import com.codex.sshterminal.ui.home.HostKeyPromptState
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClientAdapter
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClientAdapter
import kotlin.math.max
import kotlin.math.roundToInt

private const val ESC = "\u001B"
private const val TERMINAL_VIEW_BACKGROUND = 0xFF000000.toInt()
private val SOFT_KEY_HEIGHT = 24.dp
private val SOFT_KEY_GAP = 4.dp
private val SoftKeyAccent = ComposeColor(0xFF8080FF)
private const val HEADER_SWIPE_THRESHOLD_PX = 72f

@Composable
fun TerminalRoute(
    viewModel: HomeViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TerminalScreen(
        title = uiState.terminalTitle,
        status = uiState.sessionStatus,
        terminalSession = uiState.terminalSession,
        activeSlotId = uiState.activeSlotId,
        slots = uiState.sessionSlots,
        hostKeyPrompt = uiState.hostKeyPrompt,
        onDisconnect = viewModel::disconnectSession,
        onSelectSlot = viewModel::selectSessionSlot,
        onOpenConnectionScreen = viewModel::openConnectionScreen,
        onTrustHostKey = viewModel::trustHostKeyAndConnect,
        onConnectOnce = viewModel::connectOnceWithHostKey,
        onCancelHostKeyPrompt = viewModel::cancelHostKeyPrompt,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(
    title: String,
    status: SessionStatus,
    terminalSession: TerminalSession?,
    activeSlotId: Int?,
    slots: List<SessionSlotUiState>,
    hostKeyPrompt: HostKeyPromptState?,
    onDisconnect: () -> Unit,
    onSelectSlot: (Int) -> Unit,
    onOpenConnectionScreen: (Int?) -> Unit,
    onTrustHostKey: () -> Unit,
    onConnectOnce: () -> Unit,
    onCancelHostKeyPrompt: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val keyboardInset = max(imeBottom - navigationBottom, 0)
    val keyboardVisible = keyboardInset > 0
    val keyboardInsetDp = with(density) { keyboardInset.toDp() }
    val terminalBottomPadding = if (keyboardVisible) keyboardInsetDp + SOFT_KEY_HEIGHT else 0.dp
    val latestStatus by rememberUpdatedState(status)
    val latestKeyboardVisible by rememberUpdatedState(keyboardVisible)
    var ctrlEnabled by remember { mutableStateOf(false) }
    var altEnabled by remember { mutableStateOf(false) }
    var altGrEnabled by remember { mutableStateOf(false) }
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    val activeSessionSlots = remember(slots) { slots.filter { it.isActive } }
    val showSwipeHints = activeSessionSlots.size > 1

    fun clearModifiers() {
        ctrlEnabled = false
        altEnabled = false
        altGrEnabled = false
    }

    // Keep the real terminal view focused so hardware enter/keyboard events do not land on toolbar buttons.
    fun requestTerminalFocus() {
        terminalViewRef?.let { view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
            view.requestFocusFromTouch()
        }
    }

    fun refreshTerminalView(view: TerminalView?) {
        view?.invalidate()
    }

    // Drive the platform keyboard explicitly because the terminal view is not a normal text field.
    fun setKeyboardVisible(visible: Boolean) {
        terminalViewRef?.let { view ->
            requestTerminalFocus()
            if (visible) {
                view.post {
                    requestTerminalFocus()
                    inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                    keyboardController?.show()
                }
            } else {
                keyboardController?.hide()
                inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }

    fun toggleKeyboard() {
        if (latestKeyboardVisible) {
            setKeyboardVisible(false)
        } else {
            setKeyboardVisible(true)
        }
    }

    // Cycles only through active slots so swipe and chevron actions skip empty session slots.
    fun switchToAdjacentSession(direction: Int) {
        if (activeSessionSlots.size <= 1) return
        val currentIndex = activeSessionSlots.indexOfFirst { it.slotId == activeSlotId }.takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + direction).floorMod(activeSessionSlots.size)
        onSelectSlot(activeSessionSlots[nextIndex].slotId)
    }

    val terminalBridge = remember(terminalSession) {
        TerminalViewBridge(
            context = context,
            readControl = { ctrlEnabled },
            readAlt = { altEnabled || altGrEnabled },
            clearModifiers = ::clearModifiers,
            requestKeyboardToggle = {
                if (latestStatus == SessionStatus.CONNECTED) {
                    toggleKeyboard()
                }
            },
            refreshTerminalView = {
                refreshTerminalView(terminalViewRef)
            },
        )
    }

    DisposableEffect(terminalBridge) {
        onDispose {
            terminalBridge.detachView()
        }
    }

    LaunchedEffect(status, terminalSession) {
        if (status == SessionStatus.CONNECTED && terminalSession != null) {
            requestTerminalFocus()
            terminalViewRef?.post {
                terminalViewRef?.updateSize()
                refreshTerminalView(terminalViewRef)
                requestTerminalFocus()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    var dragDistance by remember { mutableFloatStateOf(0f) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.pointerInput(activeSlotId, activeSessionSlots) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, dragAmount ->
                                    dragDistance += dragAmount
                                },
                                onDragEnd = {
                                    when {
                                        dragDistance >= HEADER_SWIPE_THRESHOLD_PX -> switchToAdjacentSession(direction = -1)
                                        dragDistance <= -HEADER_SWIPE_THRESHOLD_PX -> switchToAdjacentSession(direction = 1)
                                    }
                                    dragDistance = 0f
                                },
                                onDragCancel = {
                                    dragDistance = 0f
                                },
                            )
                        },
                    ) {
                        if (showSwipeHints) {
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .clickable { switchToAdjacentSession(direction = -1) },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ChevronLeft,
                                    contentDescription = "Previous session",
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .pointerInput(activeSlotId, activeSessionSlots) {
                                    detectHorizontalDragGestures(
                                        onHorizontalDrag = { _, dragAmount ->
                                            dragDistance += dragAmount
                                        },
                                        onDragEnd = {
                                            when {
                                                dragDistance >= HEADER_SWIPE_THRESHOLD_PX -> switchToAdjacentSession(direction = -1)
                                                dragDistance <= -HEADER_SWIPE_THRESHOLD_PX -> switchToAdjacentSession(direction = 1)
                                            }
                                            dragDistance = 0f
                                        },
                                        onDragCancel = {
                                            dragDistance = 0f
                                        },
                                    )
                                },
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = if (title.isBlank()) "Terminal" else title,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when (status) {
                                    SessionStatus.CONNECTING -> "Connecting"
                                    SessionStatus.CONNECTED -> "Connected"
                                    SessionStatus.DISCONNECTING -> "Disconnecting"
                                    SessionStatus.ERROR -> "Connection error"
                                    SessionStatus.IDLE -> "Disconnected"
                                },
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                    }
                },
                actions = {
                    if (showSwipeHints) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .clickable { switchToAdjacentSession(direction = 1) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Next session",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    if (slots.any { !it.isActive }) {
                        OutlinedButton(
                            onClick = {
                                setKeyboardVisible(false)
                                val preferredSlot = slots.firstOrNull { !it.isActive }?.slotId
                                onOpenConnectionScreen(preferredSlot)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 1.dp),
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .heightIn(min = 32.dp),
                        ) {
                            Text(
                                text = "+",
                                fontSize = 14.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = status != SessionStatus.DISCONNECTING && status != SessionStatus.IDLE,
                        colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .heightIn(min = 32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PowerSettingsNew,
                            contentDescription = if (status == SessionStatus.CONNECTING) "Cancel connection" else "Disconnect session",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = terminalBottomPadding),
                color = ComposeColor.Black,
            ) {
                if (terminalSession == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ComposeColor.Black)
                            .padding(8.dp),
                    ) {
                        Text(
                            text = "Terminal session is preparing.",
                            fontSize = 12.sp,
                            color = ComposeColor(0xFFB8D4FF),
                        )
                    }
                } else {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ComposeColor.Black),
                        factory = { viewContext ->
                            TerminalView(viewContext, null).apply {
                                isFocusable = true
                                isFocusableInTouchMode = true
                                keepScreenOn = true
                                setBackgroundColor(TERMINAL_VIEW_BACKGROUND)
                                setTerminalViewClient(terminalBridge.viewClient)
                                terminalBridge.attachView(this)
                                setTextSize(with(density) { 10.dp.toPx().roundToInt() })
                                setTypeface(Typeface.MONOSPACE)
                                setTerminalCursorBlinkerRate(600)
                                terminalSession.updateTerminalSessionClient(terminalBridge.sessionClient)
                                attachSession(terminalSession)
                                setTerminalCursorBlinkerState(true, true)
                                addOnLayoutChangeListener { _: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ->
                                    if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                                        post {
                                            updateSize()
                                            refreshTerminalView(this)
                                        }
                                    }
                                }
                                terminalViewRef = this
                                post {
                                    updateSize()
                                    refreshTerminalView(this)
                                    requestTerminalFocus()
                                }
                            }
                        },
                        update = { view ->
                            terminalViewRef = view
                            terminalBridge.attachView(view)
                            view.setBackgroundColor(TERMINAL_VIEW_BACKGROUND)
                            view.setTerminalViewClient(terminalBridge.viewClient)
                            terminalSession.updateTerminalSessionClient(terminalBridge.sessionClient)
                            view.attachSession(terminalSession)
                            view.setTerminalCursorBlinkerState(status == SessionStatus.CONNECTED, true)
                            view.post {
                                view.updateSize()
                                refreshTerminalView(view)
                                if (status == SessionStatus.CONNECTED) {
                                    requestTerminalFocus()
                                }
                            }
                        },
                    )
                }
            }

            if (keyboardVisible) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(y = -keyboardInsetDp)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(SOFT_KEY_GAP),
                ) {
                    SoftKeyButton(label = "<", enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        terminalSession?.write("${ESC}[D")
                        clearModifiers()
                        requestTerminalFocus()
                    }
                    SoftKeyButton(label = ">", enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        terminalSession?.write("${ESC}[C")
                        clearModifiers()
                        requestTerminalFocus()
                    }
                    SoftKeyButton(label = "^", enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        terminalSession?.write("${ESC}[A")
                        clearModifiers()
                        requestTerminalFocus()
                    }
                    SoftKeyButton(label = "v", enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        terminalSession?.write("${ESC}[B")
                        clearModifiers()
                        requestTerminalFocus()
                    }
                    SoftKeyButton(label = "Esc", enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        terminalSession?.write(ESC)
                        clearModifiers()
                        requestTerminalFocus()
                    }
                    SoftKeyButton(label = "Tab", enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        terminalSession?.write("\t")
                        clearModifiers()
                        requestTerminalFocus()
                    }
                    SoftToggleButton(label = "Ctl", active = ctrlEnabled, enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        ctrlEnabled = !ctrlEnabled
                        requestTerminalFocus()
                    }
                    SoftToggleButton(label = "Alt", active = altEnabled, enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        altEnabled = !altEnabled
                        if (altEnabled) altGrEnabled = false
                        requestTerminalFocus()
                    }
                    SoftToggleButton(label = "AGr", active = altGrEnabled, enabled = terminalSession != null, modifier = Modifier.weight(1f)) {
                        altGrEnabled = !altGrEnabled
                        if (altGrEnabled) altEnabled = false
                        requestTerminalFocus()
                    }
                }
            }

            hostKeyPrompt?.let { prompt ->
                HostKeyPromptDialog(
                    prompt = prompt,
                    onTrustHostKey = onTrustHostKey,
                    onConnectOnce = onConnectOnce,
                    onCancel = onCancelHostKeyPrompt,
                )
            }
        }
    }
}

@Composable
private fun SoftKeyButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ComposeColor.Black,
            disabledContainerColor = ComposeColor.Black,
            contentColor = SoftKeyAccent,
            disabledContentColor = SoftKeyAccent.copy(alpha = 0.45f),
        ),
        border = BorderStroke(1.dp, SoftKeyAccent),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
        modifier = modifier.height(SOFT_KEY_HEIGHT),
    ) {
        Text(label, fontSize = 9.sp, lineHeight = 9.sp)
    }
}

@Composable
private fun SoftToggleButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) SoftKeyAccent else ComposeColor.Black,
            disabledContainerColor = ComposeColor.Black,
            contentColor = if (active) ComposeColor.White else SoftKeyAccent,
            disabledContentColor = SoftKeyAccent.copy(alpha = 0.45f),
        ),
        border = BorderStroke(
            1.dp,
            if (active) SoftKeyAccent else SoftKeyAccent,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
        modifier = modifier.height(SOFT_KEY_HEIGHT),
    ) {
        Text(label, fontSize = 9.sp, lineHeight = 9.sp)
    }
}

/** Shows the host-key trust prompt on top of the terminal while the SSH verifier is waiting. */
@Composable
private fun HostKeyPromptDialog(
    prompt: HostKeyPromptState,
    onTrustHostKey: () -> Unit,
    onConnectOnce: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = if (prompt.mode == com.codex.sshterminal.data.ssh.HostKeyPromptMode.UNKNOWN) {
        "Unknown host key"
    } else {
        "WARNING: Host key changed"
    }
    val messageLines = buildList {
        if (prompt.mode == com.codex.sshterminal.data.ssh.HostKeyPromptMode.UNKNOWN) {
            add("Server is not yet trusted.")
        }
        add("Host: ${prompt.host}")
        add("Port: ${prompt.port}")
        add("Key type: ${prompt.keyType}")
        if (prompt.mode == com.codex.sshterminal.data.ssh.HostKeyPromptMode.CHANGED) {
            add("Fingerprint old: ${prompt.previousFingerprint.orEmpty()}")
            add("Fingerprint new: ${prompt.newFingerprint}")
        } else {
            add("Fingerprint: ${prompt.newFingerprint}")
        }
    }.joinToString(separator = "\n")

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(messageLines) },
        confirmButton = {
            if (prompt.mode == com.codex.sshterminal.data.ssh.HostKeyPromptMode.UNKNOWN) {
                Button(onClick = onTrustHostKey) {
                    Text("Trust & Connect")
                }
            } else {
                Button(
                    onClick = onTrustHostKey,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Trust & Connect")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt.mode == com.codex.sshterminal.data.ssh.HostKeyPromptMode.CHANGED) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    TextButton(onClick = onConnectOnce) {
                        Text("Connect once")
                    }
                } else {
                    TextButton(onClick = onConnectOnce) {
                        Text("Connect once")
                    }
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }
        },
    )
}
private class TerminalViewBridge(
    context: Context,
    private val readControl: () -> Boolean,
    private val readAlt: () -> Boolean,
    private val clearModifiers: () -> Unit,
    private val requestKeyboardToggle: () -> Unit,
    private val refreshTerminalView: () -> Unit,
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var terminalView: TerminalView? = null

    val sessionClient = object : TerminalSessionClientAdapter() {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.post {
                if (terminalView?.mTermSession === changedSession) {
                    terminalView?.onScreenUpdated()
                }
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            terminalView?.post {
                terminalView?.onScreenUpdated(true)
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            terminalView?.post {
                terminalView?.onScreenUpdated(true)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val text = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(terminalView?.context)?.toString()
            if (!text.isNullOrEmpty()) {
                session?.write(text)
            }
        }

        override fun onColorsChanged(session: TerminalSession) {
            terminalView?.post {
                refreshTerminalView()
                terminalView?.invalidate()
            }
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView?.post {
                terminalView?.setTerminalCursorBlinkerState(state, true)
            }
        }
    }

    val viewClient = object : TerminalViewClientAdapter() {
        override fun onSingleTapUp(e: MotionEvent) {
            requestKeyboardToggle()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false

        override fun isTerminalViewSelected(): Boolean = true

        override fun readControlKey(): Boolean = readControl()

        override fun readAltKey(): Boolean = readAlt()

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            clearModifiers()
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
            clearModifiers()
            return false
        }

        override fun onEmulatorSet() {
            terminalView?.setTerminalCursorBlinkerState(true, true)
            terminalView?.post {
                terminalView?.updateSize()
                refreshTerminalView()
            }
        }
    }

    fun attachView(view: TerminalView) {
        terminalView = view
    }

    fun detachView() {
        terminalView = null
    }
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus




























