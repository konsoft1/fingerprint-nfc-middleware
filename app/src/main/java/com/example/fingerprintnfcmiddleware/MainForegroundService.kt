package com.example.fingerprintnfcmiddleware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainForegroundService : Service() {

    private val CHANNEL_ID = "NFCServiceChannel"
    private val nfcReceiver = NfcReceiver()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://your-api-server.com/") // Replace with your API's base URL
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val apiService = retrofit.create(ApiService::class.java)

    override fun onCreate() {
        super.onCreate()

        // Create the notification channel and start the foreground service
        createNotificationChannel()
        startForegroundService()

        // Register for NFC tag discovered broadcast
        val filter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(nfcReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(nfcReceiver, filter)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(nfcReceiver) // Unregister the receiver when service is destroyed
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFC Foreground Service")
            .setContentText("Reading NFC tags...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Prevents notification from being dismissed by the user
            .build()

        // Make the notification non-dismissible
        notification.flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "NFC Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // Inner class for NFC BroadcastReceiver
    private inner class NfcReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                tag?.let {
                    val tagId = it.id.joinToString(separator = "") { byte -> "%02x".format(byte) } // Convert to hex string
                    sendNfcDataToApi(tagId)
                    sendBroadcastToActivity(tagId)
                    Log.d("NFC Service", "NFC Tag discovered: $tagId")
                }
            }
        }
    }

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
    }

    private fun sendBroadcastToActivity(tagId: String) {
        val intent = Intent("com.example.fingerprintnfcmiddleware.NFC_TAG_DISCOVERED")
        intent.putExtra("tagId", tagId)
        sendBroadcast(intent)
    }
}