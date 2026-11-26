package com.example.digitalspeedometer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0D0D0D)
                ) {
                    SpeedometerScreen()
                }
            }
        }
    }
}

@Composable
fun SpeedometerScreen() {
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (!hasLocationPermission) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "A sebességméréshez GPS engedély szükséges.",
                color = Color.White,
                fontSize = 22.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }) {
                Text("Engedély megadása", fontSize = 18.sp)
            }
        }
    } else {
        ActiveSpeedometer()
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ActiveSpeedometer() {
    val context = LocalContext.current
    var currentSpeedKmh by remember { mutableIntStateOf(0) }
    var speedLimitKmh by remember { mutableStateOf<Int?>(null) }
    var currentCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var lastApiFetchTime by remember { mutableLongStateOf(0L) }

    // Beépített Android LocationManager használata külső Google Play Services nélkül
    DisposableEffect(Unit) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val speed = if (location.hasSpeed()) {
                    (location.speed * 3.6f).roundToInt()
                } else {
                    0
                }
                currentSpeedKmh = speed
                currentCoords = Pair(location.latitude, location.longitude)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                locationListener
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onDispose {
            locationManager.removeUpdates(locationListener)
        }
    }

    LaunchedEffect(currentCoords) {
        val coords = currentCoords ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastApiFetchTime > 8000L) {
            lastApiFetchTime = now
            val fetchedLimit = fetchSpeedLimitFromOSM(coords.first, coords.second)
            if (fetchedLimit != null) {
                speedLimitKmh = fetchedLimit
            }
        }
    }

    val speedColor = remember(currentSpeedKmh, speedLimitKmh) {
        val limit = speedLimitKmh
        when {
            limit == null || limit <= 0 -> Color.White
            currentSpeedKmh <= limit -> Color(0xFF00E676)
            currentSpeedKmh <= limit * 1.10 -> Color(0xFFFFD600)
            else -> Color(0xFFFF1744)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentSpeedKmh.toString(),
                color = speedColor,
                fontSize = 180.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 180.sp
            )
            Text(
                text = "km/h",
                color = Color.Gray,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(180.dp)
                    .background(Color.White, shape = CircleShape)
                    .border(width = 18.dp, color = Color(0xFFD50000), shape = CircleShape)
            ) {
                Text(
                    text = speedLimitKmh?.toString() ?: "--",
                    color = Color.Black,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Sebességhatár",
                color = Color.Gray,
                fontSize = 20.sp
            )
        }
    }
}

suspend fun fetchSpeedLimitFromOSM(lat: Double, lon: Double): Int? = withContext(Dispatchers.IO) {
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
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val elements = json.getJSONArray("elements")

            if (elements.length() > 0) {
                val tags = elements.getJSONObject(0).getJSONObject("tags")
                val maxSpeedStr = tags.optString("maxspeed", "")
                return@withContext when {
                    maxSpeedStr.toIntOrNull() != null -> maxSpeedStr.toInt()
                    maxSpeedStr.contains("urban") -> 50
                    maxSpeedStr.contains("rural") -> 90
                    maxSpeedStr.contains("motorway") -> 130
                    else -> null
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return@withContext null
}
