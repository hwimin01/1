package com.ebookreader.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ebookreader.accessibility.databinding.OverlayPanelBinding
import com.ebookreader.accessibility.ocr.ImagePreprocessor
import com.ebookreader.accessibility.ocr.OcrPostProcessor
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "ebook_overlay_channel"
        const val ACTION_READ_NOW = "com.ebookreader.READ_NOW"
        const val ACTION_STOP = "com.ebookreader.STOP"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var binding: OverlayPanelBinding

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var ocrEngine: OcrEngine
    private lateinit var ttsManager: TtsManager
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var claudeApiService: ClaudeApiService

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // 현재 활성화된 전자책 앱 패키지
    private var currentEbookPackage = ""
    private var currentPageText = ""
    private var isReading = false

    // 음성 대화 상태
    private enum class ConversationState {
        IDLE,           // 대기
        LISTENING_CMD,  // 음성 명령 청취
        LISTENING_SEARCH, // 검색어 청취
        LISTENING_QA    // AI 질문 청취
    }
    private var conversationState = ConversationState.IDLE

    // AccessibilityService로부터 이벤트 수신
    private val ebookEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                EbookAccessibilityService.ACTION_EBOOK_DETECTED -> {
                    currentEbookPackage = intent.getStringExtra(EbookAccessibilityService.EXTRA_PACKAGE) ?: ""
                    val searchAvailable = intent.getBooleanExtra(EbookAccessibilityService.EXTRA_SEARCH_AVAILABLE, false)
                    onEbookAppActivated(currentEbookPackage, searchAvailable)
                }
                EbookAccessibilityService.ACTION_PAGE_CHANGED -> {
                    onPageChanged()
                }
                "com.ebookreader.SEARCH_INJECTED" -> {
                    val success = intent.getBooleanExtra("success", false)
                    val query = intent.getStringExtra(EbookAccessibilityService.EXTRA_QUERY) ?: ""
                    if (success) {
                        ttsManager.speak("\"$query\" 검색을 시작합니다.")
                    } else {
                        ttsManager.speak("검색창을 찾지 못했습니다. 앱의 검색창을 직접 열어주세요.")
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ocrEngine = OcrEngine()
        ttsManager = TtsManager(this)
        claudeApiService = ClaudeApiService(this)
        voiceCommandManager = VoiceCommandManager(this, ::onVoiceCommand)

        registerReceiver(ebookEventReceiver, IntentFilter().apply {
            addAction(EbookAccessibilityService.ACTION_EBOOK_DETECTED)
            addAction(EbookAccessibilityService.ACTION_PAGE_CHANGED)
            addAction("com.ebookreader.SEARCH_INJECTED")
        })

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_READ_NOW -> readCurrentScreen()
            ACTION_STOP -> stopSelf()
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: return START_NOT_STICKY
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                    ?: return START_NOT_STICKY

                setupMediaProjection(resultCode, resultData)
                showOverlay()
                voiceCommandManager.startListening()
                ttsManager.speak("전자책 보조 리더가 시작되었습니다. YES24, 알라딘, 교보문고 앱을 열면 자동으로 인식합니다.")
            }
        }
        return START_STICKY
    }

    // --- 앱 감지 콜백 ---

    private fun onEbookAppActivated(packageName: String, searchAvailable: Boolean) {
        val appName = when (packageName) {
            "com.yes24.ebooks" -> "예스24"
            "com.aladin.ebookviewer", "com.aladin.android" -> "알라딘"
            "com.kyobo.ebook", "com.kyobo.ebook2" -> "교보문고"
            else -> packageName
        }
        val msg = if (searchAvailable) {
            "$appName 앱을 감지했습니다. 읽기, 검색, 질문 기능을 사용할 수 있습니다."
        } else {
            "$appName 앱을 감지했습니다. 검색창을 열면 음성 검색도 가능합니다."
        }
        ttsManager.speak(msg)
    }

    private fun onPageChanged() {
        val autoRead = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getBoolean("auto_read_on_page_change", false)

        if (autoRead && !isReading) {
            handler.postDelayed({ readCurrentScreen() }, 500)
        }
    }

    // --- OCR 읽기 ---

    private fun readCurrentScreen() {
        serviceScope.launch {
            ttsManager.speak("화면을 분석하는 중입니다.")
            isReading = true

            val bitmap = withContext(Dispatchers.IO) {
                imageReader?.acquireLatestImage()?.use { image ->
                    ImageToBitmapConverter.convert(image)
                }
            } ?: run {
                ttsManager.speak("화면 캡처에 실패했습니다.")
                isReading = false
                return@launch
            }

            // 앱별 맞춤 전처리 후 OCR
            val processedBitmap = withContext(Dispatchers.Default) {
                val config = ImagePreprocessor.configFor(currentEbookPackage)
                ImagePreprocessor.process(bitmap, config)
            }

            val rawText = withContext(Dispatchers.IO) {
                ocrEngine.recognizeText(processedBitmap)
            }

            if (rawText.isBlank()) {
                ttsManager.speak("인식된 텍스트가 없습니다. 전자책 화면이 열려있는지 확인해주세요.")
                isReading = false
                return@launch
            }

            // 후처리 (UI 잔재 제거, 오인식 교정)
            currentPageText = OcrPostProcessor.process(rawText, currentEbookPackage)

            ttsManager.speak(currentPageText) {
                isReading = false
            }
        }
    }

    // --- 음성 명령 처리 ---

    private fun onVoiceCommand(command: String) {
        when (conversationState) {
            ConversationState.LISTENING_SEARCH -> {
                conversationState = ConversationState.IDLE
                performVoiceSearch(command)
            }
            ConversationState.LISTENING_QA -> {
                conversationState = ConversationState.IDLE
                answerQuestion(command)
            }
            ConversationState.IDLE, ConversationState.LISTENING_CMD -> {
                handleBaseCommand(command)
            }
        }
    }

    private fun handleBaseCommand(command: String) {
        when {
            // 읽기
            command.contains("읽어줘") || command.contains("읽어") -> readCurrentScreen()

            // 중지
            command.contains("멈춰") || command.contains("중지") || command.contains("스톱") -> {
                ttsManager.stop()
                isReading = false
            }

            // 속도 조절
            command.contains("빠르게") || command.contains("빨리") -> {
                ttsManager.setSpeechRate(1.6f)
                ttsManager.speak("빠른 속도로 설정했습니다.")
            }
            command.contains("천천히") || command.contains("느리게") -> {
                ttsManager.setSpeechRate(0.75f)
                ttsManager.speak("느린 속도로 설정했습니다.")
            }
            command.contains("보통") && command.contains("속도") -> {
                ttsManager.setSpeechRate(1.0f)
                ttsManager.speak("보통 속도로 설정했습니다.")
            }

            // 음성 검색 — "검색해줘", "찾아줘", "검색"
            command.contains("검색") || command.contains("찾아") || command.contains("찾아줘") -> {
                startVoiceSearch()
            }

            // AI 질문
            command.contains("질문") || command.contains("물어봐") || command.contains("설명") -> {
                startVoiceQuestion()
            }

            // 요약
            command.contains("요약") -> {
                summarizePage()
            }

            // 다시 읽기
            command.contains("다시") -> {
                if (currentPageText.isNotBlank()) {
                    ttsManager.speak(currentPageText)
                } else {
                    readCurrentScreen()
                }
            }

            // 종료
            command.contains("종료") || command.contains("끝내") -> stopSelf()
        }
    }

    // --- 음성 검색 플로우 ---

    /**
     * 사용자가 "검색해줘"라고 하면 검색어를 다시 물어본 뒤
     * AccessibilityService를 통해 앱 검색창에 주입한다.
     */
    private fun startVoiceSearch() {
        if (currentEbookPackage.isBlank()) {
            ttsManager.speak("먼저 YES24, 알라딘, 또는 교보문고 앱을 열어주세요.")
            return
        }
        ttsManager.stop()
        ttsManager.speak("검색어를 말씀해주세요.") {
            conversationState = ConversationState.LISTENING_SEARCH
            voiceCommandManager.startSingleQuery { query ->
                conversationState = ConversationState.IDLE
                if (query.isNotBlank()) performVoiceSearch(query)
            }
        }
    }

    private fun performVoiceSearch(query: String) {
        ttsManager.speak("\"$query\" 검색합니다.")
        // AccessibilityService에 검색 요청 전달
        sendBroadcast(Intent(EbookAccessibilityService.ACTION_VOICE_SEARCH).apply {
            setPackage(packageName)
            putExtra(EbookAccessibilityService.EXTRA_QUERY, query)
            putExtra(EbookAccessibilityService.EXTRA_PACKAGE, currentEbookPackage)
        })
    }

    // --- AI 질의응답 플로우 ---

    private fun startVoiceQuestion() {
        ttsManager.stop()
        ttsManager.speak("질문을 말씀해주세요.") {
            conversationState = ConversationState.LISTENING_QA
            voiceCommandManager.startSingleQuery { question ->
                conversationState = ConversationState.IDLE
                if (question.isNotBlank()) answerQuestion(question)
            }
        }
    }

    private fun answerQuestion(question: String) {
        if (currentPageText.isBlank()) {
            ttsManager.speak("먼저 화면 읽기를 실행해주세요.")
            return
        }
        serviceScope.launch {
            ttsManager.speak("잠시만요.")
            val answer = claudeApiService.askAboutContent(currentPageText, question)
            ttsManager.speak(answer)
        }
    }

    private fun summarizePage() {
        if (currentPageText.isBlank()) {
            ttsManager.speak("먼저 화면 읽기를 실행해주세요.")
            return
        }
        serviceScope.launch {
            ttsManager.speak("요약하는 중입니다.")
            val summary = claudeApiService.summarizePage(currentPageText)
            ttsManager.speak(summary)
        }
    }

    // --- 오버레이 UI ---

    private fun showOverlay() {
        if (overlayView != null) return
        val inflater = LayoutInflater.from(this)
        binding = OverlayPanelBinding.inflate(inflater)
        overlayView = binding.root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        windowManager.addView(overlayView, params)
        setupOverlayButtons()
    }

    private fun setupOverlayButtons() {
        binding.btnRead.setOnClickListener { readCurrentScreen() }
        binding.btnStop.setOnClickListener {
            ttsManager.stop()
            isReading = false
        }
        binding.btnAsk.setOnClickListener { startVoiceSearch() }
        binding.btnClose.setOnClickListener { stopSelf() }
        OverlayDragHelper(binding.root, windowManager)
    }

    // --- MediaProjection 설정 ---

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels,
            PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "EbookCapture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    // --- 알림 ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "전자책 보조 리더", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "OCR 화면 읽기 서비스" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("전자책 보조 리더 실행 중")
        .setContentText("YES24 / 알라딘 / 교보문고 지원")
        .setSmallIcon(android.R.drawable.ic_accessibility_action)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_media_play, "지금 읽기",
            PendingIntent.getService(
                this, 1,
                Intent(this, OverlayService::class.java).apply { action = ACTION_READ_NOW },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_delete, "종료",
            PendingIntent.getService(
                this, 2,
                Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterReceiver(ebookEventReceiver)
        overlayView?.let { windowManager.removeView(it) }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        ttsManager.shutdown()
        voiceCommandManager.destroy()
    }
}
