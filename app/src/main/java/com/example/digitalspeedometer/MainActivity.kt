package com.example.digitalspeedometer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var speedTextView: TextView
    private lateinit var limitTextView: TextView
    private var speedLimitKmh: Int? = null
    private var lastApiFetchTime: Long = 0L
    private var locationManager: LocationManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fő elrendezés (fekvő, fekete háttér)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        // Bal oldal: Aktuális sebesség
        val leftLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        speedTextView = TextView(this).apply {
            text = "0"
            textSize = 150f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val unitTextView = TextView(this).apply {
            text = "km/h"
            textSize = 28f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }

        leftLayout.addView(speedTextView)
        leftLayout.addView(unitTextView)

        // Jobb oldal: Sebességhatár tábla
        val rightLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val signDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(36, Color.parseColor("#D50000"))
        }

        limitTextView = TextView(this).apply {
            text = "--"
            textSize = 64f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = signDrawable
            layoutParams = LinearLayout.LayoutParams(360, 360)
        }

        val limitLabelView = TextView(this).apply {
            text = "Sebességhatár"
            textSize = 20f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        rightLayout.addView(limitTextView)
        rightLayout.addView(limitLabelView)

        rootLayout.addView(leftLayout)
        rootLayout.addView(rightLayout)
        setContentView(rootLayout)

        checkLocationPermission()
    }

    private fun checkLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1001
            )
        } else {
            startGpsTracking()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGpsTracking()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsTracking() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val currentSpeedKmh = if (location.hasSpeed()) {
                    (location.speed * 3.6f).roundToInt()
                } else {
                    0
                }
                updateSpeedUI(currentSpeedKmh)

                val now = System.currentTimeMillis()
                if (now - lastApiFetchTime > 8000L) {
                    lastApiFetchTime = now
                    fetchSpeedLimitFromOSM(location.latitude, location.longitude)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateSpeedUI(currentSpeedKmh: Int) {
        speedTextView.text = currentSpeedKmh.toString()
        val limit = speedLimitKmh
        val color = when {
            limit == null || limit <= 0 -> Color.WHITE
            currentSpeedKmh <= limit -> Color.parseColor("#00E676") // ZÖLD
            currentSpeedKmh <= limit * 1.10 -> Color.parseColor("#FFD600") // SÁRGA (1-10%)
            else -> Color.parseColor("#FF1744") // PIROS (10% felett)
        }
        speedTextView.setTextColor(color)
    }

    private fun fetchSpeedLimitFromOSM(lat: Double, lon: Double) {
        thread {
            try {
                val client = OkHttpClient()
                val query = """[out:json];way(around:35,$lat,$lon)["maxspeed"];out tags;"""
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "https://overpass-api.de"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AndroidDigitalSpeedometerApp/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val elements = json.getJSONArray("elements")

                    if (elements.length() > 0) {
                        val tags = elements.getJSONObject(0).getJSONObject("tags")
                        val maxSpeedStr = tags.optString("maxspeed", "")
                        val parsedLimit = when {
                            maxSpeedStr.toIntOrNull() != null -> maxSpeedStr.toInt()
                            maxSpeedStr.contains("urban") -> 50
                            maxSpeedStr.contains("rural") -> 90
                            maxSpeedStr.contains("motorway") -> 130
                            else -> null
                        }
                        if (parsedLimit != null) {
                            speedLimitKmh = parsedLimit
                            mainHandler.post {
                                limitTextView.text = parsedLimit.toString()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
