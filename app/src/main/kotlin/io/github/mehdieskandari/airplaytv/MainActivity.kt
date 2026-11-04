package io.github.mehdieskandari.airplaytv

import android.Manifest
import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.mehdieskandari.airplaytv.service.AirPlayService
import io.github.mehdieskandari.airplaytv.ui.MainScreen
import io.github.mehdieskandari.airplaytv.ui.isTvDevice
import io.github.mehdieskandari.airplaytv.ui.theme.AirPlayTheme
import io.github.mehdieskandari.airplaytv.viewmodel.LocalNetworkAccess
import io.github.mehdieskandari.airplaytv.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AirPlayLifecycle"
        const val EXTRA_CAST_LAUNCH = "io.github.mehdieskandari.airplaytv.CAST_LAUNCH"
    }

    private val viewModel: MainViewModel by viewModels()
    private var service: AirPlayService? = null
    val isInPip = mutableStateOf(false)
    val isCastSession = mutableStateOf(false)
    private var hadActiveCast = false
    private var isLaunchedFromCast = false
    private var castWatchdogJob: Job? = null

    private val logCallback: (String) -> Unit = { viewModel.addLog(it) }
    private val pinCallback: (String?) -> Unit = { viewModel.showPin(it) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "onServiceConnected: binding AirPlayService")
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            svc.logCallback = logCallback
            svc.pinCallback = pinCallback
            viewModel.bindService(svc)
            viewModel.updateFromService()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) _refreshForeground()
            _autoStart()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected: AirPlayService unbound")
            service = null
            viewModel.unbindService()
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it.isNotEmpty()) _syncLocalNetwork(requested = true) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val fromCast = intent.getBooleanExtra(EXTRA_CAST_LAUNCH, false)
        Log.i(TAG, "onNewIntent: fromCast=$fromCast, action=${intent.action}, flags=0x${Integer.toHexString(intent.flags)}")
        if (fromCast) {
            isCastSession.value = true
            isLaunchedFromCast = true
            startCastWatchdog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val fromCast = intent.getBooleanExtra(EXTRA_CAST_LAUNCH, false)
        Log.i(TAG, "onCreate: fromCast=$fromCast, action=${intent.action}, flags=0x${Integer.toHexString(intent.flags)}, savedInstanceState=$savedInstanceState")
        if (fromCast) {
            isCastSession.value = true
            isLaunchedFromCast = true
            startCastWatchdog()
        }

        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !_granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!hasLocalNetwork()) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        _askBatteryExemptionOnce()

        bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.updateFromService()
                    delay(200)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.pref(Prefs.KEEP_SCREEN_ON), viewModel.connectionCount) { keep, conns ->
                    keep && conns > 0
                }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        // Direct cast receiver: auto-exit to home when the sender disconnects
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.connectionCount,
                    viewModel.videoPlaybackActive,
                    viewModel.videoSessionPending,
                    viewModel.mirroringActive
                ) { conns, videoActive, videoPending, mirroring ->
                    val active = conns > 0 || videoActive || videoPending || mirroring
                    Log.d(TAG, "Cast combine: conns=$conns, video=$videoActive, pending=$videoPending, mirror=$mirroring => active=$active, hadActiveCast=$hadActiveCast, isCastSession=${isCastSession.value}, isLaunchedFromCast=$isLaunchedFromCast")
                    active
                }.collect { isActive ->
                    if (isActive) {
                        castWatchdogJob?.cancel()
                        if (!hadActiveCast) {
                            Log.i(TAG, "Cast session became ACTIVE (stream running) -> hadActiveCast set to true")
                        }
                        isCastSession.value = true
                        hadActiveCast = true
                    } else if (hadActiveCast && !isChangingConfigurations) {
                        Log.i(TAG, "Cast session ENDED (hadActiveCast=true, isLaunchedFromCast=$isLaunchedFromCast)")
                        hadActiveCast = false
                        if (isLaunchedFromCast) {
                            Log.i(TAG, "Exiting cast session directly to TV desktop via finishAndRemoveTask()")
                            moveTaskToBack(true)
                            finishAndRemoveTask()
                            if (Build.VERSION.SDK_INT >= 34) {
                                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                            } else {
                                @Suppress("DEPRECATION")
                                overridePendingTransition(0, 0)
                            }
                        } else {
                            Log.i(TAG, "Manual launch mode, returning to dashboard UI")
                            isCastSession.value = false
                        }
                    } else {
                        Log.d(TAG, "Cast state idle/waiting (hadActiveCast=false)")
                    }
                }
            }
        }

        setContent {
            AirPlayTheme {
                MainScreen(
                    viewModel = viewModel,
                    isCastSession = isCastSession.value,
                    isLaunchedFromCast = isLaunchedFromCast,
                    isInPip = isInPip.value,
                    onSurfaceAvailable = { viewModel.onSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onSurfaceDestroyed(it) },
                    onPip = { enterPip() },
                    onLocalNetwork = ::_requestLocalNetwork,
                    onExitCast = {
                        Log.i(TAG, "onExitCast: Remote Back button pressed during cast, isLaunchedFromCast=$isLaunchedFromCast")
                        hadActiveCast = false
                        if (isLaunchedFromCast) {
                            moveTaskToBack(true)
                            finishAndRemoveTask()
                            if (Build.VERSION.SDK_INT >= 34) {
                                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                            } else {
                                @Suppress("DEPRECATION")
                                overridePendingTransition(0, 0)
                            }
                        } else {
                            isCastSession.value = false
                            if (viewModel.videoPlaybackActive.value) {
                                viewModel.stopVideoPlayback()
                            }
                        }
                    }
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        if (isLaunchedFromCast) {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun startCastWatchdog() {
        castWatchdogJob?.cancel()
        castWatchdogJob = lifecycleScope.launch {
            delay(15_000)
            if (!hadActiveCast && isCastSession.value) {
                Log.w(TAG, "Cast watchdog: 15s timeout with no active cast received, auto-exiting to TV desktop")
                moveTaskToBack(true)
                finishAndRemoveTask()
                if (Build.VERSION.SDK_INT >= 34) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                } else {
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            }
        }
    }

    private fun _granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun _autoStart() {
        val svc = service ?: return
        if (viewModel.pref(Prefs.AUTO_START).value && svc.serverState.value == AirPlayService.ServerState.STOPPED && hasLocalNetwork()) {
            viewModel.startServer()
        }
    }
    
    private fun _refreshForeground() {
        if (service?.serverState?.value == AirPlayService.ServerState.RUNNING) {
            startService(Intent(this, AirPlayService::class.java))
        }
    }

    private fun _syncLocalNetwork(requested: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        val p = Manifest.permission.ACCESS_LOCAL_NETWORK
        val prev = viewModel.localNetwork.value
        val now = when {
            hasLocalNetwork() -> LocalNetworkAccess.GRANTED
            shouldShowRequestPermissionRationale(p) -> LocalNetworkAccess.ASK
            requested || prev == LocalNetworkAccess.SETTINGS -> LocalNetworkAccess.SETTINGS
            else -> LocalNetworkAccess.ASK
        }
        viewModel.setLocalNetwork(now)
        if (now == LocalNetworkAccess.GRANTED && prev != LocalNetworkAccess.GRANTED) _autoStart()
    }

    private fun _requestLocalNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        if (viewModel.localNetwork.value == LocalNetworkAccess.SETTINGS) {
            startActivity(appSettingsIntent())
        } else {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart")
        _syncLocalNetwork()
        _refreshForeground()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(AirPlayService.CAST_NOTIFICATION_ID)
        } catch (_: Exception) {}
        if (!isLaunchedFromCast) {
            _checkOverlayPermission()
        }
    }

    private fun _checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.i(TAG, "SYSTEM_ALERT_WINDOW not granted, requesting overlay permission")
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
    }

    fun enterPip() {
        // Picture-in-Picture disabled to ensure native direct fullscreen cast experience on TV
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        isInPip.value = inPip
    }

    override fun onStop() {
        Log.i(TAG, "onStop: isChangingConfigurations=$isChangingConfigurations, isFinishing=$isFinishing")
        super.onStop()
        // never stop mid-session
        if (!viewModel.pref(Prefs.RUN_IN_BACKGROUND).value && !isChangingConfigurations &&
            viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            viewModel.connectionCount.value == 0) {
            viewModel.stopServer()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: isFinishing=$isFinishing")
        castWatchdogJob?.cancel()
        service?.let {
            if (it.logCallback === logCallback) it.logCallback = null
            if (it.pinCallback === pinCallback) it.pinCallback = null
        }
        unbindService(connection)
        super.onDestroy()
    }

    // ask once to be left out of battery optimization so android doesn't stop the receiver
    @android.annotation.SuppressLint("BatteryLife")
    private fun _askBatteryExemptionOnce() {
        val sp = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        if (sp.getBoolean("asked_battery_exemption", false)) return
        sp.edit().putBoolean("asked_battery_exemption", true).apply()
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        }
    }
}
