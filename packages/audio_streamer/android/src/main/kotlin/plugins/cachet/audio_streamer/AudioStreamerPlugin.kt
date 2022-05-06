package plugins.cachet.audio_streamer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener


/** AudioStreamerPlugin */
const val CHANNEL = "AudioStreamer"
const val CHANNEL_NOTIFICATION = "AudioStreamerNotification"
const val NOTIFICATION_ID = 0xb339

class AudioStreamerPlugin : FlutterPlugin, RequestPermissionsResultListener,
    EventChannel.StreamHandler, ActivityAware, MethodChannel.MethodCallHandler {

    companion object {
        const val START_SERVICE = "start_service"
        const val STOP_SERVICE = "stop_service"
    }

    /// Constants
    private val eventChannelName = "audio_streamer.eventChannel"
    private val sampleRate = 44100
    private var bufferSize = 6400 * 2 /// Magical number!
    private val maxAmplitude = 32767 // same as 2^15
    private val logTag = "AudioStreamerPlugin"

    /// Variables (i.e. will change value)
    private var eventSink: EventSink? = null
    private var recording = false

    private var currentActivity: Activity? = null
    private lateinit var channel: MethodChannel
    private var context: Context? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        val messenger = flutterPluginBinding.binaryMessenger
        val eventChannel = EventChannel(messenger, eventChannelName)
        eventChannel.setStreamHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        when (call.method) {
            START_SERVICE -> {
                Log.d(CHANNEL, "start_service")
                try {
                    val thread = HandlerThread("myThread")
                    thread.start()

                    val handler = Handler(thread.looper)
                    handler.post {
                        context?.let {
//                    it.startService(Intent(it, AudioService::class.java))
                            ContextCompat.startForegroundService(
                                it,
                                Intent(it, AudioService::class.java)
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.d(CHANNEL, "error: start_service ==> $e")
                }
            }
            STOP_SERVICE -> {
                Log.d(CHANNEL, "stop_service")
                context?.let {
                    it.stopService(Intent(it, AudioService::class.java))
                }
            }
        }
    }


    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        recording = false
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
    }

    /**
     * Called from Flutter, starts the stream.
     */
    override fun onListen(arguments: Any?, events: EventSink?) {
        this.eventSink = events
        recording = true
        streamMicData()
    }

    /**
     * Called from Flutter, which cancels the stream.
     */
    override fun onCancel(arguments: Any?) {
        recording = false
    }

    /**
     * Called by the plugin itself whenever it detects that permissions have not been granted.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        val requestAudioPermissionCode = 200
        when (requestCode) {
            requestAudioPermissionCode -> if (grantResults[0] == PackageManager.PERMISSION_GRANTED) return true
        }
        return false
    }

    /**
     * Starts recording and streaming audio data from the mic.
     * Uses a buffer array of size 512. Whenever buffer is full, the content is sent to Flutter.
     *
     *
     * Source:
     * https://www.newventuresoftware.com/blog/record-play-and-visualize-raw-audio-data-in-android
     */
    private fun streamMicData() {
        Thread(Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val audioBuffer = ShortArray(bufferSize / 2)
            val record = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(logTag, "Audio Record can't initialize!")
                return@Runnable
            }
            /** Start recording loop  */
            record.startRecording()
            while (recording) {
                /** Read data into buffer  */
                record.read(audioBuffer, 0, audioBuffer.size)
                Handler(Looper.getMainLooper()).post {
                    /// Convert to list in order to send via EventChannel.
                    val audioBufferList = ArrayList<Double>()
                    for (impulse in audioBuffer) {
                        val normalizedImpulse = impulse.toDouble() / maxAmplitude.toDouble()
                        audioBufferList.add(normalizedImpulse)
                    }
                    eventSink!!.success(audioBufferList)
                }
            }
            record.stop()
            record.release()
        }).start()
    }
}
