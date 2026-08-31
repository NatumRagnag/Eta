package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File

/** 两个 Linux rootfs 共用的磁盘布局和就绪判定。 */
internal object LinuxEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"

    fun environmentDir(context: Context, distribution: LinuxDistribution): File =
        File(context.filesDir, "terminal/${distribution.wireName}")

    fun rootfsDir(context: Context, distribution: LinuxDistribution): File =
        File(environmentDir(context, distribution), "rootfs")

    fun rootfsReady(rootfsPath: String?): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        return File(rootfsPath, READY_MARKER).isFile
    }
}
