package com.redlimerl.mcsrlauncher.data.instance

import com.redlimerl.mcsrlauncher.MCSRLauncher
import com.redlimerl.mcsrlauncher.data.launcher.LauncherSharedOptions
import kotlinx.serialization.Serializable

@Serializable
data class InstanceOptions(
    var useLauncherJavaOption: Boolean = true,
    var useLauncherResolutionOption: Boolean = true,
    var autoModUpdates: Boolean = false,
    var clearBeforeLaunch: Boolean = false,
    override var minMemory: Int = 512,
    override var maxMemory: Int = 2048,
    override var javaPath: String = "",
    override var jvmArguments: String = "",
    override var maximumResolution: Boolean = false,
    override var resolutionWidth: Int = 854,
    override var resolutionHeight: Int = 480
) : LauncherSharedOptions {

    fun <T> getSharedJavaValue(sharedOptions: (LauncherSharedOptions) -> T): T {
        return sharedOptions(if (useLauncherJavaOption) MCSRLauncher.options else this)
    }

    fun <T> getSharedResolutionValue(sharedOptions: (LauncherSharedOptions) -> T): T {
        return sharedOptions(if (useLauncherResolutionOption) MCSRLauncher.options else this)
    }

}
