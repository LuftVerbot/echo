package dev.brahmkshatriya.echo.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemTrackBinding
import dev.brahmkshatriya.echo.playback.Current
import dev.brahmkshatriya.echo.ui.adapter.MediaItemViewHolder.Companion.applyIsPlaying
import dev.brahmkshatriya.echo.ui.item.TrackAdapter
import dev.brahmkshatriya.echo.utils.image.loadInto
import dev.brahmkshatriya.echo.utils.ui.toTimeString

class TrackViewHolder(
    private val listener: TrackAdapter.Listener,
    private val clientId: String,
    private val context: EchoMediaItem?,
    val binding: ItemTrackBinding
) : ShelfListItemViewHolder(binding.root) {

    override fun bind(item: Any) {
        val shelf = shelf as? Shelf.Lists.Tracks ?: return
        val pos = bindingAdapterPosition
        val track = shelf.list[pos]
        this.track = track
        val list = shelf.list
        val isNumbered = shelf.isNumbered
        binding.itemNumber.text =
            binding.root.context.getString(R.string.number_dot, pos + 1)
        binding.itemNumber.isVisible = isNumbered
        binding.itemTitle.text = track.title
        track.cover.loadInto(binding.imageView, R.drawable.art_music)
        var subtitle = ""
        track.duration?.toTimeString()?.let {
            subtitle += it
        }
        track.toMediaItem().subtitleWithE?.let {
            if (it.isNotBlank()) subtitle += if (subtitle.isNotBlank()) " • $it" else it
        }

        binding.itemSubtitle.isVisible = subtitle.isNotEmpty()
        binding.itemSubtitle.text = subtitle

        binding.root.setOnClickListener {
            listener.onClick(clientId, context, list, pos, binding.root)
        }
        binding.root.setOnLongClickListener {
            listener.onLongClick(clientId, context, list, pos, binding.root)
        }
        binding.itemMore.setOnClickListener {
            listener.onLongClick(clientId, context, list, pos, binding.root)
        }
    }

    var track: Track? = null
    override fun onCurrentChanged(current: Current?) {
        applyIsPlaying(current, track?.id, binding.isPlaying)
    }

    override val transitionView = binding.root

    companion object {
        fun create(
            parent: ViewGroup,
            listener: TrackAdapter.Listener,
            clientId: String,
            context: EchoMediaItem?
        ): TrackViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return TrackViewHolder(
                listener, clientId, context, ItemTrackBinding.inflate(inflater, parent, false)
            )
        }
    }
}