package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class LinuxEnvironmentSettingsRepositoryTest {
    @Test
    fun selectionRoundTripsThroughDataStore() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val before = SettingsDataStore.linuxDistributionFlow().first()
        try {
            LinuxEnvironmentSettingsRepository.select(LinuxDistribution.ALPINE)
            assertEquals(LinuxDistribution.ALPINE, LinuxEnvironmentSettingsRepository.selectedFlow(context).first())

            LinuxEnvironmentSettingsRepository.select(LinuxDistribution.DEBIAN)
            assertEquals(LinuxDistribution.DEBIAN, LinuxEnvironmentSettingsRepository.selectedFlow(context).first())
            assertEquals(LinuxDistribution.DEBIAN, LinuxEnvironmentSettingsRepository.current(context))
        } finally {
            SettingsDataStore.setLinuxDistribution(before)
            LinuxEnvironmentSettingsRepository.initialize(context)
        }
    }
}
