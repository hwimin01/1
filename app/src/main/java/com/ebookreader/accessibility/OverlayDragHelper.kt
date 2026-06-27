package com.ebookreader.accessibility

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class OverlayDragHelper(
    private val view: View,
    private val windowManager: WindowManager
) {
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f

    init {
        view.setOnTouchListener { _, event ->
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }
}
