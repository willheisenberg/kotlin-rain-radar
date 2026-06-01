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
        preloadJob = viewModelScope.launch {
            delay(5000) // 5 seconds preloading window
            _isPreloading.value = false
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
