package com.example.sipcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), PjsipManager.Listener {

    private lateinit var pjsip: PjsipManager

    private lateinit var serverIpInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var destNumberInput: EditText
    private lateinit var registerBtn: Button
    private lateinit var callBtn: Button
    private lateinit var hangupBtn: Button
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        usernameInput.setText("09638917518")
        passwordInput.setText("1234")
        destNumberInput.setText("01673779266")

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
            pjsip.call(destNumberInput.text.toString().trim())
        }
        hangupBtn.setOnClickListener {
            pjsip.hangup()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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

    // ---- PjsipManager.Listener ----

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
