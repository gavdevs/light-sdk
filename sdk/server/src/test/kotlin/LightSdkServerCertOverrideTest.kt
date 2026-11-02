import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.LightSdkServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightSdkServerCertOverrideTest {

    @AfterTest
    fun restoreClassification() {
        LightSdkServer.checkCertOverride = null
    }

    @Test
    fun `an override that admits the companion's own package passes the Light-signed filter`() {
        LightSdkServer.checkCertOverride = { callingPackage ->
            if (callingPackage == "com.example.companion") {
                ClientCertType.LightSdkSignedUnverified
            } else {
                ClientCertType.Unknown
            }
        }

        val cert = checkNotNull(LightSdkServer.checkCertOverride)("com.example.companion")
        assertEquals(ClientCertType.LightSdkSignedUnverified, cert)
        assertTrue(
            LightSdkServer.isPackageAllowed(ClientFilterLevel.AllowLightSignedApks) {
                LightSdkServer.checkCertOverride?.invoke("com.example.companion") ?: ClientCertType.Unknown
            }
        )
    }

    @Test
    fun `an override-graded unverified caller does not pass the approved-only filter`() {
        LightSdkServer.checkCertOverride = { ClientCertType.LightSdkSignedUnverified }

        assertFalse(
            LightSdkServer.isPackageAllowed(ClientFilterLevel.AllowLightApprovedApks) {
                LightSdkServer.checkCertOverride?.invoke("anything") ?: ClientCertType.Unknown
            }
        )
    }
}
