package com.eelab.rflab.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eelab.rflab.crypto.KeeLoqKey
import com.eelab.rflab.crypto.KeeLoqKeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Form + list state for the Keys screen. */
data class KeyManagerUiState(
    val keys: List<KeeLoqKey> = emptyList(),
    val selectedId: Long? = null,
    val nameInput: String = "",
    val keyHexInput: String = "",
    val nameError: String? = null,
    val keyError: String? = null,
) {
    val canAdd: Boolean get() = nameInput.isNotBlank() && keyHexInput.isNotBlank()
}

/**
 * Manages the user's KeeLoq keystore from the UI: add a named key (entered as
 * hex), select the active key, delete a key. All validation lives here so the
 * screen stays a thin renderer and the rules are unit-tested.
 *
 * A key is 1..16 hex digits (a 64-bit value); whitespace, `0x` and separators
 * are tolerated. Nothing is derived or captured — the value is exactly what
 * the user types.
 */
class KeyManagerViewModel(
    private val repository: KeeLoqKeyRepository,
) : ViewModel() {

    private val form = MutableStateFlow(FormState())

    private val _uiState = MutableStateFlow(KeyManagerUiState())
    val uiState: StateFlow<KeyManagerUiState> = _uiState.asStateFlow()

    private data class FormState(
        val name: String = "",
        val keyHex: String = "",
        val nameError: String? = null,
        val keyError: String? = null,
    )

    init {
        viewModelScope.launch {
            combine(repository.keys, repository.selectedId, form) { keys, selectedId, f ->
                KeyManagerUiState(
                    keys = keys,
                    selectedId = selectedId,
                    nameInput = f.name,
                    keyHexInput = f.keyHex,
                    nameError = f.nameError,
                    keyError = f.keyError,
                )
            }.collect { state -> _uiState.value = state }
        }
    }

    fun onNameChange(value: String) = form.update { it.copy(name = value, nameError = null) }

    fun onKeyHexChange(value: String) = form.update { it.copy(keyHex = value, keyError = null) }

    /**
     * Validate the form and add the key. On success the form clears; on
     * failure the relevant field error is set and nothing is stored.
     *
     * @return true when a key was added.
     */
    fun addKey(): Boolean {
        val f = form.value
        val name = f.name.trim()
        if (name.isEmpty()) {
            form.update { it.copy(nameError = "Name required") }
            return false
        }
        val value = parseKeyHex(f.keyHex)
        if (value == null) {
            form.update { it.copy(keyError = "Enter 1-16 hex digits") }
            return false
        }
        repository.add(name, value)
        form.value = FormState()
        return true
    }

    fun selectKey(id: Long) = repository.select(id)

    fun deleteKey(id: Long) = repository.delete(id)

    companion object {
        /**
         * Parse up to 16 hex digits into a 64-bit key, tolerating spaces, a
         * leading `0x`, and `:`/`-` separators. Returns null if empty, too
         * long, or not hex.
         */
        fun parseKeyHex(raw: String): Long? {
            val cleaned = raw.trim()
                .removePrefix("0x")
                .removePrefix("0X")
                .replace(" ", "")
                .replace(":", "")
                .replace("-", "")
            if (cleaned.isEmpty() || cleaned.length > 16) return null
            if (!cleaned.all { it in "0123456789abcdefABCDEF" }) return null
            return java.lang.Long.parseUnsignedLong(cleaned, 16)
        }

        fun factory(repository: KeeLoqKeyRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { KeyManagerViewModel(repository) } }
    }
}
