package com.ebookreader.accessibility

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceCommandManager(
    private val context: Context,
    private val onCommand: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var singleQueryCallback: ((String) -> Unit)? = null
    private var isContinuousListening = false

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            createRecognizer()
        } else {
            Log.e("VoiceCommandManager", "음성 인식 미지원 기기")
        }
    }

    private fun createRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return

                if (singleQueryCallback != null) {
                    singleQueryCallback?.invoke(text)
                    singleQueryCallback = null
                } else {
                    onCommand(text.lowercase())
                }

                // 연속 청취 모드이면 재시작
                if (isContinuousListening) {
                    startListeningInternal()
                }
            }

            override fun onError(error: Int) {
                Log.w("VoiceCommandManager", "음성 인식 오류: $error")
                if (isContinuousListening) {
                    // 네트워크 오류 등에서 잠시 후 재시도
                    speechRecognizer?.cancel()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isContinuousListening) startListeningInternal()
                    }, 1000)
                }
            }

            override fun onEndOfSpeech() {}
        })
    }

    fun startListening() {
        isContinuousListening = true
        startListeningInternal()
    }

    fun stopListening() {
        isContinuousListening = false
        speechRecognizer?.stopListening()
    }

    fun startSingleQuery(callback: (String) -> Unit) {
        singleQueryCallback = callback
        startListeningInternal()
    }

    private fun startListeningInternal() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    fun destroy() {
        isContinuousListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
