package com.example.rainradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rainradar.data.DwdWmsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class RadarViewModel(
    private val baseTime: () -> Instant = { DwdWmsClient.getRoundedBaseTime() },
    private val download: suspend (Context, Instant, Instant, Boolean, (com.example.rainradar.data.FrameSource) -> Unit) -> Boolean =
        { context, time, base, force, source -> DwdWmsClient.downloadFrame(context, time, base, force, source) },
) : ViewModel() {
    private val _frameTimes = MutableStateFlow<List<Instant>>(emptyList())
    val frameTimes: StateFlow<List<Instant>> = _frameTimes.asStateFlow()

    private val _activeFrameIndex = MutableStateFlow(DwdWmsClient.PAST_FRAME_COUNT) // Start at PAST_FRAME_COUNT (the current live frame, wie KDE Extension)
    val activeFrameIndex: StateFlow<Int> = _activeFrameIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isPreloading = MutableStateFlow(false)
    val isPreloading: StateFlow<Boolean> = _isPreloading.asStateFlow()

    private val _preloadProgress = MutableStateFlow(0f)
    val preloadProgress: StateFlow<Float> = _preloadProgress.asStateFlow()

    private val _frameRevision = MutableStateFlow(0L)
    val frameRevision: StateFlow<Long> = _frameRevision.asStateFlow()

    private var playbackJob: Job? = null
    private var preloadJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var appContext: Context? = null

    init {
        refreshData(null)
        startAutoRefreshPolling()
    }

    private val _loadStatus = MutableStateFlow(RadarLoadStatus())
    val loadStatus: StateFlow<RadarLoadStatus> = _loadStatus.asStateFlow()

    fun refreshData(
        context: Context? = null,
        silent: Boolean = false,
        force: Boolean = false,
    ) {
        if (context != null) appContext = context.applicationContext
        // Polling must not cancel a batch merely because its generation is not
        // published yet. A manual force refresh still cancels immediately.
        if (silent && !force && preloadJob?.isActive == true) return
        val base = baseTime()
        val times = DwdWmsClient.generateCombinedFrameTimes(base)
        val activeContext = appContext
        if (activeContext == null) {
            _frameTimes.value = times
            return
        }
        val previousJob = preloadJob
        previousJob?.cancel()
        if (!silent || force) stopPlayback()
        preloadJob = viewModelScope.launch {
            previousJob?.join()
            _loadStatus.value = RadarLoadStatus()
            _preloadProgress.value = 0f
            val oldTimes = _frameTimes.value
            val oldBase = oldTimes.getOrNull(DwdWmsClient.PAST_FRAME_COUNT)
            val oldReady = withContext(Dispatchers.IO) {
                oldTimes.count { DwdWmsClient.isFrameReady(activeContext, it, oldBase ?: base) }
            }
            val oldForecastReady = withContext(Dispatchers.IO) {
                oldTimes.drop(DwdWmsClient.PAST_FRAME_COUNT).any {
                    DwdWmsClient.isFrameReady(activeContext, it, oldBase ?: base)
                }
            }
            val keepPreviousGeneration = !force && oldBase != base && oldForecastReady
            _isPreloading.value = !silent || force || oldReady == 0
            if (_isPreloading.value) stopPlayback()

            if (!keepPreviousGeneration) publishTimes(times, force)
            // Do not delete the displayed generation before its replacement is
            // ready. Forecast filenames include the base, so they cannot be
            // mistaken for observations or for a newer forecast generation.
            val permits = Semaphore(8)
            val ready = java.util.concurrent.ConcurrentHashMap.newKeySet<Instant>()
            withTimeoutOrNull(90_000) {
                // Load the current frame and forecasts before missing history.
                val priorityTimes = times.drop(DwdWmsClient.PAST_FRAME_COUNT) +
                    times.take(DwdWmsClient.PAST_FRAME_COUNT).asReversed()
                priorityTimes.forEach { time ->
                    launch {
                        permits.withPermit {
                            var source: com.example.rainradar.data.FrameSource? = null
                            val success = download(activeContext, time, base, force) { next ->
                                _loadStatus.update { it.active(source, -1).active(next, 1) }
                                source = next
                            }
                            if (success) {
                                ready.add(time)
                                _frameRevision.update { it + 1 }
                            }
                            _loadStatus.update { it.finished(source, success) }
                            _preloadProgress.value = ready.size.toFloat() / times.size
                        }
                    }
                }
            }
            _loadStatus.update { it.copy(serverActive = 0, dwdActive = 0) }
            val forecastsReady = times.drop(DwdWmsClient.PAST_FRAME_COUNT).all { it in ready }
            if (!keepPreviousGeneration || forecastsReady) {
                publishTimes(times, force)
                _frameRevision.update { it + 1 }
                withContext(Dispatchers.IO) {
                    DwdWmsClient.cleanOldCache(activeContext, base, times.first(), times)
                }
            }
            // If a replacement failed, keep the working old generation and
            // let the next poll retry missing files in the new generation.
            _isPreloading.value = false
        }
    }

    private fun publishTimes(times: List<Instant>, reset: Boolean) {
        val selected = _frameTimes.value.getOrNull(_activeFrameIndex.value)
        _frameTimes.value = times
        _activeFrameIndex.value = if (reset) DwdWmsClient.PAST_FRAME_COUNT else {
            times.indexOf(selected).takeIf { it >= 0 } ?: DwdWmsClient.PAST_FRAME_COUNT
        }
    }

    fun setActiveFrameIndex(index: Int) {
        val size = _frameTimes.value.size
        if (size > 0 && index in 0 until size) {
            stopPlayback()
            _activeFrameIndex.value = index
        }
    }

    fun togglePlayback() {
        if (_isPlaying.value) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        _isPlaying.value = true
        playbackJob =
            viewModelScope.launch {
                while (true) {
                    delay(700)
                    val times = _frameTimes.value
                    if (times.isNotEmpty()) {
                        _activeFrameIndex.value = (_activeFrameIndex.value + 1) % times.size
                    }
                }
            }
    }

    fun stopPlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun startAutoRefreshPolling() {
        autoRefreshJob?.cancel()
        autoRefreshJob =
            viewModelScope.launch {
                while (true) {
                    delay(30000) // 30 seconds
                    val times = _frameTimes.value
                    if (times.size >= DwdWmsClient.TOTAL_FRAME_COUNT) {
                        val currentBaseTime = times[DwdWmsClient.PAST_FRAME_COUNT]

                        // Calculate what the baseTime should be right now
                        val now = Instant.now()
                        val epochSec = now.epochSecond
                        val roundedSec =
                            ((epochSec - DwdWmsClient.SAFETY_OFFSET_SECONDS) / DwdWmsClient.FRAME_INTERVAL_SECONDS) *
                                DwdWmsClient.FRAME_INTERVAL_SECONDS
                        val expectedBaseTime = Instant.ofEpochSecond(roundedSec)

                        if (expectedBaseTime != currentBaseTime || _loadStatus.value.ready < DwdWmsClient.TOTAL_FRAME_COUNT) {
                            // Ein neues Frame ist verfügbar! Stiller Refresh ohne Ladebalken
                            refreshData(appContext, silent = true)
                        }
                    }
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        preloadJob?.cancel()
        preloadJob = null
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }
}
