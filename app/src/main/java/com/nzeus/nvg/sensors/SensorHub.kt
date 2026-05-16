package com.nzeus.nvg.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.BatteryManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class SensorHub(private val ctx: Context) : SensorEventListener {

    data class State(
        val lux: Float = 0f,
        val heading: Float = 0f,
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val alt: Double = 0.0,
        val accuracy: Float = 0f,
        val batteryPct: Int = 0,
    )

    @Volatile var state = State()
        private set

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val locClient = LocationServices.getFusedLocationProviderClient(ctx)

    fun start() {
        light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        requestLocation()
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    private fun requestLocation() {
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L).build()
        locClient.requestLocationUpdates(req, { loc -> onLocation(loc) }, ctx.mainLooper)
    }

    private fun onLocation(loc: Location) {
        state = state.copy(
            lat = loc.latitude, lon = loc.longitude,
            alt = loc.altitude, accuracy = loc.accuracy,
        )
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> state = state.copy(lux = e.values[0])
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, e.values)
                val o = FloatArray(3)
                SensorManager.getOrientation(r, o)
                val heading = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
                state = state.copy(
                    heading = heading,
                    pitch = Math.toDegrees(o[1].toDouble()).toFloat(),
                    roll = Math.toDegrees(o[2].toDouble()).toFloat(),
                )
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    fun pollBattery() {
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        state = state.copy(batteryPct = pct)
    }
}
