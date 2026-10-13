package com.wilson.win10launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Light between today's sunrise and sunset, dark otherwise.
 * Times use the NOAA sunrise equation from the device location.
 */
object Daylight {

    fun isDay(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val place = place(ctx) ?: return false
        val (rise, set) = sunTimes(place.first, place.second, now)
        return now in rise..set
    }

    /** "6:42 PM" style label for the next change, or null without a location. */
    fun nextChange(ctx: Context, now: Long = System.currentTimeMillis()): Long? {
        val place = place(ctx) ?: return null
        val (rise, set) = sunTimes(place.first, place.second, now)
        return when {
            now < rise -> rise
            now < set -> set
            else -> sunTimes(place.first, place.second, now + 20 * 60 * 60 * 1000L).first
        }
    }

    fun hasPlace(ctx: Context) = place(ctx) != null

    fun refresh(ctx: Context, then: () -> Unit) {
        if (!allowed(ctx)) {
            then()
            return
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val cached = last(lm)
        if (cached != null && System.currentTimeMillis() - cached.time < 6 * 60 * 60 * 1000L) {
            store(ctx, cached)
            then()
            return
        }
        val provider = when {
            tryEnabled(lm, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            tryEnabled(lm, LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        }
        if (provider == null) {
            if (cached != null) store(ctx, cached)
            then()
            return
        }
        var done = false
        fun finish() {
            if (done) return
            done = true
            then()
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lm.removeUpdates(this)
                store(ctx, location)
                finish()
            }

            @Deprecated("LocationListener")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            Handler(Looper.getMainLooper()).postDelayed({
                if (done) return@postDelayed
                lm.removeUpdates(listener)
                if (cached != null) store(ctx, cached)
                finish()
            }, 8000L)
        } catch (e: Exception) {
            if (cached != null) store(ctx, cached)
            finish()
        }
    }

    private fun allowed(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun place(ctx: Context): Pair<Double, Double>? {
        val sp = ctx.getSharedPreferences("win10", Context.MODE_PRIVATE)
        if (!sp.contains("sunLat") || !sp.contains("sunLon")) return null
        return sp.getFloat("sunLat", 0f).toDouble() to sp.getFloat("sunLon", 0f).toDouble()
    }

    private fun store(ctx: Context, loc: Location) {
        ctx.getSharedPreferences("win10", Context.MODE_PRIVATE).edit()
            .putFloat("sunLat", loc.latitude.toFloat())
            .putFloat("sunLon", loc.longitude.toFloat())
            .apply()
    }

    private fun last(lm: LocationManager): Location? {
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(p)
                if (loc != null) return loc
            } catch (e: Exception) {
                // provider missing or permission dropped
            }
        }
        return null
    }

    private fun tryEnabled(lm: LocationManager, provider: String): Boolean {
        return try {
            lm.isProviderEnabled(provider)
        } catch (e: Exception) {
            false
        }
    }

    /** Sunrise and sunset as epoch millis for the local calendar day of [whenMs]. */
    private fun sunTimes(lat: Double, lon: Double, whenMs: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = whenMs
        val jDate = julian(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)) + 0.5
        val n = jDate - 2451545.0 + 0.0008
        val jStar = n - lon / 360.0
        val m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360.0)
        val c = 1.9148 * sin(m) + 0.0200 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = Math.toRadians((Math.toDegrees(m) + c + 180.0 + 102.9372) % 360.0)
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val delta = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
        val latR = Math.toRadians(lat)
        val cosOmega = (sin(Math.toRadians(-0.833)) - sin(latR) * sin(delta)) / (cos(latR) * cos(delta))
        if (cosOmega >= 1.0) return 0L to 0L
        if (cosOmega <= -1.0) return whenMs - 1 to whenMs + 86_400_000L
        val omega = Math.toDegrees(acos(cosOmega))
        val rise = julianToMillis(jTransit - omega / 360.0)
        val set = julianToMillis(jTransit + omega / 360.0)
        return rise to set
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun julianToMillis(j: Double): Long = ((j - 2440587.5) * 86_400_000.0).toLong()
}
