package com.eelab.rflab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eelab.rflab.protocol.CapabilityStage
import com.eelab.rflab.ui.catalog.ProtocolCatalogViewModel
import com.eelab.rflab.ui.catalog.ProtocolFamily

/**
 * The protocol catalog: every protocol family this app knows about
 * ([ProtocolFamily]), searchable and filterable, each opening onto
 * [ProtocolDetailScreen].
 *
 * A family being listed is not a claim that it works — the summary line on
 * each card and the detail page both come straight from
 * `ProtocolCapabilities.matrix`, including the two families with zero
 * implemented functions.
 */
@Composable
fun ProtocolCatalogScreen(
    modifier: Modifier = Modifier,
    onOpenFamily: (String) -> Unit,
    viewModel: ProtocolCatalogViewModel = viewModel(factory = ProtocolCatalogViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Protocol catalog", style = MaterialTheme.typography.titleLarge)
            Text(
                "What this app can actually do, grouped by protocol. A listing " +
                    "here is not a claim that it works — open a family to see its " +
                    "real capabilities.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text("Search protocols or functions") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        LazyRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.allFamilyNames, key = { it }) { name ->
                FilterChip(
                    selected = name in state.selectedFamilies,
                    onClick = { viewModel.toggleFamily(name) },
                    label = { Text(name) },
                )
            }
        }

        if (state.visibleFamilies.isEmpty()) {
            Text(
                "No protocol family matches this search/filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.visibleFamilies, key = { it.name }) { family ->
                    FamilyCard(family, onClick = { onOpenFamily(family.name) })
                }
            }
        }
    }
}

@Composable
private fun FamilyCard(family: ProtocolFamily, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(family.name, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${family.implementedCount}/${family.totalCount} functions implemented",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stageLabel(family.bestStage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

internal fun stageLabel(stage: CapabilityStage): String = when (stage) {
    CapabilityStage.NOT_IMPLEMENTED -> "Not implemented"
    CapabilityStage.PRESENT_IN_SOURCE -> "Present in source"
    CapabilityStage.CONNECTED_TO_UI -> "Connected to UI"
    CapabilityStage.TESTED_AUTOMATICALLY -> "Tested automatically"
    CapabilityStage.VERIFIED_ON_HARDWARE -> "Verified on hardware"
}
