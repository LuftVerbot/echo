package dev.brahmkshatriya.echo.download

import android.media.DeniedByServerException
import android.media.MediaDrm
import android.media.NotProvisionedException
import android.media.UnsupportedSchemeException
import java.util.UUID

class UnsupportedDrmException(reason: Int, cause: Throwable? = null) : Exception(cause) {
    companion object {
        const val REASON_UNSUPPORTED_SCHEME = 1
        const val REASON_INSTANTIATION_ERROR = 2
    }
}

// Data class to hold key request information
data class KeyRequest(
    val requestData: ByteArray,
    val licenseServerUrl: String,
    val requestType: Int
)

// Data class to hold provision request information
data class ProvisionRequest(
    val data: ByteArray,
    val defaultUrl: String
)

class DrmKeyManager(private val uuid: UUID) {

    private var mediaDrm: MediaDrm? = null
    private var sessionId: ByteArray? = null

    init {
        if (!MediaDrm.isCryptoSchemeSupported(adjustUuid(uuid))) {
            throw UnsupportedDrmException(UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME)
        }

        try {
            mediaDrm = MediaDrm(adjustUuid(uuid))
        } catch (e: UnsupportedSchemeException) {
            throw UnsupportedDrmException(UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME, e)
        } catch (e: Exception) {
            throw UnsupportedDrmException(UnsupportedDrmException.REASON_INSTANTIATION_ERROR, e)
        }
    }

    /**
     * Opens a DRM session.
     * @throws DrmException if the session cannot be opened.
     */
    @Throws(DrmException::class)
    fun openSession() {
        try {
            sessionId = mediaDrm?.openSession()
        } catch (e: Exception) {
            throw DrmException("Failed to open DRM session", e)
        }
    }

    /**
     * Closes the DRM session.
     */
    fun closeSession() {
        sessionId?.let {
            mediaDrm?.closeSession(it)
            sessionId = null
        }
    }

    /**
     * Generates a key request.
     * @param keyType The type of key request.
     * @param optionalParameters Optional parameters for the key request.
     * @return KeyRequest containing the request data and license server URL.
     * @throws DrmException if the key request cannot be generated.
     */
    @Throws(DrmException::class)
    fun getKeyRequest(
        keyType: Int,
        data: ByteArray,
        optionalParameters: HashMap<String, String>? = null
    ): KeyRequest {
        val session = sessionId ?: throw DrmException("Session is not opened")
        val schemeData = getSchemeData()
        val initData = adjustRequestInitData(data)
        val mimeType = adjustRequestMimeType(schemeData?.mimeType)

        try {
            val request = mediaDrm?.getKeyRequest(
                session,
                initData,
                mimeType,
                keyType,
                optionalParameters
            ) ?: throw DrmException("MediaDrm is not initialized")

            val requestData = adjustRequestData(request.data) ?: byteArrayOf()
            val licenseUrl = adjustLicenseServerUrl(request.defaultUrl, schemeData)

            return KeyRequest(
                requestData = requestData,
                licenseServerUrl = licenseUrl,
                requestType = request.requestType
            )
        } catch (e: NotProvisionedException) {
            throw DrmException("Not provisioned for DRM", e)
        } catch (e: Exception) {
            throw DrmException("Failed to get key request", e)
        }
    }

    /**
     * Provides the key response to the DRM system.
     * @param response The response data from the license server.
     * @return The key set ID.
     * @throws DrmException if the response is invalid or rejected.
     */
    @Throws(DrmException::class)
    fun provideKeyResponse(response: ByteArray): ByteArray? {
        val session = sessionId ?: throw DrmException("Session is not opened")
        return try {
            mediaDrm?.provideKeyResponse(session, response)
        } catch (e: NotProvisionedException) {
            throw DrmException("Not provisioned for DRM", e)
        } catch (e: DeniedByServerException) {
            throw DrmException("Key response denied by server", e)
        } catch (e: Exception) {
            throw DrmException("Failed to provide key response", e)
        }
    }

    /**
     * Releases the DRM resources.
     */
    fun release() {
        closeSession()
        mediaDrm?.release()
        mediaDrm = null
    }

    // Helper methods to adjust UUID and request data
    private fun adjustUuid(uuid: UUID): UUID {
        // Adjust UUID if necessary, e.g., for ClearKey
        return uuid
    }

    private fun adjustRequestInitData(initData: ByteArray?): ByteArray? {
        // Modify initData if necessary based on DRM scheme
        return initData
    }

    private fun adjustRequestMimeType(mimeType: String?): String? {
        // Modify MIME type if necessary based on DRM scheme
        return mimeType
    }

    private fun adjustRequestData(requestData: ByteArray?): ByteArray? {
        // Modify request data if necessary based on DRM scheme
        return requestData
    }

    private fun adjustLicenseServerUrl(defaultUrl: String?, schemeData: SchemeData?): String {
        // Adjust license server URL if necessary
        return schemeData?.licenseServerUrl ?: defaultUrl.orEmpty()
    }

    // Placeholder for obtaining scheme data
    private fun getSchemeData(): SchemeData? {
        // Implement retrieval of SchemeData as per your requirements
        return null
    }

    // Data class for SchemeData (simplified)
    data class SchemeData(
        val data: ByteArray?,
        val mimeType: String?,
        val licenseServerUrl: String?
    )
}

// Custom exception for DRM-related errors
class DrmException(message: String, cause: Throwable? = null) : Exception(message, cause)