package com.qring.print.ui.template

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qring.print.data.TemplateRepository
import com.qring.print.model.TemplateRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TemplateViewModel(application: Application) : AndroidViewModel(application) {

    private val templateRepo = TemplateRepository(application)

    private val _templates = MutableStateFlow<List<TemplateRecord>>(emptyList())
    val templates: StateFlow<List<TemplateRecord>> = _templates.asStateFlow()

    private val _isEmpty = MutableStateFlow(true)
    val isEmpty: StateFlow<Boolean> = _isEmpty

    fun loadTemplates() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                templateRepo.listTemplates()
            }
            _templates.value = list
            _isEmpty.value = list.isEmpty()
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { templateRepo.deleteTemplate(id) }
            loadTemplates()
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
