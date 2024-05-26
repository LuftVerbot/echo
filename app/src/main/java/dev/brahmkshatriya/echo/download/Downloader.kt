package dev.brahmkshatriya.echo.download

import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2core.Downloader
import com.tonyodev.fetch2okhttp.OkHttpDownloader
import dev.brahmkshatriya.echo.EchoDatabase
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.StreamableAudio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.models.DownloadEntity
import dev.brahmkshatriya.echo.playback.TrackResolver
import dev.brahmkshatriya.echo.plugger.MusicExtension
import dev.brahmkshatriya.echo.plugger.getExtension
import dev.brahmkshatriya.echo.utils.getFromCache
import dev.brahmkshatriya.echo.utils.saveToCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class Downloader(
    private val extensionList: MutableStateFlow<List<MusicExtension>?>,
    database: EchoDatabase,
) {
    val dao = database.downloadDao()
    private val fetch: Fetch

    init {
        val fetchConfiguration = FetchConfiguration.Builder(context)
            .setDownloadConcurrentLimit(3)
            .setHttpDownloader(OkHttpDownloader(Downloader.FileDownloaderType.PARALLEL))
            .build()
        fetch = Fetch.getInstance(fetchConfiguration)
    }

    suspend fun addToDownload(
        context: Context, clientId: String, item: EchoMediaItem
    ) = withContext(Dispatchers.IO) {
        val client = extensionList.getExtension(clientId)?.client
        client as TrackClient
        when (item) {
            is EchoMediaItem.Lists -> {
                when (item) {
                    is EchoMediaItem.Lists.AlbumItem -> {
                        client as AlbumClient
                        val album = client.loadAlbum(item.album)
                        val tracks = client.loadTracks(album)
                        tracks.clear()
                        tracks.loadAll().forEach {
                            context.enqueueDownload(clientId, client, it, album.toMediaItem())
                        }
                    }

                    is EchoMediaItem.Lists.PlaylistItem -> {
                        client as PlaylistClient
                        val playlist = client.loadPlaylist(item.playlist)
                        val tracks = client.loadTracks(playlist)
                        tracks.clear()
                        tracks.loadAll().forEach {
                            context.enqueueDownload(clientId, client, it, playlist.toMediaItem())
                        }
                    }
                }
            }

            is EchoMediaItem.TrackItem -> {
                context.enqueueDownload(clientId, client, item.track)
            }

            else -> throw IllegalArgumentException("Not Supported")
        }
    }

    private suspend fun Context.enqueueDownload(
        clientId: String,
        client: TrackClient,
        small: Track,
        parent: EchoMediaItem.Lists? = null
    ) = withContext(Dispatchers.IO) {
        val loaded = client.loadTrack(small)
        val album = (parent as? EchoMediaItem.Lists.AlbumItem)?.album ?: loaded.album?.let {
            (client as? AlbumClient)?.loadAlbum(it)
        } ?: loaded.album
        val track = loaded.copy(album = album)

        val settings = getSharedPreferences(packageName, Context.MODE_PRIVATE)
        val stream = TrackResolver.selectStream(settings, track.audioStreamables)
            ?: throw Exception("No Stream Found")
        val audio = client.getStreamableAudio(stream)
        val folder = "Echo${parent?.title?.let { "/$it" } ?: ""}"
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$folder/${track.title}")

        val request = when (audio) {
            is StreamableAudio.ByteStreamAudio -> {
                TODO("inputStream to file")
            }

            is StreamableAudio.StreamableRequest -> {
                val fetchRequest = Request(audio.request.url).apply {
                    setTitle(track.title)
                    setDescription(track.artists.joinToString(", ") { it.name })
                    setNotificationVisibility(NotificationVisibility.VISIBLE)
                    setDestinationFilePath(file.absolutePath)
                    audio.request.headers.forEach {
                        addHeader(it.key, it.value)
                    }
                }
                fetch.enqueue(fetchRequest, {
                    dao.insertDownload(DownloadEntity(it.id.toLong(), track.id, clientId, parent?.title))
                    saveToCache(track.id, track, "downloads")
                }, {
                    // Handle errors
                })
            }
        }
    }

    suspend fun removeDownload(context: Context, downloadId: Long) {
        fetch.remove(downloadId.toInt())
        withContext(Dispatchers.IO) {
            dao.deleteDownload(downloadId)
        }
    }

    fun pauseDownload(context: Context, downloadId: Long) {
        fetch.pause(downloadId.toInt())
    }

    suspend fun resumeDownload(context: Context, downloadId: Long) = withContext(Dispatchers.IO) {
        val download = dao.getDownload(downloadId) ?: return@withContext
        val client = extensionList.getExtension(download.clientId)?.client
        client as TrackClient
        val track = context.getFromCache(download.itemId, Track.creator, "downloads") ?: return@withContext
        context.enqueueDownload(download.clientId, client, track)
    }
}
