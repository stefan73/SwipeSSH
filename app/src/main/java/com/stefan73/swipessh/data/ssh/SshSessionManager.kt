package com.stefan73.swipessh.data.ssh

import com.stefan73.swipessh.data.security.ConnectionPasswordStore
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.util.Base64

/** Performs SSH transport setup and applies hostname resolution policy before connecting. */
class SshSessionManager(
    private val passwordStore: ConnectionPasswordStore? = null,
) {
    /** Resolves the host, verifies the host key, and tries IPv4 addresses before IPv6 when needed. */
    suspend fun connect(
        request: SshConnectRequest,
        onAttempt: (suspend (ConnectionAttempt) -> Unit)? = null,
        onHostKeyDecision: (suspend (HostKeyVerificationRequest) -> HostKeyDecision)? = null,
    ): ActiveSshSession = withContext(Dispatchers.IO) {
        val candidates = resolveCandidates(request.host)
        var lastError: Throwable? = null

        for (candidate in candidates) {
            onAttempt?.let { callback ->
                // The callback writes into the terminal session, so it needs to run on the UI side reliably.
                withContext(Dispatchers.Main) {
                    callback(
                        ConnectionAttempt(
                            address = candidate.displayAddress,
                            resolvedFromHostname = candidate.resolvedFromHostname,
                        ),
                    )
                }
            }

            try {
                return@withContext connectToCandidate(request, candidate, onHostKeyDecision)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw lastError ?: IllegalStateException("Could not resolve host ${request.host}")
    }

    /** Checks whether a pasted private key needs a passphrase before we start the network connection. */
    suspend fun inspectPrivateKey(privateKey: String, passphrase: String?): PrivateKeyInspection = withContext(Dispatchers.IO) {
        val sshClient = SSHClient()
        try {
            loadInlineKeyProvider(sshClient, privateKey, passphrase)
            PrivateKeyInspection.Valid
        } catch (_: KeyDecryptionFailedException) {
            if (passphrase.isNullOrEmpty()) {
                PrivateKeyInspection.PassphraseRequired
            } else {
                PrivateKeyInspection.InvalidPassphrase
            }
        } catch (error: Throwable) {
            PrivateKeyInspection.InvalidKey(error.message ?: "Unsupported private key format.")
        } finally {
            runCatching { sshClient.close() }
        }
    }

    /** Opens the SSH transport, authenticates, and starts an interactive shell channel. */
    private fun connectToCandidate(
        request: SshConnectRequest,
        candidate: ResolvedHostCandidate,
        onHostKeyDecision: (suspend (HostKeyVerificationRequest) -> HostKeyDecision)?,
    ): ActiveSshSession {
        val sshClient = SSHClient().apply {
            addHostKeyVerifier(buildHostKeyVerifier(request, onHostKeyDecision))
            connectTimeout = 10_000
            timeout = 10_000
        }

        try {
            sshClient.connect(candidate.connectHost, request.port)
            authenticate(sshClient, request)

            val session = sshClient.startSession()
            session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
            val shell = session.startShell()

            return ActiveSshSession(
                sshClient = sshClient,
                session = session,
                shell = shell,
                connectedAddress = candidate.displayAddress,
                resolvedFromHostname = candidate.resolvedFromHostname,
            )
        } catch (error: Throwable) {
            runCatching { sshClient.disconnect() }
            runCatching { sshClient.close() }
            throw error
        }
    }

    /** Keeps password and private-key authentication in one place so the connect flow stays predictable. */
    private fun authenticate(sshClient: SSHClient, request: SshConnectRequest) {
        when (val authMethod = request.authMethod) {
            is SshAuthMethod.Password -> sshClient.authPassword(request.username, authMethod.password)
            is SshAuthMethod.PrivateKey -> {
                val keyProvider = loadInlineKeyProvider(sshClient, authMethod.privateKey, authMethod.passphrase)
                sshClient.authPublickey(request.username, keyProvider)
            }
        }
    }

    /** Loads an inline private key exactly the same way for preflight inspection and real authentication. */
    private fun loadInlineKeyProvider(sshClient: SSHClient, privateKey: String, passphrase: String?): KeyProvider {
        val passwordFinder = if (passphrase.isNullOrEmpty()) {
            null
        } else {
            object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>): CharArray = passphrase.toCharArray()

                override fun shouldRetry(resource: Resource<*>): Boolean = false
            }
        }

        return sshClient.loadKeys(privateKey, null, passwordFinder)
    }

    /** Resolves hostnames into an ordered list of candidates while leaving literal IPs untouched. */
    private fun resolveCandidates(host: String): List<ResolvedHostCandidate> {
        if (isLiteralIp(host)) {
            return listOf(
                ResolvedHostCandidate(
                    connectHost = host,
                    displayAddress = host,
                    resolvedFromHostname = false,
                ),
            )
        }

        val resolved = InetAddress.getAllByName(host).toList()
        val ipv4 = resolved.filterIsInstance<Inet4Address>()
        val ipv6 = resolved.filterIsInstance<Inet6Address>()

        // Prefer IPv4 first since some VPN/network combinations advertise AAAA records without usable IPv6 routing.
        val ordered = ipv4 + ipv6 + resolved.filterNot { it is Inet4Address || it is Inet6Address }

        return ordered.mapNotNull { address ->
            val hostAddress = address.hostAddress ?: return@mapNotNull null
            ResolvedHostCandidate(
                connectHost = hostAddress,
                displayAddress = hostAddress,
                resolvedFromHostname = true,
            )
        }.distinctBy { it.connectHost }
    }

    /** Detects whether the user already entered a literal IP address. */
    private fun isLiteralIp(host: String): Boolean {
        return IPV4_REGEX.matches(host) || (host.contains(':') && IPV6_REGEX.matches(host))
    }

    /** Wraps the synchronous SSHJ verifier contract around our UI-driven trust decision flow. */
    private fun buildHostKeyVerifier(
        request: SshConnectRequest,
        onHostKeyDecision: (suspend (HostKeyVerificationRequest) -> HostKeyDecision)?,
    ): HostKeyVerifier {
        return object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val currentRecord = TrustedHostKeyRecord(
                    keyType = KeyType.fromKey(key).toString(),
                    fingerprint = sha256Fingerprint(key),
                )
                val storedRecord = passwordStore?.loadTrustedHostKey(request.host, request.port)

                if (storedRecord == null) {
                    return resolveDecision(
                        request = HostKeyVerificationRequest(
                            mode = HostKeyPromptMode.UNKNOWN,
                            host = request.host,
                            port = request.port,
                            keyType = currentRecord.keyType,
                            newFingerprint = currentRecord.fingerprint,
                            previousTrustedKey = null,
                        ),
                        currentRecord = currentRecord,
                        onHostKeyDecision = onHostKeyDecision,
                    )
                }

                if (storedRecord == currentRecord) {
                    return true
                }

                return resolveDecision(
                    request = HostKeyVerificationRequest(
                        mode = HostKeyPromptMode.CHANGED,
                        host = request.host,
                        port = request.port,
                        keyType = currentRecord.keyType,
                        newFingerprint = currentRecord.fingerprint,
                        previousTrustedKey = storedRecord,
                    ),
                    currentRecord = currentRecord,
                    onHostKeyDecision = onHostKeyDecision,
                )
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
                return passwordStore?.loadTrustedHostKey(request.host, request.port)
                    ?.let { listOf(it.keyType) }
                    ?: emptyList()
            }
        }
    }

    /** Stores or skips the host key according to the user's decision and rejects the connection on cancel. */
    private fun resolveDecision(
        request: HostKeyVerificationRequest,
        currentRecord: TrustedHostKeyRecord,
        onHostKeyDecision: (suspend (HostKeyVerificationRequest) -> HostKeyDecision)?,
    ): Boolean {
        val decision = runBlocking {
            onHostKeyDecision?.invoke(request) ?: HostKeyDecision.CANCEL
        }

        return when (decision) {
            HostKeyDecision.TRUST_AND_CONNECT -> {
                passwordStore?.saveTrustedHostKey(request.host, request.port, currentRecord)
                true
            }

            HostKeyDecision.CONNECT_ONCE -> true
            HostKeyDecision.CANCEL -> throw HostKeyVerificationException("Host key not trusted.")
        }
    }

    /** Uses SHA-256 because it is the most common SSH fingerprint format shown by modern clients. */
    private fun sha256Fingerprint(key: PublicKey): String {
        val digest = try {
            SecurityUtils.getMessageDigest("SHA-256")
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Could not compute host key fingerprint.", error)
        }
        digest.update(Buffer.PlainBuffer().putPublicKey(key).getCompactData())
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest.digest())
        return "SHA256:$encoded"
    }

    private data class ResolvedHostCandidate(
        val connectHost: String,
        val displayAddress: String,
        val resolvedFromHostname: Boolean,
    )

    companion object {
        private val IPV4_REGEX = Regex(
            pattern = "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$",
        )
        private val IPV6_REGEX = Regex("^[0-9A-Fa-f:]+$")
    }
}

