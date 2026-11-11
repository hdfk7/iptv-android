package cn.hdfk7.android.iptv.feature.source

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.app.IptvApplication
import cn.hdfk7.android.iptv.core.model.LibraryError
import cn.hdfk7.android.iptv.core.model.LibraryException
import cn.hdfk7.android.iptv.core.model.PlaylistSource
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SourceManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as IptvApplication).playlistRepository
    private val mutableState = MutableStateFlow(SourceManagerState())
    val state = mutableState.asStateFlow()
    val catalog = repository.state

    fun open() {
        mutableState.value = SourceManagerState(open = true)
    }

    fun back() {
        val current = state.value
        if (current.busy) {
            return
        }
        if (current.draft != null || current.deleting != null) {
            mutableState.value = current.copy(draft = null, deleting = null, error = null)
        } else {
            mutableState.value = SourceManagerState()
        }
    }

    fun add() {
        mutableState.value = state.value.copy(draft = SourceDraft(), error = null)
    }

    fun edit(source: PlaylistSource) {
        mutableState.value = state.value.copy(
            draft = SourceDraft(source.id, source.name, source.url, source.guideAddress.orEmpty()), error = null)
    }

    fun editName(name: String) {
        val draft = state.value.draft ?: return
        mutableState.value = state.value.copy(draft = draft.copy(name = name), error = null)
    }

    fun editAddress(address: String) {
        val draft = state.value.draft ?: return
        mutableState.value = state.value.copy(draft = draft.copy(address = address), error = null)
    }

    fun editGuideAddress(address: String): Unit {
        val draft = state.value.draft ?: return
        mutableState.value = state.value.copy(draft = draft.copy(guideAddress = address), error = null)
    }

    fun askDelete(source: PlaylistSource) {
        mutableState.value = state.value.copy(deleting = source, error = null)
    }

    fun save() {
        val draft = state.value.draft ?: return
        perform(action = { repository.saveSource(draft.id, draft.name, draft.address, draft.guideAddress) }, close = false)
    }

    fun delete() {
        val source = state.value.deleting ?: return
        perform(action = { repository.deleteSource(source.id) }, close = false)
    }

    fun activate(id: String) {
        perform(action = { repository.activate(id) }, close = true)
    }

    fun restoreDefaults() {
        perform(action = { repository.restoreDefaults() }, close = true)
    }

    fun showFavorites() {
        activate(SourceLibrary.FAVORITES_ID)
    }

    private fun perform(action: suspend () -> Unit, close: Boolean) {
        if (state.value.busy) {
            return
        }
        mutableState.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch(block = {
            try {
                action()
                mutableState.value = SourceManagerState(open = !close)
            } catch (exception: LibraryException) {
                val error = when (exception.error) {
                    LibraryError.NAME -> {
                        R.string.source_name_error
                    }
                    LibraryError.ADDRESS -> {
                        R.string.source_address_error
                    }
                    LibraryError.GUIDE_ADDRESS -> {
                        R.string.source_guide_address_error
                    }
                    LibraryError.DUPLICATE -> {
                        R.string.source_duplicate
                    }
                    LibraryError.MISSING -> {
                        R.string.source_missing
                    }
                    LibraryError.STORAGE -> {
                        R.string.storage_error
                    }
                }
                mutableState.value = state.value.copy(busy = false, error = error)
            }
        })
    }
}
