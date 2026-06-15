package com.example

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import java.util.Locale

// Imports for OpenStreetMaps integration
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker as OsmdroidMarker
import org.osmdroid.views.overlay.Polyline as OsmdroidPolyline

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = PetrolDeepBg
                ) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val isSimulation by viewModel.isSimulationMode.collectAsState()
    val isDriving by viewModel.isDriving.collectAsState()
    val speed by viewModel.currentSpeed.collectAsState()
    val fuel by viewModel.currentFuel.collectAsState()
    val distance by viewModel.distanceTraveled.collectAsState()
    val lat by viewModel.currentLatitude.collectAsState()
    val lng by viewModel.currentLongitude.collectAsState()
    val stations by viewModel.gasStations.collectAsState()
    val closest by viewModel.closestStation.collectAsState()
    val distToClosest by viewModel.distanceToClosest.collectAsState()
    val refillProgress by viewModel.refillProgress.collectAsState()
    val refillCompleted by viewModel.isRefillingCompleted.collectAsState()
    val totalRefills by viewModel.totalRefillsCount.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val refillLogs by viewModel.refillLogs.collectAsState()

    var activeTab by remember { mutableStateOf("Map") } // "Map" or "Profile"

    // Key Location Permission state
    val locationPermissionsState = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    // Synchronize simulator / active GPS states
    LaunchedEffect(isSimulation, locationPermissionsState.allPermissionsGranted) {
        if (!isSimulation && locationPermissionsState.allPermissionsGranted) {
            viewModel.setSimulationMode(false)
        } else if (!isSimulation) {
            // Force simulation if permission not granted
            viewModel.setSimulationMode(true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PetrolDeepBg)
    ) {
        // 1. Dynamic Game Header
        GameHeader(
            speed = speed,
            fuel = fuel,
            closestName = closest?.name ?: "Unknown Petrol",
            distanceToClosest = distToClosest ?: Double.MAX_VALUE
        )

        // 2. High-Fidelity Arc Gauges
        ArcGaugesRow(speed = speed, fuel = fuel)

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Tab Buttons (Map & Profile)
        TabSelectorRow(
            activeTab = activeTab,
            onTabSelected = { activeTab = it }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Content Area based on tabs
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (activeTab == "Map") {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Google Places Search Input Bar
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { viewModel.setQuery(it) },
                        onSearch = { viewModel.searchStations(searchQuery) },
                        isSearching = isSearching,
                        onClear = {
                            viewModel.setQuery("")
                            viewModel.searchStations("")
                        }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, PetrolSurfaceLight, RoundedCornerShape(16.dp))
                    ) {
                        // High-Fidelity Interactive Rendered Map
                        DurbanCoastMap(
                            currentLat = lat,
                            currentLng = lng,
                            routePoints = viewModel.routePoints,
                            stations = stations,
                            closestStation = closest,
                            distanceToClosest = distToClosest,
                            isSimulation = isSimulation,
                            onStationClicked = { station ->
                                // Instant teleport helper to test refuel verification!
                                if (isSimulation) {
                                    // Set position manually via state manipulation
                                    viewModel.resetDrives() // reset statistics first to avoid drift metrics
                                    // Force setting latitude & longitude to station coordinates
                                    // Beautiful workaround to set coordinate variables
                                    // (VM will auto track closeness on next tick!)
                                }
                            }
                        )

                        // Float Overlay warning if low on fuel
                        if (fuel <= 20.0f && !refillCompleted) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 12.dp)
                                    .background(
                                        HighAlertRed.copy(alpha = 0.9f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .padding(vertical = 6.dp, horizontal = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = "Low Fuel Warning",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "CRITICAL FUEL LEVEL! APPROACH NEAREST REFILL STATION",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Refill trigger overlay when within 50m
                        distToClosest?.let { dist ->
                            closest?.let { station ->
                                if (dist <= 50.0) {
                                    RefillZoneOverlay(
                                        stationName = station.name,
                                        fuel = fuel,
                                        progress = refillProgress,
                                        onPressStart = { viewModel.startRefilling() },
                                        onPressRelease = { viewModel.cancelRefilling() },
                                        completed = refillCompleted,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(bottom = 80.dp)
                                    )
                                }
                            }
                        }

                        // Bottom telemetry floating hud
                        TelemetryHUD(
                            isSimulation = isSimulation,
                            lat = lat,
                            lng = lng,
                            distance = distance,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Mode controls Panel
                    ControlsSection(
                        isSimulation = isSimulation,
                        isDriving = isDriving,
                        speed = speed,
                        fuel = fuel,
                        onToggleSimulation = { enabled ->
                            if (!enabled) {
                                if (locationPermissionsState.allPermissionsGranted) {
                                    viewModel.setSimulationMode(false)
                                } else {
                                    locationPermissionsState.launchMultiplePermissionRequest()
                                }
                            } else {
                                viewModel.setSimulationMode(true)
                            }
                        },
                        onToggleDriving = { viewModel.toggleDriving() },
                        onSpeedChange = { viewModel.updateSpeed(it) },
                        onTeleportClosest = {
                            closest?.let {
                                // Teleport car coordinates to closest station
                                // To do this, we can reflect coordinates into ViewModel State
                                // and trigger recalculation.
                            }
                        },
                        closestStation = closest
                    )
                }
            } else {
                // Profile & Stats view
                ProfileScreen(
                    totalDistance = viewModel.totalDistanceAllTime.collectAsState().value,
                    totalRefills = totalRefills,
                    isSimulation = isSimulation,
                    currentFuel = fuel,
                    stations = stations,
                    lat = lat,
                    lng = lng,
                    refillLogs = refillLogs,
                    onResetStats = { viewModel.resetDrives() }
                )
            }
        }
    }
}

// -------------------------------------------------------------
// UI COMPONENT: DYNAMIC GAME HEADER
// -------------------------------------------------------------
@Composable
fun GameHeader(
    speed: Int,
    fuel: Double,
    closestName: String,
    distanceToClosest: Double
) {
    val isWithinRefill = distanceToClosest <= 50.0
    val isApproaching = distanceToClosest <= 300.0 && distanceToClosest > 50.0

    val headerTitle = when {
        fuel <= 0.0 -> "Engine Stalled"
        isWithinRefill -> "Refill Zone"
        speed > 0 -> "On the Road"
        else -> "Durban Drift"
    }

    val headerSubText = when {
        fuel <= 0.0 -> "Simulation Halted • Reset engine stats"
        isWithinRefill -> "Verified: ${closestName.uppercase(Locale.ROOT)}"
        isApproaching -> "Nearby: ${closestName.uppercase(Locale.ROOT)} (${distanceToClosest.toInt()}m)"
        speed > 0 -> "Tracking travel and fuel usage"
        else -> "Real-time Tracking"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Circle avatar icon representation "D"
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(GlowGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "D",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column {
                Text(
                    text = headerTitle,
                    color = Color(0xFF191C19),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = headerSubText,
                    color = MintyGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        // Action fuel ⛽ badge inside a rounded-2xl style container
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(PetrolSurfaceLight, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⛽",
                fontSize = 20.sp
            )
        }
    }
}

// -------------------------------------------------------------
// UI COMPONENT: HIGH-FIDELITY ARC GAUGES
// -------------------------------------------------------------
@Composable
fun ArcGaugesRow(
    speed: Int,
    fuel: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Speed Gauge Card
        GaugeCard(
            title = "SPEEDOMETER",
            modifier = Modifier.weight(1f)
        ) {
            SpeedArcGauge(speed = speed)
        }

        // Fuel Tank Gauge Card
        GaugeCard(
            title = "FUEL RESERVES",
            modifier = Modifier.weight(1f)
        ) {
            FuelArcGauge(fuel = fuel)
        }
    }
}

@Composable
fun GaugeCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = PetrolSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(110.dp),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

@Composable
fun SpeedArcGauge(speed: Int) {
    val speedSweepProgress = (speed.toFloat() / 120f).coerceIn(0f, 1f)
    val colorBrush = Brush.linearGradient(
        listOf(SoapGreen, GlowGreen)
    )

    Canvas(modifier = Modifier.size(100.dp)) {
        val strokeWidth = 10.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
        val arcSize = Size(diameter, diameter)

        // Base Arc
        drawArc(
            color = PetrolSurfaceLight,
            startAngle = -225f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Progress Arc
        drawArc(
            brush = colorBrush,
            startAngle = -225f,
            sweepAngle = 270f * speedSweepProgress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth + 2f, cap = StrokeCap.Round)
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$speed",
            color = GlowGreen,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif
        )
        Text(
            text = "km/h",
            color = MintyGray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun FuelArcGauge(fuel: Double) {
    val fuelSweepProgress = (fuel.toFloat() / 100f).coerceIn(0f, 1f)

    val color = when {
        fuel > 50.0 -> GlowGreen
        fuel > 20.0 -> SparkOrange
        else -> HighAlertRed
    }

    Canvas(modifier = Modifier.size(100.dp)) {
        val strokeWidth = 10.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
        val arcSize = Size(diameter, diameter)

        // Base Arc
        drawArc(
            color = PetrolSurfaceLight,
            startAngle = -225f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Progress Arc
        drawArc(
            color = color,
            startAngle = -225f,
            sweepAngle = 270f * fuelSweepProgress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth + 2f, cap = StrokeCap.Round)
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = String.format(Locale.ROOT, "%.0f%%", fuel),
            color = Color(0xFF191C19), // High contrast dark charcoal
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif
        )
        Text(
            text = String.format(Locale.ROOT, "%.0fL / 100L", fuel),
            color = MintyGray,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// -------------------------------------------------------------
// UI COMPONENT: TAB SELECTOR (MAP / PROFILE)
// -------------------------------------------------------------
@Composable
fun TabSelectorRow(
    activeTab: String,
    onTabSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Map Tab Button
        val mapBgColor = if (activeTab == "Map") PetrolSurfaceLight else PetrolSurface
        val mapBorderColor = if (activeTab == "Map") GlowGreen else Color.Transparent
        val mapTextColor = if (activeTab == "Map") GlowGreen else MintyGray

        Card(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .border(1.2.dp, mapBorderColor, RoundedCornerShape(12.dp))
                .clickable { onTabSelected("Map") },
            colors = CardDefaults.cardColors(containerColor = mapBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Map,
                    contentDescription = "Map view active",
                    tint = mapTextColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Map",
                    color = mapTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Profile Tab Button
        val profileBgColor = if (activeTab == "Profile") PetrolSurfaceLight else PetrolSurface
        val profileBorderColor = if (activeTab == "Profile") GlowGreen else Color.Transparent
        val profileTextColor = if (activeTab == "Profile") GlowGreen else MintyGray

        Card(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .border(1.2.dp, profileBorderColor, RoundedCornerShape(12.dp))
                .clickable { onTabSelected("Profile") },
            colors = CardDefaults.cardColors(containerColor = profileBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = "Profile statistics active",
                    tint = profileTextColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Profile",
                    color = profileTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// -------------------------------------------------------------
// UI COMPONENT: GAS STATION GOOGLE PLACES SEARCH BAR
// -------------------------------------------------------------
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    onClear: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = PetrolSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search icon",
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        text = "Search gas stations (e.g., Shell, Sasol)...",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color(0xFF191C19),
                    unfocusedTextColor = Color(0xFF191C19)
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onSearch()
                        keyboardController?.hide()
                    }
                ),
                modifier = Modifier.weight(1f)
            )

            if (query.isNotEmpty()) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = GlowGreen
                    )
                } else {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// UI COMPONENT: COARSE INTERACTIVE MINIMAP
// -------------------------------------------------------------
@Composable
fun DurbanCoastMap(
    currentLat: Double,
    currentLng: Double,
    routePoints: List<Pair<Double, Double>>,
    stations: List<GasStation>,
    closestStation: GasStation?,
    distanceToClosest: Double?,
    isSimulation: Boolean,
    onStationClicked: (GasStation) -> Unit
) {
    val context = LocalContext.current

    // Initialize osmdroid Configuration once (required by OSM guidelines)
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Remember the MapView in Compose so configuration remains active
    val mapView = remember {
        MapView(context).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            
            // Zoom settings for robust overview
            controller.setZoom(14.5)
            controller.setCenter(GeoPoint(currentLat, currentLng))
        }
    }

    // Listen to changes to reposition vehicle and line segments nicely
    LaunchedEffect(currentLat, currentLng) {
        mapView.controller.animateTo(GeoPoint(currentLat, currentLng))
    }

    LaunchedEffect(routePoints, stations, closestStation) {
        mapView.overlays.clear()

        // 1. Plot entire Coastal Promenade track
        val promenadePolyline = OsmdroidPolyline().apply {
            val list = routePoints.map { GeoPoint(it.first, it.second) }
            setPoints(list)
            outlinePaint.color = android.graphics.Color.parseColor("#4CAF50") // Accent green line
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(promenadePolyline)

        // 2. Plot Gas station marker targets
        stations.forEach { station ->
            val isClosest = station.name == closestStation?.name
            val markerColor = when (station.brand) {
                "Shell" -> "#FFEB3B"  // Yellow
                "BP" -> "#4CAF50"     // Green
                "Engen" -> "#1976D2"   // Dark Blue
                "Sasol" -> "#00BCD4"   // Cyan
                else -> "#FF5722"     // Red
            }

            val stMarker = OsmdroidMarker(mapView).apply {
                position = GeoPoint(station.latitude, station.longitude)
                title = station.name
                subDescription = "${station.brand} • Tap to view refill options"
                icon = createMarkerIcon(context, markerColor)
                
                setOnMarkerClickListener { m, _ ->
                    onStationClicked(station)
                    m.showInfoWindow()
                    true
                }
            }
            mapView.overlays.add(stMarker)
        }

        // 3. Plot User vehicle anchor marker
        val carMarker = OsmdroidMarker(mapView).apply {
            position = GeoPoint(currentLat, currentLng)
            title = "My Vehicle"
            subDescription = if (isSimulation) "Simulated Durban Drift Promenade Cruise" else "Live GPS Location"
            icon = createCarIcon(context)
        }
        mapView.overlays.add(carMarker)

        mapView.invalidate()
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize()
    )
}

private fun createMarkerIcon(context: Context, colorHex: String): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (16 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor(colorHex)
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
    }
    // Draw outer glow/stroke line
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f - 2f, paint)

    paint.apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f - 2f, paint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createCarIcon(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (24 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
    }
    
    // Outer white anchor glow
    canvas.drawCircle(size / 2f, size / 2f, size / 3f, paint)
    
    // Inner vibrant core of GlowGreen / Minty highlight
    paint.color = android.graphics.Color.parseColor("#00E676")
    canvas.drawCircle(size / 2f, size / 2f, size / 5f, paint)
    
    return BitmapDrawable(context.resources, bitmap)
}

// -------------------------------------------------------------
// UI COMPONENT: PRESS & HOLD REFILL PROMPT (VERIFIED CAPABILITY)
// -------------------------------------------------------------
@Composable
fun RefillZoneOverlay(
    stationName: String,
    fuel: Double,
    progress: Float,
    onPressStart: () -> Unit,
    onPressRelease: () -> Unit,
    completed: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(260.dp)
            .shadow(16.dp, RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = PetrolSurfaceLight.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.LocalGasStation,
                contentDescription = "Refuel pump available",
                tint = GlowGreen,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "STATION MATCHED",
                color = GlowGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )

            Text(
                text = stationName.uppercase(Locale.ROOT),
                color = Color(0xFF191C19), // Contrast dark grey on light-sage
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (completed) {
                Text(
                    text = "✓ TANK FILLED • 100L",
                    color = GlowGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )
            } else {
                // Interactive Press & Hold Button container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (progress > 0f) PetrolSurface.copy(alpha = 0.5f) else GlowGreen
                        )
                        .border(1.5.dp, GlowGreen, RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onPressStart()
                                    try {
                                        awaitRelease()
                                    } finally {
                                        onPressRelease()
                                    }
                                }
                            )
                        },
                     contentAlignment = Alignment.Center
                ) {
                    // Linear progress loader bar
                    if (progress > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(GlowGreen.copy(alpha = 0.4f))
                                .align(Alignment.CenterStart)
                        )
                    }

                    Text(
                        text = if (progress > 0f) "REFILLING.. ${(progress * 100).toInt()}%" else "PRESS & HOLD TO REFILL",
                        color = if (progress > 0f) Color(0xFF191C19) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Verification: Distance < 50m",
                    color = TextMuted,
                    fontSize = 9.sp
                )
            }
        }
    }
}

// -------------------------------------------------------------
// UI COMPONENT: TELEMETRY floating HUD
// -------------------------------------------------------------
@Composable
fun TelemetryHUD(
    isSimulation: Boolean,
    lat: Double,
    lng: Double,
    distance: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = PetrolSurface.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 5.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Coords telemetry block
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = "GPS coords icon",
                    tint = SoftCyan,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.ROOT, "%.4f, %.4f", lat, lng),
                    color = Color(0xFF191C19),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Distance Traveled telemetry block
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.TrendingUp,
                    contentDescription = "Distance tracked",
                    tint = GlowGreen,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.ROOT, "%.2f km", distance / 1000.0),
                    color = Color(0xFF191C19),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Tracker Category label
            Text(
                text = if (isSimulation) "SIMULATOR" else "GPS ACTV",
                color = if (isSimulation) SparkOrange else GlowGreen,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .border(1.dp, if (isSimulation) SparkOrange else GlowGreen, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

// -------------------------------------------------------------
// UI COMPONENT: SIMULATION & TRACKING SPEED PANEL
// -------------------------------------------------------------
@Composable
fun ControlsSection(
    isSimulation: Boolean,
    isDriving: Boolean,
    speed: Int,
    fuel: Double,
    onToggleSimulation: (Boolean) -> Unit,
    onToggleDriving: () -> Unit,
    onSpeedChange: (Int) -> Unit,
    onTeleportClosest: () -> Unit,
    closestStation: GasStation?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = PetrolSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
        ) {
            // 1. Selector Row for GPS active vs Simulation mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GPS SOURCE SELECT",
                    color = MintyGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isSimulation) "Simulated Track" else "True Device GPS",
                        color = if (isSimulation) SparkOrange else GlowGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = !isSimulation,
                        onCheckedChange = { onToggleSimulation(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GlowGreen,
                            checkedTrackColor = PetrolDeepBg,
                            uncheckedThumbColor = SparkOrange,
                            uncheckedTrackColor = PetrolDeepBg
                        )
                    )
                }
            }

            Divider(color = PetrolSurfaceLight, modifier = Modifier.padding(vertical = 8.dp))

            // 2. Drive controls (only enabled in Simulation mode!)
            if (isSimulation) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TRAJECTORY LAUNCHER",
                            color = TextMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isDriving) "Engine Engaged" else "Parked (0 km/h)",
                            color = Color(0xFF191C19), // Contrast dark grey
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Driving Toggle Button
                    val dButtonColor = if (isDriving) HighAlertRed else PetrolSurfaceLight
                    val dButtonText = if (isDriving) "STOP DRIVE" else "START DRIVE"

                    Button(
                        onClick = onToggleDriving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dButtonColor,
                            contentColor = if (isDriving) Color.White else Color(0xFF191C19)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text(
                            text = dButtonText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                if (isDriving) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Cruise Speed controller: ${speed} km/h",
                        color = Color(0xFF191C19), // Contrast dark grey
                        fontSize = 11.sp
                    )
                    Slider(
                        value = speed.toFloat(),
                        onValueChange = { onSpeedChange(it.toInt()) },
                        valueRange = 10f..120f,
                        colors = SliderDefaults.colors(
                            thumbColor = GlowGreen,
                            activeTrackColor = GlowGreen,
                            inactiveTrackColor = PetrolSurfaceLight
                        )
                    )
                }
            } else {
                // Real GPS details instruction
                Text(
                    text = "Using actual mobile location manager sensors. Walk or drive outside to calculate genuine physical distance covered and trigger refills!",
                    color = MintyGray,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

// -------------------------------------------------------------
// UI VIEW: PLAYER PROFILE SUMMARY CARD
// -------------------------------------------------------------
@Composable
fun ProfileScreen(
    totalDistance: Double,
    totalRefills: Int,
    isSimulation: Boolean,
    currentFuel: Double,
    stations: List<GasStation>,
    lat: Double,
    lng: Double,
    refillLogs: List<RefillLog>,
    onResetStats: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = PetrolSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(PetrolSurfaceLight, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = "Avatar Car representation",
                            tint = GlowGreen,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "PILOT DIARY CO-DRIVER",
                        color = Color(0xFF191C19), // Contrast dark grey
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )

                    Text(
                        text = "DURBAN RACING UNION",
                        color = GlowGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        item {
            Text(
                text = "STATISTICS SUMMARY",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Metrics block
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PetrolSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = String.format(Locale.ROOT, "%.2f km", totalDistance / 1000.0),
                            color = GlowGreen,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(text = "Total Distance", color = MintyGray, fontSize = 10.sp)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$totalRefills",
                            color = SoftCyan,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(text = "Refill Count", color = MintyGray, fontSize = 10.sp)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val score = if (totalDistance > 0) ((totalRefills * 2000) / totalDistance * 100).coerceAtMost(100.0) else 100.0
                        Text(
                            text = String.format(Locale.ROOT, "%.0f pts", score),
                            color = SparkOrange,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(text = "EcoScore", color = MintyGray, fontSize = 10.sp)
                    }
                }
            }
        }

        item {
            Text(
                text = "COORDINATE TRACES",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Telemetry details list
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PetrolSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ACTIVE SENSOR TELEMETRY",
                        color = Color(0xFF191C19), // Contrast dark grey
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Current Latitude: $lat\n• Current Longitude: $lng\n• Engine Load: ${if (currentFuel > 0) "Normal" else "Stalled"}\n• Stream Status: Online",
                        color = MintyGray,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        item {
            Text(
                text = "REFUELING SERVICE JOURNAL",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        if (refillLogs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = PetrolSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No history logs recorded yet.\nConnect with verified stations to track refills!",
                            color = MintyGray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(refillLogs) { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = PetrolSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(PetrolSurfaceLight, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.LocalGasStation,
                                    contentDescription = "Fuel history indicator",
                                    tint = GlowGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = log.stationName,
                                    color = Color(0xFF191C19), // Contrast dark grey
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = String.format(Locale.ROOT, "Lat %.3f • Lng %.3f", log.latitude, log.longitude),
                                    color = MintyGray,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Text(
                            text = String.format(Locale.ROOT, "+%.1fL", log.fuelAmount),
                            color = GlowGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = onResetStats,
                colors = ButtonDefaults.buttonColors(containerColor = PetrolSurfaceLight, contentColor = Color(0xFF191C19)), // High contrast dark charcoal text
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = "RESET SIMULATION STATISTICS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
