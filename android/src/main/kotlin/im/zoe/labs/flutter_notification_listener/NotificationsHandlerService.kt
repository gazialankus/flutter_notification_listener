package im.zoe.labs.flutter_notification_listener

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.session.PlaybackState.*
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.NotificationCompat
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import org.json.JSONObject
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


private const val ACTION_POSTED: String = "posted"
private const val ACTION_REMOVED: String = "removed"
private const val STATE_PLAYING = 0
private const val STATE_PAUSED = 1
private const val STATE_STOPPED = 2
private const val STATE_UNKNOWN = -1

class NotificationsHandlerService: MethodChannel.MethodCallHandler, NotificationListenerService() {
    private val queue = ArrayDeque<NotificationEvent>()
    private lateinit var mBackgroundChannel: MethodChannel
    private lateinit var mContext: Context

    private val tokens: HashMap<String, MediaSession.Token> = HashMap()
    private var trackInfo: TrackInfo = TrackInfo()

    // notification event cache: packageName_id -> event
    private val eventsCache = HashMap<String, NotificationEvent>()

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
      when (call.method) {
          "service.initialized" -> {
              initFinish()
              return result.success(true)
          }
          // this should move to plugin
          "service.promoteToForeground" -> {
              // add data
              val cfg = Utils.PromoteServiceConfig.fromMap(call.arguments as Map<*, *>).apply {
                  foreground = true
              }
              return result.success(promoteToForeground(cfg))
          }
          "service.demoteToBackground" -> {
              return result.success(demoteToBackground())
          }
          "service.tap" -> {
              // tap the notification
              Log.d(TAG, "tap the notification")
              val args = call.arguments<ArrayList<*>?>()
              val uid = args!![0]!! as String
              return result.success(tapNotification(uid))
          }
          "service.tap_action" -> {
              // tap the action
              Log.d(TAG, "tap action of notification")
              val args = call.arguments<ArrayList<*>?>()
              val uid = args!![0]!! as String
              val idx = args[1]!! as Int
              return result.success(tapNotificationAction(uid, idx))
          }
          "service.send_input" -> {
              // send the input data
              Log.d(TAG, "set the content for input and the send action")
              val args = call.arguments<ArrayList<*>?>()
              val uid = args!![0]!! as String
              val idx = args[1]!! as Int
              val data = args[2]!! as Map<*, *>
              return result.success(sendNotificationInput(uid, idx, data))
          }
          "service.get_full_notification" -> {
              val args = call.arguments<ArrayList<*>?>()
              val uid = args!![0]!! as String
              if (!eventsCache.contains(uid)) {
                  return result.error("notFound", "can't found this notification $uid", "")
              }
              return result.success(Utils.Marshaller.marshal(eventsCache[uid]?.mSbn))
          }
          else -> {
              Log.d(TAG, "unknown method ${call.method}")
              result.notImplemented()
          }
      }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // if get shutdown release the wake lock
        Log.d(TAG, "onStartCommand. ${intent?.action}'")

