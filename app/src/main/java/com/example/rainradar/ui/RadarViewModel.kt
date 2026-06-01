package com.example.rainradar.ui

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
    private val _showForecast = MutableStateFlow(true)
    val showForecast: StateFlow<Boolean> = _showForecast.asStateFlow()

    private val _frameTimes = MutableStateFlow<List<Instant>>(emptyList())
    val frameTimes: StateFlow<List<Instant>> = _frameTimes.asStateFlow()

    private val _activeFrameIndex = MutableStateFlow(0)
    val activeFrameIndex: StateFlow<Int> = _activeFrameIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isPreloading = MutableStateFlow(false)
    val isPreloading: StateFlow<Boolean> = _isPreloading.asStateFlow()

    private val _preloadProgress = MutableStateFlow(0f)
    val preloadProgress: StateFlow<Float> = _preloadProgress.asStateFlow()

    private var playbackJob: Job? = null
    private var preloadJob: Job? = null
    private val maxFrames = 24

    init {
        refreshData()
    }

    fun toggleForecast() {
        _showForecast.value = !_showForecast.value
        refreshData()
    }

    fun refreshData() {
        stopPlayback()
        preloadJob?.cancel()

        val times = DwdWmsClient.generateFrameTimes(_showForecast.value, maxFrames)
        _frameTimes.value = times
        _activeFrameIndex.value = if (_showForecast.value) 0 else maxFrames - 1

        _isPreloading.value = true
        _preloadProgress.value = 0f

        // Safety timeout: if preloading takes more than 30 seconds (e.g. no internet),
        // force-finish to prevent blocking the user forever
        preloadJob = viewModelScope.launch {
            delay(30000)
            if (_isPreloading.value) {
                _isPreloading.value = false
                _preloadProgress.value = 1f
            }
        }
    }

    /**
     * Called by the UI layer to report real tile-cache progress (0f..1f).
     * When progress reaches 1.0, preloading is automatically finished.
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

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        preloadJob?.cancel()
        preloadJob = null
    }
}

