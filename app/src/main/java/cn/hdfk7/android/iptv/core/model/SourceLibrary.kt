package cn.hdfk7.android.iptv.core.model

data class PlaylistSource(val id: String, val name: String, val url: String, val guideAddress: String? = null)

data class SourceLibrary(
    val sources: List<PlaylistSource>,
    val activeSourceId: String,
    val favorites: List<Channel> = emptyList()
) {
    companion object {
        const val FAVORITES_ID = "favorites"
    }
}

data class CatalogState(
    val library: SourceLibrary = SourceLibrary(emptyList(), SourceLibrary.FAVORITES_ID),
    val loaded: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val updatedAt: Long? = null,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val storageFailed: Boolean = false
)

enum class LibraryError { NAME, ADDRESS, GUIDE_ADDRESS, DUPLICATE, MISSING, STORAGE }

class LibraryException(val error: LibraryError) : Exception(error.name)
