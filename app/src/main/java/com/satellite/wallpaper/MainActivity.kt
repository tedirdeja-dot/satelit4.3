package com.satellite.wallpaper

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var preview: CropPreviewView
    private lateinit var button: Button
    private lateinit var progress: View
    private lateinit var status: TextView
    private lateinit var changeView: Button
    private lateinit var grantOverlay: Button
    private lateinit var lockStatic: CheckBox
    private var leavingActivity = false
    private var displayedUpdatedAt = 0L
    private var cacheReceiverRegistered = false
    private var waitingForNotificationPermission = false

    private val cacheUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SatelliteWallpaperService.ACTION_CACHE_UPDATED) {
                showCachedOrWaiting()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeBottomInset()

        preview = findViewById(R.id.crop_preview)
        button = findViewById(R.id.set_wallpaper)
        progress = findViewById(R.id.progress)
        progress.visibility = View.GONE
        status = findViewById(R.id.status)

        preview.onStateChanged = { WallpaperRepository.saveState(this, it) }
        button.setOnClickListener { openConfirm() }

        changeView = findViewById(R.id.change_view)
        grantOverlay = findViewById(R.id.grant_overlay)
        lockStatic = findViewById(R.id.lock_static)

        changeView.setOnClickListener { openPicker() }
        grantOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.grant_battery).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }
        UnlockService.start(this) // mantiene la app viva y escucha el desbloqueo aunque esté cerrada
        lockStatic.isChecked = WallpaperRepository.lockEnabled(this)
        lockStatic.setOnCheckedChangeListener { _, checked ->
            WallpaperRepository.setLockEnabled(this, checked)
        }

        showUpdateIntervalMenu()
    }

    private fun versionLabel(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "v${pi.versionName} (build $code)"
    }.getOrDefault("v?")

    override fun onResume() {
        super.onResume()
        registerCacheReceiver()
        findViewById<TextView>(R.id.version).text = "Versión ${versionLabel()}"
        grantOverlay.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<View>(R.id.grant_battery).visibility = if (ignoring) View.GONE else View.VISIBLE
        val log = WallpaperRepository.attemptLog(this)
        findViewById<TextView>(R.id.attempt_log).text =
            if (log.isBlank()) "${versionLabel()} · sin intentos en segundo plano todavía." else "${versionLabel()} · últimos intentos:\n$log"
        showCachedOrWaiting()
    }

    override fun onPause() {
        if (cacheReceiverRegistered) {
            runCatching { unregisterReceiver(cacheUpdatedReceiver) }
            cacheReceiverRegistered = false
        }
        super.onPause()
    }

    private fun registerCacheReceiver() {
        if (cacheReceiverRegistered) return
        val filter = IntentFilter(SatelliteWallpaperService.ACTION_CACHE_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(cacheUpdatedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cacheUpdatedReceiver, filter)
        }
        cacheReceiverRegistered = true
    }

    private fun showUpdateIntervalMenu() {
        val intervals = intArrayOf(0, 15, 30, 60)
        val labels = arrayOf(
            getString(R.string.update_interval_0),
            getString(R.string.update_interval_15),
            getString(R.string.update_interval_30),
            getString(R.string.update_interval_60)
        )
        val selected = intervals.indexOf(WallpaperRepository.updateIntervalMinutes(this))
            .takeIf { it >= 0 } ?: 1

        AlertDialog.Builder(this)
            .setTitle(R.string.update_interval_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                WallpaperRepository.setUpdateIntervalMinutes(this, intervals[which])
                dialog.dismiss()
                continueStartup()
            }
            .setCancelable(false)
            .show()
    }

    private fun continueStartup() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            waitingForNotificationPermission = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
            return
        }
        openInitialScreen()
    }

    private fun openInitialScreen() {
        if (!WallpaperRepository.hasSelection(this)) {
            // Primer inicio: elegir una vista no descarga la imagen; la primera descarga ocurre al desbloquear.
            openPicker()
        } else {
            showCachedOrWaiting()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && waitingForNotificationPermission) {
            waitingForNotificationPermission = false
            openInitialScreen()
        }
    }

    private fun openPicker() {
        startActivityForResult(Intent(this, PickViewActivity::class.java), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!isActivityUsable()) return
        if (requestCode == REQ_CONFIRM) {
            if (resultCode == RESULT_OK) saveAndOpenWallpaperPicker()
            return
        }
        if (requestCode != REQ_PICK) return
        showCachedOrWaiting()
    }

    private fun showCachedOrWaiting() {
        val cached = runCatching { WallpaperRepository.loadCached(this) }.getOrNull()
        if (cached == null) {
            showWaitingForUnlock()
            return
        }

        val cachedUpdatedAt = WallpaperRepository.updatedAt(this)
        if (displayedUpdatedAt != cachedUpdatedAt || !button.isEnabled) {
            showImageSafely(cached)
        } else {
            cached.recycle()
        }
    }

    private fun showWaitingForUnlock() {
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.setText(R.string.waiting_for_unlock)
        button.isEnabled = false
    }

    override fun onDestroy() {
        leavingActivity = true
        super.onDestroy()
    }

    private fun applySafeBottomInset() {
        val root = findViewById<View>(R.id.root_container)
        val basePadding = root.paddingBottom
        val extra = (24 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + bottom + extra)
            insets
        }
        root.requestApplyInsets()
    }

    /** Muestra la imagen a pantalla completa con Aceptar / Cancelar antes de aplicar el fondo. */
    private fun openConfirm() {
        WallpaperRepository.saveState(this, preview.state)
        startActivityForResult(Intent(this, ConfirmWallpaperActivity::class.java), REQ_CONFIRM)
    }

    private fun showImageSafely(bitmap: Bitmap) {
        try {
            showImage(bitmap)
        } catch (t: Throwable) {
            showError()
        }
    }

    private fun showImage(bitmap: Bitmap) {
        progress.visibility = View.GONE
        status.visibility = View.GONE
        displayedUpdatedAt = WallpaperRepository.updatedAt(this)
        preview.setBitmap(
            bitmap,
            WallpaperRepository.loadState(this),
            WallpaperRepository.updatedAt(this)
        )
        button.isEnabled = true
    }

    private fun showError() {
        if (!isActivityUsable()) return
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        val reason = WallpaperRepository.lastError
        status.text = getString(R.string.download_error) +
            (if (reason != null) "\n\nMotivo: $reason" else "") +
            "\n\n" + getString(R.string.update_will_retry_on_unlock)
        button.isEnabled = false
    }

    private fun isActivityUsable(): Boolean = !(leavingActivity || isFinishing || isDestroyed)

    private fun saveAndOpenWallpaperPicker() {
        WallpaperRepository.saveState(this, preview.state)
        val component = ComponentName(this, SatelliteWallpaperService::class.java)
        val intent = Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER").apply {
            putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT", component)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            startActivity(Intent("android.service.wallpaper.LIVE_WALLPAPER_CHOOSER"))
        }
    }

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_CONFIRM = 1002
        private const val REQ_NOTIFICATIONS = 2001
    }
}
