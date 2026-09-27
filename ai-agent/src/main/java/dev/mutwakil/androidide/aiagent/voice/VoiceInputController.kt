/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.mutwakil.androidide.aiagent.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dev.mutwakil.androidide.aiagent.R

/**
 * Thin wrapper around [SpeechRecognizer] for pt-BR voice input.
 *
 * The caller is responsible for requesting [Manifest.permission.RECORD_AUDIO] at
 * runtime: check [needsPermission] first and request it (e.g. via an
 * `ActivityResultLauncher`) before calling [startListening].
 */
class VoiceInputController(context: Context) {

  private val appContext = context.applicationContext
  private var recognizer: SpeechRecognizer? = null
  private var onResult: ((String) -> Unit)? = null
  private var onError: ((String) -> Unit)? = null

  /** Returns true when the device has a speech recognition service available. */
  fun isAvailable(context: Context): Boolean =
    SpeechRecognizer.isRecognitionAvailable(context)

  /** Returns true when [Manifest.permission.RECORD_AUDIO] still needs to be requested. */
  fun needsPermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED

  /**
   * Starts listening for pt-BR speech. Results are delivered to [onResult];
   * failures (including an unavailable recognition service) to [onError].
   */
  fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
    this.onResult = onResult
    this.onError = onError

    if (!isAvailable(appContext)) {
      onError(appContext.getString(R.string.aiagent_voice_unavailable))
      return
    }
    if (needsPermission()) {
      onError(appContext.getString(R.string.aiagent_voice_permission_required))
      return
    }

    // Reset any previous session before starting a new one.
    stopListening()

    val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
    this.recognizer = recognizer
    recognizer.setRecognitionListener(createListener())
    recognizer.startListening(createIntent())
  }

  /** Stops listening and releases the underlying recognizer. Safe to call any time. */
  fun stopListening() {
    onResult = null
    onError = null
    recognizer?.let {
      try {
        it.stopListening()
      } catch (_: Exception) {
        // Best effort; the recognizer may already be idle.
      }
      it.destroy()
    }
    recognizer = null
  }

  /** Releases all resources. After this the controller must not be reused. */
  fun destroy() {
    stopListening()
  }

  private fun createIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
      )
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

  private fun createListener(): RecognitionListener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) = Unit
      override fun onBeginningOfSpeech() = Unit
      override fun onRmsChanged(rmsdB: Float) = Unit
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() = Unit
      override fun onPartialResults(partialResults: Bundle?) = Unit
      override fun onEvent(eventType: Int, params: Bundle?) = Unit

      override fun onResults(results: Bundle?) {
        val text = results
          ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          ?.firstOrNull { it.isNotBlank() }
        val resultCallback = onResult
        stopListening()
        if (text != null) {
          resultCallback?.invoke(text)
        } else {
          onError?.invoke(appContext.getString(R.string.aiagent_voice_error_no_match))
        }
      }

      override fun onError(error: Int) {
        val message = when (error) {
          SpeechRecognizer.ERROR_NETWORK,
          SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
          SpeechRecognizer.ERROR_SERVER ->
            appContext.getString(R.string.aiagent_voice_error_network)
          SpeechRecognizer.ERROR_NO_MATCH,
          SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
          SpeechRecognizer.ERROR_AUDIO ->
            appContext.getString(R.string.aiagent_voice_error_no_match)
          SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            appContext.getString(R.string.aiagent_voice_error_busy)
          SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            appContext.getString(R.string.aiagent_voice_permission_required)
          else -> appContext.getString(R.string.aiagent_voice_error_generic)
        }
        val errorCallback = onError
        stopListening()
        errorCallback?.invoke(message)
      }
    }
}
