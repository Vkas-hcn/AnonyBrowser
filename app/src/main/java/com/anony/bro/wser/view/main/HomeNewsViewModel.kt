package com.anony.bro.wser.view.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anony.bro.wser.data.home.HomeContentItem
import com.anony.bro.wser.data.home.HomeContentRepository
import com.anony.bro.wser.data.recommendations.RegionResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeNewsViewModel(private val repository: HomeContentRepository = HomeContentRepository()) : ViewModel() {
    private val _state = MutableStateFlow<HomeNewsState>(HomeNewsState.Loading)
    val state: StateFlow<HomeNewsState> = _state.asStateFlow()
    fun load(context: Context) {
        if (_state.value !is HomeNewsState.Loading) return
        val appContext = context.applicationContext
        repository.cached(appContext).takeIf { it.isNotEmpty() }?.let { _state.value = HomeNewsState.Content(it) }
        viewModelScope.launch {
            val fresh = repository.refresh(appContext, RegionResolver.resolve(appContext))
            if (fresh.isNotEmpty()) _state.value = HomeNewsState.Content(fresh) else if (_state.value is HomeNewsState.Loading) _state.value = HomeNewsState.Empty
        }
    }
}
sealed interface HomeNewsState { data object Loading : HomeNewsState; data class Content(val items: List<HomeContentItem>) : HomeNewsState; data object Empty : HomeNewsState }
