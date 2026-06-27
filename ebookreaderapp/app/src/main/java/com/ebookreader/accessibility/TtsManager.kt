package com.ebookreader.accessibility

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.Locale
import java.util.UUID

class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val pendingQueue = mutableListOf<Pair<String, (() -> Unit)?>>()
    private var onDoneCallback: (() -> Unit)? = null

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                val locale = Locale.KOREAN
                tts?.language = locale
                tts?.setSpeechRate(prefs.getFloat("speech_rate", 1.0f))
                tts?.setPitch(prefs.getFloat("speech_pitch", 1.0f))
                setupProgressListener()
                flushQueue()
            } else {
                Log.e("TtsManager", "TTS 초기화 실패: $status")
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "FINAL") {
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }
            }
            override fun onError(utteranceId: String?) {}
        })
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isInitialized) {
            pendingQueue.add(Pair(text, onDone))
            return
        }
        tts?.stop()
        onDoneCallback = onDone

        // 긴 텍스트를 문장 단위로 나눠서 발화 (TTS 최대 길이 제한 우회)
        val sentences = splitIntoSentences(text)
        sentences.forEachIndexed { index, sentence ->
            val utteranceId = if (index == sentences.lastIndex) "FINAL" else UUID.randomUUID().toString()
            tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        // 문장 부호 기준으로 분리, 최대 500자 단위
        val maxLen = 500
        if (text.length <= maxLen) return listOf(text)

        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxLen) {
            val cutPoint = remaining.substring(0, maxLen).lastIndexOf('.')
                .takeIf { it > 0 } ?: remaining.substring(0, maxLen).lastIndexOf(' ')
                .takeIf { it > 0 } ?: maxLen
            result.add(remaining.substring(0, cutPoint + 1).trim())
            remaining = remaining.substring(cutPoint + 1).trim()
        }
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }

    fun stop() {
        tts?.stop()
        onDoneCallback = null
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
        prefs.edit().putFloat("speech_rate", rate).apply()
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
        prefs.edit().putFloat("speech_pitch", pitch).apply()
    }

    private fun flushQueue() {
        pendingQueue.forEach { (text, cb) -> speak(text, cb) }
        pendingQueue.clear()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
