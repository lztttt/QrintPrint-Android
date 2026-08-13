package com.qring.printer.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.printer.data.HistoryRepository
import com.qring.printer.model.HistoryRecord
import com.qring.printer.model.HIST_TYPE_WRONGBOOK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val historyRepo = HistoryRepository(application)

    private val _records = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val records: StateFlow<List<HistoryRecord>> = _records.asStateFlow()

    private val _isEmpty = MutableStateFlow(true)
    val isEmpty: StateFlow<Boolean> = _isEmpty

    /** 所有错题本记录中的标签（去重排序） */
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags.asStateFlow()

    private val _selectedFilterTag = MutableStateFlow<String?>(null)
    val selectedFilterTag: StateFlow<String?> = _selectedFilterTag.asStateFlow()

    fun loadHistory() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { historyRepo.listHistory() }
            _records.value = list
            _isEmpty.value = list.isEmpty()
            // 从错题本记录中提取所有标签
            val tags = mutableSetOf<String>()
            list.filter { it.typeName == HIST_TYPE_WRONGBOOK }.forEach { record ->
                extractTags(record.payload).forEach { tags.add(it) }
            }
            _allTags.value = tags.sorted()
        }
    }

    fun setFilterTag(tag: String?) {
        _selectedFilterTag.value = tag
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

    private fun extractTags(payload: String): List<String> {
        return try {
            val json = JSONObject(payload)
            val tagsStr = json.optString("tags", "")
            if (tagsStr.isNotEmpty()) tagsStr.split(",").filter { it.isNotEmpty() } else emptyList()
        } catch (e: Exception) { emptyList() }
    }
}
