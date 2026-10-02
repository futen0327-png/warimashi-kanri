package jp.warimashi.voiceop.diag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * 画面OFF・バックグラウンド時のキー診断（debug ビルド専用）。
 *
 * 画面が消えているとアクティビティにキーは届かない。代わりに Android は
 * - 音量キー → 「再生中」のメディアセッションの VolumeProvider（onAdjustVolume）
 * - メディアキー（再生/一時停止など）→ メディアセッションの onMediaButtonEvent
 * に回すので、STATE_PLAYING のセッションとリモート音量（VOLUME_CONTROL_RELATIVE）を用意して、
 * 何が届いたかをすべて KeyLog に残す。システム音量は変わらない。
 *
 * メディアキーの届け先は「最後に音を鳴らしたアプリ」が優先されるため、
 * 必要なら無音の AudioTrack を再生して優先を取る（EXTRA_SILENT_AUDIO）。
 */
class KeyDiagnosticService : Service() {

    companion object {
        const val EXTRA_SILENT_AUDIO = "silent_audio"
        private const val ACTION_STOP = "jp.warimashi.voiceop.diag.STOP"
        private const val CHANNEL_ID = "key_diag"
        private const val NOTIFICATION_ID = 7301
        private const val WHERE = "Service"

        @Volatile var running = false
            private set

        fun start(context: Context, silentAudio: Boolean) {
            val intent = Intent(context, KeyDiagnosticService::class.java)
                .putExtra(EXTRA_SILENT_AUDIO, silentAudio)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeyDiagnosticService::class.java))
        }
    }

    private var session: MediaSession? = null
    private var silentTrack: AudioTrack? = null

    /** 画面のON/OFFもログに残して、キーの記録と時刻を突き合わせられるようにする。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val what = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> "画面OFF"
                Intent.ACTION_SCREEN_ON -> "画面ON"
                Intent.ACTION_USER_PRESENT -> "ロック解除"
                else -> intent.action ?: "?"
            }
            KeyLog.add(WHERE, "---- $what ----")
        }
    }

    /** 音量キー。リモート音量なので端末の音量は変えず、方向だけ受け取る。 */
    private val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
        override fun onAdjustVolume(direction: Int) {
            val dir = when (direction) {
                1 -> "+1(音量UP)"
                -1 -> "-1(音量DOWN)"
                0 -> "0(SAME)"
                else -> direction.toString()
            }
            KeyLog.add(WHERE, "VolumeProvider.onAdjustVolume direction=$dir")
        }

        override fun onSetVolumeTo(volume: Int) {
            KeyLog.add(WHERE, "VolumeProvider.onSetVolumeTo volume=$volume")
        }
    }

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val e = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (e != null) {
                KeyLog.key("Service.onMediaButtonEvent", e)
            } else {
                KeyLog.add(WHERE, "onMediaButtonEvent (KeyEventなし) $mediaButtonIntent")
            }
            // 既定処理に回すと onPlay / onPause などに変換される。それも下で記録する。
            return super.onMediaButtonEvent(mediaButtonIntent)
        }

        override fun onPlay() = KeyLog.add(WHERE, "Callback.onPlay")
        override fun onPause() = KeyLog.add(WHERE, "Callback.onPause")
        override fun onStop() = KeyLog.add(WHERE, "Callback.onStop")
        override fun onSkipToNext() = KeyLog.add(WHERE, "Callback.onSkipToNext")
        override fun onSkipToPrevious() = KeyLog.add(WHERE, "Callback.onSkipToPrevious")
        override fun onFastForward() = KeyLog.add(WHERE, "Callback.onFastForward")
        override fun onRewind() = KeyLog.add(WHERE, "Callback.onRewind")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val silent = intent?.getBooleanExtra(EXTRA_SILENT_AUDIO, false) ?: false
        val s = session ?: createSession().also { session = it }
        startInForeground(s)
        setSilentAudio(silent)
        running = true
        KeyLog.add(WHERE, "画面OFF診断 開始（MediaSession=PLAYING, リモート音量=RELATIVE, 無音再生=${if (silent) "ON" else "OFF"}）")
        return START_NOT_STICKY
    }

    private fun createSession(): MediaSession = MediaSession(this, "KeyDiagnostic").apply {
        setCallback(callback)
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
                )
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        )
        setPlaybackToRemote(volumeProvider)
        isActive = true
    }

    private fun startInForeground(s: MediaSession) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "キー診断", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, KeyDiagnosticActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, KeyDiagnosticService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("キー診断: 画面OFF診断中")
            .setContentText("リモコンのボタンを押すとログに記録します")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 無音を鳴らし続けて「最後に音を鳴らしたアプリ」になり、メディアキーの届け先の優先を取る。 */
    private fun setSilentAudio(on: Boolean) {
        if (!on) {
            releaseSilentTrack()
            return
        }
        if (silentTrack != null) return
        val sampleRate = 8000
        val frames = sampleRate // 1秒分の無音をループ
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        track.write(ShortArray(frames), 0, frames)
        track.setLoopPoints(0, frames, -1)
        track.play()
        silentTrack = track
    }

    private fun releaseSilentTrack() {
        silentTrack?.let {
            runCatching { it.stop() }
            it.release()
        }
        silentTrack = null
    }

    override fun onDestroy() {
        running = false
        unregisterReceiver(screenReceiver)
        releaseSilentTrack()
        session?.run {
            isActive = false
            release()
        }
        session = null
        KeyLog.add(WHERE, "画面OFF診断 停止")
        super.onDestroy()
    }
}
