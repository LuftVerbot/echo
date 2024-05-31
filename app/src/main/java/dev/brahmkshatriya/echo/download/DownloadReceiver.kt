package dev.brahmkshatriya.echo.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import dev.brahmkshatriya.echo.EchoDatabase
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.dao.DownloadDao
import dev.brahmkshatriya.echo.utils.getFromCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class DownloadReceiver : BroadcastReceiver() {

    @Inject
    lateinit var database: EchoDatabase

    private val downloadDao: DownloadDao by lazy { database.downloadDao() }
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val action = intent?.action ?: return
        if ("com.downloader.action.COMPLETED" == action) {
            val downloadId = intent.getLongExtra("downloadId", -1)
            val download = runBlocking {
                withContext(Dispatchers.IO) { downloadDao.getDownload(downloadId) }
            } ?: return
            val track = context.getFromCache(download.itemId, Track.creator, "downloads") ?: return

            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${track.title}.mp3")
            val newFile = File("${file.absolutePath}.mp3")

            if (file.renameTo(newFile)) {
                MediaScannerConnection.scanFile(
                    context, arrayOf(newFile.toString()), null, null
                )
            }

            runBlocking {
                withContext(Dispatchers.IO) { downloadDao.deleteDownload(downloadId) }
            }
        }
    }
}
