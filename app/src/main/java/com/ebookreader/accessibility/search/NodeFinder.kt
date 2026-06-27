package com.ebookreader.accessibility.search

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityNodeInfo 트리 탐색 유틸리티.
 *
 * 전자책 앱들은 검색 EditText에 리소스 ID를 붙이지 않거나
 * 난독화로 ID가 달라질 수 있어 다중 전략으로 탐색한다.
 */
object NodeFinder {

    /** 리소스 ID로 탐색 (가장 빠름, 버전마다 ID가 바뀔 수 있음) */
    fun byViewId(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        root ?: return null
        val results = root.findAccessibilityNodeInfosByViewId(viewId)
        return results.firstOrNull()
    }

    /** 여러 후보 ID를 순서대로 시도 */
    fun byViewIds(root: AccessibilityNodeInfo?, vararg viewIds: String): AccessibilityNodeInfo? {
        for (id in viewIds) {
            val node = byViewId(root, id)
            if (node != null) return node
        }
        return null
    }

    /** 힌트 텍스트(플레이스홀더)로 탐색 */
    fun byHintText(root: AccessibilityNodeInfo?, vararg hints: String): AccessibilityNodeInfo? {
        return findInTree(root) { node ->
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            hints.any { hint.contains(it.lowercase()) }
        }
    }

    /** className이 EditText이고 focusable인 노드 탐색 */
    fun editTextNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        collectFromTree(root) { node ->
            if (node.className?.contains("EditText") == true && node.isFocusable) {
                results.add(node)
            }
        }
        return results
    }

    /** 텍스트가 들어있는 노드 탐색 */
    fun byText(root: AccessibilityNodeInfo?, vararg texts: String): AccessibilityNodeInfo? {
        return findInTree(root) { node ->
            val t = node.text?.toString()?.lowercase() ?: ""
            texts.any { t.contains(it.lowercase()) }
        }
    }

    /** 노드에 텍스트를 직접 입력 (ACTION_SET_TEXT) */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 노드에서 IME 액션(검색/완료) 실행 */
    fun performImeAction(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
    }

    /** 노드 포커스 요청 */
    fun requestFocus(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) ||
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // --- 내부 트리 순회 ---

    private fun findInTree(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val found = findInTree(node.getChild(i), predicate)
            if (found != null) return found
        }
        return null
    }

    private fun collectFromTree(
        node: AccessibilityNodeInfo?,
        action: (AccessibilityNodeInfo) -> Unit
    ) {
        node ?: return
        action(node)
        for (i in 0 until node.childCount) {
            collectFromTree(node.getChild(i), action)
        }
    }
}
