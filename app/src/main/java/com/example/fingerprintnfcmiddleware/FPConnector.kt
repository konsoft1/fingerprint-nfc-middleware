package com.example.fingerprintnfcmiddleware

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.nextbiometrics.biometrics.NBBiometricsContext
import com.nextbiometrics.biometrics.NBBiometricsExtractResult
import com.nextbiometrics.biometrics.NBBiometricsFingerPosition
import com.nextbiometrics.biometrics.NBBiometricsIdentifyResult
import com.nextbiometrics.biometrics.NBBiometricsSecurityLevel
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.biometrics.NBBiometricsTemplate
import com.nextbiometrics.biometrics.NBBiometricsTemplateType
import com.nextbiometrics.biometrics.NBBiometricsVerifyResult
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewEvent
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewListener
import com.nextbiometrics.devices.NBDevice
import com.nextbiometrics.devices.NBDeviceScanFormatInfo
import com.nextbiometrics.devices.NBDeviceScanResult
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.nextbiometrics.devices.NBDeviceSecurityModel
import com.nextbiometrics.devices.NBDeviceType
import com.nextbiometrics.devices.NBDevices
import com.nextbiometrics.system.NextBiometricsException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.AbstractMap
import java.util.Date
import java.util.LinkedList

class FPConnector(private val applicationContext: Context) {

    private var device : NBDevice? = null
    private var terminate: Boolean = false
    private var isSpi: Boolean = false
    private var dialogResult: Boolean = false
    private var scanFormatInfo: NBDeviceScanFormatInfo? = null
    private var bgscanResult: NBDeviceScanResult? = null
    private var isScanAndExtractInProgress: Boolean = false
    private var timeStart: Long = 0
    private var timeStop: Long = 0
    private var quality: Int = 0
    private var success: Boolean = false
    private var extractedTemplate: String = "";
    private var isSpoofEnabled: Boolean = false
    private var isValidSpoofScore: Boolean = false
    private val spoofCause: String = "Spoof Detected."

    private var previewListener: PreviewListener = PreviewListener()
    private var previewStartTime: Long = 0
    private var previewEndTime: Long = 0

    private val DEFAULT_SPI_NAME: String = "/dev/spidev0.0"
    //Default SYSFS path to access GPIO pins
    private val DEFAULT_SYSFS_PATH: String = "/sys/class/gpio"
    private val DEFAULT_SPICLK = 8000000
    private val DEFAULT_AWAKE_PIN_NUMBER = 0
    private val DEFAULT_RESET_PIN_NUMBER = 0
    private val DEFAULT_CHIP_SELECT_PIN_NUMBER = 0
    private val ENABLE_BACKGROUND_SUBTRACTION: Int = 1
    private val MIN_ANTISPOOF_THRESHOLD: Int = 0
    private val MAX_ANTISPOOF_THRESHOLD: Int = 1000
    private val spoofScore = MAX_ANTISPOOF_THRESHOLD
    private val DEFAULT_ANTISPOOF_THRESHOLD: String = "363"
    private var ANTISPOOF_THRESHOLD: String = DEFAULT_ANTISPOOF_THRESHOLD

    var FLAGS: Int = 0

    fun initFp() {
        try {
            if ((device == null) || (device != null && !device!!.isSessionOpen())) {
                //During Reset/unplug-device Session Closed. Need to re-Initilize Device
                if (deviceInit()) showMessage("Device initialized")
            }
            if (device != null) {
                val context = NBBiometricsContext(device)
                val msg = "Device went to low power mode."
                try {
                    context.cancelOperation()
                    device!!.lowPowerMode()
                } catch (ex: NextBiometricsException) {
                    showMessage(msg)
                    //Toast.makeText(mainActivity, msg, Toast.LENGTH_LONG).show()
                }
            } else {
                throw Exception("ERROR: Device Not Found!")
            }
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
            throw ex
        } catch (ex: Throwable) {
            showMessage("ERROR: " + ex.message, true)
            ex.printStackTrace()
            throw ex
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
            throw ex
            return false
        } catch (ex: Throwable) {
            showMessage("ERROR: $ex", true)
            ex.printStackTrace()
            throw ex
            return false
        }
    }

