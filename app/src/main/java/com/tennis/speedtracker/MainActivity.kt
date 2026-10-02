package com.tennis.speedtracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var videoView: VideoView
    private lateinit var touchOverlay: View
    private lateinit var tvGuide: TextView
    private lateinit var tvInitSpeed: TextView
    private lateinit var tvSubStats: TextView

    private lateinit var btnResetCalib: Button
    private lateinit var btnLoadVideo: Button
    private lateinit var btnPrevFrame: Button
    private lateinit var btnNextFrame: Button
    private lateinit var btnSetImpact: Button
    private lateinit var btnSetBounce: Button

    // 코트 캘리브레이션 4개 픽셀 좌표
    private val calibPixels = mutableListOf<Pair<Double, Double>>()
    private var homographyMatrix: DoubleArray? = null

    // 실제 국제 테니스 코트 기준 좌표 (단위: 미터)
    // 원점: 서버 쪽 베이스라인 중앙 (0, 0)
    private val realCourtPoints = listOf(
        Pair(-4.115, 0.0),    // 1. 베이스라인 좌측 코너
        Pair(4.115, 0.0),     // 2. 베이스라인 우측 코너
        Pair(-4.115, 18.29),  // 3. 반대편 서비스라인 좌측
        Pair(4.115, 18.29)    // 4. 반대편 서비스라인 우측
    )

    // 측정 상태 변수
    private var isSelectingBounce = false
    private var impactTimeMs: Long = -1L
    private var bounceTimeMs: Long = -1L
    private var videoDurationMs: Int = 0
    private var isVideoMode = false

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadVideoFile(it) }
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
        tvInitSpeed = findViewById(R.id.tvInitSpeed)
        tvSubStats = findViewById(R.id.tvSubStats)

        btnResetCalib = findViewById(R.id.btnResetCalib)
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
                handleTouch(event.x.toDouble(), event.y.toDouble())
            }
            true
        }

        btnResetCalib.setOnClickListener {
            calibPixels.clear()
            homographyMatrix = null
            isSelectingBounce = false
            tvGuide.text = "1단계: 내 쪽 베이스라인 [좌측 코너]를 터치하세요"
            tvInitSpeed.text = "--- KM/H"
            tvSubStats.text = "코트 기준점 4개를 먼저 등록해주세요"
            Toast.makeText(this, "코트 캘리브레이션을 초기화합니다.", Toast.LENGTH_SHORT).show()
        }

        btnLoadVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        // 비디오 모드 프레임 탐색
        btnPrevFrame.setOnClickListener {
            val cur = videoView.currentPosition
            videoView.seekTo((cur - 33).coerceAtLeast(0))
            updateVideoGuide()
        }

        btnNextFrame.setOnClickListener {
            val cur = videoView.currentPosition
            videoView.seekTo((cur + 33).coerceAtMost(videoDurationMs))
            updateVideoGuide()
        }

        btnSetImpact.setOnClickListener {
            impactTimeMs = videoView.currentPosition.toLong()
            Toast.makeText(this, "임팩트 설정: ${impactTimeMs}ms", Toast.LENGTH_SHORT).show()
            updateVideoGuide()
        }

        btnSetBounce.setOnClickListener {
            bounceTimeMs = videoView.currentPosition.toLong()
            isSelectingBounce = true
            tvGuide.text = "화면에서 [공이 바닥에 튄 위치]를 터치하세요!"
            Toast.makeText(this, "화면의 바운드 지점을 클릭하세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTouch(x: Double, y: Double) {
        // 1. 코트 캘리브레이션 4개 점 수집
        if (calibPixels.size < 4) {
            calibPixels.add(Pair(x, y))
            when (calibPixels.size) {
                1 -> tvGuide.text = "2단계: 내 쪽 베이스라인 [우측 코너]를 터치하세요"
                2 -> tvGuide.text = "3단계: 반대편 서비스라인 [좌측 교차점]을 터치하세요"
                3 -> tvGuide.text = "4단계: 반대편 서비스라인 [우측 교차점]을 터치하세요"
                4 -> {
                    // 4점 수집 즉시 호모그래피 행렬 연산
                    homographyMatrix = HomographySolver.findHomography(calibPixels, realCourtPoints)
                    if (homographyMatrix != null) {
                        tvGuide.text = "코트 캘리브레이션 완료! 이제 서브 구속을 측정할 수 있습니다."
                        Toast.makeText(this, "호모그래피 행렬 연산 성공!", Toast.LENGTH_SHORT).show()
                    } else {
                        tvGuide.text = "오류: 기준점이 일직선상에 있습니다. [코트 재설정]을 누르세요."
                        calibPixels.clear()
                    }
                }
            }
            return
        }

        // 2. 바운드 지점 클릭 처리 및 동적 구속 연산
        if (isSelectingBounce || !isVideoMode) {
            val H = homographyMatrix
            if (H == null) {
                Toast.makeText(this, "먼저 코트 4개 지점을 터치해 캘리브레이션하세요.", Toast.LENGTH_SHORT).show()
                return
            }

            // 바운드 픽셀 -> 실제 코트 지면 좌표 (X, Y) 역변환
            val (realX, realY) = HomographySolver.transform(H, x, y)

            // 비행 시간(dt) 산출
            val dtSec = if (isVideoMode) {
                if (impactTimeMs < 0 || bounceTimeMs < 0 || bounceTimeMs <= impactTimeMs) {
                    Toast.makeText(this, "임팩트와 바운드 프레임을 먼저 바르게 지정하세요.", Toast.LENGTH_SHORT).show()
                    return
                }
                (bounceTimeMs - impactTimeMs) / 1000.0
            } else {
                // 라이브 터치 모드 간이 타임스탬프 (기본 0.45초 가정 또는 연동)
                0.45
            }

            // 3. 실제 3차원 유클리드 비행 거리 동적 계산
            // 서버 타구 시작 위치: 중앙(0.0), 코트안쪽(0.5m), 타구높이(2.70m)
            val impactX = 0.0
            val impactY = 0.5
            val impactZ = 2.70

            val distanceM = sqrt(
                (realX - impactX) * (realX - impactX) +
                (realY - impactY) * (realY - impactY) +
                (0.0 - impactZ) * (0.0 - impactZ)
            )

            // 4. 구간 평균 속도 및 공기역학 항력 보정 초속 산출
            val vAvgMps = distanceM / dtSec
            val vAvgKph = vAvgMps * 3.6
            val vInitKph = vAvgKph * 1.16  // 항력 보정계수 (초속)

            // 5. 결과 HUD 표시
            tvInitSpeed.text = "%.1f KM/H".format(vInitKph)
            tvInitSpeed.setTextColor(Color.parseColor("#FF2222"))
            tvSubStats.text = "평균: %.1f km/h | 거리: %.2fm | 시간: %.3fs | 착지: (%.1f, %.1f)".format(
                vAvgKph, distanceM, dtSec, realX, realY
            )
            tvGuide.text = "계산 완료! 실측 비행거리: %.2fm (네트 기준 %.2fm 지점 착지)".format(distanceM, realY - 11.89)

            isSelectingBounce = false
        }
    }

    private fun loadVideoFile(uri: Uri) {
        isVideoMode = true
        viewFinder.visibility = View.GONE
        videoView.visibility = View.VISIBLE

        btnPrevFrame.visibility = View.VISIBLE
        btnNextFrame.visibility = View.VISIBLE
        btnSetImpact.visibility = View.VISIBLE
        btnSetBounce.visibility = View.VISIBLE
        btnLoadVideo.text = "다른 영상"

        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener {
            videoDurationMs = videoView.duration
            videoView.seekTo(1)
            tvGuide.text = "코트 기준점 4개를 먼저 터치한 뒤, [임팩트]와 [바운드]를 지정하세요."
        }
    }

    private fun updateVideoGuide() {
        val cur = videoView.currentPosition
        tvGuide.text = "현재: ${cur}ms | 임팩트: ${if (impactTimeMs >= 0) "${impactTimeMs}ms" else "미지정"} | 바운드: ${if (bounceTimeMs >= 0) "${bounceTimeMs}ms" else "미지정"}"
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
            } catch (e: Exception) {
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

/**
 * 3x3 평면 호모그래피 행렬 자체 연산기 (가우스-요르단 소거법)
 */
object HomographySolver {
    fun findHomography(
        src: List<Pair<Double, Double>>,
        dst: List<Pair<Double, Double>>
    ): DoubleArray? {
        if (src.size != 4 || dst.size != 4) return null
        val a = Array(8) { DoubleArray(9) }

        for (i in 0 until 4) {
            val u = src[i].first
            val v = src[i].second
            val x = dst[i].first
            val y = dst[i].second

            // Row 2i
            a[2 * i][0] = u;     a[2 * i][1] = v;     a[2 * i][2] = 1.0
            a[2 * i][3] = 0.0;   a[2 * i][4] = 0.0;   a[2 * i][5] = 0.0
            a[2 * i][6] = -u * x; a[2 * i][7] = -v * x; a[2 * i][8] = x

            // Row 2i + 1
            a[2 * i + 1][0] = 0.0;   a[2 * i + 1][1] = 0.0;   a[2 * i + 1][2] = 0.0
            a[2 * i + 1][3] = u;     a[2 * i + 1][4] = v;     a[2 * i + 1][5] = 1.0
            a[2 * i + 1][6] = -u * y; a[2 * i + 1][7] = -v * y; a[2 * i + 1][8] = y
        }

        // 가우스 소거법 (Partial Pivoting)
        for (i in 0 until 8) {
            var maxRow = i
            for (k in i + 1 until 8) {
                if (abs(a[k][i]) > abs(a[maxRow][i])) maxRow = k
            }
            val temp = a[i]
            a[i] = a[maxRow]
            a[maxRow] = temp

            if (abs(a[i][i]) < 1e-12) return null // 특이 행렬

            for (k in i + 1 until 8) {
                val factor = a[k][i] / a[i][i]
                for (j in i until 9) a[k][j] -= factor * a[i][j]
            }
        }

        val h = DoubleArray(9)
        h[8] = 1.0
        for (i in 7 downTo 0) {
            var sum = 0.0
            for (j in i + 1 until 8) sum += a[i][j] * h[j]
            h[i] = (a[i][8] - sum) / a[i][i]
        }
        return h
    }

    fun transform(h: DoubleArray, u: Double, v: Double): Pair<Double, Double> {
        val denom = h[6] * u + h[7] * v + h[8]
        val x = (h[0] * u + h[1] * v + h[2]) / denom
        val y = (h[3] * u + h[4] * v + h[5]) / denom
        return Pair(x, y)
    }
}
