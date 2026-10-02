package com.tennis.speedtracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.log10

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var videoView: VideoView
    private lateinit var tvSpeed: TextView
    private lateinit var tvGuide: TextView
    private lateinit var btnLoadVideo: Button

    // 오디오 감지 관련
    private var isAudioRunning = false
    private val audioScope = CoroutineScope(Dispatchers.IO)
    private var lastImpactTimestampNs: Long = 0L
    private var isTracking = false

    // 프레임 모션 감지용
    private var prevFrameLuma: ByteArray? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadSelectedVideo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        videoView = findViewById(R.id.videoView)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvGuide = findViewById(R.id.tvGuide)
        btnLoadVideo = findViewById(R.id.btnLoadVideo)

        btnLoadVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        if (allPermissionsGranted()) {
            startCameraWithAnalyzer()
            startAudioTrigger()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }
    }

    // 1. 오디오 트리거: 타구음 피크 감지 (자동 임팩트 t0)
    @SuppressLint("MissingPermission")
    private fun startAudioTrigger() {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )

        val audioBuffer = ShortArray(bufferSize)
        audioRecord.startRecording()
        isAudioRunning = true

        audioScope.launch {
            while (isAudioRunning) {
                val read = audioRecord.read(audioBuffer, 0, bufferSize)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) sum += audioBuffer[i] * audioBuffer[i]
                    val rms = kotlin.math.sqrt(sum / read)
                    val db = 20 * log10(rms)

                    // 타구음 임계치 (약 82dB 이상) 감지
                    if (db > 82.0 && !isTracking) {
                        lastImpactTimestampNs = System.nanoTime()
                        isTracking = true

                        withContext(Dispatchers.Main) {
                            tvSpeed.text = "TRACKING"
                            tvSpeed.setTextColor(Color.parseColor("#FFCC00"))
                            tvGuide.text = "타구음 감지됨! 바운드 모션 추적 중..."
                        }

                        // 1.5초 후에도 바운드가 안 잡히면 자동 리셋
                        launch {
                            delay(1500)
                            if (isTracking) {
                                isTracking = false
                                withContext(Dispatchers.Main) {
                                    tvSpeed.text = "READY"
                                    tvSpeed.setTextColor(Color.parseColor("#00FF66"))
                                    tvGuide.text = "대기 중 (서브를 넣으면 자동 측정됩니다)"
                                }
                            }
                        }
                    }
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }
    }

    // 2. 비전 분석: 임팩트 후 서비스 박스 영역 모션 급증 감지 (자동 바운드 t1)
    private fun startCameraWithAnalyzer() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                processFrameMotion(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                // Ignore
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrameMotion(imageProxy: ImageProxy) {
        if (!isTracking) {
            imageProxy.close()
            return
        }

        val nowNs = System.nanoTime()
        val dtFromImpactSec = (nowNs - lastImpactTimestampNs) / 1_000_000_000.0

        // 서브 체공 시간(0.35초 ~ 0.75초) 사이에 들어왔을 때 모션 검사
        if (dtFromImpactSec in 0.35..0.75) {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            if (prevFrameLuma != null && prevFrameLuma!!.size == data.size) {
                var diffCount = 0
                // 연산량 최적화를 위해 16픽셀 간격으로 샘플링 비교
                for (i in 0 until data.size step 16) {
                    val diff = kotlin.math.abs(data[i].toInt() - prevFrameLuma!![i].toInt())
                    if (diff > 35) diffCount++
                }

                // 공 착지로 인한 급격한 픽셀 변화 감지 (바운드 확정)
                if (diffCount > 150) {
                    isTracking = false
                    calculateAndDisplaySpeed(dtFromImpactSec)
                }
            }
            prevFrameLuma = data
        }

        imageProxy.close()
    }

    private fun calculateAndDisplaySpeed(dtSec: Double) {
        // 검증된 공식: 17.21m + 공기역학 항력 보정계수 1.16
        val distance = 17.21
        val vAvgMps = distance / dtSec
        val vAvgKph = vAvgMps * 3.6
        val vInitKph = vAvgKph * 1.16

        runOnUiThread {
            tvSpeed.text = "%.1f KM/H".format(vInitKph)
            tvSpeed.setTextColor(Color.parseColor("#FF2222"))
            tvGuide.text = "자동 측정 완료! 비행시간: %.3f초".format(dtSec)
        }
    }

    private fun loadSelectedVideo(uri: Uri) {
        isAudioRunning = false
        viewFinder.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        videoView.setVideoURI(uri)
        videoView.start()
        tvGuide.text = "불러온 영상을 재생 중입니다."
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        isAudioRunning = false
        analysisExecutor.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
