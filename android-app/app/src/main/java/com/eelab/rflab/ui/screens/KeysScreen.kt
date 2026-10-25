package com.eelab.rflab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eelab.rflab.crypto.KeeLoqKey
import com.eelab.rflab.ui.keys.KeyManagerViewModel

/**
 * Manage the user's KeeLoq keystore: add a named key (entered as hex), pick
 * the active key, and delete keys. Keys are the user's own, stay on the phone,
 * and are used only to decrypt frames from a remote you own or to transmit to
 * a receiver you hold the key for.
 */
@Composable
fun KeysScreen(
    viewModel: KeyManagerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("KeeLoq keys", style = MaterialTheme.typography.titleMedium)
        Text(
            "Keys you own, stored only on this phone. Used to read the counter " +
                "of your own remote and to send to a receiver you have the key for.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = state.nameInput,
            onValueChange = viewModel::onNameChange,
            label = { Text("Name (e.g. Garage)") },
            singleLine = true,
            isError = state.nameError != null,
            supportingText = state.nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.keyHexInput,
            onValueChange = viewModel::onKeyHexChange,
            label = { Text("Key (up to 16 hex digits)") },
            singleLine = true,
            isError = state.keyError != null,
            supportingText = state.keyError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.addKey() },
            enabled = state.canAdd,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add key") }

        Text("Stored keys (${state.keys.size})", style = MaterialTheme.typography.titleSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.keys, key = { it.id }) { key ->
                KeyRow(
                    key = key,
                    selected = key.id == state.selectedId,
                    onSelect = { viewModel.selectKey(key.id) },
                    onDelete = { viewModel.deleteKey(key.id) },
                )
            }
        }
    }
}

@Composable
private fun KeyRow(
    key: KeeLoqKey,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(key.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    // Masked: show only the last 4 hex digits so a shoulder
                    // glance does not reveal the whole key.
                    "••••••••••••" + String.format("%04X", key.key and 0xFFFF),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${key.name}")
            }
        }
    }
}
