package com.tennis.speedtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var videoView: VideoView
    private lateinit var tvSpeed: TextView
    private lateinit var tvGuide: TextView

    private lateinit var btnLoadVideo: Button
    private lateinit var btnPrevFrame: Button
    private lateinit var btnNextFrame: Button
    private lateinit var btnSetImpact: Button
    private lateinit var btnSetBounce: Button
    private lateinit var btnCalculate: Button

    // 영상 분석용 타임스탬프 (ms)
    private var impactMs: Int = -1
    private var bounceMs: Int = -1
    private var videoDurationMs: Int = 0

    // 갤러리 영상 선택 런처
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
        btnPrevFrame = findViewById(R.id.btnPrevFrame)
        btnNextFrame = findViewById(R.id.btnNextFrame)
        btnSetImpact = findViewById(R.id.btnSetImpact)
        btnSetBounce = findViewById(R.id.btnSetBounce)
        btnCalculate = findViewById(R.id.btnCalculate)

        setupButtons()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }
    }

    private fun setupButtons() {
        btnLoadVideo.setOnClickListener {
            // 스마트폰 갤러리에서 동영상 파일 선택 창 띄우기
            pickVideoLauncher.launch("video/*")
        }

        // 1프레임(약 16ms ~ 33ms) 단위 앞/뒤 이동
        btnPrevFrame.setOnClickListener {
            val current = videoView.currentPosition
            videoView.seekTo((current - 33).coerceAtLeast(0))
            updateGuideInfo()
        }

        btnNextFrame.setOnClickListener {
            val current = videoView.currentPosition
            videoView.seekTo((current + 33).coerceAtMost(videoDurationMs))
            updateGuideInfo()
        }

        btnSetImpact.setOnClickListener {
            impactMs = videoView.currentPosition
            Toast.makeText(this, "임팩트 시점: ${impactMs}ms", Toast.LENGTH_SHORT).show()
            updateGuideInfo()
        }

        btnSetBounce.setOnClickListener {
            bounceMs = videoView.currentPosition
            Toast.makeText(this, "바운드 시점: ${bounceMs}ms", Toast.LENGTH_SHORT).show()
            updateGuideInfo()
        }

        btnCalculate.setOnClickListener {
            calculateSpeedFromVideo()
        }
    }

    private fun loadSelectedVideo(uri: Uri) {
        // 카메라 프리뷰 숨기고 비디오 재생 뷰 활성화
        viewFinder.visibility = View.GONE
        videoView.visibility = View.VISIBLE

        // 비디오 제어 버튼들 표시
        btnPrevFrame.visibility = View.VISIBLE
        btnNextFrame.visibility = View.VISIBLE
        btnSetImpact.visibility = View.VISIBLE
        btnSetBounce.visibility = View.VISIBLE
        btnCalculate.visibility = View.VISIBLE
        btnLoadVideo.text = "다른 영상 선택"

        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            videoDurationMs = videoView.duration
            videoView.seekTo(1) // 첫 프레임 정지 표시
            tvGuide.text = "영상이 로드되었습니다. [◀1F / 1F▶]로 이동해 임팩트와 바운드를 지정하세요."
        }
    }

    private fun updateGuideInfo() {
        val cur = videoView.currentPosition
        tvGuide.text = "현재: ${cur}ms | 임팩트: ${if (impactMs >= 0) "${impactMs}ms" else "미지정"} | 바운드: ${if (bounceMs >= 0) "${bounceMs}ms" else "미지정"}"
    }

    private fun calculateSpeedFromVideo() {
        if (impactMs < 0 || bounceMs < 0) {
            Toast.makeText(this, "임팩트와 바운드 시점을 모두 지정해야 합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val dtSec = (bounceMs - impactMs) / 1000.0
        if (dtSec <= 0.1 || dtSec > 1.5) {
            Toast.makeText(this, "비행 시간(${dtSec}s)이 비정상적입니다. 다시 지정하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 검증된 물리 공식 적용: 거리 17.21m + 항력 보정계수 1.16
        val distance = 17.21
        val vAvgMps = distance / dtSec
        val vAvgKph = vAvgMps * 3.6
        val vInitKph = vAvgKph * 1.16 // 초속 환산

        tvSpeed.text = "%.1f KM/H".format(vInitKph)
        tvSpeed.setTextColor(Color.parseColor("#FF2222"))
        tvGuide.text = "계산 완료! 비행시간: %.3f초 | 환산초속: %.1f km/h".format(dtSec, vInitKph)
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
            } catch (exc: Exception) {
                // Ignore
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
