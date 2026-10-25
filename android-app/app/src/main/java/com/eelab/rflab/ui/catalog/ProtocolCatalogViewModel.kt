package com.eelab.rflab.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.protocol.CapabilityStage
import com.eelab.rflab.protocol.ProtocolCapabilities
import com.eelab.rflab.protocol.ProtocolCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One protocol family's row in the catalog list: its name and its functions. */
data class ProtocolFamily(
    val name: String,
    val capabilities: List<ProtocolCapability>,
) {
    /** How many of this family's functions are implemented at all. */
    val implementedCount: Int get() = capabilities.count { it.presentInSource }
    val totalCount: Int get() = capabilities.size

    /** The furthest stage any function in this family has reached. */
    val bestStage: CapabilityStage get() = capabilities.maxOf { it.stage.ordinal }
        .let { CapabilityStage.entries[it] }
}

data class ProtocolCatalogUiState(
    val query: String = "",
    val selectedFamilies: Set<String> = emptySet(),
    val allFamilyNames: List<String> = emptyList(),
    val visibleFamilies: List<ProtocolFamily> = emptyList(),
)

/**
 * Search + family-filter state for the protocol catalog screen.
 *
 * The underlying data ([ProtocolCapabilities.matrix]) is the single source
 * of truth for what each family can do; this ViewModel only groups and
 * filters it for display — it adds no protocol-support rules of its own.
 *
 * @param allCapabilities injectable for tests; defaults to the real matrix.
 */
class ProtocolCatalogViewModel(
    allCapabilities: List<ProtocolCapability> = ProtocolCapabilities.matrix,
) : ViewModel() {

    private val families: List<ProtocolFamily> = allCapabilities
        .groupBy { it.protocol }
        .map { (name, caps) -> ProtocolFamily(name, caps) }

    private val query = MutableStateFlow("")
    private val selectedFamilies = MutableStateFlow<Set<String>>(emptySet())

    private val _uiState = MutableStateFlow(computeState("", emptySet()))
    val uiState: StateFlow<ProtocolCatalogUiState> = _uiState.asStateFlow()

    fun onQueryChange(value: String) {
        query.value = value
        publish()
    }

    /** Toggle one family's filter chip; selecting none shows every family. */
    fun toggleFamily(name: String) {
        selectedFamilies.update { current -> if (name in current) current - name else current + name }
        publish()
    }

    fun clearFilters() {
        query.value = ""
        selectedFamilies.value = emptySet()
        publish()
    }

    private fun publish() {
        _uiState.value = computeState(query.value, selectedFamilies.value)
    }

    private fun computeState(query: String, selected: Set<String>): ProtocolCatalogUiState {
        val needle = query.trim().lowercase()
        val visible = families
            .filter { selected.isEmpty() || it.name in selected }
            .filter { family ->
                needle.isEmpty() ||
                    family.name.lowercase().contains(needle) ||
                    family.capabilities.any { it.function.lowercase().contains(needle) }
            }
        return ProtocolCatalogUiState(
            query = query,
            selectedFamilies = selected,
            allFamilyNames = families.map { it.name },
            visibleFamilies = visible,
        )
    }

    companion object {
        fun factory(
            allCapabilities: List<ProtocolCapability> = ProtocolCapabilities.matrix,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProtocolCatalogViewModel(allCapabilities) }
        }
    }
}

private fun MutableStateFlow<Set<String>>.update(transform: (Set<String>) -> Set<String>) {
    value = transform(value)
}
