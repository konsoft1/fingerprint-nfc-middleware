package com.example.fingerprintnfcmiddleware

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nextbiometrics.biometrics.NBBiometricsContext
import com.nextbiometrics.devices.NBDevice
import com.nextbiometrics.devices.NBDeviceScanFormatInfo
import com.nextbiometrics.devices.NBDeviceScanResult
import com.nextbiometrics.devices.NBDeviceSecurityModel
import com.nextbiometrics.devices.NBDeviceType
import com.nextbiometrics.devices.NBDevices
import com.nextbiometrics.system.NextBiometricsException
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val isNfcServiceRunning = mutableStateOf(false)
    private val isFpServiceRunning = mutableStateOf(false)
    private val tagList = mutableStateListOf<String>()
    private val templateList = mutableStateListOf<String>()

    //Storage Permission request code
    private val READ_WRITE_PERMISSION_REQUEST_CODE: Int = 1
    private val PERMISSION_CALLBACK_CODE: Int = 2
    /**
     * USB permission
     */
    private val ACTION_USB_PERMISSION: String = "com.example.yourapp.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            isNfcServiceRunning.value = isServiceRunning(NFCForegroundService::class.java)
            isFpServiceRunning.value = isServiceRunning(FPForegroundService::class.java)

            MainScreen(
                onStartNfcService = {
                    startNfcService()
                    isNfcServiceRunning.value = true
                },
                onStopNfcService = {
                    stopNfcService()
                    isNfcServiceRunning.value = false
                },
                onStartFpService = {
                    startFpService()
                    isFpServiceRunning.value = true
                },
                onStopFpService = {
                    stopFpService()
                    isFpServiceRunning.value = false
                },
                isNfcServiceRunning = isNfcServiceRunning.value,
                isFpServiceRunning = isFpServiceRunning.value,
                tagList = tagList,
                templateList = templateList
            )
        }

        //handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        registerBroadcastReceiver("NFC_TAG_DISCOVERED")
        registerBroadcastReceiver("NFC_SERVICE_STOPPED")
        registerBroadcastReceiver("FP_TEMPLATE_DISCOVERED")
        registerBroadcastReceiver("FP_SERVICE_STOPPED")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        //handleNfcIntent(intent)
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "$packageName.NFC_TAG_DISCOVERED" -> {
                    val tagId = intent.getStringExtra("tagId")
                    tagId?.let {
                        // Display the NFC tag information in the UI
                        addTagToList(it)
                    }
                }
                "$packageName.FP_TEMPLATE_DISCOVERED" -> {
                    val template = intent.getStringExtra("template")
                    template?.let {
                        // Display the NFC tag information in the UI
                        addTemplateToList(it)
                    }
                }
                "$packageName.NFC_SERVICE_STOPPED" -> {
                    isNfcServiceRunning.value = false
                }
                "$packageName.FP_SERVICE_STOPPED" -> {
                    isFpServiceRunning.value = false
                }
            }
        }
    }

    private fun registerBroadcastReceiver(filterName: String) {
        val filter = IntentFilter("$packageName.$filterName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()

        unregisterReceiver(broadcastReceiver)
    }

    private fun startNfcService() {
        val serviceIntent = Intent(this, NFCForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        }
    }

    private fun startFpService() {

        if(!checkPermission())
        {
            requestPermission();
        }

        val serviceIntent = Intent(this, FPForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        }
    }


    //Check storage permission for device calibration data(65210-S).
    private fun checkPermission(): Boolean {
        /*API-Level-30-Start*/
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        } else {
            val readPermission = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val writePermission = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            return readPermission == PackageManager.PERMISSION_GRANTED && writePermission == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.setData(
                    Uri.parse(
                        String.format(
                            "package:%s", *arrayOf<Any>(
                                applicationContext.packageName
                            )
                        )
                    )
                )
                startActivityForResult(intent, PERMISSION_CALLBACK_CODE)
            } catch (e: java.lang.Exception) {
                val intent = Intent()
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, PERMISSION_CALLBACK_CODE)
            }
        } else {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf<String>(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ACTION_USB_PERMISSION
                ),
                READ_WRITE_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun stopNfcService() {
        val serviceIntent = Intent(this, NFCForegroundService::class.java)
        stopService(serviceIntent)
    }

    private fun stopFpService() {
        val serviceIntent = Intent(this, FPForegroundService::class.java)
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

    /*private fun handleNfcIntent(intent: Intent) {
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let {
                val tagId = it.id.joinToString(separator = "") { byte -> "%02x".format(byte) }
                Log.d("MainActivity", "NFC Tag discovered: $tagId")
                // Process the NFC tag ID as needed
                sendNfcDataToApi(tagId)
                addTagToList(tagId)
            }
            moveTaskToBack(true)
        }
    }

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://your-api-server.com/nfc/") // Replace with your API's base URL
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val apiService = retrofit.create(ApiService::class.java)

    private fun sendNfcDataToApi(tagId: String) {
        val nfcData = NfcData(tagId = tagId)
        val call = apiService.sendNfcData(nfcData)

        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d("NFC Service", "Data sent successfully: $tagId")
                } else {
                    Log.e("NFC Service", "Failed to send data: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e("NFC Service", "Error sending data", t)
            }
        })
    }*/

    private fun addTagToList(tagId: String) {
        tagList.add(tagId)
        Log.d("MainActivity", "Received NFC Tag: $tagId")
    }

    private fun addTemplateToList(template: String) {
        templateList.add(template)
        Log.d("MainActivity", "Received FP Template: $template")
    }
}

@Composable
fun MainScreen(
    onStartNfcService: () -> Unit,
    onStopNfcService: () -> Unit,
    onStartFpService: () -> Unit,
    onStopFpService: () -> Unit,
    isNfcServiceRunning: Boolean,
    isFpServiceRunning: Boolean,
    tagList: List<String>,
    templateList: List<String>
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(color = Color(.9f, 1f, .9f))
            .padding(16.dp)) {
            Button(
                onClick = onStartNfcService,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                enabled = !isNfcServiceRunning // Enabled only if the service is not running
            ) {
                Text(text = "Start NFC Service")
            }
            Button(
                onClick = onStopNfcService,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                enabled = isNfcServiceRunning // Enabled only if the service is running
            ) {
                Text(text = "Stop NFC Service")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Discovered NFC Tags:")
            Spacer(modifier = Modifier.height(8.dp))
            NFCList(tagList = tagList)
        }

        Column(modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(color = Color(1f, 1f, .85f))
            .padding(16.dp)) {
            Button(
                onClick = onStartFpService,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                enabled = !isFpServiceRunning // Enabled only if the service is not running
            ) {
                Text(text = "Start FP Service")
            }
            Button(
                onClick = onStopFpService,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                enabled = isFpServiceRunning // Enabled only if the service is running
            ) {
                Text(text = "Stop FP Service")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Discovered FP Templates:")
            Spacer(modifier = Modifier.height(8.dp))
            FPList(templateList = templateList)
        }
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

@Composable
fun FPList(templateList: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxHeight()) {
        items(templateList) { template ->
            Text(text = "Template: $template", modifier = Modifier.padding(4.dp))
        }
    }
}

@Preview(name = "Preview", showBackground = true)
@Composable
fun MainScreenPreviewServiceNotRunning() {
    MainScreen(
        onStartNfcService = {},
        onStopNfcService = {},
        onStartFpService = {},
        onStopFpService = {},
        isNfcServiceRunning = false,
        isFpServiceRunning = true,
        tagList = listOf("Tag1", "Tag2", "Tag3"),
        templateList = listOf("Template1", "Template2", "Template3")
    )
}