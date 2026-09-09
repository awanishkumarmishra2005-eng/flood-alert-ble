package com.hydrotrap.floodalert

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID =
        ParcelUuid(UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB"))

    private val MAX_TTL = 3
    private val seenIds = mutableSetOf<Int>()

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var advertiser: BluetoothLeAdvertiser
    private lateinit var scanner: BluetoothLeScanner
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var relayCountView: TextView

    private var relayedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(createUI())

        bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        requestBluetoothPermissions()
    }

    private fun createUI(): LinearLayout {

        val root = LinearLayout(this)

        root.orientation = LinearLayout.VERTICAL
        root.setPadding(30, 50, 30, 30)
        root.setBackgroundColor(Color.WHITE)

        val title = TextView(this)
        title.text = "FLOOD ALERT MESH"
        title.textSize = 26f
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.rgb(20, 60, 120))
        title.setPadding(0, 0, 0, 20)

        statusView = TextView(this)
        statusView.text = "Status: Starting..."
        statusView.textSize = 16f
        statusView.gravity = Gravity.CENTER
        statusView.setPadding(0, 0, 0, 20)

        val sosButton = Button(this)
        sosButton.text = "SEND SOS ALERT"
        sosButton.textSize = 18f

        sosButton.setOnClickListener {
            broadcastAlert("SOS - Flood Emergency", MAX_TTL)
        }

        val relayText = TextView(this)
        relayText.text = "BLE relay system"
        relayText.textSize = 15f
        relayText.gravity = Gravity.CENTER
        relayText.setPadding(0, 20, 0, 10)

        relayCountView = TextView(this)
        relayCountView.text = "Alerts relayed: 0"
        relayCountView.gravity = Gravity.CENTER

        logView = TextView(this)
        logView.textSize = 13f
        logView.setPadding(10, 20, 10, 20)

        val scroll = ScrollView(this)
        scroll.addView(logView)

        root.addView(title)
        root.addView(statusView)
        root.addView(sosButton)
        root.addView(relayText)
        root.addView(relayCountView)
        root.addView(scroll)

        return root
    }

    private fun requestBluetoothPermissions() {

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )

        } else {

            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                100
            )

        } else {

            startBluetooth()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }
        ) {

            startBluetooth()

        } else {

            statusView.text =
                "Bluetooth permissions required"
        }
    }

    private fun startBluetooth() {

        val adapter = bluetoothManager.adapter

        if (adapter == null) {

            statusView.text =
                "Bluetooth not supported"

            return
        }

        if (!adapter.isEnabled) {

            statusView.text =
                "Please turn ON Bluetooth"

            return
        }

        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        statusView.text =
            "Status: BLE listening"

        startListening()
    }

    private fun encode(
        ttl: Int,
        id: Int,
        message: String
    ): ByteArray {

        val textBytes =
            message.toByteArray().copyOf(14)

        val data =
            ByteArray(5 + textBytes.size)

        data[0] = ttl.toByte()

        data[1] = (id shr 24).toByte()
        data[2] = (id shr 16).toByte()
        data[3] = (id shr 8).toByte()
        data[4] = id.toByte()

        System.arraycopy(
            textBytes,
            0,
            data,
            5,
            textBytes.size
        )

        return data
    }

    private fun broadcastAlert(
        message: String,
        ttl: Int,
        existingId: Int? = null
    ) {

        if (ttl <= 0) return

        val id =
            existingId
                ?: (message.hashCode() xor System.currentTimeMillis().toInt())

        seenIds.add(id)

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                )
                .setTxPowerLevel(
                    AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                )
                .setConnectable(false)
                .setTimeout(8000)
                .build()

        val data =
            AdvertiseData.Builder()
                .addServiceUuid(SERVICE_UUID)
                .addServiceData(
                    SERVICE_UUID,
                    encode(ttl, id, message)
                )
                .setIncludeDeviceName(false)
                .build()

        try {

            advertiser.startAdvertising(
                settings,
                data,
                advertiseCallback
            )

            log("Broadcasting: $message")

        } catch (e: SecurityException) {

            log("Bluetooth permission missing")
        }
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartFailure(
                errorCode: Int
            ) {

                log("Advertising failed: $errorCode")
            }
        }

    private fun startListening() {

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(SERVICE_UUID)
                .build()

        val settings =
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )
                .build()

        try {

            scanner.startScan(
                listOf(filter),
                settings,
                scanCallback
            )

        } catch (e: SecurityException) {

            log("Scanning permission missing")
        }
    }

    private val scanCallback =
        object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                val bytes =
                    result.scanRecord
                        ?.getServiceData(SERVICE_UUID)
                        ?: return

                if (bytes.size < 5) return

                val ttl =
                    bytes[0].toInt()

                val id =
                    ((bytes[1].toInt() and 255) shl 24) or
                    ((bytes[2].toInt() and 255) shl 16) or
                    ((bytes[3].toInt() and 255) shl 8) or
                    (bytes[4].toInt() and 255)

                if (seenIds.contains(id)) return

                seenIds.add(id)

                val message =
                    String(
                        bytes.copyOfRange(
                            5,
                            bytes.size
                        )
                    ).trim { it.code == 0 }

                relayedCount++

                runOnUiThread {

                    relayCountView.text =
                        "Alerts relayed: $relayedCount"

                }

                log(
                    "Received: $message | TTL=$ttl"
                )

                if (ttl > 1) {

                    broadcastAlert(
                        message,
                        ttl - 1,
                        id
                    )
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) {

                log("Scan failed: $errorCode")
            }
        }

    private fun log(message: String) {

        runOnUiThread {

            logView.append(
                "$message\n"
            )
        }
    }
}
