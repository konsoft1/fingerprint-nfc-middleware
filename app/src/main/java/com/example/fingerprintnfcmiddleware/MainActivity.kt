package com.example.fingerprintnfcmiddleware

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nextbiometrics.biometrics.NBBiometricsContext
import com.nextbiometrics.devices.NBDevice
import com.nextbiometrics.devices.NBDeviceScanFormatInfo
import com.nextbiometrics.devices.NBDeviceScanResult
import com.nextbiometrics.devices.NBDeviceSecurityModel
import com.nextbiometrics.devices.NBDeviceType
import com.nextbiometrics.devices.NBDevices
import com.nextbiometrics.system.NextBiometricsException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var device : NBDevice? = null
    private var terminate: Boolean = false
    private var isSpi: Boolean = false
    private var dialogResult: Boolean = false
    private var scanFormatInfo: NBDeviceScanFormatInfo? = null
    private var bgscanResult: NBDeviceScanResult? = null

    private val DEFAULT_SPI_NAME: String = "/dev/spidev0.0"
    //Default SYSFS path to access GPIO pins
    private val DEFAULT_SYSFS_PATH: String = "/sys/class/gpio"
    private val DEFAULT_SPICLK = 8000000
    private val DEFAULT_AWAKE_PIN_NUMBER = 0
    private val DEFAULT_RESET_PIN_NUMBER = 0
    private val DEFAULT_CHIP_SELECT_PIN_NUMBER = 0
    private val ENABLE_BACKGROUND_SUBTRACTION: Int = 1

    var FLAGS: Int = 0

    private val nfcTagReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tagId = intent?.getStringExtra("tagId")
            tagId?.let {
                // Display the NFC tag information in the UI
                addTagToList(it)
            }
        }
    }

    private val tagList = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val isServiceRunning = remember { mutableStateOf(isServiceRunning(MainForegroundService::class.java)) }

            MainScreen(
                onStartService = {
                    startNFCService()
                    isServiceRunning.value = true
                },
                onStopService = {
                    stopNFCService()
                    isServiceRunning.value = false
                },
                isServiceRunning = isServiceRunning.value,
                tagList = tagList
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Register receiver to listen for NFC tag broadcasts
        val filter = IntentFilter("com.example.fingerprintnfcmiddleware.NFC_TAG_DISCOVERED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nfcTagReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(nfcTagReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister receiver to stop receiving broadcasts when the activity is not visible
        unregisterReceiver(nfcTagReceiver)
    }

    private fun startNFCService() {

        // Fingerprinter
        try {
            if ((device == null) || (device != null && !device!!.isSessionOpen())) {
                //During Reset/unplug-device Session Closed. Need to re-Initilize Device
                deviceInit()
                //if (deviceInit()) showMessage("Device initialized")
            }
            if (device != null) {
                val context: NBBiometricsContext = NBBiometricsContext(device)
                val msg = "Device went to low power mode."
                try {
                    context.cancelOperation()
                    device!!.lowPowerMode()
                } catch (ex: NextBiometricsException) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
        } catch (ex: Throwable) {
            showMessage("ERROR: " + ex.message, true)
            ex.printStackTrace()
        }

        // NFC
        val serviceIntent = Intent(this, MainForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        }
    }


    private fun deviceInit(): Boolean {
        try {
            NBDevices.initialize(applicationContext)
            terminate = true
            // wait for callback here
            // or for the sake of simplicity, sleep
            showMessage("Waiting for a USB device")
            for (i in 0..49) {
                Thread.sleep(500)
                if (NBDevices.getDevices().size != 0) break
            }
            val devices = NBDevices.getDevices()
            if (devices.size == 0) {
                Log.i("MainActivity", "No USB device connected")
                showMessage("No USB device found, trying an SPI device")
                try {
                    isSpi = false
                    device = NBDevice.connectToSpi(
                        DEFAULT_SPI_NAME,
                        DEFAULT_SYSFS_PATH,
                        DEFAULT_SPICLK,
                        DEFAULT_AWAKE_PIN_NUMBER,
                        DEFAULT_RESET_PIN_NUMBER,
                        DEFAULT_CHIP_SELECT_PIN_NUMBER,
                        FLAGS
                    )
                    isSpi = true
                } catch (e: Exception) {
                    // showMessage("Problem when opening SPI device: " + e.getMessage());
                }
                if (device == null) {
                    Log.i("MainActivity", "No SPI devices connected")
                    showMessage("No device connected", true)
                    return false
                }
                if ((devices.size == 0) && !isSpi) {
                    showMessage("No device connected.", true)
                    NBDevices.terminate()
                    device!!.dispose()
                    device = null
                    return false
                }
            } else {
                device = devices[0]
                isSpi = false
            }

            openSession()
            // If the device requires external calibration data (NB-65210-S), load or create them
            if (device != null && device!!.capabilities.requiresExternalCalibrationData) {
                solveCalibrationData(device!!)
            }

            val scanFormats = device?.getSupportedScanFormats()
            if (scanFormats != null) {
                if (scanFormats.size == 0) throw Exception("No supported formats found!")
            }
            scanFormatInfo = scanFormats?.get(0)

            if ((device != null) && isAllowOneTimeBG(device!!)) {
                device!!.setParameter(
                    NBDevice.NB_DEVICE_PARAMETER_SUBTRACT_BACKGROUND.toLong(),
                    ENABLE_BACKGROUND_SUBTRACTION
                )
                bgscanResult = device!!.scanBGImage(scanFormatInfo)
            }
            return true
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
            return false
        } catch (ex: Throwable) {
            showMessage("ERROR: $ex", true)
            ex.printStackTrace()
            return false
        }
    }
    @Throws(java.lang.Exception::class)
    private fun solveCalibrationData(device: NBDevice) {
        val paths = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .toString() + "/NBData/" + device.serialNumber + "_calblob.bin"
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .toString() + "/NBData/"
        ).mkdirs()
        val file = File(paths)
        if (!file.exists()) {
            // Ask the user whether he wants to calibrate the device
            this@MainActivity.runOnUiThread {
                val dialogClickListener =
                    DialogInterface.OnClickListener { dialog, which ->
                        synchronized(this@MainActivity) {
                            dialogResult = (which == DialogInterface.BUTTON_POSITIVE)
                            (this@MainActivity as Object).notifyAll()
                        }
                    }
                AlertDialog.Builder(this@MainActivity).setMessage(
                    """
                    This device is not calibrated yet. Do you want to calibrate it?
                    
                    If yes, at first perfectly clean the sensor, and only then select the YES button.
                    """.trimIndent()
                ).setPositiveButton("Yes", dialogClickListener)
                    .setNegativeButton("No", dialogClickListener).show()
            }

            synchronized(this@MainActivity) {
                (this@MainActivity as Object).wait()
                if (!dialogResult) throw java.lang.Exception("The device is not calibrated")
            }
            showMessage("Creating calibration data: $paths")
            showMessage("This operation may take several minutes.")
            try {
                val data = device.GenerateCalibrationData()
                val fos = FileOutputStream(paths)
                fos.write(data)
                fos.close()
                showMessage("Calibration data created")
            } catch (e: java.lang.Exception) {
                e.message?.let { showMessage(it, true) }
            }
        }
        if (file.exists()) {
            val size = file.length().toInt()
            val bytes = ByteArray(size)
            try {
                val buf = BufferedInputStream(FileInputStream(file))
                buf.read(bytes, 0, bytes.size)
                buf.close()
            } catch (ex: IOException) {
            }
            device.SetBlobParameter(NBDevice.BLOB_PARAMETER_CALIBRATION_DATA, bytes)
        } else {
            throw java.lang.Exception("Missing compensation data - $paths")
        }
    }

    private fun isAllowOneTimeBG(dev: NBDevice): Boolean {
        val devType = dev.type
        //For the below device types alone one-time BG capture supported. NBEnhance supported modules.
        return if (devType == NBDeviceType.NB2020U || devType == NBDeviceType.NB2023U || devType == NBDeviceType.NB2033U || devType == NBDeviceType.NB65200U || devType == NBDeviceType.NB65210S) {
            true
        } else {
            false
        }
    }

    private fun showMessage(message: String) {
        showMessage(message, false)
    }

    private fun showMessage(message: String, isErrorMessage: Boolean) {
        Toast.makeText(this, "FINGER>> ${if (isErrorMessage) "ERR>>" else "INF>>"} $message", Toast.LENGTH_LONG).show()
    }

    private fun openSession() {
        if (device != null && !device!!.isSessionOpen) {
            val cakId = "DefaultCAKKey1\u0000".toByteArray()
            val cak = byteArrayOf(
                0x05.toByte(),
                0x4B.toByte(),
                0x38.toByte(),
                0x3A.toByte(),
                0xCF.toByte(),
                0x5B.toByte(),
                0xB8.toByte(),
                0x01.toByte(),
                0xDC.toByte(),
                0xBB.toByte(),
                0x85.toByte(),
                0xB4.toByte(),
                0x47.toByte(),
                0xFF.toByte(),
                0xF0.toByte(),
                0x79.toByte(),
                0x77.toByte(),
                0x90.toByte(),
                0x90.toByte(),
                0x81.toByte(),
                0x51.toByte(),
                0x42.toByte(),
                0xC1.toByte(),
                0xBF.toByte(),
                0xF6.toByte(),
                0xD1.toByte(),
                0x66.toByte(),
                0x65.toByte(),
                0x0A.toByte(),
                0x66.toByte(),
                0x34.toByte(),
                0x11.toByte()
            )
            val cdkId = "Application Lock\u0000".toByteArray()
            val cdk = byteArrayOf(
                0x6B.toByte(),
                0xC5.toByte(),
                0x51.toByte(),
                0xD1.toByte(),
                0x12.toByte(),
                0xF7.toByte(),
                0xE3.toByte(),
                0x42.toByte(),
                0xBD.toByte(),
                0xDC.toByte(),
                0xFB.toByte(),
                0x5D.toByte(),
                0x79.toByte(),
                0x4E.toByte(),
                0x5A.toByte(),
                0xD6.toByte(),
                0x54.toByte(),
                0xD1.toByte(),
                0xC9.toByte(),
                0x90.toByte(),
                0x28.toByte(),
                0x05.toByte(),
                0xCF.toByte(),
                0x5E.toByte(),
                0x4C.toByte(),
                0x83.toByte(),
                0x63.toByte(),
                0xFB.toByte(),
                0xC2.toByte(),
                0x3C.toByte(),
                0xF6.toByte(),
                0xAB.toByte()
            )
            val defaultAuthKey1Id = "AUTH1\u0000".toByteArray()
            val defaultAuthKey1 = byteArrayOf(
                0xDA.toByte(),
                0x2E.toByte(),
                0x35.toByte(),
                0xB6.toByte(),
                0xCB.toByte(),
                0x96.toByte(),
                0x2B.toByte(),
                0x5F.toByte(),
                0x9F.toByte(),
                0x34.toByte(),
                0x1F.toByte(),
                0xD1.toByte(),
                0x47.toByte(),
                0x41.toByte(),
                0xA0.toByte(),
                0x4D.toByte(),
                0xA4.toByte(),
                0x09.toByte(),
                0xCE.toByte(),
                0xE8.toByte(),
                0x35.toByte(),
                0x48.toByte(),
                0x3C.toByte(),
                0x60.toByte(),
                0xFB.toByte(),
                0x13.toByte(),
                0x91.toByte(),
                0xE0.toByte(),
                0x9E.toByte(),
                0x95.toByte(),
                0xB2.toByte(),
                0x7F.toByte()
            )
            val security = NBDeviceSecurityModel.get(device!!.capabilities.securityModel.toInt())
            if (security == NBDeviceSecurityModel.Model65200CakOnly) {
                device!!.openSession(cakId, cak)
            } else if (security == NBDeviceSecurityModel.Model65200CakCdk) {
                try {
                    device!!.openSession(cdkId, cdk)
                    device!!.SetBlobParameter(NBDevice.BLOB_PARAMETER_SET_CDK, null)
                    device!!.closeSession()
                } catch (ex: RuntimeException) {
                }
                device!!.openSession(cakId, cak)
                device!!.SetBlobParameter(NBDevice.BLOB_PARAMETER_SET_CDK, cdk)
                device!!.closeSession()
                device!!.openSession(cdkId, cdk)
            } else if (security == NBDeviceSecurityModel.Model65100) {
                device!!.openSession(defaultAuthKey1Id, defaultAuthKey1)
            }
        }
    }

    private fun stopNFCService() {
        val serviceIntent = Intent(this, MainForegroundService::class.java)
        stopService(serviceIntent)
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun addTagToList(tagId: String) {
        tagList.add(tagId)
        Log.d("MainActivity", "Received NFC Tag: $tagId")
    }
}

@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    isServiceRunning: Boolean,
    tagList: List<String>
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Button(
            onClick = onStartService,
            modifier = Modifier.padding(bottom = 8.dp),
            enabled = !isServiceRunning // Enabled only if the service is not running
        ) {
            Text(text = "Start NFC Service")
        }
        Button(
            onClick = onStopService,
            modifier = Modifier.padding(top = 8.dp),
            enabled = isServiceRunning // Enabled only if the service is running
        ) {
            Text(text = "Stop NFC Service")
        }
        Text(text = "Discovered NFC Tags:")
        Spacer(modifier = Modifier.height(8.dp))
        NFCList(tagList = tagList)
    }
}

@Composable
fun NFCList(tagList: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxHeight()) {
        items(tagList) { tag ->
            Text(text = "Tag ID: $tag", modifier = Modifier.padding(4.dp))
        }
    }
}

@Preview(name = "Service Not Running", showBackground = true)
@Composable
fun MainScreenPreviewServiceNotRunning() {
    MainScreen(
        onStartService = {},
        onStopService = {},
        isServiceRunning = false,
        tagList = listOf("Tag1", "Tag2", "Tag3")
    )
}

@Preview(name = "Service Running", showBackground = true)
@Composable
fun MainScreenPreviewServiceRunning() {
    MainScreen(
        onStartService = {},
        onStopService = {},
        isServiceRunning = true,
        tagList = listOf("Tag1", "Tag2", "Tag3")
    )
}