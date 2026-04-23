package com.example.sipcall

import android.util.Log
import org.pjsip.pjsua2.*

/**
 * PjsipManager wraps the PJSUA2 endpoint, account, and call.
 *
 * IMPORTANT — DIRECTOR OBJECT LIFETIME:
 * PJSUA2 uses SWIG "director" classes for LogWriter, Account, and Call.
 * The C++ side keeps only a weak JNI reference to the Kotlin subclass
 * instance. If the Kotlin side stops holding a strong reference, GC can
 * collect it, and the next callback from a PJSIP thread dereferences a
 * freed pointer → SIGSEGV inside pj::Endpoint::utilLogWrite / similar.
 *
 * We therefore hold STRONG references to every director object for as
 * long as PJSIP can call back into it:
 *   - logWriter  : field, lives until shutdown()
 *   - account    : field
 *   - currentCall: field
 *   - epConfig   : field (holds the log writer reference on the C++ side)
 */
class PjsipManager(private val listener: Listener) {

    interface Listener {
        fun onRegState(code: Int, reason: String, registered: Boolean)
        fun onCallState(state: String, lastStatusCode: Int, lastReason: String)
        fun onLog(line: String)
    }

    companion object {
        private const val TAG = "PjsipManager"

        // PJSIP invite state ints — stable across PJSUA2 versions.
        internal const val INV_STATE_DISCONNECTED = 6

        // PJSUA call media status ints.
        internal const val MEDIA_STATUS_ACTIVE = 1
        internal const val MEDIA_STATUS_REMOTE_HOLD = 3

        // PJMEDIA type audio.
        internal const val MEDIA_TYPE_AUDIO = 1

        init {
            try {
                System.loadLibrary("pjsua2")
                Log.d(TAG, "libpjsua2 loaded")
            } catch (t: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load pjsua2: ${t.message}")
                throw t
            }
        }
    }

    private var endpoint: Endpoint? = null
    private var account: SipAccount? = null
    private var currentCall: SipCall? = null

    // STRONG references — do NOT let these go out of scope, GC will eat
    // the Kotlin instance and the native side will dereference garbage.
    private var logWriter: PjsipLogWriter? = null
    private var epConfig: EpConfig? = null

    fun start() {
        if (endpoint != null) return
        try {
            val ep = Endpoint()
            ep.libCreate()

            val cfg = EpConfig()
            cfg.logConfig.level = 4
            cfg.logConfig.consoleLevel = 4

            val writer = PjsipLogWriter(listener)
            cfg.logConfig.writer = writer
            // Hold strong refs so neither gets collected by Kotlin GC.
            this.logWriter = writer
            this.epConfig = cfg

            cfg.uaConfig.userAgent = "SipCallAndroid/1.0 PJSUA2"

            ep.libInit(cfg)

            val tConfig = TransportConfig()
            tConfig.port = 0
            ep.transportCreate(
                pjsip_transport_type_e.PJSIP_TRANSPORT_UDP,
                tConfig
            )

            ep.libStart()
            Log.d(TAG, "PJSIP endpoint started")

            safeSetCodecPriority(ep, "PCMU/8000", 255)
            safeSetCodecPriority(ep, "PCMA/8000", 254)
            safeSetCodecPriority(ep, "G722/8000", 240)
            safeSetCodecPriority(ep, "opus/48000", 230)

            endpoint = ep
        } catch (e: Throwable) {
            Log.e(TAG, "start() failed", e)
            listener.onLog("PJSIP start failed: ${e.message}")
        }
    }

