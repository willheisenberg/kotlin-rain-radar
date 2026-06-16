package com.example.rainradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rainradar.data.DwdWmsClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class RadarViewModel : ViewModel() {
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

    private var playbackJob: Job? = null
    private var preloadJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var appContext: Context? = null

    init {
        refreshData(null)
        startAutoRefreshPolling()
    }

    @kotlin.jvm.Volatile
    private var isFirstRefreshDone = false

    fun refreshData(context: Context? = null, silent: Boolean = false, force: Boolean = false) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext
        }
        
        val oldTimes = _frameTimes.value
        val activeIndex = _activeFrameIndex.value
        val activeTime = oldTimes.getOrNull(activeIndex)

        val base = DwdWmsClient.getRoundedBaseTime()
        
        val times: List<Instant>
        if (!force && oldTimes.size == DwdWmsClient.TOTAL_FRAME_COUNT) {
            val currentBaseTime = oldTimes[DwdWmsClient.PAST_FRAME_COUNT]
            val diffSeconds = base.epochSecond - currentBaseTime.epochSecond
            val diffSteps = (diffSeconds / DwdWmsClient.FRAME_INTERVAL_SECONDS).toInt()
            if (diffSteps in 1 until DwdWmsClient.TOTAL_FRAME_COUNT) {
                val mutableTimes = oldTimes.drop(diffSteps).toMutableList()
                for (k in (DwdWmsClient.TOTAL_FRAME_COUNT - diffSteps) until DwdWmsClient.TOTAL_FRAME_COUNT) {
                    val newTime = base.plusSeconds((k - DwdWmsClient.PAST_FRAME_COUNT) * DwdWmsClient.FRAME_INTERVAL_SECONDS)
                    mutableTimes.add(newTime)
                }
                times = mutableTimes
            } else {
                times = DwdWmsClient.generateCombinedFrameTimes(base)
            }
        } else {
            times = DwdWmsClient.generateCombinedFrameTimes(base)
        }
        _frameTimes.value = times

        if (force) {
            _activeFrameIndex.value = DwdWmsClient.PAST_FRAME_COUNT
        } else if (activeTime != null) {
            val newIndex = times.indexOf(activeTime)
            if (newIndex != -1) {
                _activeFrameIndex.value = newIndex
            } else {
                _activeFrameIndex.value = DwdWmsClient.PAST_FRAME_COUNT
            }
        } else {
            _activeFrameIndex.value = DwdWmsClient.PAST_FRAME_COUNT
        }

        if (context == null && appContext == null) {
            _isPreloading.value = false
            _preloadProgress.value = 1f
            return
        }
        
        val activeContext = context ?: appContext ?: return

        if (!silent || force) {
            stopPlayback()
        }
        preloadJob?.cancel()

        // Launch preloader job on background IO threads
        preloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 1. Force clear cache on background IO thread if requested
            if (force) {
                val dir = java.io.File(activeContext.cacheDir, "radar_cache")
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { it.delete() }
                }
            }

            // 3. Check cached files count on background IO thread
            var cachedCount = 0
            times.forEach { time ->
                if (DwdWmsClient.isFrameReady(activeContext, time, base)) {
                    cachedCount++
                }
            }

            val isFirst = !isFirstRefreshDone && context != null
            if (isFirst) {
                isFirstRefreshDone = true
            }

            // If less than 32 frames are cached (e.g. after more than 20 minutes of inactivity or if cache was cleared), 
            // we show the preloading screen to avoid staring at a blank map during longer downloads.
            val isCacheInsufficient = cachedCount < 32
            val effectiveSilent = if (isCacheInsufficient) false else silent

            // Switch to Main thread for loading UI state setup
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!effectiveSilent || force) {
                    stopPlayback() // Ensure playback is stopped if showing loading screen
                    _isPreloading.value = true
                    _preloadProgress.value = 0f
                } else {
                    _isPreloading.value = false
                }
            }

            val total = times.size
            val completed = java.util.concurrent.atomic.AtomicInteger(0)

            // CRITICAL: Clean old cache BEFORE downloading, so that stale forecast
            // images (which have now moved into the past) are deleted and won't be
            // served as cache hits during download. Without this, old prediction
            // images get displayed as historical observations.
            val oldestAllowed = times.firstOrNull() ?: base.minusSeconds(3600 * 3)
            DwdWmsClient.cleanOldCache(activeContext, base, oldestAllowed, times)
            
            // Download frames concurrently up to 5 parallel tasks
            val semaphore = kotlinx.coroutines.sync.Semaphore(5)
            val jobs = times.map { time ->
                launch {
                    semaphore.acquire()
                    try {
                        DwdWmsClient.downloadFrame(activeContext, time, base, force = force)
                    } finally {
                        semaphore.release()
                        val currentCompleted = completed.incrementAndGet()
                        if (!effectiveSilent) {
                            _preloadProgress.value = currentCompleted.toFloat() / total
                        }
                    }
                }
            }
            
            // Wait for all downloads to finish or time out
            jobs.forEach { it.join() }

            // Second pass: clean up any files that fell out of the active window
            DwdWmsClient.cleanOldCache(activeContext, base, oldestAllowed, times)
            
            // Switch to Main thread to reset loading states
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                _isPreloading.value = false
                _preloadProgress.value = 1f
            }
        }
    }

    /**
     * Retained for compatibility.
     */
    fun updatePreloadProgress(progress: Float) {
        _preloadProgress.value = progress
        if (progress >= 1f) {
            _isPreloading.value = false
            preloadJob?.cancel()
            preloadJob = null
        }
    }

    fun setActiveFrameIndex(index: Int) {
        val size = _frameTimes.value.size
        if (size > 0 && index in 0 until size) {
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
        playbackJob = viewModelScope.launch {
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
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(30000) // 30 seconds
                val times = _frameTimes.value
                if (times.size >= DwdWmsClient.TOTAL_FRAME_COUNT) {
                    val currentBaseTime = times[DwdWmsClient.PAST_FRAME_COUNT]
                    
                    // Calculate what the baseTime should be right now
                    val now = Instant.now()
                    val epochSec = now.epochSecond
                    val roundedSec = ((epochSec - DwdWmsClient.SAFETY_OFFSET_SECONDS) / DwdWmsClient.FRAME_INTERVAL_SECONDS) * DwdWmsClient.FRAME_INTERVAL_SECONDS
                    val expectedBaseTime = Instant.ofEpochSecond(roundedSec)
                    
                    if (expectedBaseTime != currentBaseTime) {
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

