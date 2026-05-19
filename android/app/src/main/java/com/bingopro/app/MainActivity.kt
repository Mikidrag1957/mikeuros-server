package com.bingopro.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tts = TextToSpeech(this, this)

        webView = WebView(this)
        setContentView(webView)

        // Apply system bar insets so WebView content isn't hidden behind nav/status bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, webView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        webView.setOnApplyWindowInsetsListener { v, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        configureWebView()
        registerBridges()

        webView.loadUrl("file:///android_asset/bingo.html")
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
        if (ttsReady) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun configureWebView() {
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        settings.mediaPlaybackRequiresUserGesture = false

        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.blockNetworkLoads = false

        settings.cacheMode = WebSettings.LOAD_DEFAULT

        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        settings.textZoom = 100
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NARROW_COLUMNS

        webView.isClickable = true
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        settings.userAgentString = settings.userAgentString
            .replace("Android", "BingoPRO-Android")
            .replace("Mobile", "BingoPRO")

        webView.webChromeClient = object : WebChromeClient() {}

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAndroidConfig()
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
    }

    private fun registerBridges() {
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.addJavascriptInterface(TTSBridge(), "TTSBridge")
    }

    private fun injectAndroidConfig() {
        webView.evaluateJavascript("""
        (function() {
            window.__IS_ANDROID_APP = true;

            function _getLang() {
                try {
                    return (typeof cfg !== 'undefined' && cfg.vozLang || 'es-ES').split('-')[0];
                } catch(e) { return 'es'; }
            }

            // ── Override hablarNumero ──────────────────────────────────────
            var _origHablarNumero = window.hablarNumero;
            window.hablarNumero = function(num) {
                var lang = _getLang();
                if (window.TTSBridge && window.TTSBridge.isReady()) {
                    try {
                        window.TTSBridge.speak(String(num), lang);
                        window.AndroidBridge.playBallSound();
                        return;
                    } catch(e) {}
                }
                if (_origHablarNumero) _origHablarNumero(num);
            };

            // ── Override hablarTexto ───────────────────────────────────────
            var _origHablarTexto = window.hablarTexto;
            window.hablarTexto = function(texto, rate, pitch, _fromIntro) {
                if (_fromIntro && window.cdSaltado) return;
                if (window.globalMuted) return;
                if (window.TTSBridge && window.TTSBridge.isReady()) {
                    try {
                        if (pitch && pitch >= 1.3) {
                            window.TTSBridge.speakPitched(texto, _getLang(), pitch);
                        } else {
                            window.TTSBridge.speak(texto, _getLang());
                        }
                        return;
                    } catch(e) {}
                }
                if (_origHablarTexto) _origHablarTexto(texto, rate, pitch, _fromIntro);
            };

            // ── Override setLang ───────────────────────────────────────────
            var _origSetLang = window.setLang;
            window.setLang = function(lang) {
                if (_origSetLang) _origSetLang(lang);
                try { window.TTSBridge.setLanguage(_getLang()); } catch(e) {}
            };

            // ── Override cantarBingo / cantarLinea ────────────────────────
            var _origCantarBingo = window.cantarBingo;
            window.cantarBingo = function() {
                if (_origCantarBingo) _origCantarBingo();
                try {
                    var lc = _getLang();
                    var msg = lc === 'en' ? 'BINGO!' : (lc === 'sk' ? 'BINGO!' : '¡BINGO!');
                    window.AndroidBridge.playBingoSound();
                    window.TTSBridge.speakPitched(msg, lc, 1.6);
                } catch(e) {}
            };

            var _origCantarLinea = window.cantarLinea;
            window.cantarLinea = function() {
                if (_origCantarLinea) _origCantarLinea();
                try {
                    var lc = _getLang();
                    var msg = lc === 'en' ? 'LINE!' : (lc === 'sk' ? 'LINIA!' : '¡LÍNEA!');
                    window.AndroidBridge.playLineSound();
                    window.TTSBridge.speakPitched(msg, lc, 1.5);
                } catch(e) {}
            };

            // ── Override playEffect for native sound tones ────────────────
            var _origPlayEffect = window.playEffect;
            window.playEffect = function(idx) {
                if (window.globalMuted) return;
                var fx = window.fxConfig && window.fxConfig[idx];
                if (!fx) return;
                if (fx.dataURL) { if (_origPlayEffect) _origPlayEffect(idx); return; }
                if (fx.archivo && fx.archivo.startsWith('data:')) { if (_origPlayEffect) _origPlayEffect(idx); return; }
                try {
                    if (idx === 7 || idx === 10) { window.AndroidBridge.playBingoSound(); return; }
                    if (idx === 1 || idx === 4 || idx === 5 || idx === 6) { window.AndroidBridge.playLineSound(); return; }
                } catch(e) {}
                if (_origPlayEffect) _origPlayEffect(idx);
            };

            // ── Override sacarBola ─────────────────────────────────────────
            var _origSacarBola = window.sacarBola;
            window.sacarBola = function() {
                return _origSacarBola ? _origSacarBola() : false;
            };

            // ── Override toggleMute ───────────────────────────────────────
            var _origToggleMute = window.toggleMute;
            window.toggleMute = function() {
                if (_origToggleMute) _origToggleMute();
                if (window.globalMuted) {
                    try { window.TTSBridge.stop(); } catch(e) {}
                }
            };

            // ── Stop TTS when countdown is skipped ────────────────────────
            var _origSaltarCuentaAtras = window.saltarCuentaAtras;
            window.saltarCuentaAtras = function() {
                if (_origSaltarCuentaAtras) _origSaltarCuentaAtras();
                try { window.TTSBridge.stop(); } catch(e) {}
            };

            // ── Speak welcome greeting immediately via TTS ───────────────
            window._bwSpoken = false;
            var _origIniciarCuentaAtras = window.iniciarCuentaAtras;
            window.iniciarCuentaAtras = function() {
                if (_origIniciarCuentaAtras) _origIniciarCuentaAtras();
                if (!window._bwSpoken && window.TTSBridge && window.TTSBridge.isReady()) {
                    try {
                        var _txt = (typeof t === 'function' ? t('speech_welcome') : 'Bienvenidos al Bingo');
                        window.TTSBridge.speakPitched(_txt, _getLang(), 1.3);
                        window._bwSpoken = true;
                    } catch(e) {}
                }
            };

            console.log('[BingoPRO] Native TTS + sound bridges injected');
        })();
        """.trimIndent(), null)
    }

    // ── GENERATE SINE WAVE TONE ──────────────────────────────────────────
    private fun playTone(frequencyHz: Int, durationMs: Int) {
        try {
            val sampleRate = 22050
            val numSamples = (sampleRate * durationMs / 1000)
            val samples = ShortArray(numSamples)
            val amplitude = 16000
            for (i in 0 until numSamples) {
                val angle = 2.0 * PI * i / (sampleRate.toDouble() / frequencyHz)
                samples[i] = (amplitude * sin(angle)).toInt().toShort()
            }
            val audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                numSamples * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack.write(samples, 0, numSamples)
            audioTrack.play()
            try { Thread.sleep(durationMs.toLong() + 30) } catch (_: Exception) {}
            audioTrack.stop()
            audioTrack.release()
        } catch (_: Exception) {}
    }

    private fun getLocaleForLang(lang: String): Locale {
        return when (lang.lowercase().take(2)) {
            "en" -> Locale.ENGLISH
            "sk" -> Locale.forLanguageTag("sk")
            "ca" -> Locale("ca", "ES")
            "fr" -> Locale.FRENCH
            "it" -> Locale.ITALIAN
            else -> Locale("es", "ES")
        }
    }

    // ── TTS BRIDGE ──────────────────────────────────────────────────────
    inner class TTSBridge {

        @JavascriptInterface
        fun isReady(): Boolean = ttsReady

        @JavascriptInterface
        fun checkLang(lang: String): String {
            if (!ttsReady || tts == null) return "tts_not_ready"
            val locale = getLocaleForLang(lang)
            val result = tts?.setLanguage(locale) ?: -3
            return when (result) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE -> "ok($lang)"
                TextToSpeech.LANG_AVAILABLE -> "lang_ok($lang)"
                TextToSpeech.LANG_MISSING_DATA -> "no_data($lang)"
                TextToSpeech.LANG_NOT_SUPPORTED -> "unsupported($lang)"
                else -> "unknown($result)"
            }
        }

        @JavascriptInterface
        fun speak(text: String, lang: String) {
            if (!ttsReady || tts == null) return
            val locale = getLocaleForLang(lang)
            tts?.setLanguage(locale)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ball_utterance")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }

        @JavascriptInterface
        fun speakPitched(text: String, lang: String, pitch: Float) {
            if (!ttsReady || tts == null) return
            val locale = getLocaleForLang(lang)
            tts?.setLanguage(locale)
            tts?.setPitch(pitch)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ball_utterance")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
            tts?.setPitch(1.0f)
        }

        @JavascriptInterface
        fun setLanguage(lang: String) {
            if (tts == null) return
            tts?.setLanguage(getLocaleForLang(lang))
        }

        @JavascriptInterface
        fun stop() {
            tts?.stop()
        }
    }

    // ── ANDROID BRIDGE ──────────────────────────────────────────────────
    inner class AndroidBridge {

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun showLongToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun vibrate(pattern: String) {
            try {
                val parts = pattern.split(",").map { it.trim().toLong() }
                val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        android.os.VibrationEffect.createWaveform(parts.toLongArray(), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(parts.toLongArray(), -1)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun playBallSound() {
            playTone(880, 150)
        }

        @JavascriptInterface
        fun playBingoSound() {
            playTone(1200, 200)
            try { Thread.sleep(150) } catch (_: Exception) {}
            playTone(1500, 300)
        }

        @JavascriptInterface
        fun playLineSound() {
            playTone(1000, 100)
            try { Thread.sleep(100) } catch (_: Exception) {}
            playTone(1200, 100)
        }

        @JavascriptInterface
        fun isApp(): Boolean = true

        @JavascriptInterface
        fun getPlatformVersion(): String = "Android ${Build.VERSION.SDK_INT}"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        webView.destroy()
        super.onDestroy()
    }
}
