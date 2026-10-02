package com.tennis.speedtracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var videoView: VideoView
    private lateinit var touchOverlay: View
    private lateinit var tvGuide: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvSubStats: TextView

    private lateinit var btnResetCalib: Button
    private lateinit var btnModeLive: Button
    private lateinit var btnLoadVideo: Button
    private lateinit var btnPrevFrame: Button
    private lateinit var btnNextFrame: Button
    private lateinit var btnSetImpact: Button
    private lateinit var btnSetBounce: Button

    private val calibPixels = mutableListOf<Pair<Double, Double>>()
    private var homographyMatrix: DoubleArray? = null
    private val realCourtPoints = listOf(
        Pair(-4.115, 0.0), Pair(4.115, 0.0), Pair(-4.115, 18.29), Pair(4.115, 18.29)
    )

    private var isVideoMode = false
    private var isSelectingBounce = false
    private var mediaPlayer: MediaPlayer? = null
    private var currentMs = 0L
    private var videoImpactMs = -1L

    private var isAudioListening = false
    private var liveImpactTimeMs = -1L
    private var liveAudioScope = CoroutineScope(Dispatchers.IO)

    // 구글 포토를 우회하고 '삼성 내장 갤러리'를 강제 호출하는 런처
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> loadVideoFileRobustly(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }
    }

    private fun initViews() {
        viewFinder = findViewById(R.id.viewFinder)
        videoView = findViewById(R.id.videoView)
        touchOverlay = findViewById(R.id.touchOverlay)
        tvGuide = findViewById(R.id.tvGuide)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvSubStats = findViewById(R.id.tvSubStats)

        btnResetCalib = findViewById(R.id.btnResetCalib)
        btnModeLive = findViewById(R.id.btnModeLive)
        btnLoadVideo = findViewById(R.id.btnLoadVideo)
        btnPrevFrame = findViewById(R.id.btnPrevFrame)
        btnNextFrame = findViewById(R.id.btnNextFrame)
        btnSetImpact = findViewById(R.id.btnSetImpact)
        btnSetBounce = findViewById(R.id.btnSetBounce)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        touchOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleScreenTouch(event.x.toDouble(), event.y.toDouble())
            }
            true
        }

        btnResetCalib.setOnClickListener {
            calibPixels.clear()
            homographyMatrix = null
            isSelectingBounce = false
            tvGuide.text = "1단계: 내 쪽 베이스라인 [좌측 코너]를 터치하세요"
            tvSpeed.text = "--- KM/H"
            tvSubStats.text = "코트 기준점 4개를 먼저 등록해주세요"
        }

        btnModeLive.setOnClickListener {
            isVideoMode = false
            videoView.visibility = View.GONE
            viewFinder.visibility = View.VISIBLE
            mediaPlayer?.pause()
            
            toggleVideoControls(false)
            startAudioTrigger()
            
            if (homographyMatrix != null) {
                tvGuide.text = "[라이브 모드] 서브(소리) 후 바운드 지점을 화면에서 터치하세요!"
            } else {
                tvGuide.text = "코트 기준점 4개를 먼저 터치하여 캘리브레이션 하세요."
            }
        }

        btnLoadVideo.setOnClickListener {
            stopAudioTrigger()
            // 강제로 시스템 갤러리(MediaStore) 앱을 띄우는 Intent
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            intent.type = "video/*"
            pickVideoLauncher.launch(intent)
        }

        btnPrevFrame.setOnClickListener {
            mediaPlayer?.let { mp ->
                currentMs = (currentMs - 16).coerceAtLeast(0)
                seekToAccurate(mp, currentMs)
                updateVideoGuide()
            }
        }

        btnNextFrame.setOnClickListener {
            mediaPlayer?.let { mp ->
                currentMs = (currentMs + 16).coerceAtMost(mp.duration.toLong())
                seekToAccurate(mp, currentMs)
                updateVideoGuide()
            }
        }

        btnSetImpact.setOnClickListener {
            videoImpactMs = currentMs
            Toast.makeText(this, "임팩트 시점 확정", Toast.LENGTH_SHORT).show()
            updateVideoGuide()
        }

        btnSetBounce.setOnClickListener {
            if (videoImpactMs < 0) {
                Toast.makeText(this, "임팩트를 먼저 지정하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isSelectingBounce = true
            tvGuide.text = "[바운드 확정] 화면에서 공이 튄 정확한 지점을 콕 터치하세요!"
        }
    }

    private fun seekToAccurate(mp: MediaPlayer, targetMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mp.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(targetMs.toInt())
        }
    }

    // 재생 불가 버그를 방지하는 강력한 로컬 캐시 복제 로직
    private fun loadVideoFileRobustly(uri: Uri) {
        Toast.makeText(this, "영상을 안전하게 불러오는 중입니다...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 앱 내부 캐시 폴더에 임시 파일 생성 (권한 충돌 방지)
                val tempFile = File(cacheDir, "serve_video_temp.mp4")
                
                // 2. 갤러리의 영상 스트림을 안전한 로컬 파일로 1:1 고속 복사
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    isVideoMode = true
                    viewFinder.visibility = View.GONE
                    videoView.visibility = View.VISIBLE
                    toggleVideoControls(true)

                    // 3. 가상 URI(content://) 대신 실제 물리적 경로(Path)를 VideoView에 주입
                    videoView.setVideoPath(tempFile.absolutePath)
                    
                    videoView.setOnPreparedListener { mp ->
                        mediaPlayer = mp
                        mp.pause()
                        currentMs = 0L
                        videoImpactMs = -1L
                        seekToAccurate(mp, 0L)
                        updateVideoGuide()
                    }
                    
                    videoView.setOnErrorListener { _, what, extra ->
                        Toast.makeText(this@MainActivity, "재생 오류가 발생했습니다. ($what, $extra)", Toast.LENGTH_LONG).show()
                        true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "영상 로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleScreenTouch(x: Double, y: Double) {
        if (calibPixels.size < 4) {
            calibPixels.add(Pair(x, y))
            when (calibPixels.size) {
                1 -> tvGuide.text = "2단계: 내 쪽 베이스라인 [우측 코너]를 터치하세요"
                2 -> tvGuide.text = "3단계: 반대편 서비스라인 [좌측 교차점]을 터치하세요"
                3 -> tvGuide.text = "4단계: 반대편 서비스라인 [우측 교차점]을 터치하세요"
                4 -> {
                    homographyMatrix = HomographySolver.findHomography(calibPixels, realCourtPoints)
                    if (homographyMatrix != null) {
                        tvGuide.text = "코트 설정 완료! 모드를 선택하고 측정을 시작하세요."
                        Toast.makeText(this, "호모그래피 연산 성공", Toast.LENGTH_SHORT).show()
                    } else {
                        tvGuide.text = "기준점 오류! [코트 재설정]을 누르고 다시 지정하세요."
                        calibPixels.clear()
                    }
                }
            }
            return
        }

        val H = homographyMatrix
        if (H == null) return

        if (isVideoMode && isSelectingBounce) {
            val dtSec = (currentMs - videoImpactMs) / 1000.0
            if (dtSec <= 0) {
                Toast.makeText(this, "바운드는 임팩트 이후여야 합니다.", Toast.LENGTH_SHORT).show()
                return
            }
            calculateAndShowSpeed(H, x, y, dtSec)
            isSelectingBounce = false
            updateVideoGuide()

        } else if (!isVideoMode) {
            if (liveImpactTimeMs > 0) {
                val liveBounceTimeMs = System.currentTimeMillis()
                val dtSec = (liveBounceTimeMs - liveImpactTimeMs) / 1000.0
                
                if (dtSec in 0.2..2.0) {
                    calculateAndShowSpeed(H, x, y, dtSec)
                    tvGuide.text = "측정 완료! (다음 서브 타구음 대기 중...)"
                }
                liveImpactTimeMs = -1L
            } else {
                Toast.makeText(this, "타구 소리가 먼저 감지되어야 합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun calculateAndShowSpeed(H: DoubleArray, pixelX: Double, pixelY: Double, dtSec: Double) {
        val (realX, realY) = HomographySolver.transform(H, pixelX, pixelY)

        val impX = 0.0
        val impY = 0.5
        val impZ = 2.70

        val distance = sqrt(
            (realX - impX) * (realX - impX) +
            (realY - impY) * (realY - impY) +
            (0.0 - impZ) * (0.0 - impZ)
        )

        val vAvgMps = distance / dtSec
        val vAvgKph = vAvgMps * 3.6
        val vInitKph = vAvgKph * 1.16

        tvSpeed.text = "%.1f KM/H".format(vInitKph)
        tvSpeed.setTextColor(Color.parseColor("#FF2222"))
        tvSubStats.text = "거리: %.2fm | 시간: %.3f초 | 착지점: (%.1f, %.1f)".format(distance, dtSec, realX, realY)
    }

    private fun toggleVideoControls(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        btnPrevFrame.visibility = v
        btnNextFrame.visibility = v
        btnSetImpact.visibility = v
        btnSetBounce.visibility = v
    }

    private fun updateVideoGuide() {
        val impStr = if (videoImpactMs >= 0) "${videoImpactMs}ms" else "미지정"
        tvGuide.text = "비디오 분석 중 - 현재: ${currentMs}ms | 임팩트: $impStr"
    }

    @SuppressLint("MissingPermission")
    private fun startAudioTrigger() {
        if (!allPermissionsGranted() || isAudioListening) return
        isAudioListening = true
        liveAudioScope.launch {
            val sampleRate = 44100
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            
            val buffer = ShortArray(bufferSize)
            audioRecord.startRecording()

            while (isAudioListening) {
                val read = audioRecord.read(buffer, 0, bufferSize)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) sum += buffer[i] * buffer[i]
                    val rms = sqrt(sum / read)
                    val db = 20 * log10(rms)

                    if (db > 80.0 && liveImpactTimeMs == -1L && homographyMatrix != null && !isVideoMode) {
                        liveImpactTimeMs = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            tvGuide.text = "⚡ 타구음 감지! 공이 튄 바닥 지점을 화면에서 터치하세요!"
                            tvSpeed.text = "TRACKING"
                            tvSpeed.setTextColor(Color.parseColor("#FFCC00"))
                        }
                        delay(2000)
                    }
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }
    }

    private fun stopAudioTrigger() {
        isAudioListening = false
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioTrigger()
        mediaPlayer?.release()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}

object HomographySolver {
    fun findHomography(src: List<Pair<Double, Double>>, dst: List<Pair<Double, Double>>): DoubleArray? {
        if (src.size != 4 || dst.size != 4) return null
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val u = src[i].first; val v = src[i].second
            val x = dst[i].first; val y = dst[i].second
            a[2 * i][0] = u; a[2 * i][1] = v; a[2 * i][2] = 1.0; a[2 * i][3] = 0.0; a[2 * i][4] = 0.0; a[2 * i][5] = 0.0
            a[2 * i][6] = -u * x; a[2 * i][7] = -v * x; a[2 * i][8] = x
            a[2 * i + 1][0] = 0.0; a[2 * i + 1][1] = 0.0; a[2 * i + 1][2] = 0.0; a[2 * i + 1][3] = u; a[2 * i + 1][4] = v; a[2 * i + 1][5] = 1.0
            a[2 * i + 1][6] = -u * y; a[2 * i + 1][7] = -v * y; a[2 * i + 1][8] = y
        }
        for (i in 0 until 8) {
            var maxRow = i
            for (k in i + 1 until 8) if (abs(a[k][i]) > abs(a[maxRow][i])) maxRow = k
            val temp = a[i]; a[i] = a[maxRow]; a[maxRow] = temp
            if (abs(a[i][i]) < 1e-12) return null
            for (k in i + 1 until 8) {
                val factor = a[k][i] / a[i][i]
                for (j in i until 9) a[k][j] -= factor * a[i][j]
            }
        }
        val h = DoubleArray(9); h[8] = 1.0
        for (i in 7 downTo 0) {
            var sum = 0.0
            for (j in i + 1 until 8) sum += a[i][j] * h[j]
            h[i] = (a[i][8] - sum) / a[i][i]
        }
        return h
    }

    fun transform(h: DoubleArray, u: Double, v: Double): Pair<Double, Double> {
        val denom = h[6] * u + h[7] * v + h[8]
        return Pair((h[0] * u + h[1] * v + h[2]) / denom, (h[3] * u + h[4] * v + h[5]) / denom)
    }
}