    private fun safeSetCodecPriority(ep: Endpoint, codec: String, prio: Int) {
        try {
            ep.codecSetPriority(codec, prio.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Codec $codec not available: ${e.message}")
        }
    }

    fun register(
        username: String,
        password: String,
        serverIp: String,
        serverPort: Int = 5060
    ) {
        val ep = endpoint ?: run {
            listener.onLog("Endpoint not ready — did you call start()?")
            return
        }
        if (username.isBlank() || serverIp.isBlank()) {
            listener.onLog("Username / server IP cannot be blank")
            return
        }

        // Tear down any existing account first.
        try { account?.delete() } catch (_: Exception) {}
        account = null

        try {
            val acfg = AccountConfig()
            acfg.idUri = "sip:$username@$serverIp"
            acfg.regConfig.registrarUri = "sip:$serverIp:$serverPort"
            acfg.regConfig.registerOnAdd = true
            acfg.regConfig.timeoutSec = 300

            val cred = AuthCredInfo("digest", "*", username, 0, password)
            acfg.sipConfig.authCreds.add(cred)

            // If audio is one-way on a real call, uncomment both:
            // acfg.natConfig.iceEnabled = true
            // acfg.sipConfig.proxies.add("sip:$serverIp:$serverPort;lr")

            val acc = SipAccount(listener, this, ep)
            listener.onLog("Creating account sip:$username@$serverIp ...")
            acc.create(acfg)
            account = acc

            listener.onLog("Register request sent as $username → sip:$serverIp:$serverPort")
        } catch (e: Throwable) {
            Log.e(TAG, "register() failed", e)
            listener.onLog("Register failed: ${e.message}")
        }
    }

    fun call(destNumber: String) {
        val acc = account ?: run {
            listener.onLog("Not registered yet")
            return
        }
        if (destNumber.isBlank()) {
            listener.onLog("Enter a number to dial")
            return
        }

        currentCall?.let {
            try { it.hangup(CallOpParam(true)) } catch (_: Exception) {}
            try { it.delete() } catch (_: Exception) {}
        }
        currentCall = null

        try {
            val dest = "sip:$destNumber@${extractHostFromAccount(acc)}"
            val call = SipCall(acc, listener, this, -1)
            val prm = CallOpParam(true)
            prm.opt.audioCount = 1
            prm.opt.videoCount = 0

            call.makeCall(dest, prm)
            currentCall = call
            listener.onLog("Dialing $dest")
        } catch (e: Throwable) {
            Log.e(TAG, "call() failed", e)
            listener.onLog("Call failed: ${e.message}")
        }
    }

    private fun extractHostFromAccount(acc: SipAccount): String {
        return try {
            val uri = acc.info.uri
            uri.substringAfter('@').substringBefore(';').substringBefore(':')
                .ifEmpty { "localhost" }
        } catch (_: Exception) {
            "localhost"
        }
    }

    fun hangup() {
        currentCall?.let {
            try { it.hangup(CallOpParam(true)) } catch (e: Exception) {
                Log.w(TAG, "hangup: ${e.message}")
            }
        }
    }

    internal fun clearCall(call: SipCall) {
        if (currentCall === call) currentCall = null
    }

    fun shutdown() {
        try {
            currentCall?.let {
                try { it.hangup(CallOpParam(true)) } catch (_: Exception) {}
                try { it.delete() } catch (_: Exception) {}
            }
            currentCall = null

            try { account?.delete() } catch (_: Exception) {}
            account = null

            endpoint?.let {
                try { it.libDestroy() } catch (_: Exception) {}
                try { it.delete() } catch (_: Exception) {}
            }
            endpoint = null

            // Release director refs last, after endpoint is gone so no
            // log callbacks can still arrive.
            logWriter = null
            epConfig = null
        } catch (e: Throwable) {
            Log.w(TAG, "shutdown: ${e.message}")
        }
    }
}

/**
 * Safe thread-registration helper.
 *
 * PJSIP callbacks (onRegState, onCallState, onCallMediaState, LogWriter.write)
 * usually run on PJSIP's OWN worker thread, which PJSIP already has a
 * pj_thread_t descriptor for. Calling Endpoint.libRegisterThread() again on
 * such a thread replaces the descriptor, then the next pj_grp_lock_release
 * fails its ownership assertion → SIGABRT in pj/lock.c line 273.
 *
 * We must only register threads PJSIP hasn't seen. Use libIsThreadRegistered()
 * to gate the call. This matches the guidance in the PJSUA2 docs:
 *   "Only register threads which are not created by PJLIB or PJSIP itself."
 */
internal object PjsipThreads {
    fun ensureRegistered(ep: Endpoint?) {
        val e = ep ?: return
        try {
            if (!e.libIsThreadRegistered()) {
                e.libRegisterThread(Thread.currentThread().name)
            }
        } catch (_: Throwable) {
            // If the registration check or call fails, there's nothing
            // safer we can do — swallow rather than crash the callback.
        }
    }
}

/**
 * Top-level LogWriter subclass. Kept as a named class (not anonymous
 * inside start()) so its lifetime isn't tied to a local scope —
 * PjsipManager holds a field reference to the instance.
 */
internal class PjsipLogWriter(
    private val listener: PjsipManager.Listener
) : LogWriter() {
    override fun write(entry: LogEntry) {
        val line = try { entry.msg.trimEnd() } catch (_: Exception) { "" }
        Log.d("pjsip", line)
        try { listener.onLog(line) } catch (_: Throwable) {}
    }
}

internal class SipAccount(
    private val listener: PjsipManager.Listener,
    private val manager: PjsipManager,
    private val ep: Endpoint
) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        PjsipThreads.ensureRegistered(ep)
        val active = try { info.regIsActive } catch (_: Exception) { false }
        val code = try { prm.code } catch (_: Exception) { 0 }
        val reason = try { prm.reason ?: "" } catch (_: Exception) { "" }
        listener.onRegState(code, reason, active)
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        PjsipThreads.ensureRegistered(ep)
        listener.onLog("Incoming call ignored (callId=${prm.callId})")
    }
}

internal class SipCall(
    acc: SipAccount,
    private val listener: PjsipManager.Listener,
    private val manager: PjsipManager,
    callId: Int
) : Call(acc, callId) {

    override fun onCallState(prm: OnCallStateParam) {
        PjsipThreads.ensureRegistered(Endpoint.instance())
        try {
            val ci = info
            listener.onCallState(
                stateToString(ci.state),
                ci.lastStatusCode,
                ci.lastReason ?: ""
            )
            if (ci.state == PjsipManager.INV_STATE_DISCONNECTED) {
                manager.clearCall(this)
                try { delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            listener.onLog("onCallState err: ${e.message}")
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        PjsipThreads.ensureRegistered(Endpoint.instance())
        try {
            val ci = info
            for (i in 0 until ci.media.size.toInt()) {
                val mi = ci.media[i]
                val isAudio = (mi.type == PjsipManager.MEDIA_TYPE_AUDIO)
                val isLive = (mi.status == PjsipManager.MEDIA_STATUS_ACTIVE ||
                        mi.status == PjsipManager.MEDIA_STATUS_REMOTE_HOLD)
                if (isAudio && isLive) {
                    val aud = getAudioMedia(i)
                    val mgr = Endpoint.instance().audDevManager()
                    mgr.captureDevMedia.startTransmit(aud)
                    aud.startTransmit(mgr.playbackDevMedia)
                    listener.onLog("Audio media active (codec negotiated)")
                }
            }
        } catch (e: Exception) {
            listener.onLog("onCallMediaState err: ${e.message}")
        }
    }

    private fun stateToString(s: Int): String = when (s) {
        0 -> "NULL"
        1 -> "CALLING"
        2 -> "INCOMING"
        3 -> "EARLY"
        4 -> "CONNECTING"
        5 -> "CONFIRMED"
        6 -> "DISCONNECTED"
        else -> "STATE_$s"
    }
}