        when (intent?.action) {
            ACTION_SHUTDOWN -> {
                (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                        if (isHeld) release()
                    }
                }
                Log.i(TAG, "stop notification handler service!")
                disableServiceSettings(mContext)
                stopForeground(true)
                stopSelf()
            }
            else -> {

            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        mContext = this

        // store the service instance
        instance = this

        Log.i(TAG, "notification listener service onCreate")
        startListenerService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "notification listener service onDestroy")
        val bdi = Intent(mContext, RebootBroadcastReceiver::class.java)
        // remove notification
        sendBroadcast(bdi)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "notification listener service onTaskRemoved")
    }

    private fun findTokenForSbn(sbn: StatusBarNotification): SbnAndToken? {
        Log.d(TAG, "findTokenForSbn")

        var playingToken: SbnAndToken? = null
        var pausedToken: SbnAndToken? = null

        val token = getTokenIfAvailable(sbn) ?: return null
        Log.d(TAG, "findTokenForSbn 1")
        val controller = MediaController(this, token)
        val playbackState: Int = controller.playbackState?.state ?: return null
        Log.d(TAG, "findTokenForSbn 2")
        if (playbackState == PlaybackState.STATE_PLAYING) return SbnAndToken(sbn, token)
        Log.d(TAG, "findTokenForSbn 3")
        if (playbackState == PlaybackState.STATE_PAUSED) return SbnAndToken(sbn, token)
        Log.d(TAG, "findTokenForSbn null")
        return null
    }

    private fun getTokenIfAvailable(sbn: StatusBarNotification): MediaSession.Token? {
        Log.d(TAG, "getTokenIfAvailable")
        val notif = sbn.notification
        val bundle: Bundle = notif.extras
        return bundle.getParcelable<Parcelable>("android.mediaSession") as MediaSession.Token?
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        Log.d(TAG, "onNotificationRemoved")
        super.onNotificationRemoved(sbn, rankingMap)
        if (sbn == null) {
            Log.d(TAG, "no sbn")
            return
        }

        FlutterInjector.instance().flutterLoader().startInitialization(mContext)
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(mContext, null)

        val token: MediaSession.Token? = tokens.remove(sbn.key)
        if (token != null) handleMediaNotification(token, ACTION_REMOVED)

    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        Log.d(TAG, "onNotificationPosted")

        FlutterInjector.instance().flutterLoader().startInitialization(mContext)
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(mContext, null)

        Log.d(TAG, "Started and ensured")

        val sbnAndToken = findTokenForSbn(sbn)
        Log.d(TAG, "Media Token: ${sbnAndToken?.token}")
        if (sbnAndToken?.token != null) {
            tokens[sbn.key] = sbnAndToken.token
            val isHandled = handleMediaNotification(sbnAndToken.token, ACTION_POSTED)
            if (isHandled) {
                Log.d(TAG, " sbnAndToken.token is handled")
                return
            }
        }

        val evt = NotificationEvent(mContext, sbn)

        // store the evt to cache
        eventsCache[evt.uid] = evt

        synchronized(sServiceStarted) {
            if (!sServiceStarted.get()) {
                Log.d(TAG, "service is not start try to queue the event")
                queue.add(evt)
            } else {
                Log.d(TAG, "send event to flutter side immediately!")
                Handler(mContext.mainLooper).post { sendEvent(evt) }
            }
        }
    }

    private fun handleMediaNotification(token: MediaSession.Token, action: String): Boolean {
        if (action == ACTION_POSTED) {
            val trackInfo = extractFieldsFor(token) ?: return false
            this.trackInfo = trackInfo
            sendTrack(trackInfo)
        } else if (action == ACTION_REMOVED) {
            finishPlaying(token)
        }
        return true
    }

    private fun sendTrack(trackInfo: TrackInfo?) {
        if (trackInfo == null) {
            sendMediaEvent(TrackInfo())
            return
        }

        val audioManager = mContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        trackInfo.volumePercent = volume * 100 / maxVolume

        sendMediaEvent(trackInfo)
    }

    private fun finishPlaying(token: MediaSession.Token) {
        val controller = MediaController(mContext, token)
        val mediaMetadata = controller.metadata ?: return
        val id = deriveId(mediaMetadata)
        if (id == trackInfo.id) sendTrack(null)
    }

    private fun extractFieldsFor(token: MediaSession.Token): TrackInfo? {
        val controller = MediaController(mContext, token)

        val mediaMetadata = controller.metadata ?: return null

        val id: String = deriveId(mediaMetadata)
        val lastId = trackInfo.id

        val playbackState = controller.playbackState
            ?: // if we don't have a playback state, we can't do anything
            return null
        val state: Int = getPlaybackState(playbackState)

        // back out now if we're not interested in this state
        if (state == STATE_UNKNOWN) {
            this.trackInfo.clear()
            return null
        }
        if (state == STATE_PAUSED && lastId != null && id != lastId) return null
        if (state == STATE_STOPPED && id != lastId) return null

        val trackInfo = TrackInfo()
        trackInfo.id = id
        trackInfo.source = controller.packageName
        trackInfo.state = state
        trackInfo.album = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        trackInfo.title = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        trackInfo.artist = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        trackInfo.genre = mediaMetadata.getString(MediaMetadata.METADATA_KEY_GENRE)
        trackInfo.duration = mediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        trackInfo.position = playbackState.position

        return trackInfo
    }

    private fun getPlaybackState(state: PlaybackState): Int {
        return when (state.state) {
            PlaybackState.STATE_PLAYING -> STATE_PLAYING
            PlaybackState.STATE_PAUSED -> STATE_PAUSED
            PlaybackState.STATE_STOPPED -> STATE_STOPPED
            else -> STATE_UNKNOWN
        }
    }

    private fun deriveId(mediaMetadata: MediaMetadata): String {
        val album = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val title = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return "$title:$artist:$album"
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val evt = NotificationEvent(mContext, sbn)
        // remove the event from cache
        eventsCache.remove(evt.uid)
        Log.d(TAG, "notification removed: ${evt.uid}")
    }

    private fun initFinish() {
        Log.d(TAG, "service's flutter engine initialize finished. sServiceStarted? $sServiceStarted")
        synchronized(sServiceStarted) {
            while (!queue.isEmpty()) sendEvent(queue.remove())
            sServiceStarted.set(true)
        }
    }

    private fun promoteToForeground(cfg: Utils.PromoteServiceConfig? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "promoteToForeground need sdk >= 26")
            return false
        }

        if (cfg?.foreground != true) {
            Log.i(TAG, "no need to start foreground: ${cfg?.foreground}")
            return false
        }

        // first is not running already, start at first
        if (!FlutterNotificationListenerPlugin.isServiceRunning(mContext, this.javaClass)) {
            Log.e(TAG, "service is not running")
            return false
        }

        // get args from store or args
        val cfg = cfg ?: Utils.PromoteServiceConfig.load(this)
        // make the service to foreground

        // take a wake lock
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // create a channel for notification
        val channel = NotificationChannel(CHANNEL_ID, "Flutter Notifications Listener Plugin", NotificationManager.IMPORTANCE_HIGH)
        val imageId = resources.getIdentifier("ic_launcher", "mipmap", packageName)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(cfg.title)
            .setContentText(cfg.description)
            .setShowWhen(cfg.showWhen ?: false)
            .setSubText(cfg.subTitle)
            .setSmallIcon(imageId)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        Log.d(TAG, "promote the service to foreground")
        startForeground(ONGOING_NOTIFICATION_ID, notification)

        return true
    }

    private fun demoteToBackground(): Boolean {
        Log.d(TAG, "demote the service to background")
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                if (isHeld) release()
            }
        }
        stopForeground(true)
        return true
    }

    private fun tapNotification(uid: String): Boolean {
        Log.d(TAG, "tap the notification: $uid")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid] ?: return false
        n.mSbn.notification.contentIntent.send()
        return true
    }

    private fun tapNotificationAction(uid: String, idx: Int): Boolean {
        Log.d(TAG, "tap the notification action: $uid @$idx")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid]
        if (n == null) {
            Log.e(TAG, "notification is null: $uid")
            return false
        }
        if (n.mSbn.notification.actions.size <= idx) {
            Log.e(TAG, "tap action out of range: size ${n.mSbn.notification.actions.size} index $idx")
            return false
        }

        val act = n.mSbn.notification.actions[idx]
        if (act == null) {
            Log.e(TAG, "notification $uid action $idx not exits")
            return false
        }
        act.actionIntent.send()
        return true
    }

    private fun sendNotificationInput(uid: String, idx: Int, data: Map<*, *>): Boolean {
        Log.d(TAG, "tap the notification action: $uid @$idx")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid]
        if (n == null) {
            Log.e(TAG, "notification is null: $uid")
            return false
        }
        if (n.mSbn.notification.actions.size <= idx) {
            Log.e(TAG, "send inputs out of range: size ${n.mSbn.notification.actions.size} index $idx")
            return false
        }

        val act = n.mSbn.notification.actions[idx]
        if (act == null) {
            Log.e(TAG, "notification $uid action $idx not exits")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            if (act.remoteInputs == null) {
                Log.e(TAG, "notification $uid action $idx remote inputs not exits")
                return false
            }

            val intent = Intent()
            val bundle = Bundle()
            act.remoteInputs.forEach {
                if (data.containsKey(it.resultKey as String)) {
                    Log.d(TAG, "add input content: ${it.resultKey} => ${data[it.resultKey]}")
                    bundle.putCharSequence(it.resultKey, data[it.resultKey] as String)
                }
            }
            RemoteInput.addResultsToIntent(act.remoteInputs, intent, bundle)
            act.actionIntent.send(mContext, 0, intent)
            Log.d(TAG, "send the input action success")
            return true
        } else {
            Log.e(TAG, "not implement :sdk < KITKAT_WATCH")
            return false
        }
    }

    companion object {

        var callbackHandle = 0L
        var mediaCallbackHandle = 1L

        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        var instance: NotificationsHandlerService? = null

        @JvmStatic
        private val TAG = "NotificationsListenerService"

        private const val ONGOING_NOTIFICATION_ID = 100
        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"
        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"

        private const val CHANNEL_ID = "flutter_notifications_listener_channel"

        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null
        @JvmStatic
        private val sServiceStarted = AtomicBoolean(false)

        private const val BG_METHOD_CHANNEL_NAME = "flutter_notification_listener/bg_method"

        private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
        private const val ACTION_NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

        const val NOTIFICATION_INTENT_KEY = "object"
        const val NOTIFICATION_INTENT = "notification_event"

        fun permissionGiven(context: Context): Boolean {
            Log.i(TAG, "permissionGiven?")
            val packageName = context.packageName
            val flat = Settings.Secure.getString(context.contentResolver, ENABLED_NOTIFICATION_LISTENERS)
            if (!TextUtils.isEmpty(flat)) {
                val names = flat.split(":").toTypedArray()
                for (name in names) {
                    val componentName = ComponentName.unflattenFromString(name)
                    val nameMatch = TextUtils.equals(packageName, componentName?.packageName)
                    if (nameMatch) {
                        Log.i(TAG, "permissionGiven, yes!")
                        return true
                    }
                }
            }

            Log.i(TAG, "permissionGiven, no!")
            return false
        }

        fun openPermissionSettings(context: Context): Boolean {
            Log.i(TAG, "openPermissionSettings")
            context.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        fun enableServiceSettings(context: Context) {
            Log.i(TAG, "enableServiceSettings")
            toggleServiceSettings(context, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        }

        fun disableServiceSettings(context: Context) {
            Log.i(TAG, "disableServiceSettings")
            toggleServiceSettings(context, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }

        private fun toggleServiceSettings(context: Context, state: Int) {
            Log.i(TAG, "toggleServiceSettings")
            val receiver = ComponentName(context, NotificationsHandlerService::class.java)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
            Log.i(TAG, "toggleServiceSettings done")
        }

        fun updateFlutterEngine(context: Context) {
            Log.d(TAG, "call instance update flutter engine from plugin init. has instance? ${instance != null}")

            instance?.updateFlutterEngine(context)
            // we need to `finish init` manually
            instance?.initFinish()
        }
    }

    private fun getFlutterEngine(context: Context): FlutterEngine {
        var eng = FlutterEngineCache.getInstance().get(FlutterNotificationListenerPlugin.FLUTTER_ENGINE_CACHE_KEY)
        if (eng != null) return eng

        Log.i(TAG, "flutter engine cache is null, create a new one")
        eng = FlutterEngine(context)

        // ensure initialization
        FlutterInjector.instance().flutterLoader().startInitialization(context)
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(context, arrayOf())

        // call the flutter side init
        // get the call back handle information
        val cb = context.getSharedPreferences(FlutterNotificationListenerPlugin.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .getLong(FlutterNotificationListenerPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)

        val mediaCb = context.getSharedPreferences(FlutterNotificationListenerPlugin.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .getLong(FlutterNotificationListenerPlugin.MEDIA_CALLBACK_DISPATCHER_HANDLE_KEY, 0)

        if (cb != 0L) {
            Log.d(TAG, "try to find callback: $cb")
            val info = FlutterCallbackInformation.lookupCallbackInformation(cb)
            val args = DartExecutor.DartCallback(context.assets,
                FlutterInjector.instance().flutterLoader().findAppBundlePath(), info)
            // call the callback
            eng.dartExecutor.executeDartCallback(args)
        } else {
            Log.e(TAG, "Fatal: no callback register")
        }

        if (mediaCb != 1L) {
            Log.d(TAG, "try to find media callback: $mediaCb")
            val info = FlutterCallbackInformation.lookupCallbackInformation(mediaCb)
            val args = DartExecutor.DartCallback(context.assets,
                FlutterInjector.instance().flutterLoader().findAppBundlePath(), info)
            // call the callback
            eng.dartExecutor.executeDartCallback(args)
        } else {
            Log.e(TAG, "Fatal: no media callback register")
        }

        FlutterEngineCache.getInstance().put(FlutterNotificationListenerPlugin.FLUTTER_ENGINE_CACHE_KEY, eng)
        return eng
    }

    private fun updateFlutterEngine(context: Context) {
        Log.d(TAG, "update the flutter engine of service")
        // take the engine
        val eng = getFlutterEngine(context)
        sBackgroundFlutterEngine = eng

        // set the method call
        mBackgroundChannel = MethodChannel(eng.dartExecutor.binaryMessenger, BG_METHOD_CHANNEL_NAME)
        mBackgroundChannel.setMethodCallHandler(this)
    }

    private fun startListenerService(context: Context) {
        Log.d(TAG, "start listener service")
        synchronized(sServiceStarted) {
            // promote to foreground
            // TODO: take from intent, currently just load form store
            promoteToForeground(Utils.PromoteServiceConfig.load(context))

            // we should to update
            Log.d(TAG, "service's flutter engine is null, should update one")
            updateFlutterEngine(context)

            sServiceStarted.set(true)
        }
        Log.d(TAG, "service start finished")
    }

    private fun sendMediaEvent(trackInfo: TrackInfo) {
        Log.d(TAG, "send media event: ${trackInfo.toJson()}")
        if (mediaCallbackHandle == 1L) {
            mediaCallbackHandle = mContext.getSharedPreferences(FlutterNotificationListenerPlugin.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                    .getLong(FlutterNotificationListenerPlugin.MEDIA_CALLBACK_HANDLE_KEY, 0)
        }

        try {
            mBackgroundChannel.invokeMethod("sink_media_event", listOf(mediaCallbackHandle, trackInfo.toJson()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendEvent(evt: NotificationEvent) {
        Log.d(TAG, "send notification event: ${evt.data}")
        if (callbackHandle == 0L) {
            callbackHandle = mContext.getSharedPreferences(FlutterNotificationListenerPlugin.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                .getLong(FlutterNotificationListenerPlugin.CALLBACK_HANDLE_KEY, 0)
        }

        // why mBackgroundChannel can be null?
        Log.d(TAG, "$mBackgroundChannel")

        try {
            mBackgroundChannel.invokeMethod("sink_event", listOf(callbackHandle, evt.data))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class SbnAndToken(val sbn: StatusBarNotification, val token: MediaSession.Token)

    private data class TrackInfo(
        var id: String? = null,
        var source: String? = null,
        var state: Int? = null,
        var album: String? = null,
        var title: String? = null,
        var artist: String? = null,
        var genre: String? = null,
        var duration: Long? = null,
        var position: Long? = null,
        var volumePercent: Int? = null,
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("source", source)
            json.put("state", state)
            json.put("album", album)
            json.put("title", title)
            json.put("artist", artist)
            json.put("genre", genre)
            json.put("duration", duration)
            json.put("position", position)
            json.put("volumePercent", volumePercent)
            return json.toString()
        }
        fun clear() {
            id = null
            source = null
            state = null
            album = null
            title = null
            artist = null
            genre = null
            duration = null
            position = null
        }
    }
}

