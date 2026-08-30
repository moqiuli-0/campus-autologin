package com.campusnet.autologin

import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 一次性定位器：优先返回 2 分钟内的缓存定位，否则监听 网络定位/GPS 直到拿到第一个结果或超时。
 * 仅供"是否在学校"的粗略判断（精度几十米即可），不做持续定位。
 */
object CampusLocator {

    suspend fun getCurrentLocation(context: Context, timeoutMs: Long = 6000L): Pair<Double, Double>? =
        withContext(Dispatchers.Main) {
            val lm = context.applicationContext
                .getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER
            ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (providers.isEmpty()) return@withContext null

            // 快路径：2 分钟内的缓存定位直接采用
            val latest = providers.mapNotNull { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            }.maxByOrNull { it.time }
            if (latest != null && System.currentTimeMillis() - latest.time <= 120_000L) {
                return@withContext Pair(latest.latitude, latest.longitude)
            }

            try {
                withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        val listener = object : android.location.LocationListener {
                            override fun onLocationChanged(loc: android.location.Location) {
                                if (cont.isActive) cont.resume(Pair(loc.latitude, loc.longitude))
                            }
                        }
                        val executor = android.os.Looper.getMainLooper()
                        providers.forEach { p ->
                            runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, executor) }
                        }
                        cont.invokeOnCancellation {
                            providers.forEach { p -> runCatching { lm.removeUpdates(listener) } }
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
}