    fun scanAndExtract(): String {
        //Hide Menu before scan and extract starts
        isScanAndExtractInProgress = true
        extractedTemplate = ""

        var context: NBBiometricsContext? = null
        try {
            if ((device == null) || (device != null && !device!!.isSessionOpen)) {
                //During Reset/unplug-device Session Closed. Need to re-Initilize Device
                if (deviceInit()) showMessage("Device initialized")
            }
            if (device != null) {
                // If the device requires external calibration data (NB-65210-S), load or create them
                if (device!!.capabilities.requiresExternalCalibrationData) {
                    solveCalibrationData(device!!)
                }
                context = NBBiometricsContext(device)
                var extractResult: NBBiometricsExtractResult? = null

                showMessage("")
                showMessage("Extracting fingerprint template, please put your finger on sensor!")
                previewListener.reset()
                timeStop = 0
                timeStart = timeStop
                quality = 0
                success = false
                try {
                    timeStart = System.currentTimeMillis()
                    //Hide Menu before scan and extract starts.
                    isScanAndExtractInProgress = true
                    //if (isSpoofEnabled) enableSpoof()
                    //Enable Image preview for FAP20
                    //device.setParameter(410,1);
                    extractResult = context.extract(
                        NBBiometricsTemplateType.ISO,
                        NBBiometricsFingerPosition.UNKNOWN,
                        scanFormatInfo,
                        previewListener
                    )
                    timeStop = System.currentTimeMillis()
                } catch (ex: java.lang.Exception) {
                    if (!isSpi) throw ex

                    // Workaround for a specific customer device problem: If the SPI is idle for certain time, it is put to sleep in a way which breaks the communication
                    // The workaround is to reopen the SPI connection, which resets the communication
                    /*if (isSpi && (ex.message.equals(retrySPICause, ignoreCase = true))) {
                        //Retry Max Times for SPI
                        context.dispose()
                        context = null
                        device!!.dispose()
                        device = null
                        device = NBDevice.connectToSpi(
                            DEFAULT_SPI_NAME,
                            DEFAULT_SYSFS_PATH,
                            DEFAULT_SPICLK,
                            DEFAULT_AWAKE_PIN_NUMBER,
                            DEFAULT_RESET_PIN_NUMBER,
                            DEFAULT_CHIP_SELECT_PIN_NUMBER,
                            FLAGS
                        )
                        // If the device requires external calibration data (NB-65210-S), load or create them
                        if (device != null && device!!.capabilities.requiresExternalCalibrationData) {
                            solveCalibrationData(device!!)
                        }

                        val scanFormats = device.getSupportedScanFormats()
                        if (scanFormats.size == 0) throw java.lang.Exception("No supported formats found!")
                        scanFormatInfo = scanFormats[0]
                        // And retry the extract operation
                        context = NBBiometricsContext(device)
                        timeStart = System.currentTimeMillis()
                        if (isSpoofEnabled) enableSpoof()
                        //Enable Image preview for FAP20
                        //device.setParameter(410,1);
                        extractResult = context.extract(
                            NBBiometricsTemplateType.ISO,
                            NBBiometricsFingerPosition.UNKNOWN,
                            scanFormatInfo,
                            previewListener
                        )
                        timeStop = System.currentTimeMillis()
                    } else {
                        //If block handled for IO Command failed exception for SPI.
                        throw ex
                    }*/
                }
                if (extractResult!!.status != NBBiometricsStatus.OK) {
                    throw java.lang.Exception("Extraction failed, reason: " + extractResult.status)
                }
                //Antispoof check
                val tmpSpoofThreshold: Int = ANTISPOOF_THRESHOLD.toInt()
                if (isSpoofEnabled && isValidSpoofScore && previewListener.spoofScore <= tmpSpoofThreshold) {
                    throw java.lang.Exception("Extraction failed, reason: " + spoofCause)
                }
                showMessage("Extracted successfully!")
                val template = extractResult.template
                quality = template.quality
                /*showResultOnUiThread(
                    previewListener.getLastImage(),
                    String.format(
                        "Last scan = %d msec, Image process = %d msec, Extract = %d msec, Total time = %d msec\nTemplate quality = %d, Last finger detect score = %d",
                        previewListener.getTimeScanEnd() - previewListener.getTimeScanStart(),
                        previewListener.getTimeOK() - previewListener.getTimeScanEnd(),
                        timeStop - previewListener.getTimeOK(),
                        timeStop - previewListener.getTimeScanStart(),
                        quality,
                        previewListener.getFdetScore()
                    )
                )
                if (isAutoSaveEnabled) {
                    saveImageApi(imageFormatName, "Extraction_Template")
                }*/
                // Verification
                //
                /*showMessage("")
                showMessage("Verifying fingerprint, please put your finger on sensor!")
                previewListener.reset()
                context.dispose()
                context = null
                context = NBBiometricsContext(device)
                timeStart = System.currentTimeMillis()
                //Enable Image preview for FAP20
                //device.setParameter(410,1);
                val verifyResult: NBBiometricsVerifyResult = context.verify(
                    NBBiometricsTemplateType.ISO,
                    NBBiometricsFingerPosition.UNKNOWN,
                    scanFormatInfo,
                    previewListener,
                    template,
                    NBBiometricsSecurityLevel.NORMAL
                )
                timeStop = System.currentTimeMillis()
                if (verifyResult.status != NBBiometricsStatus.OK) {
                    throw java.lang.Exception("Not verified, reason: " + verifyResult.status)
                }
                if (isSpoofEnabled && isValidSpoofScore && previewListener.spoofScore <= tmpSpoofThreshold) {
                    throw java.lang.Exception("Not verified, reason: " + spoofCause)
                }
                showMessage("Verified successfully!")*/
                /*showResultOnUiThread(
                    previewListener.getLastImage(),
                    String.format(
                        "Last scan = %d msec, Image process = %d msec, Extract+Verify = %d msec, Total time = %d msec\nMatch score = %d, Last finger detect score = %d",
                        previewListener.getTimeScanEnd() - previewListener.getTimeScanStart(),
                        previewListener.getTimeOK() - previewListener.getTimeScanEnd(),
                        timeStop - previewListener.getTimeOK(),
                        timeStop - previewListener.getTimeScanStart(),
                        verifyResult.score,
                        previewListener.getFdetScore()
                    )
                )
                if (isAutoSaveEnabled) {
                    saveImageApi(imageFormatName, "Verification_Template")
                }*/
                // Identification
                //
                /*showMessage("")
                val templates: MutableList<AbstractMap.SimpleEntry<Any, NBBiometricsTemplate>> =
                    LinkedList()
                templates.add(AbstractMap.SimpleEntry("TEST", template))*/

                // add more templates
                /*showMessage("Identifying fingerprint, please put your finger on sensor!")
                previewListener.reset()
                context.dispose()
                context = null
                context = NBBiometricsContext(device)
                timeStart = System.currentTimeMillis()
                //Enable Image preview for FAP20
                //device.setParameter(410,1);
                val identifyResult: NBBiometricsIdentifyResult = context.identify(
                    NBBiometricsTemplateType.ISO,
                    NBBiometricsFingerPosition.UNKNOWN,
                    scanFormatInfo,
                    previewListener,
                    templates.iterator(),
                    NBBiometricsSecurityLevel.NORMAL
                )
                timeStop = System.currentTimeMillis()
                if (identifyResult.status != NBBiometricsStatus.OK) {
                    throw java.lang.Exception("Not identified, reason: " + identifyResult.status)
                }
                if (isSpoofEnabled && isValidSpoofScore && previewListener.spoofScore <= tmpSpoofThreshold) {
                    throw java.lang.Exception("Not identified, reason: " + spoofCause)
                }
                showMessage("Identified successfully with fingerprint: " + identifyResult.templateId)*/
                /*showResultOnUiThread(
                    previewListener.getLastImage(),
                    String.format(
                        "Last scan = %d msec, Image process = %d msec, Extract+Identify = %d msec, Total time = %d msec\nMatch score = %d, Last finger detect score = %d",
                        previewListener.getTimeScanEnd() - previewListener.getTimeScanStart(),
                        previewListener.getTimeOK() - previewListener.getTimeScanEnd(),
                        timeStop - previewListener.getTimeOK(),
                        timeStop - previewListener.getTimeScanStart(),
                        verifyResult.score,
                        previewListener.getFdetScore()
                    )
                )
                if (isAutoSaveEnabled) {
                    saveImageApi(imageFormatName, "Identification_Template")
                }*/
                // Save template
                val binaryTemplate = context.saveTemplate(template)
                showMessage(
                    String.format(
                        "Extracted template length: %d bytes",
                        binaryTemplate.size
                    )
                )
                val base64Template = Base64.encodeToString(binaryTemplate, 0)
                showMessage("Extracted template: $base64Template")

                extractedTemplate = base64Template

                // Store template to file
                val dirPath =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        .toString() + "/NBCapturedImages/"
                val filePath = dirPath + createFileName() + "-ISO-Template.bin"
                val files = File(dirPath)
                files.mkdirs()
                showMessage("Saving ISO template to $filePath")
                val fos = FileOutputStream(filePath)
                fos.write(binaryTemplate)
                fos.close()
                success = true
            }
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
            throw ex
        } catch (ex: Throwable) {
            showMessage("ERROR: " + ex.message, true)
            ex.printStackTrace()
            throw ex
        }
        if (context != null) {
            context.dispose()
        }

        onScanExtractCompleted()

        return extractedTemplate
    }

