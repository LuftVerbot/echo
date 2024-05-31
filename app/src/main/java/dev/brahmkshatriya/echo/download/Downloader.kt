package dev.brahmkshatriya.echo.download

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.ParcelFileDescriptor
import com.downloader.PRDownloader
import com.downloader.OnDownloadListener
import com.kyant.taglib.Metadata
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
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
import dev.brahmkshatriya.echo.utils.loadBitmap
import dev.brahmkshatriya.echo.utils.saveToCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.File

class Downloader(
    private val context: Context,
    private val extensionList: MutableStateFlow<List<MusicExtension>?>,
    database: EchoDatabase,
) {
    val dao = database.downloadDao()
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Initialize PRDownloader
        PRDownloader.initialize(context)
    }

    suspend fun addToDownload(
        clientId: String, item: EchoMediaItem
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
                            enqueueDownload(clientId, client, it, album.toMediaItem())
                        }
                    }

                    is EchoMediaItem.Lists.PlaylistItem -> {
                        client as PlaylistClient
                        val playlist = client.loadPlaylist(item.playlist)
                        val tracks = client.loadTracks(playlist)
                        tracks.clear()
                        tracks.loadAll().forEach {
                            enqueueDownload(clientId, client, it, playlist.toMediaItem())
                        }
                    }
                }
            }

            is EchoMediaItem.TrackItem -> {
                enqueueDownload(clientId, client, item.track)
            }

            else -> throw IllegalArgumentException("Not Supported")
        }
    }

    private suspend fun enqueueDownload(
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

        val settings = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
        val stream = TrackResolver.selectStream(settings, track.audioStreamables)
            ?: throw Exception("No Stream Found")
        val audio = client.getStreamableAudio(stream)
        val folder = "Echo${parent?.title?.let { "/$it" } ?: ""}"
        val dirPath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$folder"
        val fileName = "${track.title}.mp3"
        val file = File(dirPath, fileName)

        when (audio) {
            is StreamableAudio.ByteStreamAudio -> {
                file.parentFile?.mkdirs()
                file.outputStream().use { outputStream ->
                    audio.stream.use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                applyTags(file, track)
                dao.insertDownload(DownloadEntity(file.absolutePath.hashCode().toLong(), track.id, clientId, parent?.title))
                context.saveToCache(track.id, track, "downloads")
                MediaScannerConnection.scanFile(
                    context, arrayOf(file.toString()), null, null
                )
                dao.deleteDownload(file.absolutePath.hashCode().toLong()) // Remove from download queue
            }

            is StreamableAudio.StreamableRequest -> {
                val request = audio.request
                PRDownloader.download(request.url, dirPath, fileName)
                    .build()
                    .setOnStartOrResumeListener { }
                    .setOnPauseListener { }
                    .setOnCancelListener { }
                    .setOnProgressListener { }
                    .start(object : OnDownloadListener {
                        override fun onDownloadComplete() {
                            downloadScope.launch {
                                val downloadedFile = File(dirPath, fileName)
                                applyTags(downloadedFile, track)
                                dao.insertDownload(DownloadEntity(request.url.hashCode().toLong(), track.id, clientId, parent?.title))
                                context.saveToCache(track.id, track, "downloads")
                                MediaScannerConnection.scanFile(
                                    context, arrayOf(downloadedFile.toString()), null, null
                                )
                                dao.deleteDownload(request.url.hashCode().toLong()) // Remove from download queue
                            }
                        }

                        override fun onError(error: com.downloader.Error?) {
                            // Handle error
                        }
                    })
            }
        }
    }

    private fun applyTags(newFile: File, track: Track) {
        val fd = ParcelFileDescriptor.open(newFile, ParcelFileDescriptor.MODE_READ_WRITE)

        val metadata = TagLib.getMetadata(fd = fd.dup().detachFd(), readPictures = true)
            ?: Metadata(PropertyMap(), arrayOf())
        val artwork = runBlocking { track.cover.loadBitmap(context) }?.let {
            val stream = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Picture(stream.toByteArray(), "Back Cover", "Back Cover", "image/png")
        }

        artwork?.let { TagLib.savePictures(fd.dup().detachFd(), arrayOf(it)) }
        val props = metadata.propertyMap.apply {
            set("TITLE", arrayOf(track.title))
            set("ARTIST", arrayOf(track.artists.joinToString(", ") { it.name }))
            track.album?.run {
                set("ALBUM", arrayOf(title))
                set("ALBUMARTIST", arrayOf(artists.joinToString(", ") { it.name }))
                releaseDate?.let { set("DATE", arrayOf(it)) }
            }
        }
        TagLib.savePropertyMap(fd.dup().detachFd(), props)
    }

    suspend fun removeDownload(downloadId: Long) {
        PRDownloader.cancel(downloadId.toInt())
        withContext(Dispatchers.IO) {
            dao.deleteDownload(downloadId)
        }
    }

    fun pauseDownload(downloadId: Long) {
        PRDownloader.pause(downloadId.toInt())
    }

    suspend fun resumeDownload(downloadId: Long) = withContext(Dispatchers.IO) {
        val download = dao.getDownload(downloadId) ?: return@withContext
        val client = extensionList.getExtension(download.clientId)?.client
        client as TrackClient
        val track = context.getFromCache(download.itemId, Track.creator, "downloads")
            ?: return@withContext
        enqueueDownload(download.clientId, client, track)
    }
}
