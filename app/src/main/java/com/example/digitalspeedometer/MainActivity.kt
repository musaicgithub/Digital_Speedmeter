package hu.sebessegmero.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : Activity(), LocationListener {

    companion object {
        private const val REQ_LOCATION = 1
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val MIN_MOVE_METERS = 60f
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MAX_INTERVAL_MS = 30_000L

        private val GREEN = Color.parseColor("#00E676")
        private val YELLOW = Color.parseColor("#FFD600")
        private val RED = Color.parseColor("#FF1744")
    }

    private lateinit var locationManager: LocationManager
    private lateinit var speedText: TextView
    private lateinit var unitText: TextView
    private lateinit var statusText: TextView
    private lateinit var signView: SpeedSignView

    private val executor = Executors.newSingleThreadExecutor()
    private val queryInFlight = AtomicBoolean(false)

    private var lastQueryLat = Double.NaN
    private var lastQueryLon = Double.NaN
    private var lastQueryTime = 0L

    private var currentLimit: Int? = null
    private var currentSpeed: Int = 0

    // ---------------------------------------------------------------- életciklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 4. követelmény: a képernyő ne aludjon el
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
        hideSystemBars()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            statusText.text = "Helymeghatározási engedély szükséges"
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                startLocationUpdates()
            } else {
                statusText.text = "Az engedély megtagadva – a mérés nem működik"
            }
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Bal oldal: hatalmas sebesség + mértékegység
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        speedText = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(
                40, 700, 2, TypedValue.COMPLEX_UNIT_SP
            )
        }
        left.addView(
            speedText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        unitText = TextView(this).apply {
            text = "km/h"
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
        }
        left.addView(
            unitText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        row.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.6f)
        )

        // Jobb oldal: közlekedési tábla
        val right = FrameLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        signView = SpeedSignView(this)
        right.addView(
            signView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        row.addView(
            right,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f)
        )

        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        statusText = TextView(this).apply {
            text = "GPS jel keresése…"
            setTextColor(Color.parseColor("#757575"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /** 3. követelmény: színezés a sebességhatár alapján. */
    private fun refreshUi() {
        speedText.text = currentSpeed.toString()

        val limit = currentLimit
        val color = when {
            limit == null -> Color.WHITE
            currentSpeed <= limit -> GREEN
            currentSpeed <= limit * 1.10 -> YELLOW
            else -> RED
        }
        speedText.setTextColor(color)
        signView.limit = limit
    }

    // ---------------------------------------------------------------- GPS

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                statusText.text = "Kapcsold be a GPS-t (Helymeghatározás)"
            } else {
                statusText.text = "GPS jel keresése…"
            }
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500L, 0f, this
            )
        } catch (e: SecurityException) {
            statusText.text = "Nincs helymeghatározási engedély"
        } catch (e: IllegalArgumentException) {
            statusText.text = "Nincs GPS-szolgáltató ezen az eszközön"
        }
    }

    override fun onLocationChanged(location: Location) {
        val kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        currentSpeed = if (kmh < 1f) 0 else kmh.roundToInt()

        statusText.text = String.format(
            Locale.US, "GPS pontosság: ±%d m", location.accuracy.roundToInt()
        )
        refreshUi()
        maybeQuerySpeedLimit(location)
    }

    override fun onProviderEnabled(provider: String) {
        statusText.text = "GPS jel keresése…"
    }

    override fun onProviderDisabled(provider: String) {
        statusText.text = "Kapcsold be a GPS-t (Helymeghatározás)"
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Régebbi Android-verziók miatt kell, nem csinál semmit.
    }

    // ---------------------------------------------------------------- Overpass

    private fun maybeQuerySpeedLimit(location: Location) {
        if (queryInFlight.get()) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastQueryTime

        val shouldQuery = if (lastQueryLat.isNaN()) {
            true
        } else {
            val res = FloatArray(1)
            Location.distanceBetween(
                lastQueryLat, lastQueryLon, location.latitude, location.longitude, res
            )
            (res[0] > MIN_MOVE_METERS && elapsed > MIN_INTERVAL_MS) || elapsed > MAX_INTERVAL_MS
        }
        if (!shouldQuery) return

        // Csak mozgás közben, vagy ha még sosem kérdeztünk, érdemes lekérdezni
        if (!lastQueryLat.isNaN() && currentSpeed == 0 && elapsed < MAX_INTERVAL_MS) return

        if (!queryInFlight.compareAndSet(false, true)) return
        val lat = location.latitude
        val lon = location.longitude
        lastQueryLat = lat
        lastQueryLon = lon
        lastQueryTime = now

        executor.execute {
            try {
                val limit = fetchSpeedLimit(lat, lon)
                runOnUiThread {
                    currentLimit = limit
                    refreshUi()
                }
            } catch (_: Exception) {
                // Hálózati hiba: az utolsó ismert határt megtartjuk,
                // a következő ciklusban újra próbálkozunk.
            } finally {
                queryInFlight.set(false)
            }
        }
    }

    /** Lekéri a legközelebbi út maxspeed értékét. Háttérszálon fut. */
    private fun fetchSpeedLimit(lat: Double, lon: Double): Int? {
        val latS = String.format(Locale.US, "%.6f", lat)
        val lonS = String.format(Locale.US, "%.6f", lon)

        val query = "[out:json][timeout:10];" +
            "way(around:35,$latS,$lonS)" +
            "[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|" +
            "residential|living_street|service|motorway_link|trunk_link|primary_link|" +
            "secondary_link|tertiary_link)\$\"];" +
            "out tags geom;"

        val url = URL(OVERPASS_URL + "?data=" + URLEncoder.encode(query, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "SebessegmeroApp/1.0 (Android)")
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val elements = JSONObject(body).optJSONArray("elements") ?: return null

            data class Candidate(val dist: Double, val limit: Int?)

            val candidates = ArrayList<Candidate>()
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val geom = el.optJSONArray("geometry") ?: continue
                if (geom.length() < 2) continue
                val tags = el.optJSONObject("tags")
                val limit = parseMaxSpeed(tags?.optString("maxspeed", "") ?: "")

                var best = Double.MAX_VALUE
                for (j in 0 until geom.length() - 1) {
                    val a = geom.getJSONObject(j)
                    val b = geom.getJSONObject(j + 1)
                    val d = distToSegmentMeters(
                        lat, lon,
                        a.getDouble("lat"), a.getDouble("lon"),
                        b.getDouble("lat"), b.getDouble("lon")
                    )
                    best = min(best, d)
                }
                candidates.add(Candidate(best, limit))
            }
            if (candidates.isEmpty()) return null

            candidates.sortBy { it.dist }
            val nearest = candidates.first().dist
            // A legközelebbi úthoz (kb. ugyanolyan távolságra) tartozó első érvényes határ
            return candidates.firstOrNull { it.dist <= nearest + 15.0 && it.limit != null }?.limit
        } finally {
            conn.disconnect()
        }
    }

    private fun parseMaxSpeed(raw: String): Int? {
        val s = raw.trim().lowercase(Locale.US)
        if (s.isEmpty()) return null
        return when (s) {
            "none", "signals", "variable" -> null
            "walk" -> 5
            "hu:urban" -> 50
            "hu:rural" -> 90
            "hu:trunk" -> 110
            "hu:motorway" -> 130
            "hu:living_street" -> 20
            else -> {
                val m = Regex("^(\\d+)").find(s) ?: return null
                val n = m.groupValues[1].toInt()
                if (s.contains("mph")) (n * 1.609).roundToInt() else n
            }
        }
    }

    /** Pont–szakasz távolság méterben (lokális síkvetítéssel). */
    private fun distToSegmentMeters(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Double {
        val kx = cos(Math.toRadians(pLat)) * 111_320.0
        val ky = 110_540.0
        val ax = (aLon - pLon) * kx
        val ay = (aLat - pLat) * ky
        val bx = (bLon - pLon) * kx
        val by = (bLat - pLat) * ky

        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, (-ax * dx - ay * dy) / len2))
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }
}

/** Európai stílusú sebességkorlátozó tábla (piros gyűrű, fehér mező, fekete szám). */
class SpeedSignView(context: Context) : View(context) {

    var limit: Int? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val outerR = size / 2f * 0.96f
        val innerR = outerR * 0.78f

        val l = limit
        ringPaint.color = if (l == null) Color.parseColor("#616161") else Color.parseColor("#D50000")
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.drawCircle(cx, cy, innerR, facePaint)

        val label = l?.toString() ?: "?"
        textPaint.color = if (l == null) Color.parseColor("#9E9E9E") else Color.BLACK
        textPaint.textSize = when (label.length) {
            1 -> innerR * 1.25f
            2 -> innerR * 1.15f
            else -> innerR * 0.95f
        }
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }
}
