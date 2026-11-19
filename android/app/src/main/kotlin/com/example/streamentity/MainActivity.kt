package com.example.streamentity

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.streamentity.service.StreamEntityService

class MainActivity : ComponentActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private var customViewContainer: FrameLayout? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isServiceBound = false
    private var serviceInstance: StreamEntityService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StreamEntityService.LocalBinder
            serviceInstance = binder?.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            serviceInstance = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent unexpected crashes from killing the process
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("StreamEntityCrash", "UNCAUGHT EXCEPTION in ${thread.name}: ${throwable.message}", throwable)
        }

        // Keep screen on during active playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0D0D11")
        window.navigationBarColor = Color.parseColor("#0D0D11")

        startAndBindService()
        initLayout()
        setupBackNavigation()
    }

    private fun startAndBindService() {
        val intent = Intent(this, StreamEntityService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initLayout() {
        rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0D0D11"))
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0D0D11"))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback

                customViewContainer = FrameLayout(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.BLACK)
                    addView(view)
                }

                webView.visibility = View.GONE
                rootContainer.addView(customViewContainer)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                hideSystemUI()
            }

            override fun onHideCustomView() {
                if (customView == null) return

                showSystemUI()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                rootContainer.removeView(customViewContainer)
                customViewContainer = null
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }

            override fun getDefaultVideoPoster(): Bitmap {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("vlc://")) {
                    handleVlcIntent(url)
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && retryCount < 10) {
                    retryCount++
                    handler.postDelayed({
                        webView.loadUrl("http://127.0.0.1:3000/")
                    }, 400)
                }
            }
        }

        rootContainer.addView(webView)

        // Native Hardware-Accelerated Media3 ExoPlayer for MKV / 4K HEVC / HDR / Multi-Audio
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.w("StreamEntityPlayer", "ExoPlayer playback notice: ${error.errorCodeName} - ${error.message}")
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        runOnUiThread { closeNativePlayer() }
                    }
                }
            })
        }

        playerContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            player = exoPlayer
        }
        playerContainer.addView(playerView)

        // Sleek overlay top bar with Back / Close button
        val topOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(40, 40, 40, 0)
            }
        }

        val btnClose = TextView(this).apply {
            text = "✕  Back to Dashboard"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(36, 20, 36, 20)
            setOnClickListener {
                closeNativePlayer()
            }
        }
        topOverlay.addView(btnClose)
        playerContainer.addView(topOverlay)

        rootContainer.addView(playerContainer)

        // Native bridge for MKV hardware playback in index.html
        webView.addJavascriptInterface(AndroidBridge(), "StreamEntityNative")

        setContentView(rootContainer)

        // Load local server root
        handler.postDelayed({
            webView.loadUrl("http://127.0.0.1:3000/")
        }, 150)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun playMedia(streamUrl: String, title: String) {
            runOnUiThread {
                try {
                    val fullUrl = if (streamUrl.startsWith("/")) "http://127.0.0.1:3000$streamUrl" else streamUrl
                    val mediaItem = MediaItem.Builder()
                        .setUri(Uri.parse(fullUrl))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(title)
                                .build()
                        )
                        .build()

                    exoPlayer?.setMediaItem(mediaItem)
                    exoPlayer?.prepare()
                    exoPlayer?.play()

                    playerContainer.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Log.e("MainActivity", "Playback start error", e)
                }
            }
        }

        @JavascriptInterface
        fun pauseMedia() {
            runOnUiThread {
                try { exoPlayer?.pause() } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun resumeMedia() {
            runOnUiThread {
                try { exoPlayer?.play() } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun stopMedia() {
            runOnUiThread { closeNativePlayer() }
        }

        @JavascriptInterface
        fun isNativeApp(): Boolean = true
    }

    private fun closeNativePlayer() {
        try {
            exoPlayer?.pause()
            exoPlayer?.stop()
        } catch (e: Exception) {}
        playerContainer.visibility = View.GONE
    }

    private fun handleVlcIntent(rawUrl: String) {
        try {
            val streamUrl = rawUrl.replace("vlc://", "http://")
            val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(streamUrl), "video/*")
                setPackage("org.videolan.vlc")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(vlcIntent)
        } catch (e: Exception) {
            // Fallback to general media player chooser
            try {
                val streamUrl = rawUrl.replace("vlc://", "http://")
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(streamUrl), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(genericIntent, "Play Stream With..."))
            } catch (err: Exception) {
                Toast.makeText(this, "External media player not found. Streaming in StreamEntity.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerContainer.visibility == View.VISIBLE) {
                    closeNativePlayer()
                } else if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    moveTaskToBack(true)
                }
            }
        })
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onDestroy() {
        try {
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {}
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