sealed interface PrivateKeyInspection {
    data object Valid : PrivateKeyInspection
    data object PassphraseRequired : PrivateKeyInspection
    data object InvalidPassphrase : PrivateKeyInspection
    data class InvalidKey(val message: String) : PrivateKeyInspection
}

enum class HostKeyPromptMode {
    UNKNOWN,
    CHANGED,
}

enum class HostKeyDecision {
    TRUST_AND_CONNECT,
    CONNECT_ONCE,
    CANCEL,
}

data class TrustedHostKeyRecord(
    val keyType: String,
    val fingerprint: String,
)

data class HostKeyVerificationRequest(
    val mode: HostKeyPromptMode,
    val host: String,
    val port: Int,
    val keyType: String,
    val newFingerprint: String,
    val previousTrustedKey: TrustedHostKeyRecord?,
)

class HostKeyVerificationException(message: String) : RuntimeException(message)

data class ConnectionAttempt(
    val address: String,
    val resolvedFromHostname: Boolean,
)

data class SshConnectRequest(
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: SshAuthMethod,
)

sealed interface SshAuthMethod {
    data class Password(val password: String) : SshAuthMethod
    data class PrivateKey(val privateKey: String, val passphrase: String?) : SshAuthMethod
}

/** Wraps the active SSH channel so callers do not need to know SSHJ internals. */
class ActiveSshSession(
    private val sshClient: SSHClient,
    private val session: Session,
    private val shell: Session.Shell,
    val connectedAddress: String,
    val resolvedFromHostname: Boolean,
) {
    /** Continuously reads shell output until the remote channel closes. */
    suspend fun readLoop(onChunk: (ByteArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        val inputStream: InputStream = shell.inputStream
        val buffer = ByteArray(4096)

        while (true) {
            currentCoroutineContext().ensureActive()
            val bytesRead = inputStream.read(buffer)
            if (bytesRead <= 0) {
                break
            }
            onChunk(buffer, bytesRead)
        }
    }

    /** Sends terminal input to the remote shell. */
    suspend fun send(data: ByteArray, offset: Int = 0, count: Int = data.size) = withContext(Dispatchers.IO) {
        val outputStream: OutputStream = shell.outputStream
        outputStream.write(data, offset, count)
        outputStream.flush()
    }

    /** Propagates terminal geometry changes to the remote PTY. */
    suspend fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = withContext(Dispatchers.IO) {
        runCatching {
            val resizeMethod = session.javaClass.getMethod(
                "changeWindowDimensions",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            resizeMethod.isAccessible = true
            resizeMethod.invoke(session, columns, rows, widthPixels, heightPixels)
        }
    }

    /** Closes the shell, session, and SSH client in best-effort order. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { shell.close() }
        runCatching { session.close() }
        runCatching { sshClient.disconnect() }
        runCatching { sshClient.close() }
    }
}

