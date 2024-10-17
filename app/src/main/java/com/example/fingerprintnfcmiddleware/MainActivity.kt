package com.example.fingerprintnfcmiddleware

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
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

class MainActivity : ComponentActivity() {

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
        val serviceIntent = Intent(this, MainForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
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