package com.ebookreader.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ebookreader.accessibility.search.SearchAdapterFactory

/**
 * 전자책 앱 감지 + 검색창 제어 접근성 서비스.
 *
 * 주요 역할:
 * 1. YES24 / 알라딘 / 교보문고 앱 전환 감지
 * 2. 검색창 존재 여부 실시간 감시
 * 3. 음성 검색 결과를 앱 검색창에 주입
 * 4. 페이지 변경 감지 (자동 읽기 지원)
 */
class EbookAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_EBOOK_DETECTED = "com.ebookreader.EBOOK_DETECTED"
        const val ACTION_VOICE_SEARCH = "com.ebookreader.VOICE_SEARCH"
        const val ACTION_PAGE_CHANGED = "com.ebookreader.PAGE_CHANGED"
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_QUERY = "search_query"
        const val EXTRA_SEARCH_AVAILABLE = "search_available"
        const val TAG = "EbookA11y"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentPackage = ""
    private var lastContentSignature = ""   // 페이지 변경 감지용 콘텐츠 해시

    // 음성 검색 대기 상태
    private var pendingVoiceQuery: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "접근성 서비스 연결됨")
        registerVoiceSearchReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        val adapter = SearchAdapterFactory.get(pkg)

        when (event.eventType) {

            // 앱 전환 감지
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != currentPackage) {
                    currentPackage = pkg
                    if (adapter != null) {
                        Log.d(TAG, "전자책 앱 감지: $pkg")
                        notifyEbookDetected(pkg)
                        checkSearchAvailability(pkg)
                    }
                }
            }

            // 화면 콘텐츠 변경 (페이지 넘김 감지)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (adapter == null) return
                handler.removeCallbacksAndMessages("page_check")
                handler.postDelayed({
                    detectPageChange(event.source)
                    // 대기 중인 음성 검색이 있으면 지금 실행
                    pendingVoiceQuery?.let { query ->
                        pendingVoiceQuery = null
                        injectVoiceSearch(query)
                    }
                }, 300)
            }

            // 검색창 포커스 변경
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (adapter == null) return
                val node = event.source ?: return
                if (node.className?.contains("EditText") == true) {
                    Log.d(TAG, "EditText 포커스: ${node.viewIdResourceName}")
                    notifySearchFocused(pkg, node)
                }
            }

            else -> {}
        }
    }

    /**
     * OverlayService에서 음성 검색 요청을 수신하여 앱 검색창에 주입.
     */
    private fun injectVoiceSearch(query: String) {
        val adapter = SearchAdapterFactory.get(currentPackage) ?: run {
            Log.w(TAG, "지원하지 않는 앱: $currentPackage")
            return
        }

        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow null — 검색 주입 대기")
            pendingVoiceQuery = query
            return
        }

        val success = adapter.submitSearch(root, query)
        Log.d(TAG, "검색 주입 결과: $success (query=$query, app=$currentPackage)")

        // 결과를 OverlayService에 브로드캐스트
        sendLocalBroadcast(Intent("com.ebookreader.SEARCH_INJECTED").apply {
            putExtra("success", success)
            putExtra(EXTRA_QUERY, query)
        })
    }

    private fun detectPageChange(source: AccessibilityNodeInfo?) {
        source ?: return
        // 현재 화면의 모든 텍스트를 수집해 간단한 해시로 변경 감지
        val signature = buildContentSignature(source)
        if (signature != lastContentSignature && signature.isNotBlank()) {
            lastContentSignature = signature
            sendLocalBroadcast(Intent(ACTION_PAGE_CHANGED).apply {
                putExtra(EXTRA_PACKAGE, currentPackage)
            })
        }
    }

    private fun buildContentSignature(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectText(node, sb, maxChars = 200)
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, maxChars: Int) {
        if (sb.length >= maxChars) return
        node.text?.let { sb.append(it) }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i) ?: continue, sb, maxChars)
        }
    }

    private fun checkSearchAvailability(packageName: String) {
        handler.postDelayed({
            val adapter = SearchAdapterFactory.get(packageName) ?: return@postDelayed
            val root = rootInActiveWindow ?: return@postDelayed
            val available = adapter.findSearchNode(root) != null
            sendLocalBroadcast(Intent(ACTION_EBOOK_DETECTED).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_SEARCH_AVAILABLE, available)
            })
        }, 500)
    }

    private fun notifyEbookDetected(packageName: String) {
        sendLocalBroadcast(Intent(ACTION_EBOOK_DETECTED).apply {
            putExtra(EXTRA_PACKAGE, packageName)
        })
    }

    private fun notifySearchFocused(packageName: String, node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        sendLocalBroadcast(Intent("com.ebookreader.SEARCH_FOCUSED").apply {
            putExtra(EXTRA_PACKAGE, packageName)
        })
    }

    private fun sendLocalBroadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun registerVoiceSearchReceiver() {
        // OverlayService가 음성 인식 결과를 ACTION_VOICE_SEARCH로 보내면 여기서 수신
        // (실제 BroadcastReceiver는 OverlayService에서 등록)
    }

    override fun onInterrupt() {
        Log.w(TAG, "접근성 서비스 중단")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
