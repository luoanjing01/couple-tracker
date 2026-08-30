package com.coupletracker.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * GPS/网络定位追踪器：基于 Google Fused Location Provider
 * - 每5秒上报一次位置（3秒内位移<5m则跳过，省电省流量）
 * - 使用 UsageStatsManager 同级别协程，保证后台上报
 */
class LocationTracker(private val context: Context, private val scope: CoroutineScope) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null
    private var lastLocation: Location? = null
    private var lastReportAt = 0L
    @Volatile private var batteryPct: Int? = null

    /** TrackerService 每10秒把电量缓存到这里，上报位置时顺便带上 */
    fun setBatteryCache(pct: Int) { batteryPct = pct }

    fun hasPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun start(intervalMs: Long = 5000L) {
        if (!hasPermission()) return
        runCatching { fusedClient.lastLocation.await()?.let { report(it, force = true) } }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs * 2)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.lastLocation?.let { report(it, force = false) }
            }
        }
        locationCallback = cb
        fusedClient.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    fun stop() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun report(loc: Location, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && lastLocation != null) {
            val delta = now - lastReportAt
            val moved = loc.distanceTo(lastLocation!!)
            if (delta < 3000 && moved < 5f) return
        }
        lastLocation = loc; lastReportAt = now
        val isMoving = (loc.hasSpeed() && loc.speed > 0.5f)

        scope.launch(Dispatchers.IO) {
            val user = UserRepository.get().getUser()
            val userId = user?.id ?: return@launch
            val coupleId = user?.coupleCode?.let { _ ->
                // 简化：用 userId 当 couple_id 占位
                userId
            } ?: userId

            runCatching {
                NetworkModule.restService.reportLocation(
                    com.coupletracker.android.data.LocationRow(
                        user_id = userId,
                        couple_id = coupleId,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                        speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                        battery_level = batteryPct,
                        is_moving = isMoving
                    )
                )
            }
        }
    }

    @Suppress("unused")
    @SuppressLint("MissingPermission")
    private fun getLastKnownSystem(): Location? {
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            best
        }.getOrNull()
    }
}
