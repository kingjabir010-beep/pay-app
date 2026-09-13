package com.piprapay.liteforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.piprapay.liteforwarder.databinding.ActivityMainBinding
import com.piprapay.liteforwarder.db.AppDatabase
import com.piprapay.liteforwarder.db.QueuedSms
import com.piprapay.liteforwarder.net.ApiClient
import com.piprapay.liteforwarder.work.SendQueueWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.swipeRefresh.setOnRefreshListener {
            refreshAll()
            binding.swipeRefresh.isRefreshing = false
        }

        binding.inputBaseUrl.setText(Prefs.getBaseUrl(this))

        binding.btnGrantPermissions.setOnClickListener { askPermissions() }
        binding.btnBatteryOptimization.setOnClickListener { requestBatteryOptimizationExemption() }

        binding.btnConnect.setOnClickListener { connectDevice() }
        binding.btnDisconnect.setOnClickListener { disconnectDevice() }

        binding.btnAddNumber.setOnClickListener {
            val n = binding.inputAllowedNumber.text.toString().trim()
            if (n.isNotEmpty()) {
                Prefs.addAllowedNumber(this, n)
                binding.inputAllowedNumber.setText("")
                refreshAllowedNumbers()
            }
        }
        binding.btnClearNumbers.setOnClickListener {
            Prefs.setAllowedNumbers(this, emptyList())
            refreshAllowedNumbers()
        }

        binding.btnSyncNow.setOnClickListener {
            SendQueueWorker.enqueueImmediate(this)
            Toast.makeText(this, "Syncing now…", Toast.LENGTH_SHORT).show()
        }

        ForwarderService.ensureRunning(this)
        SendQueueWorker.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        refreshPermissionStatus()
        refreshPairingStatus()
        refreshAllowedNumbers()
        refreshQueue()
    }

    // ---- Permissions ----------------------------------------------------

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions.launch(perms.toTypedArray())
    }

    private fun refreshPermissionStatus() {
        val smsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val readOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        binding.txtPermStatus.text = if (smsOk && readOk) {
            "SMS permission: granted ✓"
        } else {
            "SMS permission: NOT granted — the app cannot see any SMS until you grant this."
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } else {
            Toast.makeText(this, "Already exempted from battery optimization ✓", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Pairing ----------------------------------------------------------

    private fun refreshPairingStatus() {
        if (Prefs.isPaired(this)) {
            binding.txtStatus.text = "Status: Paired as \"${Prefs.getDeviceName(this) ?: "device"}\" ✓"
            binding.txtStatus.setTextColor(getColor(R.color.status_ok))
        } else {
            binding.txtStatus.text = "Status: Not paired — enter your site URL + OTP below"
            binding.txtStatus.setTextColor(getColor(R.color.status_bad))
        }
    }

    private fun connectDevice() {
        val url = binding.inputBaseUrl.text.toString().trim().trimEnd('/')
        val otp = binding.inputOtp.text.toString().trim().uppercase()
        if (url.isEmpty() || otp.isEmpty()) {
            Toast.makeText(this, "Enter both the site URL and the OTP", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setBaseUrl(this, url)

        binding.btnConnect.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.deviceLogin(url, otp, Build.MODEL ?: "Android device")
            }
            binding.btnConnect.isEnabled = true

            if (result.ok && result.body?.optBoolean("ok") == true) {
                val token = result.body.optString("token")
                val name = result.body.optString("name", "device")
                Prefs.setDeviceToken(this@MainActivity, token)
                Prefs.setDeviceName(this@MainActivity, name)
                Toast.makeText(this@MainActivity, "Connected! Device is now paired.", Toast.LENGTH_LONG).show()
                refreshPairingStatus()
                SendQueueWorker.enqueueImmediate(this@MainActivity)
            } else {
                val err = result.body?.optString("error") ?: result.error ?: "Unknown error (HTTP ${result.code})"
                Toast.makeText(this@MainActivity, "Failed to connect: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disconnectDevice() {
        Prefs.setDeviceToken(this, null)
        Prefs.setDeviceName(this, null)
        refreshPairingStatus()
        Toast.makeText(this, "Device forgotten. Pair again with a fresh OTP.", Toast.LENGTH_SHORT).show()
    }

    // ---- Allowed numbers ----------------------------------------------------

    private fun refreshAllowedNumbers() {
        val numbers = Prefs.getAllowedNumbers(this)
        binding.txtAllowedNumbers.text = if (numbers.isEmpty()) {
            "(none — smart auto-detect mode: any bKash/Nagad-looking SMS from any sender)"
        } else {
            "Allowed: " + numbers.joinToString(", ")
        }
    }

    // ---- Queue / log ----------------------------------------------------

    private fun refreshQueue() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).queuedSmsDao()
            val recent = withContext(Dispatchers.IO) { dao.getRecent(50) }
            val pendingCount = withContext(Dispatchers.IO) { dao.countUnsent() }
            val totalCount = withContext(Dispatchers.IO) { dao.countAll() }

            binding.txtQueueSummary.text = "$pendingCount pending, $totalCount total"

            if (recent.isEmpty()) {
                binding.txtLog.text = "No messages captured yet."
                return@launch
            }

            val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)
            val sb = StringBuilder()
            for (item in recent) {
                val time = sdf.format(java.util.Date(item.receivedAtMillis))
                val statusIcon = when (item.status) {
                    QueuedSms.STATUS_SENT -> "✓ sent"
                    QueuedSms.STATUS_FAILED -> "… retrying (offline/queued)"
                    else -> "… queued"
                }
                sb.append("[$time] ${item.sender}  —  $statusIcon\n")
                if (item.provider != null) {
                    sb.append("   ${item.provider?.uppercase()}  Tk${item.amount ?: "?"}  TrxID:${item.trxId ?: "-"}\n")
                }
                sb.append("\n")
            }
            binding.txtLog.text = sb.toString().trim()
        }
    }
}
