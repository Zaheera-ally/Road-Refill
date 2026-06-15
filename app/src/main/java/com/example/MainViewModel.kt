package com.example

import android.app.Application
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PlacesRepository()
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)

    // Local Persistence Layer via Room
    private val db = AppDatabase.getDatabase(application)
    private val refillRepository = RefillRepository(db.refillLogDao())

    val refillLogs: StateFlow<List<RefillLog>> = refillRepository.allLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current State Variables
    private val _isSimulationMode = MutableStateFlow(true) // Start with simulation as shown in screens
    val isSimulationMode: StateFlow<Boolean> = _isSimulationMode.asStateFlow()

    private val _isDriving = MutableStateFlow(false)
    val isDriving: StateFlow<Boolean> = _isDriving.asStateFlow()

    private val _currentSpeed = MutableStateFlow(0) // km/h
    val currentSpeed: StateFlow<Int> = _currentSpeed.asStateFlow()

    private val _currentFuel = MutableStateFlow(90.0) // Liters (Max 100)
    val currentFuel: StateFlow<Double> = _currentFuel.asStateFlow()

    private val _distanceTraveled = MutableStateFlow(0.0) // meters
    val distanceTraveled: StateFlow<Double> = _distanceTraveled.asStateFlow()

    private val _currentLatitude = MutableStateFlow(-29.8650) // Starting position Durban Point
    val currentLatitude: StateFlow<Double> = _currentLatitude.asStateFlow()

    private val _currentLongitude = MutableStateFlow(31.0480)
    val currentLongitude: StateFlow<Double> = _currentLongitude.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _gasStations = MutableStateFlow<List<GasStation>>(repository.durbanPresets)
    val gasStations: StateFlow<List<GasStation>> = _gasStations.asStateFlow()

    private val _closestStation = MutableStateFlow<GasStation?>(null)
    val closestStation: StateFlow<GasStation?> = _closestStation.asStateFlow()

    private val _distanceToClosest = MutableStateFlow<Double?>(null) // meters
    val distanceToClosest: StateFlow<Double?> = _distanceToClosest.asStateFlow()

    // Hold-to-refill state
    private val _refillProgress = MutableStateFlow(0.0f) // 0.0f to 1.0f
    val refillProgress: StateFlow<Float> = _refillProgress.asStateFlow()

    private val _isRefillingCompleted = MutableStateFlow(false)
    val isRefillingCompleted: StateFlow<Boolean> = _isRefillingCompleted.asStateFlow()

    // Statistics Tracker (shown on Profile Screen)
    private val _totalRefillsCount = MutableStateFlow(0)
    val totalRefillsCount: StateFlow<Int> = _totalRefillsCount.asStateFlow()

    private val _totalDistanceAllTime = MutableStateFlow(0.0) // meters
    val totalDistanceAllTime: StateFlow<Double> = _totalDistanceAllTime.asStateFlow()

    // Durban coast coordinates (coastal route path) for high-fidelity interpolation
    val routePoints = listOf(
        Pair(-29.8650, 31.0480), // Durban Point Park
        Pair(-29.8550, 31.0430), // uShaka Marine World
        Pair(-29.8450, 31.0380), // Durban Beachfront
        Pair(-29.8300, 31.0360), // North Beach
        Pair(-29.8100, 31.0370), // Blue Lagoon
        Pair(-29.7900, 31.0410), // Virginia South
        Pair(-29.7700, 31.0480), // Glenashley Coastal
        Pair(-29.7500, 31.0650), // Virginia Beach North
        Pair(-29.7350, 31.0800), // La Lucia Beach
        Pair(-29.7258, 31.0858), // Shell Umhlanga Rocks (Target Refill Spot!)
        Pair(-29.7150, 31.0900)  // Lagoon Drive Resort
    )

    // Index tracking along routePoints
    private var currentPathSegmentIndex = 0
    private var segmentFraction = 0.0

    // Core Active GPS Variables
    private var locationCallback: LocationCallback? = null
    private var lastKnownLocation: Location? = null

    // Jobs
    private var simulationJob: Job? = null
    private var activeGpsJob: Job? = null
    private var refillingJob: Job? = null

    init {
        // Load the persisted coordinates and fuel state first (Room / Supabase)
        loadPersistedState()
        // Start simulation updater or distance analyzer
        startSimulationLoop()
        searchStations("") // Load initial local stations
    }

    fun setSimulationMode(enabled: Boolean) {
        _isSimulationMode.value = enabled
        if (enabled) {
            stopActiveGpsTracking()
            _currentLatitude.value = routePoints[currentPathSegmentIndex].first
            _currentLongitude.value = routePoints[currentPathSegmentIndex].second
        } else {
            // Active GPS mode
            _currentSpeed.value = 0
            _isDriving.value = false
            startActiveGpsTracking()
        }
        recalculateClosestStation()
    }

    fun toggleDriving() {
        if (_currentFuel.value <= 1.0 && !_isDriving.value) {
            // Cannot start driving if out of fuel
            return
        }
        _isDriving.value = !_isDriving.value
        if (_isDriving.value) {
            _currentSpeed.value = 60 // Simulated standard speed (60 km/h) as in Screen 2
        } else {
            _currentSpeed.value = 0
            saveUserState() // trip segment finished, save immediately!
        }
    }

    fun updateSpeed(newSpeed: Int) {
        if (_isDriving.value) {
            _currentSpeed.value = newSpeed
            if (newSpeed == 0) {
                _isDriving.value = false
                saveUserState() // trip segment finished, save immediately!
            }
        }
    }

    fun setQuery(query: String) {
        _searchQuery.value = query
    }

    fun searchStations(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            val results = repository.searchGasStations(
                query,
                _currentLatitude.value,
                _currentLongitude.value
            )
            _gasStations.value = results
            _isSearching.value = false
            recalculateClosestStation()
        }
    }

    // Hold-to-Refill logic
    fun startRefilling() {
        // Verify eligibility (must be within 50 meters of closest station)
        val dist = _distanceToClosest.value ?: Double.MAX_VALUE
        if (dist > 50.0) return

        _isRefillingCompleted.value = false
        refillingJob?.cancel()
        refillingJob = viewModelScope.launch {
            val stepSize = 0.05f
            val updateIntervalMs = 50L // 1 second total hold time
            while (_refillProgress.value < 1.0f) {
                delay(updateIntervalMs)
                _refillProgress.value = (_refillProgress.value + stepSize).coerceAtMost(1.0f)
            }
            // Refuel completed!
            val fuelRestored = 100.0 - _currentFuel.value
            _currentFuel.value = 100.0 // reset to full 100L / 100%
            _totalRefillsCount.value += 1

            _closestStation.value?.let { station ->
                refillRepository.insert(
                    RefillLog(
                        stationName = station.name,
                        fuelAmount = fuelRestored,
                        latitude = _currentLatitude.value,
                        longitude = _currentLongitude.value
                    )
                )
            }

            _isRefillingCompleted.value = true
            _refillProgress.value = 0.0f
            saveUserState() // save complete fuel tank and refill stats immediately!
        }
    }

    fun cancelRefilling() {
        refillingJob?.cancel()
        _refillProgress.value = 0.0f
    }

    fun resetDrives() {
        _distanceTraveled.value = 0.0
        _currentFuel.value = 90.0
        _isDriving.value = false
        _currentSpeed.value = 0
        currentPathSegmentIndex = 0
        segmentFraction = 0.0
        _currentLatitude.value = routePoints[0].first
        _currentLongitude.value = routePoints[0].second
        _isRefillingCompleted.value = false
        
        viewModelScope.launch {
            refillRepository.clearAll()
        }
        
        recalculateClosestStation()
        saveUserState() // Save reset values to keep DB perfectly synced
    }

    // Simulation loop
    private fun startSimulationLoop() {
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            while (true) {
                delay(1000) // update once every second
                if (_isSimulationMode.value && _isDriving.value) {
                    val speedKmh = _currentSpeed.value
                    if (speedKmh > 0 && _currentFuel.value > 0.0) {
                        // Calculate simulated movement for 1 second
                        val metersSimulated = (speedKmh * 1000.0) / 3600.0 // meters per sec
                        _distanceTraveled.value += metersSimulated
                        _totalDistanceAllTime.value += metersSimulated

                        // Fuel depletion rate: e.g., 0.15 Liters per 100 meters (1.5L per km)
                        // At 60km/h: travels 16.67m/s -> consumes 16.67 * 0.0015 = 0.025 Liters per second
                        // To make the gas depletion visible and fun, let's drain slightly faster if in the game loop, 
                        // e.g. 0.3 Liters per second of drive at 60 km/h so the user can easily interact within the dashboard demo!
                        val fuelDepreciationPerMeter = 0.015 // adjusted for premium game-feel
                        val consumedFuel = metersSimulated * fuelDepreciationPerMeter
                        val remainingFuel = (_currentFuel.value - consumedFuel).coerceAtLeast(0.0)
                        _currentFuel.value = remainingFuel

                        if (remainingFuel <= 0.0) {
                            // Fuel fully depleted
                            _currentSpeed.value = 0
                            _isDriving.value = false
                            saveUserState() // save stalled state immediately!
                        }

                        // Advance the position along the coastal route
                        advanceSimulatedPosition(metersSimulated)

                        // Periodic state persistence (throttled every 10 simulation seconds of driving)
                        saveTicker++
                        if (saveTicker >= 10) {
                            saveTicker = 0
                            saveUserState()
                        }
                    }
                }
                recalculateClosestStation()
            }
        }
    }

    private fun advanceSimulatedPosition(metersToMove: Double) {
        if (routePoints.isEmpty()) return

        var remainingMeters = metersToMove
        while (remainingMeters > 0.0) {
            val nextIndex = (currentPathSegmentIndex + 1) % routePoints.size
            val startPt = routePoints[currentPathSegmentIndex]
            val endPt = routePoints[nextIndex]

            val distanceOfSegment = haversine(
                startPt.first, startPt.second,
                endPt.first, endPt.second
            )

            val currentMeterProgressOnSegment = segmentFraction * distanceOfSegment
            val availableMetersOnSegment = distanceOfSegment - currentMeterProgressOnSegment

            if (remainingMeters < availableMetersOnSegment) {
                // We fit inside this segment!
                val newProgress = currentMeterProgressOnSegment + remainingMeters
                segmentFraction = newProgress / distanceOfSegment
                remainingMeters = 0.0
            } else {
                // Move index to next segment
                remainingMeters -= availableMetersOnSegment
                currentPathSegmentIndex = nextIndex
                segmentFraction = 0.0
            }
        }

        // Apply interpolated coordinates
        val activeIndex = currentPathSegmentIndex
        val nextIndex = (activeIndex + 1) % routePoints.size
        val startPt = routePoints[activeIndex]
        val endPt = routePoints[nextIndex]

        val lat = startPt.first + (endPt.first - startPt.first) * segmentFraction
        val lng = startPt.second + (endPt.second - startPt.second) * segmentFraction

        _currentLatitude.value = lat
        _currentLongitude.value = lng
    }

    private fun recalculateClosestStation() {
        val currentLat = _currentLatitude.value
        val currentLng = _currentLongitude.value
        val stations = _gasStations.value

        if (stations.isEmpty()) {
            _closestStation.value = null
            _distanceToClosest.value = null
            return
        }

        var minDistance = Double.MAX_VALUE
        var nearest: GasStation? = null

        stations.forEach { station ->
            val dist = haversine(
                currentLat, currentLng,
                station.latitude, station.longitude
            )
            if (dist < minDistance) {
                minDistance = dist
                nearest = station
            }
        }

        _closestStation.value = nearest
        _distanceToClosest.value = minDistance
    }

    // Active GPS Tracking Implementation
    private fun startActiveGpsTracking() {
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 2000L
            ).apply {
                setMinUpdateDistanceMeters(1.0f)
            }.build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    val prevLat = _currentLatitude.value
                    val prevLng = _currentLongitude.value

                    _currentLatitude.value = location.latitude
                    _currentLongitude.value = location.longitude
                    
                    // Speed from actual GPS is in meters/sec, convert to km/h
                    val speedKmh = (location.speed * 3.6).toInt()
                    _currentSpeed.value = speedKmh
                    _isDriving.value = speedKmh > 1

                    // Calculate distance covered using Haversine
                    if (lastKnownLocation != null) {
                        val distanceDelta = haversine(
                            prevLat, prevLng,
                            location.latitude, location.longitude
                        )
                        if (distanceDelta > 0.5) {
                            _distanceTraveled.value += distanceDelta
                            _totalDistanceAllTime.value += distanceDelta

                            // Deplete fuel in real-time GPS mode as well!
                            val fuelDepreciationPerMeter = 0.015
                            val consumedFuel = distanceDelta * fuelDepreciationPerMeter
                            _currentFuel.value = (_currentFuel.value - consumedFuel).coerceAtLeast(0.0)
                        }
                    }
                    lastKnownLocation = location
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("MainViewModel", "Location permission missing for Active GPS: ${e.message}")
        }
    }

    private fun stopActiveGpsTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        lastKnownLocation = null
        saveUserState() // save GPS progress metrics immediately!
    }

    // Haversine Core Math Formula
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Radian Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c // returns meters
    }

    // Persistence engine (Local Room & Remote Supabase REST)
    private var saveTicker = 0

    private fun loadPersistedState() {
        viewModelScope.launch {
            try {
                var loadedFromSupabase = false
                if (SupabaseService.isConfigured) {
                    val remoteState = SupabaseService.loadRemoteState()
                    if (remoteState != null) {
                        _currentFuel.value = remoteState.currentFuel
                        _totalDistanceAllTime.value = remoteState.totalDistanceAllTime
                        _totalRefillsCount.value = remoteState.totalRefillsCount
                        _currentLatitude.value = remoteState.currentLatitude
                        _currentLongitude.value = remoteState.currentLongitude
                        loadedFromSupabase = true
                        Log.d("MainViewModel", "Loaded state successfully from remote Supabase: $remoteState")
                        
                        // Sync it down to local cache
                        db.userStateDao().insertUserState(
                            UserState(
                                id = "default_user",
                                currentFuel = remoteState.currentFuel,
                                totalDistanceAllTime = remoteState.totalDistanceAllTime,
                                totalRefillsCount = remoteState.totalRefillsCount,
                                currentLatitude = remoteState.currentLatitude,
                                currentLongitude = remoteState.currentLongitude,
                                lastUpdated = remoteState.lastUpdated
                            )
                        )
                    }
                }

                if (!loadedFromSupabase) {
                    val localState = db.userStateDao().getUserStateDirect()
                    if (localState != null) {
                        _currentFuel.value = localState.currentFuel
                        _totalDistanceAllTime.value = localState.totalDistanceAllTime
                        _totalRefillsCount.value = localState.totalRefillsCount
                        _currentLatitude.value = localState.currentLatitude
                        _currentLongitude.value = localState.currentLongitude
                        Log.d("MainViewModel", "Loaded state successfully from local Room DB: $localState")
                    } else {
                        Log.d("MainViewModel", "No local or remote user state found. Welcome to Durban Drift!")
                    }
                }
                recalculateClosestStation()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading user state: ${e.message}", e)
            }
        }
    }

    private fun saveUserState() {
        val f = _currentFuel.value
        val d = _totalDistanceAllTime.value
        val r = _totalRefillsCount.value
        val lat = _currentLatitude.value
        val lng = _currentLongitude.value
        
        viewModelScope.launch {
            try {
                // Ensure nuclear local persistence
                db.userStateDao().insertUserState(
                    UserState(
                        id = "default_user",
                        currentFuel = f,
                        totalDistanceAllTime = d,
                        totalRefillsCount = r,
                        currentLatitude = lat,
                        currentLongitude = lng,
                        lastUpdated = System.currentTimeMillis()
                    )
                )

                // Handshake with Cloud Backend Supabase if credentials are provided in Secrets
                if (SupabaseService.isConfigured) {
                    val dto = SupabaseUserStateDto(
                        id = "default_user",
                        currentFuel = f,
                        totalDistanceAllTime = d,
                        totalRefillsCount = r,
                        currentLatitude = lat,
                        currentLongitude = lng,
                        lastUpdated = System.currentTimeMillis()
                    )
                    SupabaseService.saveRemoteState(dto)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to sync state changes: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        simulationJob?.cancel()
        stopActiveGpsTracking()
    }
}
