package dev.brahmkshatriya.echo.offline

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.extensions.plugger.LazyPluginRepo
import dev.brahmkshatriya.echo.extensions.plugger.catchLazy
import kotlinx.coroutines.flow.MutableStateFlow

class BuiltInExtensionRepo(
    private val extension: OfflineExtension
) : LazyPluginRepo<Metadata, ExtensionClient> {

    override fun getAllPlugins() = MutableStateFlow(
        listOf(
//            getLazy(TestExtension.metadata, TestExtension()),
            getLazy(OfflineExtension.metadata, extension),
        )
    )

    private fun getLazy(metadata: Metadata, extension: ExtensionClient) =
        Result.success(Pair(metadata, catchLazy { extension }))
}