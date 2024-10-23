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
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.Thread.sleep

class NFCForegroundService : Service() {

    private val CHANNEL_ID = "NFCServiceChannel"
    private lateinit var notificationManager: NotificationManager
    private val NOTIFICATION_ID = 1

    private val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.NFC_API_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val apiService = retrofit.create(ApiService::class.java)

    override fun onCreate() {
        super.onCreate()

        // Create the notification channel and start the foreground service
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForegroundService()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            // Ensure that the intent is explicit
            setAction(Intent.ACTION_MAIN)
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE // Correct usage for Android 14+
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFC Foreground Service")
            .setContentText("Waiting NFC tags...")
            .setSmallIcon(android.R.drawable.ic_dialog_dialer)
            .setContentIntent(pendingIntent)
            //.addAction(android.R.drawable.ic_delete, "Stop service", stopPendingIntent)
            .setOngoing(true)
            .build()

        notification.flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tag = intent?.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        tag?.let {
            val tagId =
                it.id.joinToString(separator = "") { byte -> "%02x".format(byte) } // Convert to hex string

            val newId = convertTagId(it)

            updateNotification("NFC tag tapped: $tagId")
            sendNfcBroadcastToActivity("$newId")
            //sendNfcDataToApi(tagId)
            Log.d("NFC Service", "Mifare Tag discovered: $newId")
        }
        return START_STICKY
    }

    private fun convertTagId(tag: Tag): ULong {
        val id = tag.id
        val newId: ULong = id[2].toUByte() * 100000u + id[1].toUByte() * (0x100).toULong() + id[0].toUByte()
        return newId
    }

    private fun updateNotification(status: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFC Foreground Service")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_dialer)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "NFC Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun sendNfcDataToApi(tagId: String) {
        val nfcData = NfcData(tagId = tagId)
        val call = apiService.sendNfcData(nfcData)

        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d("NFC Service", "Data sent successfully: $tagId")
                    updateNotification("NFC data sent to API")
                    stopForegroundService()
                } else {
                    Log.e("NFC Service", "Failed to send data: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e("NFC Service", "Error sending data", t)
            }
        })
    }

    private fun sendNfcBroadcastToActivity(tagId: String) {
        val intent = Intent("$packageName.NFC_TAG_DISCOVERED")
        intent.setPackage(packageName)
        intent.putExtra("tagId", tagId)
        sendBroadcast(intent)

        updateNotification("NFC data broadcast: $tagId")
    }

    private fun stopForegroundService() {
        // Allow the notification to be swiped away by setting it as cancelable
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFC Foreground Service")
            .setContentText("NFC handling completed.")
            .setSmallIcon(android.R.drawable.ic_dialog_dialer)
            .setAutoCancel(true) // Notification can be swiped away
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Stop the foreground service and remove the notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}