package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Linux 发行版选择的单一持久化入口。 */
internal object LinuxEnvironmentSettingsRepository {
    @Volatile
    private var cachedSelection: LinuxDistribution? = null

    fun selectedFlow(context: Context): Flow<LinuxDistribution> =
        SettingsDataStore.linuxDistributionFlow()
            .map { persisted ->
                decode(persisted) ?: defaultSelection(context.applicationContext)
            }
            .onEach { cachedSelection = it }

    fun current(context: Context): LinuxDistribution =
        cachedSelection ?: defaultSelection(context.applicationContext)

    suspend fun initialize(context: Context) {
        cachedSelection = selectedFlow(context.applicationContext).first()
    }

    suspend fun select(distribution: LinuxDistribution) {
        cachedSelection = distribution
        SettingsDataStore.setLinuxDistribution(distribution.wireName)
    }

    private fun decode(value: String?): LinuxDistribution? =
        LinuxDistribution.entries.firstOrNull { distribution -> distribution.wireName == value }

    private fun defaultSelection(context: Context): LinuxDistribution {
        val alpineReady = LinuxEnvironmentPaths.rootfsReady(
            LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.ALPINE).absolutePath,
        )
        val debianReady = LinuxEnvironmentPaths.rootfsReady(
            LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.DEBIAN).absolutePath,
        )
        return if (alpineReady && !debianReady) LinuxDistribution.ALPINE else LinuxDistribution.DEBIAN
    }
}
