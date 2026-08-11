package com.qring.printer.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.HistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val historyRepo = HistoryRepository(application)

    private val _records = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val records: StateFlow<List<HistoryRecord>> = _records.asStateFlow()

    private val _isEmpty = MutableStateFlow(true)
    val isEmpty: StateFlow<Boolean> = _isEmpty

    fun loadHistory() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                historyRepo.listHistory()
            }
            _records.value = list
            _isEmpty.value = list.isEmpty()
        }
    }

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyRepo.deleteHistory(id) }
            loadHistory()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyRepo.clearHistory() }
            loadHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
