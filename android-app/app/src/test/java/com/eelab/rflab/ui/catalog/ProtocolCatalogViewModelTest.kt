package com.eelab.rflab.ui.catalog

import com.eelab.rflab.protocol.CapabilityAction
import com.eelab.rflab.protocol.ProtocolCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ViewModel behavior test: search + family-filter state for the catalog screen. */
class ProtocolCatalogViewModelTest {

    private fun cap(protocol: String, function: String, present: Boolean = true) = ProtocolCapability(
        protocol = protocol,
        function = function,
        presentInSource = present,
        connectedToUi = false,
        testedAutomatically = false,
        verifiedOnHardware = false,
        evidence = "test fixture",
        action = CapabilityAction.NONE,
    )

    private val fixture = listOf(
        cap("Alpha", "Does the alpha thing"),
        cap("Alpha", "Does another alpha thing"),
        cap("Beta", "Does the beta thing"),
        cap("Gamma", "Not implemented thing", present = false),
    )

    @Test
    fun startsShowingEveryFamilyUnfiltered() {
        val vm = ProtocolCatalogViewModel(fixture)
        val state = vm.uiState.value
        assertEquals(setOf("Alpha", "Beta", "Gamma"), state.allFamilyNames.toSet())
        assertEquals(setOf("Alpha", "Beta", "Gamma"), state.visibleFamilies.map { it.name }.toSet())
    }

    @Test
    fun queryFiltersByFamilyNameCaseInsensitively() {
        val vm = ProtocolCatalogViewModel(fixture)
        vm.onQueryChange("beta")
        assertEquals(listOf("Beta"), vm.uiState.value.visibleFamilies.map { it.name })
    }

    @Test
    fun queryFiltersByFunctionTextToo() {
        val vm = ProtocolCatalogViewModel(fixture)
        vm.onQueryChange("another alpha")
        assertEquals(listOf("Alpha"), vm.uiState.value.visibleFamilies.map { it.name })
    }

    @Test
    fun queryWithNoMatchesShowsNoFamilies() {
        val vm = ProtocolCatalogViewModel(fixture)
        vm.onQueryChange("nonexistent protocol xyz")
        assertTrue(vm.uiState.value.visibleFamilies.isEmpty())
    }

    @Test
    fun familyFilterChipRestrictsToSelectedFamilies() {
        val vm = ProtocolCatalogViewModel(fixture)
        vm.toggleFamily("Alpha")
        assertEquals(listOf("Alpha"), vm.uiState.value.visibleFamilies.map { it.name })

        vm.toggleFamily("Beta")
        assertEquals(setOf("Alpha", "Beta"), vm.uiState.value.visibleFamilies.map { it.name }.toSet())
    }

    @Test
    fun togglingAnAlreadySelectedFamilyDeselectsIt() {
        val vm = ProtocolCatalogViewModel(fixture)
        vm.toggleFamily("Alpha")
        vm.toggleFamily("Alpha")
        // Back to "no filter" == every family visible.
        assertEquals(setOf("Alpha", "Beta", "Gamma"), vm.uiState.value.visibleFamilies.map { it.name }.toSet())
    }

    @Test
    fun queryAndFamilyFilterCombine() {
        val vm = ProtocolCatalogViewModel(fixture)
        vm.toggleFamily("Alpha")
        vm.onQueryChange("another")
        assertEquals(listOf("Alpha"), vm.uiState.value.visibleFamilies.map { it.name })

        vm.onQueryChange("beta")
        assertTrue(vm.uiState.value.visibleFamilies.isEmpty())
    }

    @Test
    fun clearFiltersResetsQueryAndSelection() {
        val vm = ProtocolCatalogViewModel(fixture)
        vm.toggleFamily("Alpha")
        vm.onQueryChange("alpha")
        vm.clearFilters()
        val state = vm.uiState.value
        assertEquals("", state.query)
        assertTrue(state.selectedFamilies.isEmpty())
        assertEquals(3, state.visibleFamilies.size)
    }

    @Test
    fun implementedCountReflectsPresentInSourceOnly() {
        val vm = ProtocolCatalogViewModel(fixture)
        val gamma = vm.uiState.value.visibleFamilies.first { it.name == "Gamma" }
        assertEquals(0, gamma.implementedCount)
        assertEquals(1, gamma.totalCount)

        val alpha = vm.uiState.value.visibleFamilies.first { it.name == "Alpha" }
        assertEquals(2, alpha.implementedCount)
    }
}
