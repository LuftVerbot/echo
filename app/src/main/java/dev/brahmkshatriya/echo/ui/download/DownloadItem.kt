package dev.brahmkshatriya.echo.ui.download

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemDownloadBinding
import dev.brahmkshatriya.echo.databinding.ItemDownloadGroupBinding
import dev.brahmkshatriya.echo.models.DownloadEntity
import dev.brahmkshatriya.echo.plugger.MusicExtension
import dev.brahmkshatriya.echo.ui.media.MediaContainerEmptyAdapter
import dev.brahmkshatriya.echo.ui.media.MediaItemViewHolder.Companion.placeHolder
import dev.brahmkshatriya.echo.utils.getFromCache
import dev.brahmkshatriya.echo.utils.loadInto
import dev.brahmkshatriya.echo.utils.loadWith
import kotlinx.coroutines.flow.MutableStateFlow

sealed class DownloadItem {

    data class Single(
        val id: Long,
        val clientId: String,
        val clientIcon: String?,
        val item: EchoMediaItem,
        val isDownloading: Boolean,
        val progress: Int,
        val groupName: String? = null
    ) : DownloadItem()

    data class Group(
        val name: String,
        val areChildrenVisible: Boolean
    ) : DownloadItem()

    companion object {
        fun DownloadEntity.toItem(application: Application, extensionListFlow: MutableStateFlow<List<MusicExtension>?>): DownloadItem? {
            val extension = extensionListFlow.value?.find { it.metadata.id == clientId } ?: return null
            val track = application.getFromCache(itemId, Track.creator, "downloads") ?: return null
            return Single(id, clientId, extension.metadata.iconUrl, track.toMediaItem(), true, 0, groupName)
        }
    }
}
