package com.example.sipcall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), PjsipManager.Listener {

    private lateinit var pjsip: PjsipManager
    private lateinit var audioManager: AudioManager

    private lateinit var serverIpInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var destNumberInput: EditText
    private lateinit var registerBtn: Button
    private lateinit var callBtn: Button
    private lateinit var hangupBtn: Button
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphone: Boolean = false
    private var savedMicMute: Boolean = false
    private var voiceModeActive: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        serverIpInput = findViewById(R.id.server_ip)
        usernameInput = findViewById(R.id.username)
        passwordInput = findViewById(R.id.password)
        destNumberInput = findViewById(R.id.dest_number)
        registerBtn = findViewById(R.id.btn_register)
        callBtn = findViewById(R.id.btn_call)
        hangupBtn = findViewById(R.id.btn_hangup)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)

        serverIpInput.setText("103.209.42.30")
        usernameInput.setText("09638917817")
        passwordInput.setText("1234")
        destNumberInput.setText("01716517528")

        ensurePermissions()

        pjsip = PjsipManager(this)
        pjsip.start()

        registerBtn.setOnClickListener {
            pjsip.register(
                username = usernameInput.text.toString().trim(),
                password = passwordInput.text.toString(),
                serverIp = serverIpInput.text.toString().trim()
            )
        }
        callBtn.setOnClickListener {
            // CRITICAL: enter voice mode BEFORE PJSIP opens the audio device.
            // PJSIP opens the device synchronously inside makeCall(), and on
            // Android the device's routing is decided AT OPEN TIME based on
            // the current AudioManager.mode. Setting it after the call starts
            // is too late — the mic ends up on the wrong path and captures silence.
            enterVoiceCallMode()
            pjsip.call(destNumberInput.text.toString().trim())
        }
        hangupBtn.setOnClickListener {
            pjsip.hangup()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (voiceModeActive) restoreAudioMode()
        pjsip.shutdown()
    }

    private fun ensurePermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed, 1001)
        }
    }

    private fun enterVoiceCallMode() {
        if (voiceModeActive) return
        savedAudioMode = audioManager.mode
        savedSpeakerphone = audioManager.isSpeakerphoneOn
        @Suppress("DEPRECATION")
        savedMicMute = audioManager.isMicrophoneMute

        // VoIP mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Force speakerphone ON so audio comes out the loud speaker
        audioManager.isSpeakerphoneOn = true

        // Make sure mic isn't muted
        @Suppress("DEPRECATION")
        audioManager.isMicrophoneMute = false

        // Crank the call volume to max so we can definitely hear it
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)

        // Also bump the music stream as a fallback in case routing is wrong
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)

        voiceModeActive = true
        appendLog("Audio: IN_COMMUNICATION, speaker=ON, vol=$maxVol/$maxVol")
    }

    private fun restoreAudioMode() {
        try {
            audioManager.mode = savedAudioMode
            audioManager.isSpeakerphoneOn = savedSpeakerphone
            @Suppress("DEPRECATION")
            audioManager.isMicrophoneMute = savedMicMute
            voiceModeActive = false
            appendLog("Audio mode restored")
        } catch (_: Exception) {}
    }

    override fun onRegState(code: Int, reason: String, registered: Boolean) {
        runOnUiThread {
            val tag = if (registered) "REGISTERED" else "UNREGISTERED"
            statusView.text = "SIP: $tag ($code $reason)"
            appendLog("RegState: $code $reason  registered=$registered")
        }
    }

    override fun onCallState(state: String, lastStatusCode: Int, lastReason: String) {
        runOnUiThread {
            statusView.text = "Call: $state ($lastStatusCode $lastReason)"
            appendLog("CallState: $state  $lastStatusCode $lastReason")

            if (state == "DISCONNECTED" && voiceModeActive) {
                restoreAudioMode()
            }
        }
    }

    override fun onLog(line: String) {
        runOnUiThread { appendLog(line) }
    }

    private fun appendLog(line: String) {
        val cur = logView.text.toString()
        val next = (line + "\n" + cur).take(8000)
        logView.text = next
    }
}