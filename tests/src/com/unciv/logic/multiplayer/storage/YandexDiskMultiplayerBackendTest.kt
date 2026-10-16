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
}
