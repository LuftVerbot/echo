package dev.brahmkshatriya.echo.player

import android.content.Context
import android.os.Parcel
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.ExtensionModule
import dev.brahmkshatriya.echo.viewmodels.ExtensionViewModel.Companion.noClient
import dev.brahmkshatriya.echo.viewmodels.ExtensionViewModel.Companion.trackNotSupported
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@UnstableApi
class TrackDataSource(
    private val context: Context,
    private val extensionListFlow: ExtensionModule.ExtensionListFlow,
    private val global: Queue,
    private val dataSource: DataSource
) : BaseDataSource(true) {

    class Factory(
        private val context: Context,
        private val extensionListFlow: ExtensionModule.ExtensionListFlow,
        private val global: Queue,
        private val factory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource() =
            TrackDataSource(context, extensionListFlow, global, factory.createDataSource())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) =
        dataSource.read(buffer, offset, length)

    private fun getTrack(id: String) = global.getTrack(id)
        ?: throw Exception(context.getString(R.string.track_not_found))

    private suspend fun getAudio(id: String) {
        val streamableTrack = getTrack(id)
        if(streamableTrack.loaded != null) return

        val client = extensionListFlow.getClient(streamableTrack.clientId)
            ?: throw Exception(context.noClient().message)
        if (client !is TrackClient)
            throw Exception(context.trackNotSupported(client.metadata.name).message)

        val track = getTrackFromCache(id)
            ?: client.loadTrack(streamableTrack.unloaded)
                .also { saveTrackToCache(id, it) }

        streamableTrack.loaded = track
        streamableTrack.onLoad.emit(track)

    }

    private fun getTrackFromCache(id: String): Track? {
        val fileName = id.hashCode().toString()
        val file = File(context.cacheDir, fileName)
        return if (file.exists()) {
            val bytes = FileInputStream(file).use { it.readBytes() }
            val parcel = Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val track = Track.creator.createFromParcel(parcel)
            parcel.recycle()
            track
        } else {
            null
        }
    }

    private fun saveTrackToCache(id: String, track: Track) {
        val fileName = id.hashCode().toString()
        val parcel = Parcel.obtain()
        track.writeToParcel(parcel, 0)
        val bytes = parcel.marshall()
        parcel.recycle()
        FileOutputStream(File(context.cacheDir, fileName)).use { it.write(bytes) }
    }

    override fun open(dataSpec: DataSpec): Long {
        runBlocking { runCatching { getAudio(dataSpec.uri.toString()) } }.getOrThrow()
        return dataSource.open(dataSpec)
    }

    override fun getUri() = dataSource.uri
    override fun close() = dataSource.close()
}