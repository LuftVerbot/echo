package dev.brahmkshatriya.echo.download

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.brahmkshatriya.echo.EchoDatabase
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.db.models.DownloadEntity
import dev.brahmkshatriya.echo.extensions.get
import dev.brahmkshatriya.echo.extensions.getExtension
import dev.brahmkshatriya.echo.extensions.run
import dev.brahmkshatriya.echo.offline.MediaStoreUtils.id
import dev.brahmkshatriya.echo.ui.settings.AudioFragment.AudioPreference.Companion.select
import dev.brahmkshatriya.echo.utils.getFromCache
import dev.brahmkshatriya.echo.utils.saveToCache
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

class Downloader(
    private val extensionList: MutableStateFlow<List<MusicExtension>?>,
    private val throwable: MutableSharedFlow<Throwable>,
    context: Context,
    database: EchoDatabase,
) : CoroutineScope {
    val dao = database.downloadDao()

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val activeDownloads = ConcurrentHashMap<Long, Job>()
    private val activeDownloadGroups = mutableMapOf<Long, DownloadGroup>()
    private val notificationBuilders = mutableMapOf<Int, NotificationCompat.Builder>()

    private val settings = context.getSharedPreferences(
        context.packageName,
        Context.MODE_PRIVATE
    )

    private val illegalChars = "[/\\\\:*?\"<>|]".toRegex()

    private val downloadSemaphore = Semaphore(settings.getInt("download_num", 2))

    suspend fun addToDownload(
        context: Context, clientId: String, item: EchoMediaItem
    ) {
        val extension = extensionList.getExtension(clientId) ?: return
        when (item) {
            is EchoMediaItem.Lists -> handleListItem(context, extension, item)
            is EchoMediaItem.TrackItem -> enqueueDownload(context, extension, item.track, 0)
            else -> throw IllegalArgumentException("Not Supported")
        }
    }

    private suspend fun handleListItem(
        context: Context,
        extension: Extension<*>,
        item: EchoMediaItem.Lists
    ) {
        when (item) {
            is EchoMediaItem.Lists.AlbumItem -> handleAlbumDownload(context, extension, item)
            is EchoMediaItem.Lists.PlaylistItem -> handlePlaylistDownload(context, extension, item)
            is EchoMediaItem.Lists.RadioItem -> Unit
        }
    }

    private suspend fun handleAlbumDownload(
        context: Context,
        extension: Extension<*>,
        item: EchoMediaItem.Lists.AlbumItem
    ) {
        extension.get<AlbumClient, Unit>(throwable) {
            val album = loadAlbum(item.album)
            val tracks = loadTracks(album)
            tracks.clear()
            val allTracks = tracks.loadAll()
            val groupId = album.id.id()
            val groupTitle = album.title

            val notificationId = (groupId and 0x7FFFFFFF).toInt()
            val notificationBuilder = DownloadNotificationHelper.buildNotification(
                context,
                "Downloading Album: $groupTitle",
                progress = 0,
                indeterminate = false
            )
            val group = DownloadGroup(
                id = groupId,
                title = groupTitle,
                totalTracks = allTracks.size,
                downloadedTracks = 0,
                notificationId = notificationId,
                notificationBuilder = notificationBuilder
            )
            activeDownloadGroups[groupId] = group

            DownloadNotificationHelper.updateNotification(
                context,
                notificationId,
                notificationBuilder.build()
            )

            allTracks.forEachIndexed { index, track ->
                enqueueDownload(context, extension, track, order = index + 1, parent = item, group = group)
            }
        }
    }

    private suspend fun handlePlaylistDownload(
        context: Context,
        extension: Extension<*>,
        item: EchoMediaItem.Lists.PlaylistItem
    ) {
        extension.get<PlaylistClient, Unit>(throwable) {
            val playlist = loadPlaylist(item.playlist)
            val tracks = loadTracks(playlist)
            tracks.clear()
            val allTracks = tracks.loadAll()
            val groupId = playlist.id.id()
            val groupTitle = playlist.title

            val notificationId = (groupId and 0x7FFFFFFF).toInt()
            val notificationBuilder = DownloadNotificationHelper.buildNotification(
                context,
                "Downloading Playlist: $groupTitle",
                progress = 0,
                indeterminate = false
            )
            val group = DownloadGroup(
                id = groupId,
                title = groupTitle,
                totalTracks = allTracks.size,
                downloadedTracks = 0,
                notificationId = notificationId,
                notificationBuilder = notificationBuilder
            )
            activeDownloadGroups[groupId] = group

            DownloadNotificationHelper.updateNotification(
                context,
                notificationId,
                notificationBuilder.build()
            )

            allTracks.forEachIndexed { index, track ->
                enqueueDownload(context, extension, track, order = index + 1, parent = item, group = group)
            }
        }
    }

    private fun enqueueDownload(
        context: Context,
        extension: Extension<*>,
        track: Track,
        order: Int,
        parent: EchoMediaItem.Lists? = null,
        group: DownloadGroup? = null
    ) {
        launch {
            try {
                val loadedTrack =
                    extension.get<TrackClient, Track>(throwable) { loadTrack(track) }
                        ?: return@launch

                val album =
                    (parent as? EchoMediaItem.Lists.AlbumItem)?.album
                        ?: loadedTrack.album?.let {
                            extension.get<AlbumClient, Album>(throwable) { loadAlbum(it) }
                        } ?: loadedTrack.album

                val completeTrack = loadedTrack.copy(album = album, cover = track.cover)
                val stream = completeTrack.servers.select(settings)

                val media = extension.get<TrackClient, Streamable.Media.Server>(throwable) {
                    loadStreamableMedia(stream) as Streamable.Media.Server
                } ?: return@launch

                val source = media.sources.select(settings)

                val sanitizedParent = illegalChars.replace(parent?.title.orEmpty(), "_")
                val folder =
                    if (sanitizedParent.isNotBlank()) "Echo/$sanitizedParent" else "Echo"
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDirectory = File(downloadsDir, folder).apply { mkdirs() }

                val sanitizedTitle = illegalChars.replace(completeTrack.title, "_")
                val fileExtension = when (source) {
                    is Streamable.Source.Http -> "m4a"
                    is Streamable.Source.Channel, is Streamable.Source.ByteStream -> "mp3"
                    else -> "m4a"
                }

                val uniqueFile = getUniqueFile(targetDirectory, sanitizedTitle, fileExtension)
                val downloadId = completeTrack.id.id()
                val notificationId: Int
                val notificationBuilder: NotificationCompat.Builder

                if (group != null) {
                    notificationId = group.notificationId
                    notificationBuilder = group.notificationBuilder
                } else {
                    notificationId = (downloadId and 0x7FFFFFFF).toInt()
                    notificationBuilder = DownloadNotificationHelper.buildNotification(
                        context,
                        "Downloading: ${completeTrack.title}",
                        indeterminate = true
                    )
                    notificationBuilders[notificationId] = notificationBuilder

                    DownloadNotificationHelper.updateNotification(
                        context,
                        notificationId,
                        notificationBuilder.build()
                    )
                }

                val job = handleDownload(
                    context,
                    extension,
                    source,
                    completeTrack,
                    targetDirectory,
                    sanitizedTitle,
                    fileExtension,
                    downloadId,
                    notificationId,
                    notificationBuilder,
                    group,
                    order
                )

                activeDownloads[downloadId] = job
            } catch (e: Exception) {
                throwable.emit(e)
                val notificationId = (track.id.id() and 0x7FFFFFFF).toInt()
                DownloadNotificationHelper.errorNotification(
                    context,
                    notificationId,
                    track.title,
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun handleDownload(
        context: Context,
        extension: Extension<*>,
        audio: Streamable.Source,
        completeTrack: Track,
        targetDirectory: File,
        sanitizedTitle: String,
        fileExtension: String,
        downloadId: Long,
        notificationId: Int,
        notificationBuilder: NotificationCompat.Builder,
        group: DownloadGroup?,
        order: Int
    ): Job = launch {
        downloadSemaphore.withPermit {
            val tempFile = File(targetDirectory, "$sanitizedTitle.tmp").apply { createNewFile() }
            var totalHttpBytes = 0L
            val inputStream = when (audio) {
                is Streamable.Source.Channel -> audio.channel.toInputStream()
                is Streamable.Source.ByteStream -> audio.stream
                is Streamable.Source.Http -> {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(audio.request.url).build()
                    val response = client.newCall(request).execute()
                    totalHttpBytes = response.body.contentLength()
                    response.body.byteStream()
                }
                else -> null
            } ?: throw IllegalArgumentException("Unsupported audio stream type")

            var received: Long = 0
            val totalBytes = when (audio) {
                is Streamable.Source.Channel -> audio.totalBytes
                is Streamable.Source.ByteStream -> audio.totalBytes
                is Streamable.Source.Http -> totalHttpBytes
                else -> -1L
            }

            try {
                BufferedInputStream(inputStream).use { bis ->
                    FileOutputStream(tempFile, false).use { fos ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        val progressUpdateInterval = 500L
                        var lastUpdateTime = System.currentTimeMillis()
                        var lastProgress = 0

                        while (bis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                            received += bytesRead

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= progressUpdateInterval) {
                                val progress = if (totalBytes > 0) {
                                    ((received * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    -1
                                }

                                if (group == null && (progress >= lastProgress + 10 || progress == 100)) {
                                    lastProgress = progress
                                    lastUpdateTime = currentTime

                                    withContext(Dispatchers.Main) {
                                        if (progress >= 0) {
                                            notificationBuilder.setProgress(100, progress, false)
                                                .setContentText("Downloading: $progress%")
                                        } else {
                                            notificationBuilder.setProgress(0, 0, true)
                                                .setContentText("Downloading...")
                                        }
                                        DownloadNotificationHelper.updateNotification(
                                            context,
                                            notificationId,
                                            notificationBuilder.build()
                                        )
                                    }
                                } else if (group != null) {
                                    lastUpdateTime = currentTime
                                }
                            }

                            if (totalBytes in 1..received) {
                                break
                            }
                        }
                    }
                }

                val clientId = extensionList.value?.find { it.id == extension.id }?.id.orEmpty()
                val detectedExtension = probeFileFormat(tempFile) ?: fileExtension
                val finalFile = getUniqueFile(targetDirectory, sanitizedTitle, detectedExtension)
                if (tempFile.renameTo(finalFile)) {
                    dao.insertDownload(
                        DownloadEntity(
                            id = downloadId,
                            itemId = completeTrack.id,
                            clientId = clientId,
                            groupName = group?.title,
                            downloadPath = finalFile.absolutePath
                        )
                    )

                    context.saveToCache(completeTrack.id, completeTrack, "downloads")
                    try {
                        val data = getLyrics(extension, completeTrack, clientId)
                        if (data != null) {
                            context.saveToCache(completeTrack.id, data, "lyrics")
                        }
                    } catch (e: Exception) {
                        throwable
                    }
                    sendDownloadCompleteBroadcast(context, downloadId, order)

                    updateGroupProgress(context, group, notificationId)

                    if (group == null) {
                        withContext(Dispatchers.Main) {
                            notificationBuilder.setContentText("Download complete")
                                .setProgress(0, 0, false)
                                .setOngoing(false)
                            DownloadNotificationHelper.updateNotification(
                                context,
                                notificationId,
                                notificationBuilder.build()
                            )
                        }
                    }
                } else {
                    throw Exception("Failed to rename temporary file")
                }
            } catch (e: Exception) {
                throwable.emit(e)
                if (tempFile.exists()) tempFile.delete()
                DownloadNotificationHelper.errorNotification(
                    context,
                    notificationId,
                    completeTrack.title,
                    e.message ?: "Unknown error"
                )
            } finally {
                activeDownloads.remove(downloadId)
                notificationBuilders.remove(notificationId)
            }
        }
    }

    private suspend fun getLyrics(
        extension: Extension<*>,
        track: Track,
        clientId: String
    ): Lyrics? {
        val data = extension.get<LyricsClient, PagedData<Lyrics>>(throwable) {
            searchTrackLyrics(clientId, track)
        }
        val value = extension.run(throwable) { data?.loadFirst()?.firstOrNull() }
        return if (value != null) {
            extension.get<LyricsClient, Lyrics>(throwable) {
                loadLyrics(value)
            }?.fillGaps()
        } else {
            null
        }
    }

    private fun Lyrics.fillGaps(): Lyrics {
        val lyrics = this.lyrics as? Lyrics.Timed
        return if (lyrics != null && lyrics.fillTimeGaps) {
            val new = mutableListOf<Lyrics.Item>()
            var last = 0L
            lyrics.list.forEach {
                if (it.startTime > last) {
                    new.add(Lyrics.Item("", last, it.startTime))
                }
                new.add(it)
                last = it.endTime
            }
            this.copy(lyrics = Lyrics.Timed(new))
        } else this
    }

    private fun sendDownloadCompleteBroadcast(context: Context, downloadId: Long, order: Int) {
        Intent(context, DownloadReceiver::class.java).also { intent ->
            intent.action = "dev.brahmkshatriya.echo.DOWNLOAD_COMPLETE"
            intent.putExtra("downloadId", downloadId)
            intent.putExtra("order", order)
            context.sendBroadcast(intent)
        }
    }

    private suspend fun updateGroupProgress(context: Context, group: DownloadGroup?, notificationId: Int) {
        group?.let {
            it.downloadedTracks += 1
            if (it.downloadedTracks >= it.totalTracks) {
                withContext(Dispatchers.Main) {
                    it.notificationBuilder.setContentText("Download complete")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                    DownloadNotificationHelper.updateNotification(
                        context,
                        notificationId,
                        it.notificationBuilder.build()
                    )
                }
                activeDownloadGroups.remove(it.id)
            } else {
                withContext(Dispatchers.Main) {
                    it.notificationBuilder.setContentText("Downloaded ${it.downloadedTracks}/${it.totalTracks} Songs")
                        .setProgress(it.totalTracks, it.downloadedTracks, false)
                    DownloadNotificationHelper.updateNotification(
                        context,
                        notificationId,
                        it.notificationBuilder.build()
                    )
                }
            }
        }
    }

    private fun probeFileFormat(file: File): String?  {
        val ffprobeCommand =
            "-v error -show_entries format=format_name -of default=noprint_wrappers=1:nokey=1 \"${file.absolutePath}\""

        val session = FFprobeKit.execute(ffprobeCommand)
        return if (ReturnCode.isSuccess(session.returnCode)) {
            session.output?.trim()?.split(",")?.firstOrNull()
        } else {
            null
        }
    }

    private fun getUniqueFile(directory: File, baseName: String, extension: String): File {
        var uniqueName = "$baseName.$extension"
        var file = File(directory, uniqueName)
        var counter = 1

        while (file.exists()) {
            uniqueName = "$baseName ($counter).$extension"
            file = File(directory, uniqueName)
            counter++
        }

        return file
    }

    suspend fun removeDownload(context: Context, downloadId: Long) {
        activeDownloads[downloadId]?.cancel()
        activeDownloads.remove(downloadId)
        dao.deleteDownload(downloadId)
        withContext(Dispatchers.Main) {
            DownloadNotificationHelper.completeNotification(
                context,
                (downloadId and 0x7FFFFFFF).toInt(),
                "Download Removed"
            )
        }
    }

    fun pauseDownload(context: Context, downloadId: Long) {
        activeDownloads[downloadId]?.cancel()
        println("Paused download: $downloadId")
    }


    fun resumeDownload(context: Context, downloadId: Long) {
        val download = dao.getDownload(downloadId) ?: return
        val extension = extensionList.getExtension(download.clientId) ?: return
        val track = context.getFromCache<Track>(download.itemId, "downloads") ?: return
        enqueueDownload(context, extension, track, 0)
    }

    companion object {
        private data class DownloadGroup(
            val id: Long,
            val title: String,
            val totalTracks: Int,
            var downloadedTracks: Int,
            val notificationId: Int,
            val notificationBuilder: NotificationCompat.Builder
        )
    }
}