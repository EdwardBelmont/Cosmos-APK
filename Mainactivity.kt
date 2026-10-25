package com.belmont.cosmos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var mediaSession: MediaSession? = null
    private val CHANNEL_ID = "cosmos_playback_channel"

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "COSMOS_PREV" -> webView.evaluateJavascript("prevTrack();", null)
                "COSMOS_TOGGLE" -> webView.evaluateJavascript("togglePlay();", null)
                "COSMOS_NEXT" -> webView.evaluateJavascript("nextTrack();", null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#040109")
        window.navigationBarColor = android.graphics.Color.parseColor("#040109")

        setupMediaSessionAndChannel()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webChromeClient = WebChromeClient()
        // Servidor virtual interno para servir el audio y las carátulas de MediaStore con CORS habilitado (para que el FFT de acordes funcione al 100%)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.startsWith("https://cosmos.local/audio/")) {
                    val id = url.removePrefix("https://cosmos.local/audio/").toLongOrNull() ?: return null
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    return try {
                        val stream = contentResolver.openInputStream(contentUri)
                        val headers = mapOf("Access-Control-Allow-Origin" to "*")
                        WebResourceResponse("audio/mpeg", "UTF-8", 200, "OK", headers, stream)
                    } catch (e: Exception) { null }
                }
                if (url.startsWith("https://cosmos.local/albumart/")) {
                    val albumId = url.removePrefix("https://cosmos.local/albumart/").toLongOrNull() ?: return null
                    val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                    return try {
                        val stream = contentResolver.openInputStream(artUri)
                        val headers = mapOf("Access-Control-Allow-Origin" to "*")
                        WebResourceResponse("image/jpeg", "UTF-8", 200, "OK", headers, stream)
                    } catch (e: Exception) { null }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkPermissionsAndScan()
            }
        }

        webView.addJavascriptInterface(CosmosBridge(), "CosmosNative")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun setupMediaSessionAndChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "COSMOS Reproductor", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        mediaSession = MediaSession(this, "CosmosBelmontSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { runOnUiThread { webView.evaluateJavascript("playAudio();", null) } }
                override fun onPause() { runOnUiThread { webView.evaluateJavascript("pauseAudio();", null) } }
                override fun onSkipToNext() { runOnUiThread { webView.evaluateJavascript("nextTrack();", null) } }
                override fun onSkipToPrevious() { runOnUiThread { webView.evaluateJavascript("prevTrack();", null) } }
            })
            isActive = true
        }

        val filter = IntentFilter().apply {
            addAction("COSMOS_PREV")
            addAction("COSMOS_TOGGLE")
            addAction("COSMOS_NEXT")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaActionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(mediaActionReceiver, filter)
        }
    }

    private fun checkPermissionsAndScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            scanDeviceMusic()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        scanDeviceMusic()
    }

    private fun scanDeviceMusic() {
        Thread {
            val arr = JSONArray()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val obj = JSONObject().apply {
                        put("id", "ms_$id")
                        put("title", cursor.getString(titleCol) ?: "Pista")
                        put("artist", cursor.getString(artistCol) ?: "Edward Belmont")
                        put("album", cursor.getString(albumCol) ?: "Cosmos")
                        put("audioUrl", "https://cosmos.local/audio/$id")
                        put("cover", "https://cosmos.local/albumart/$albumId")
                    }
                    arr.put(obj)
                }
            }

            val jsonStr = arr.toString()
            runOnUiThread {
                webView.evaluateJavascript("window.onNativeLibraryLoaded(${JSONObject.quote(jsonStr)});", null)
            }
        }.start()
    }

    inner class CosmosBridge {
        @JavascriptInterface
        fun rescanMediaStore() {
            runOnUiThread { checkPermissionsAndScan() }
        }

        @JavascriptInterface
        fun updateNotification(title: String, artist: String, album: String, isPlaying: Boolean) {
            runOnUiThread { showMediaNotification(title, artist, album, isPlaying) }
        }
    }

    private fun showMediaNotification(title: String, artist: String, album: String, isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )

        val prevPending = PendingIntent.getBroadcast(this, 1, Intent("COSMOS_PREV"), PendingIntent.FLAG_IMMUTABLE)
        val togglePending = PendingIntent.getBroadcast(this, 2, Intent("COSMOS_TOGGLE"), PendingIntent.FLAG_IMMUTABLE)
        val nextPending = PendingIntent.getBroadcast(this, 3, Intent("COSMOS_NEXT"), PendingIntent.FLAG_IMMUTABLE)

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("$artist • $album")
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", prevPending)
            .addAction(playPauseIcon, if (isPlaying) "Pausa" else "Play", togglePending)
            .addAction(android.R.drawable.ic_media_next, "Siguiente", nextPending)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(777, builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mediaActionReceiver)
        mediaSession?.release()
    }
}
