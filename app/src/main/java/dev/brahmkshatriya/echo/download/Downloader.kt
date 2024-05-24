package dev.brahmkshatriya.echo.download

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
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
import okhttp3.*
import java.io.IOException
import java.io.InputStream
import java.util.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class Downloader(
    private val extensionList: MutableStateFlow<List<MusicExtension>?>,
    database: EchoDatabase,
) {
    val dao = database.downloadDao()
    private val channelId = "download_channel"
    private val notificationId = 1

    init {
        createNotificationChannel()
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

        val id = when (audio) {
            is StreamableAudio.ByteStreamAudio -> {
                saveByteStreamToFile(audio.stream, folder, "${track.title}.flac", context)
            }

            is StreamableAudio.StreamableRequest -> {
                val request = audio.request
                downloadUsingOkHttp(request, folder, "${track.title}.flac", context)
            }
        }

        dao.insertDownload(DownloadEntity(id, track.id, clientId, parent?.title))
        saveToCache(track.id, track, "downloads")
    }

    private fun downloadUsingOkHttp(
        request: dev.brahmkshatriya.echo.common.models.Request, 
        folder: String, 
        fileName: String, 
        context: Context
    ): Long {
        val client = OkHttpClient()
        val okhttpRequest = okhttp3.Request.Builder()
            .url(request.url)
            .apply {
                request.headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        val call = client.newCall(okhttpRequest)
        val response = call.execute()

        if (!response.isSuccessful) throw IOException("Failed to download file: ${response.code}")

        val file = File(downloadDirectoryFor(folder), fileName)
        response.body?.let { body ->
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            val inputStream = body.byteStream()
            file.outputStream().use { outputStream ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    // Update progress here
                    val progress = (downloadedBytes.toDouble() / totalBytes * 100).toInt()
                    showProgressNotification(context, progress)
                }
            }
        }

        return file.hashCode().toLong()
    }

    private fun showProgressNotification(context: Context, progress: Int) {
        val notificationManager = NotificationManagerCompat.from(context)
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Downloading...")
            .setContentText("Download in progress")
            .setSmallIcon(R.drawable.ic_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, false)

        notificationManager.notify(notificationId, builder.build())

        if (progress == 100) {
            notificationManager.cancel(notificationId)
        }
    }

    private fun saveByteStreamToFile(stream: InputStream, folder: String, fileName: String, context: Context): Long {
        val file = File(downloadDirectoryFor(folder), fileName)
        file.parentFile?.mkdirs() // Ensure the directory exists
        stream.use { input ->
            file.outputStream().use { output ->
                val totalBytes = input.available().toLong()
                var downloadedBytes = 0L
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    // Update progress here
                    val progress = (downloadedBytes.toDouble() / totalBytes * 100).toInt()
                    showProgressNotification(context, progress)
                }
            }
        }
        return file.hashCode().toLong()
    }

    private fun downloadDirectoryFor(folder: String?): File {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    suspend fun removeDownload(context: Context, downloadId: Long) {
        withContext(Dispatchers.IO) {
            val file = findDownloadedFileById(downloadId)
            file?.delete()
            dao.deleteDownload(downloadId)
        }
    }

    private fun findDownloadedFileById(downloadId: Long): File? {
        // Implement logic to find the downloaded file by its ID
        return null
    }

    fun pauseDownload(context: Context, downloadId: Long) {
        // Pausing download is more complex with OkHttp and would require additional logic to manage
        println("pauseDownload: $downloadId")
    }

    suspend fun resumeDownload(context: Context, downloadId: Long) = withContext(Dispatchers.IO) {
        println("resumeDownload: $downloadId")
        val download = dao.getDownload(downloadId) ?: return@withContext
        val client = extensionList.getExtension(download.clientId)?.client
        client as TrackClient
        val track =
            context.getFromCache(download.itemId, Track.creator, "downloads")
                ?: return@withContext
        context.enqueueDownload(download.clientId, client, track)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Download Channel"
            val descriptionText = "Channel for download progress"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
