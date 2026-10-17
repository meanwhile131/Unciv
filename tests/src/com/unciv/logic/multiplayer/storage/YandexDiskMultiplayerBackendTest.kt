package com.unciv.logic.multiplayer.storage

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.multiplayer.ApiVersion
import com.unciv.logic.multiplayer.Multiplayer
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class YandexDiskMultiplayerBackendTest {
    @Test
    fun yandexDiskBackendIsSelected() {
        TestGame()
        UncivGame.Current.settings.multiplayer.setServer(Constants.yandexDiskMultiplayerServer)

        Assert.assertTrue(Multiplayer.usesYandexDisk())
        Assert.assertFalse(Multiplayer.usesCustomServer())
        Assert.assertFalse(Multiplayer.usesDropbox())

        val storage = MultiplayerServer().fileStorage()
        Assert.assertTrue("Expected Yandex Disk file storage backend", storage === YandexDisk)
    }

    @Test
    fun yandexDiskIsApiV0() {
        TestGame()
        val detected = runBlocking { ApiVersion.detect(Constants.yandexDiskMultiplayerServer) }
        Assert.assertEquals(ApiVersion.APIv0, detected)
    }

    @Test
    fun yandexDiskAliasesAreRecognized() {
        TestGame()
        for (alias in listOf("YandexDisk", "yandex disk", "YANDEXDISK", "Yandex.Disk")) {
            UncivGame.Current.settings.multiplayer.setServer(alias)
            Assert.assertTrue("Alias not recognized: $alias", Multiplayer.usesYandexDisk())
            Assert.assertTrue(Constants.isYandexDiskServer(alias))
            Assert.assertTrue(MultiplayerServer().fileStorage() === YandexDisk)
        }
    }

    @Test
    fun successfulResponseMeansOnline() {
        for (code in listOf(200, 201, 299)) {
            Assert.assertSame(
                "HTTP $code should mean the API accepted us",
                YandexDisk.Status.Online,
                YandexDisk.Status.fromResponse(code, hasToken = true)
            )
        }
    }

    @Test
    fun unauthorizedResponseDistinguishesMissingFromRejectedToken() {
        for (code in listOf(401, 403)) {
            Assert.assertSame(
                "HTTP $code with a configured token means the token was rejected",
                YandexDisk.Status.InvalidToken,
                YandexDisk.Status.fromResponse(code, hasToken = true)
            )
            Assert.assertSame(
                "HTTP $code without a token means we never had one to reject",
                YandexDisk.Status.NoToken,
                YandexDisk.Status.fromResponse(code, hasToken = false)
            )
        }
    }

    @Test
    fun unexpectedResponseIsUnreachable() {
        // 429 is turned into FileStorageRateLimitReached before it gets here
        for (code in listOf(400, 404, 429, 500, 503)) {
            val status = YandexDisk.Status.fromResponse(code, hasToken = true)
            Assert.assertTrue("HTTP $code should be unreachable, was $status",
                status is YandexDisk.Status.Unreachable)
        }
    }

    @Test
    fun everyStatusHasItsOwnDescription() {
        val statuses = listOf(
            YandexDisk.Status.Online,
            YandexDisk.Status.NoToken,
            YandexDisk.Status.InvalidToken,
            YandexDisk.Status.Unreachable(null)
        )
        val descriptions = statuses.map { it.description }
        for (status in statuses) {
            Assert.assertTrue("Empty description for $status", status.description.isNotBlank())
        }
        Assert.assertEquals(
            "Ambiguous status descriptions: $descriptions",
            statuses.size, descriptions.toSet().size
        )
    }
}
