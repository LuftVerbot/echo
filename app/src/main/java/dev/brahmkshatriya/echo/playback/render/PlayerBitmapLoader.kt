package dev.brahmkshatriya.echo.playback.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.utils.loadBitmap
import dev.brahmkshatriya.echo.utils.toData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future

@UnstableApi
class PlayerBitmapLoader(
    val context: Context,
    private val scope: CoroutineScope
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String) = true

    override fun decodeBitmap(data: ByteArray) = scope.future(Dispatchers.IO) {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Failed to decode bitmap")
    }

    private val emptyBitmap
        get() = context.loadBitmap(R.drawable.art_music) ?: error("Empty bitmap")

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        val cover = runCatching { uri.toString().toData<ImageHolder>() }.getOrNull()
        cover?.loadBitmap(context) ?: emptyBitmap
    }
}