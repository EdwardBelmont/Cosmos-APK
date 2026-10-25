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

    // Crear un diseño personalizado estilo Spotify/Suno con tonos púrpuras y estilo Glassmorphism
    val notificationLayout = android.widget.RemoteViews(packageName, android.R.layout.notification_template_media).apply {
        // Forzar colores y diseño inmersivo oscuro acorde a la identidad visual de COSMOS
        setTextViewText(android.R.id.title, title)
        setTextViewText(android.R.id.text1, "$artist • $album (COSMOS)")
        setImageViewResource(android.R.id.button1, if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(title)
        .setContentText("$artist • $album")
        .setSubtext("COSMOS • Belmont Edition")
        .setOngoing(isPlaying)
        .setAutoCancel(false)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setColor(android.graphics.Color.parseColor("#9333ea")) // Color morado acentuado del sistema
        .setColorized(true)
        .addAction(android.R.drawable.ic_media_previous, "Anterior", prevPending)
        .addAction(playPauseIcon, if (isPlaying) "Pausa" else "Play", togglePending)
        .addAction(android.R.drawable.ic_media_next, "Siguiente", nextPending)
        .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))

    val nm = getSystemService(NotificationManager::class.java)
    nm.notify(777, builder.build())
}
