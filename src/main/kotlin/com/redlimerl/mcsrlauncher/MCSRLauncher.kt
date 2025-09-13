package com.redlimerl.mcsrlauncher

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.fonts.roboto.FlatRobotoFont
import com.github.ajalt.clikt.core.main
import com.redlimerl.mcsrlauncher.data.device.RuntimeOSType
import com.redlimerl.mcsrlauncher.data.launcher.LauncherOptions
import com.redlimerl.mcsrlauncher.gui.MainMenuGui
import com.redlimerl.mcsrlauncher.instance.InstanceProcess
import com.redlimerl.mcsrlauncher.launcher.*
import com.redlimerl.mcsrlauncher.util.I18n
import com.redlimerl.mcsrlauncher.util.LauncherWorker
import com.redlimerl.mcsrlauncher.util.OSUtils
import com.redlimerl.mcsrlauncher.util.UpdaterUtils
import kotlinx.serialization.json.Json
import org.apache.commons.io.FileUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.layout.PatternLayout
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JDialog
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import kotlin.system.exitProcess

object MCSRLauncher {

    val APP_NAME: String = javaClass.simpleName
    lateinit var LOG_APPENDER: LauncherLogAppender private set
    lateinit var LOGGER: Logger private set
    val BASE_PATH: Path = Paths.get("").resolve("launcher")
    val IS_DEV_VERSION = javaClass.`package`.implementationVersion == null
    val APP_VERSION = javaClass.`package`.implementationVersion ?: "dev"
    val GAME_PROCESSES = arrayListOf<InstanceProcess>()
    val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val ARGS_PORT = if (IS_DEV_VERSION) 53511 else 53510
    lateinit var MAIN_FRAME: MainMenuGui private set
    lateinit var options: LauncherOptions private set

    @JvmStatic
    fun main(args: Array<String>) {
        if (!LauncherOptions.path.toFile().exists() && !checkPathIsEmpty()) {
            val updateConfirm = JOptionPane.showConfirmDialog(null, "This directory contains files that are not related to the launcher.\nRunning the launcher here may create additional files in this folder, which could cause unexpected issues.\nDo you still want to continue?", "Warning!", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (updateConfirm != JOptionPane.YES_OPTION) return
        }

        LOGGER = LogManager.getLogger(APP_NAME).also {
            val mainLogger = (it as org.apache.logging.log4j.core.Logger)
            val ctx = LogManager.getContext(false) as LoggerContext
            val config = ctx.configuration
            val rootLogger = config.rootLogger

            LOG_APPENDER = LauncherLogAppender(mainLogger.appenders.values.first().layout as PatternLayout)
            LOG_APPENDER.start()

            rootLogger.addAppender(LOG_APPENDER, null, null)
            ctx.updateLoggers()
        }

        LOGGER.info("Starting launcher - Version: $APP_VERSION, Java: ${System.getProperty("java.version")}")
        val server: ServerSocket
        try {
            server = ServerSocket(ARGS_PORT)
        } catch (e: IOException) {
            LOGGER.info("port(${ARGS_PORT}) is already opened. send argument to launcher args server instead of setup the launcher.")
            Socket("localhost", ARGS_PORT).use { socket ->
                ObjectOutputStream(socket.getOutputStream()).use { outputStream -> outputStream.writeObject(args) }
            }
            return
        }

        // Setup Theme
        LOGGER.warn("Loading theme")
        SwingUtilities.invokeAndWait {
            FlatRobotoFont.install()
            FlatLaf.setPreferredFontFamily(FlatRobotoFont.FAMILY)
            FlatLaf.setPreferredLightFontFamily( FlatRobotoFont.FAMILY_LIGHT )
            FlatLaf.setPreferredSemiboldFontFamily( FlatRobotoFont.FAMILY_SEMIBOLD )
            FlatDarkLaf.setup()
        }

        object : LauncherWorker(null, "Loading...", "Initializing...") {
            override fun work(dialog: JDialog) {
                LOGGER.warn("Base Path: {}", BASE_PATH.absolutePathString())
                LOGGER.warn("OS & Arch: {}", RuntimeOSType.current())
                LOGGER.warn("System OS: {}", OSUtils.systemInfo.operatingSystem.family)
                LOGGER.warn("System Bits: {}", OSUtils.systemInfo.operatingSystem.bitness)
                LOGGER.warn("System Arch: {}", System.getProperty("os.arch"))

                this.setState("Loading Launcher Options...")
                options = try {
                    JSON.decodeFromString<LauncherOptions>(FileUtils.readFileToString(LauncherOptions.path.toFile(), Charsets.UTF_8))
                } catch (e: NoSuchFileException) {
                    LOGGER.warn("{} not found. creating new one", LauncherOptions.path.name)
                    LauncherOptions().also { it.save() }
                } catch (e: Exception) {
                    LOGGER.error(e, e)
                    LauncherOptions()
                }

                this.setState("Loading Updater...")
                UpdaterUtils.setup()

                this.setState("Checking for Launcher Update...")
                val latestVersion = UpdaterUtils.checkLatestVersion(this)
                if (latestVersion != null) {
                    SwingUtilities.invokeAndWait {
                        val updateConfirm = JOptionPane.showConfirmDialog(null, I18n.translate("message.new_update_found").plus("\nCurrent: $APP_VERSION\nNew: $latestVersion"), I18n.translate("text.check_update"), JOptionPane.YES_NO_OPTION)
                        if (updateConfirm == JOptionPane.YES_OPTION) {
                            UpdaterUtils.launchUpdater()
                        }
                    }
                }

                this.setState("Loading Accounts...")
                AccountManager.load()

                this.setState("Loading Instances...")
                InstanceManager.loadAll()

                this.setState("Loading Meta...")
                GameAssetManager.init()
                try {
                    MetaManager.load(this)
                } catch (e: Exception) {
                    LOGGER.error("Failed to load meta info", e)
                    if (!MetaManager.hasLoadedPackages()) {
                        dialog.dispose()
                        JOptionPane.showMessageDialog(null, I18n.translate("message.meta_load_fail"), I18n.translate("text.error"), JOptionPane.OK_OPTION)
                        return
                    }
                }

                dialog.dispose()

                LOGGER.warn("Setup gui")
                SwingUtilities.invokeLater {
                    MAIN_FRAME = MainMenuGui()
                }

                LOGGER.info("Setup launch arguments")
                thread {
                    while (!Thread.interrupted()) {
                        val client = server.accept()
                        ObjectInputStream(client.getInputStream()).use { inputStream ->
                            @Suppress("UNCHECKED_CAST") val receivedArgs = inputStream.readObject() as Array<String>
                            ArgumentHandler().main(receivedArgs)
                        }
                        client.close()
                    }
                }
                ArgumentHandler().main(args)
            }

            override fun onError(e: Throwable) {
                super.onError(e)
                exitProcess(1)
            }
        }.indeterminate().showDialog().start()
    }

    private fun checkPathIsEmpty(): Boolean {
        val exeExecute = System.getProperty("launch4j.exefile")

        if (exeExecute != null) return true

        val jarPath = File(object {}.javaClass.protectionDomain.codeSource.location.toURI().path)

        if (jarPath.isFile && jarPath.extension == "jar") {
            val jarDir = jarPath.parentFile

            for (file in jarDir.listFiles()!!) {
                if (file.name.contains(APP_NAME, true)) continue
                if (file.name.contains(jarPath.nameWithoutExtension, true)) continue
                if (file.name == "logs" && file.isDirectory) continue
                if (file.name == "launcher" && file.isDirectory) continue
                return false
            }
        }
        return true
    }
}
