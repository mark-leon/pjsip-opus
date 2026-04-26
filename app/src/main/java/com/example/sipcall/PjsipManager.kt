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
        internal const val INV_STATE_CONFIRMED = 5
        internal const val INV_STATE_DISCONNECTED = 6

        // PJSUA call media status ints.
        internal const val MEDIA_STATUS_ACTIVE = 1
        internal const val MEDIA_STATUS_LOCAL_HOLD = 2
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
            this.logWriter = writer
            this.epConfig = cfg

            cfg.uaConfig.userAgent = "SipCallAndroid/1.0 PJSUA2"

            // ---- STUN: critical for NAT traversal ----
            // Without this, PJSIP advertises the phone's private LAN IP
            // (e.g. 10.38.149.168) in SDP, and the FreeSWITCH server cannot
            // route RTP back to a private address — result: zero audio.
            // Google's public STUN servers are fine for testing.
            cfg.uaConfig.stunServer.add("stun.l.google.com:19302")
            cfg.uaConfig.stunServer.add("stun1.l.google.com:19302")

            // Media config — important for Android audio.
            cfg.medConfig.clockRate = 16000
            cfg.medConfig.sndClockRate = 16000
            cfg.medConfig.channelCount = 1
            cfg.medConfig.audioFramePtime = 20
            // Disable PJSIP's software echo canceller — Android's
            // MODE_IN_COMMUNICATION already provides hardware AEC.
            cfg.medConfig.ecTailLen = 0

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

            setupAudioDevice(ep)

            endpoint = ep
        } catch (e: Throwable) {
            Log.e(TAG, "start() failed", e)
            listener.onLog("PJSIP start failed: ${e.message}")
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

            // ---- NAT traversal config ----
            // Use STUN to discover public IP/port for SDP and RTP.
            acfg.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
            acfg.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT

            // ICE off — FreeSWITCH typically doesn't need it and ICE adds
            // setup time. STUN-discovered public address in SDP is enough.
            acfg.natConfig.iceEnabled = false

            // Force re-INVITE / UPDATE if the discovered public address
            // changes mid-call (e.g. NAT rebinding).
            acfg.natConfig.contactRewriteUse = 1
            acfg.natConfig.viaRewriteUse = 1
            acfg.natConfig.sdpNatRewriteUse = 1

            // Send small UDP keepalive every 15s so the NAT pinhole on the
            // SIP transport stays open between REGISTER and INVITE.
            acfg.natConfig.udpKaIntervalSec = 15

            // Route through registrar so requests follow the same path.
            acfg.sipConfig.proxies.add("sip:$serverIp:$serverPort;lr")

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



    private fun setupAudioDevice(ep: Endpoint) {
        try {
            val audDevMgr = ep.audDevManager()
            val devCount = audDevMgr.devCount
            listener.onLog("Audio device count: $devCount")
            Log.d(TAG, "Audio device count: $devCount")

            if (devCount <= 0) {
                listener.onLog(
                    "WARNING: no audio devices found. Your libpjsua2.so was " +
                            "built without OpenSL/Oboe support — rebuild PJSIP with " +
                            "PJMEDIA_AUDIO_DEV_HAS_OPENSL=1 or PJMEDIA_AUDIO_DEV_HAS_ANDROID_OBOE=1."
                )
                return
            }

            // Log every device so we know what backend is compiled in.
            var nonNullDev: Long = -1
            for (i in 0 until devCount) {
                val info = audDevMgr.getDevInfo(i.toInt())
                val name = info.name ?: "?"
                val driver = info.driver ?: "?"
                listener.onLog("  AudDev[$i] $name drv=$driver in=${info.inputCount} out=${info.outputCount}")
                Log.d(TAG, "  AudDev[$i] $name drv=$driver in=${info.inputCount} out=${info.outputCount}")

                // Prefer first device that isn't the Null Audio Device.
                if (nonNullDev < 0 &&
                    !name.contains("Null", ignoreCase = true) &&
                    info.inputCount > 0 && info.outputCount > 0
                ) {
                    nonNullDev = i
                }
            }

            val dev = if (nonNullDev >= 0) nonNullDev else 0
            audDevMgr.setCaptureDev(dev.toInt())
            audDevMgr.setPlaybackDev(dev.toInt())
            listener.onLog("Audio device set to index $dev")
        } catch (e: Exception) {
            Log.e(TAG, "Audio dev setup failed", e)
            listener.onLog("Audio dev setup failed: ${e.message}")
        }
    }

    private fun safeSetCodecPriority(ep: Endpoint, codec: String, prio: Int) {
        try {
            ep.codecSetPriority(codec, prio.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Codec $codec not available: ${e.message}")
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
                val isActive = (mi.status == PjsipManager.MEDIA_STATUS_ACTIVE)

                if (isAudio && isActive) {
                    val aud = getAudioMedia(i)
                    val mgr = Endpoint.instance().audDevManager()
                    mgr.captureDevMedia.startTransmit(aud)
                    aud.startTransmit(mgr.playbackDevMedia)
                    listener.onLog("Audio bridged")
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