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

    private val _activeFrameIndex = MutableStateFlow(35) // Start at index 35 (the current live frame)
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

    fun refreshData(context: Context? = null) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext
        }
        stopPlayback()
        preloadJob?.cancel()

        val times = DwdWmsClient.generateCombinedFrameTimes()
        _frameTimes.value = times
        _activeFrameIndex.value = 35 // Reset to the current live frame

        if (context == null && appContext == null) {
            _isPreloading.value = false
            _preloadProgress.value = 1f
            return
        }
        
        val activeContext = context ?: appContext ?: return

        _isPreloading.value = true
        _preloadProgress.value = 0f

        // Launch preloader job on background IO threads
        preloadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val total = times.size
            var completed = 0
            
            // Download frames concurrently up to 5 parallel tasks
            val semaphore = kotlinx.coroutines.sync.Semaphore(5)
            val jobs = times.map { time ->
                launch {
                    semaphore.acquire()
                    try {
                        DwdWmsClient.downloadFrame(activeContext, time)
                    } finally {
                        semaphore.release()
                        synchronized(this@RadarViewModel) {
                            completed++
                            _preloadProgress.value = completed.toFloat() / total
                        }
                    }
                }
            }
            
            // Wait for all downloads to finish or time out
            jobs.forEach { it.join() }
            
            _isPreloading.value = false
            _preloadProgress.value = 1f
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
                    val currentBaseTime = times[35]
                    
                    // Calculate what the baseTime should be right now
                    val now = Instant.now()
                    val epochSec = now.epochSecond
                    val roundedSec = ((epochSec - 600) / 300) * 300
                    val expectedBaseTime = Instant.ofEpochSecond(roundedSec)
                    
                    if (expectedBaseTime != currentBaseTime) {
                        // A new frame is available! Trigger refresh
                        refreshData(appContext)
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

