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

class FPForegroundService : Service() {

    private val CHANNEL_ID = "FPServiceChannel"
    //private val fpReceiver = FpReceiver()

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.FP_API_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val apiService = retrofit.create(ApiService::class.java)

    override fun onCreate() {
        super.onCreate()

        // Create the notification channel and start the foreground service
        createNotificationChannel()
        startForegroundService()

        // Register for Fingerprint discovered broadcast
        /*val filter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(fpReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(fpReceiver, filter)
            }
        }*/

        serviceScope.launch {
            initFp()
        }
    }

    private suspend fun initFp() {
        try {
            val fpConnector = FPConnector(applicationContext)
            fpConnector.initFp()
            delay(1)
            while(true) {
                val template = fpConnector.scanAndExtract()
                if (template.isNotEmpty()) {
                    sendFpDataToApi(template)
                    sendFpBroadcastToActivity(template)
                }
                delay(1)
            }
        } catch (e: CancellationException) {
            Log.d("FP Service", "Coroutine was cancelled")
        } catch (e: Exception) {
            Log.w("FP Service", "Error in FP coroutine", e)
            e.message?.let { sendFpBroadcastToActivity(it) }
            haltFp()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        serviceJob.cancel()

        //unregisterReceiver(fpReceiver) // Unregister the receiver when service is destroyed
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
            .setContentTitle("Fingerprint Foreground Service")
            .setContentText("Reading Fingerprints...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
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
                "Fingerprint Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // Inner class for Fingerprint BroadcastReceiver
    /*private inner class FpReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                tag?.let {
                    val tagId = it.id.joinToString(separator = "") { byte -> "%02x".format(byte) } // Convert to hex string
                    sendFpDataToApi(tagId)
                    sendFpBroadcastToActivity(tagId)
                    Log.d("FP Service", "FP Tag discovered: $tagId")
                }
            }
        }
    }*/

    private fun sendFpDataToApi(template: String) {
        val fpData = FpData(template = template)
        val call = apiService.sendFpData(fpData)

        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d("FP Service", "Data sent successfully: $template")
                } else {
                    Log.e("FP Service", "Failed to send data: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e("FP Service", "Error sending data", t)
            }
        })
    }

    private fun sendFpBroadcastToActivity(template: String) {
        val intent = Intent("$packageName.FP_TEMPLATE_DISCOVERED")
        intent.setPackage(packageName)
        intent.putExtra("template", template)
        sendBroadcast(intent)
    }

    private fun haltFp() {
        sendStopBroadcastToActivity()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStopBroadcastToActivity() {
        val intent = Intent("$packageName.FP_SERVICE_STOPPED")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }
}