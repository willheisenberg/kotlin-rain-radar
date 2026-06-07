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

    private val _activeFrameIndex = MutableStateFlow(36) // Start at index 36 (the current live frame, wie KDE Extension)
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
        val times = DwdWmsClient.generateCombinedFrameTimes(base)
        _frameTimes.value = times

        if (force) {
            _activeFrameIndex.value = 36
        } else if (activeTime != null) {
            val newIndex = times.indexOf(activeTime)
            if (newIndex != -1) {
                _activeFrameIndex.value = newIndex
            } else {
                _activeFrameIndex.value = 36
            }
        } else {
            _activeFrameIndex.value = 36
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

            // If less than 50 frames are cached (e.g. after hours of inactivity or if cache was cleared), 
            // we show the preloading screen to avoid staring at a blank map during longer downloads.
            val isCacheInsufficient = cachedCount < 50
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
            var completed = 0
            
            // Download frames concurrently up to 5 parallel tasks
            val semaphore = kotlinx.coroutines.sync.Semaphore(5)
            val jobs = times.map { time ->
                launch {
                    semaphore.acquire()
                    try {
                        DwdWmsClient.downloadFrame(activeContext, time, base, force = force)
                    } finally {
                        semaphore.release()
                        val currentCompleted = synchronized(this@RadarViewModel) {
                            completed++
                            completed
                        }
                        if (!effectiveSilent) {
                            _preloadProgress.value = currentCompleted.toFloat() / total
                        }
                    }
                }
            }
            
            // Wait for all downloads to finish or time out
            jobs.forEach { it.join() }

            // Clean up old cache files (old forecast files and history older than times.first())
            val oldestAllowed = times.firstOrNull() ?: base.minusSeconds(3600 * 3)
            DwdWmsClient.cleanOldCache(activeContext, base, oldestAllowed)
            
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
                if (times.size >= 60) {
                    val currentBaseTime = times[36]
                    
                    // Calculate what the baseTime should be right now
                    val now = Instant.now()
                    val epochSec = now.epochSecond
                    val roundedSec = ((epochSec - 600) / 300) * 300
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

