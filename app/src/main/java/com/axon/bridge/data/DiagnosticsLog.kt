package com.axon.bridge.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

object DiagnosticsLog {
    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = mutableEntries

    var onEntryAdded: (() -> Unit)? = null

    fun add(message: String) {
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        mutableEntries.value = (listOf("$time  $message") + mutableEntries.value).take(MAX_ENTRIES)
        onEntryAdded?.invoke()
    }

    fun clear() {
        mutableEntries.value = emptyList()
        onEntryAdded?.invoke()
    }

    private const val MAX_ENTRIES = 12
}