    fun onScanExtractCompleted() {
        if (device == null) {
            //device is null
            showMessage("Device Unplugged or No Active Session.", true)
        }
        isScanAndExtractInProgress = false
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
        /*if (!file.exists()) {
            // Ask the user whether he wants to calibrate the device
            runOnUiThread {
                val dialogClickListener =
                    DialogInterface.OnClickListener { dialog, which ->
                        synchronized(mainActivity) {
                            dialogResult = (which == DialogInterface.BUTTON_POSITIVE)
                            (mainActivity as Object).notifyAll()
                        }
                    }
                AlertDialog.Builder(mainActivity).setMessage(
                    """
                    This device is not calibrated yet. Do you want to calibrate it?
                    
                    If yes, at first perfectly clean the sensor, and only then select the YES button.
                    """.trimIndent()
                ).setPositiveButton("Yes", dialogClickListener)
                    .setNegativeButton("No", dialogClickListener).show()
            }

            synchronized(mainActivity) {
                (mainActivity as Object).wait()
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
        }*/
        if (file.exists()) {
            val size = file.length().toInt()
            val bytes = ByteArray(size)
            try {
                val buf = BufferedInputStream(FileInputStream(file))
                buf.read(bytes, 0, bytes.size)
                buf.close()
            } catch (_: IOException) {
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
        println("FINGER>> ${if (isErrorMessage) "ERR>>" else "INF>>"} $message")
        //Toast.makeText(this, "FINGER>> ${if (isErrorMessage) "ERR>>" else "INF>>"} $message", Toast.LENGTH_LONG).show()
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
                } catch (_: RuntimeException) {
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

    fun createFileName(): String {
        val DEFAULT_FILE_PATTERN = "yyyy-MM-dd-HH-mm-ss"
        val date = Date(System.currentTimeMillis())
        val format = SimpleDateFormat(DEFAULT_FILE_PATTERN)
        return format.format(date)
    }

    //
    // Preview Listener
    //
    inner class PreviewListener : NBBiometricsScanPreviewListener {
        private var counter = 0
        private var sequence = 0
        var lastImage: ByteArray? = null
            private set
        var timeFDET: Long = 0
            private set
        var timeScanStart: Long = 0
            private set
        var timeScanEnd: Long = 0
            private set
        var timeOK: Long = 0
            private set
        var fdetScore: Int = 0
            private set

        fun reset() {
            counter = 0
            sequence++
            lastImage = null
            timeScanStart = 0
            timeScanEnd = timeScanStart
            timeOK = timeScanEnd
            timeFDET = timeOK
            fdetScore = 0
            spoofScore = MAX_ANTISPOOF_THRESHOLD
        }

        var spoofScore: Int = 0

        override fun preview(event: NBBiometricsScanPreviewEvent) {
            val image = event.image
            spoofScore = event.spoofScoreValue

            isValidSpoofScore = true

            if (spoofScore <= MIN_ANTISPOOF_THRESHOLD || spoofScore > MAX_ANTISPOOF_THRESHOLD) {
                spoofScore = MIN_ANTISPOOF_THRESHOLD
                isValidSpoofScore = false
            }
            if (isValidSpoofScore) {
                updateMessage(
                    String.format(
                        "PREVIEW #%d: Status: %s, Finger detect score: %d, Spoof Score: %d, image %d bytes",
                        ++counter, event.scanStatus.toString(), event.fingerDetectValue, spoofScore,
                        image?.size ?: 0
                    )
                )
            } else {
                updateMessage(
                    String.format(
                        "PREVIEW #%d: Status: %s, Finger detect score: %d, image %d bytes",
                        ++counter,
                        event.scanStatus.toString(),
                        event.fingerDetectValue,
                        image?.size ?: 0
                    )
                )
            }
            if (image != null) lastImage = image
            // Approx. time when finger was detected = last preview before operation that works with finger image
            if (event.scanStatus != NBDeviceScanStatus.BAD_QUALITY && event.scanStatus != NBDeviceScanStatus.BAD_SIZE && event.scanStatus != NBDeviceScanStatus.DONE && event.scanStatus != NBDeviceScanStatus.OK && event.scanStatus != NBDeviceScanStatus.KEEP_FINGER_ON_SENSOR && event.scanStatus != NBDeviceScanStatus.SPOOF && event.scanStatus != NBDeviceScanStatus.SPOOF_DETECTED && event.scanStatus != NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING && event.scanStatus != NBDeviceScanStatus.CANCELED) {
                timeFDET = System.currentTimeMillis()
            }
            if (event.scanStatus == NBDeviceScanStatus.DONE || event.scanStatus == NBDeviceScanStatus.OK || event.scanStatus == NBDeviceScanStatus.CANCELED || event.scanStatus == NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING || event.scanStatus == NBDeviceScanStatus.SPOOF_DETECTED) {
                // Approx. time when scan was finished = time of the first event OK, DONE, CANCELED or WAIT_FOR_DATA_PROCESSING
                if (timeScanEnd == 0L) timeScanEnd = System.currentTimeMillis()
            } else {
                // Last scan start = time of any event just before OK, DONE, CANCELED or WAIT_FOR_DATA_PROCESSING
                timeScanStart = System.currentTimeMillis()
            }
            // Time when scan was completed
            if (event.scanStatus == NBDeviceScanStatus.OK || event.scanStatus == NBDeviceScanStatus.DONE || event.scanStatus == NBDeviceScanStatus.CANCELED || event.scanStatus == NBDeviceScanStatus.SPOOF_DETECTED) {
                timeOK = System.currentTimeMillis()
                fdetScore = event.fingerDetectValue
            }

            if (previewListener.lastImage != null && (device.toString()
                    .contains("65210") || device.toString().contains("65200"))
            ) {
                if (previewStartTime == 0L) {
                    previewStartTime = System.currentTimeMillis()
                }

                previewEndTime = System.currentTimeMillis()
                /*showResultOnUiThread(
                    previewListener.lastImage,
                    String.format(
                        "Preview scan time = %d msec,\n Finger detect score = %d",
                        previewEndTime - previewStartTime,
                        event.fingerDetectValue
                    )
                )*/
                previewStartTime = System.currentTimeMillis()
            }
        }
    };

    private fun updateMessage(message: String) {
        Log.i("MainActivity", String.format("%d: %s", System.currentTimeMillis(), message))
    }

    private fun clearMessages() {
        Log.i("MainActivity", "-------------------------------------------")
    }

}