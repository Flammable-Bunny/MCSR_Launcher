package com.redlimerl.mcsrlauncher.gui

import com.redlimerl.mcsrlauncher.MCSRLauncher
import com.redlimerl.mcsrlauncher.data.instance.BasicInstance
import com.redlimerl.mcsrlauncher.gui.component.InstanceGroupComboBox
import com.redlimerl.mcsrlauncher.gui.component.JavaSettingsPanel
import com.redlimerl.mcsrlauncher.gui.component.LogViewerPanel
import com.redlimerl.mcsrlauncher.gui.component.ResolutionSettingsPanel
import com.redlimerl.mcsrlauncher.instance.mod.ModData
import com.redlimerl.mcsrlauncher.launcher.InstanceManager
import com.redlimerl.mcsrlauncher.util.AssetUtils
import com.redlimerl.mcsrlauncher.util.I18n
import com.redlimerl.mcsrlauncher.util.LauncherWorker
import com.redlimerl.mcsrlauncher.util.SwingUtils
import org.apache.commons.io.FileUtils
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.text.SimpleDateFormat
import javax.swing.*
import javax.swing.filechooser.FileFilter
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableModel
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

class InstanceOptionGui(parent: Window, val instance: BasicInstance) : InstanceOptionDialog(parent) {

    var mods: List<ModData> = emptyList()

    init {
        title = getUpdatedTitle()
        minimumSize = Dimension(800, 500)
        setLocationRelativeTo(parent)

        this.cancelButton.addActionListener { this.dispose() }
        this.launchButton.addActionListener { instance.launchWithDialog() }

        initInstanceTab()
        initVersionTab()
        initModsTab()
        initJavaTab()
        initLogTab()

        I18n.translateGui(this)
        isVisible = true
    }

    private fun getUpdatedTitle(): String {
        return "${I18n.translate("instance.edit")} - ${instance.displayName}"
    }

    private fun initInstanceTab() {
        instanceNameField.text = instance.displayName

        InstanceGroupComboBox.init(instanceGroupField)
        instanceGroupField.selectedItem = InstanceManager.getInstanceGroup(instance)

        instanceApplyChangesButton.addActionListener {
            if (instanceNameField.text != instance.displayName) {
                InstanceManager.renameInstance(instance, instanceNameField.text)
                title = getUpdatedTitle()
            }
            if (instanceGroupField.selectedItem?.toString() != InstanceManager.getInstanceGroup(instance)) {
                InstanceManager.moveInstanceGroup(instance, instanceGroupField.selectedItem as String)
            }
        }

        instanceOpenDirectoryButton.addActionListener {
            Desktop.getDesktop().open(instance.getGamePath().toFile().apply { mkdirs() })
        }

        val resolutionSettingsPanel = ResolutionSettingsPanel(instance.options, instance.options::save)

        instanceResolutionCheckBox.addActionListener {
            SwingUtils.setEnabledRecursively(resolutionSettingsPanel, !instanceResolutionCheckBox.isSelected)
            instance.options.useLauncherResolutionOption = instanceResolutionCheckBox.isSelected
            instance.options.save()
        }
        instanceResolutionCheckBox.isSelected = instance.options.useLauncherJavaOption
        SwingUtils.setEnabledRecursively(resolutionSettingsPanel, !instanceResolutionCheckBox.isSelected)

        this.instanceResolutionPanel.layout = BorderLayout()
        this.instanceResolutionPanel.add(resolutionSettingsPanel, BorderLayout.CENTER)
    }

    private fun initVersionTab() {
        versionsTable.tableHeader.reorderingAllowed = false
        versionsTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        versionsTable.setDefaultEditor(Object::class.java, null)
        versionsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && versionsTable.selectedRow == -1 && versionsTable.rowCount > 0)
                versionsTable.setRowSelectionInterval(it.lastIndex, it.lastIndex)
        }

        val tableModel = DefaultTableModel(arrayOf(), arrayOf(I18n.translate("text.type"), I18n.translate("text.version")))
        tableModel.addRow(arrayOf("Minecraft", instance.minecraftVersion))
        tableModel.addRow(arrayOf("LWJGL", instance.lwjglVersion.version))
        val fabric = instance.fabricVersion
        if (fabric != null) {
            tableModel.addRow(arrayOf("Fabric Loader", fabric.loaderVersion))
            tableModel.addRow(arrayOf("Fabric Intermediary", "${fabric.intermediaryVersion} (${fabric.intermediaryType.intermediaryName})"))
        }
        versionsTable.model = tableModel

        if (changeVersionButton.actionListeners.isEmpty()) {
            changeVersionButton.addActionListener {
                val changeVersion = ChangeGameVersionGui(this@InstanceOptionGui, instance)
                if (changeVersion.hasChanged) {
                    initVersionTab()
                }
            }
        }
    }

    private fun updateMods() {
        this.mods = instance.getMods()

        modsTable.tableHeader.reorderingAllowed = false
        modsTable.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        modsTable.setDefaultEditor(Object::class.java, null)

        val modTableModel = object : AbstractTableModel() {
            override fun getRowCount(): Int = mods.size

            override fun getColumnCount(): Int = 5

            override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
                val mod = mods[rowIndex]
                return when(columnIndex) {
                    0 -> mod.name
                    1 -> if (mod.isEnabled) "✅" else "❌"
                    2 -> mod.version
                    3 -> SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.MEDIUM, SimpleDateFormat.SHORT, MCSRLauncher.options.language.getLocale()).format(mod.file.lastModified())
                    4 -> AssetUtils.formatFileSize(mod.file.length())
                    else -> ""
                }
            }

            override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

            override fun getColumnName(column: Int): String {
                return arrayOf(I18n.translate("text.name"), I18n.translate("text.enabled"), I18n.translate("text.version"), I18n.translate("text.last_modified"), I18n.translate("text.size"))[column]
            }
        }
        modsTable.model = modTableModel

        SwingUtilities.invokeLater {
            SwingUtils.autoFitTableColumns(modsTable)
        }
    }

    private fun initModsTab() {
        manageSpeedrunModsButton.addActionListener {
            ManageSpeedrunModsGui(this, instance, false) {
                updateMods()
            }
        }

        updateSpeedrunModsButton.addActionListener {
            object : LauncherWorker(this@InstanceOptionGui, I18n.translate("text.manage_speedrun_mods"), I18n.translate("message.checking_updates")) {
                override fun work(dialog: JDialog) {
                    val updates = instance.updateSpeedrunMods(this)
                    if (updates.isNotEmpty()) {
                        JOptionPane.showMessageDialog(this@InstanceOptionGui,
                            I18n.translate("message.download_success").plus("\n")
                                .plus(updates.joinToString("\n") { "- ${it.name} v${it.version}" })
                        )
                    }
                }
            }.showDialog().start()
        }

        autoUpdateSpeedrunModsCheckBox.isSelected = instance.options.autoModUpdates
        autoUpdateSpeedrunModsCheckBox.addActionListener {
            instance.options.autoModUpdates = autoUpdateSpeedrunModsCheckBox.isSelected
            instance.options.save()
        }

        updateMods()

        fun updateModSelection() {
            val enabled = modsTable.selectedRows.isNotEmpty()
            enableModButton.isEnabled = enabled
            disableModButton.isEnabled = enabled
            deleteModButton.isEnabled = enabled
        }

        modsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateModSelection()
            }
        }

        updateModSelection()

        openModsDirButton.addActionListener {
            Desktop.getDesktop().open(instance.getModsPath().toFile().apply { mkdirs() })
        }

        addModFileButton.addActionListener {
            val fileChooser = JFileChooser(instance.getModsPath().toFile()).apply {
                isMultiSelectionEnabled = true
                dialogType = JFileChooser.CUSTOM_DIALOG
                dialogTitle = I18n.translate("text.add_mod_file")
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = object : FileFilter() {
                    override fun accept(f: File): Boolean {
                        return f.isDirectory || f.name.lowercase().endsWith(".jar")
                    }

                    override fun getDescription(): String {
                        return "JAR File (*.jar)"
                    }
                }
            }
            SwingUtils.makeEditablePathFileChooser(fileChooser)

            val result = fileChooser.showDialog(this, I18n.translate("text.select"))

            if (result == JFileChooser.APPROVE_OPTION) {
                val selectedFiles = fileChooser.selectedFiles
                for (file in selectedFiles) {
                    if (file.parentFile.absolutePath == instance.getModsPath().absolutePathString() || instance.getModsPath().resolve(file.name).exists()) continue
                    if (ModData.get(file) == null) continue
                    FileUtils.copyFile(file, instance.getModsPath().resolve(file.name).toFile())
                }
                updateMods()
            }
        }

        this.dropTarget = object : DropTarget() {
            override fun dragOver(dtde: DropTargetDragEvent) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && optionTab.selectedIndex == 2) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                } else {
                    dtde.rejectDrag()
                }
            }
            override fun drop(dtde: DropTargetDropEvent) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && optionTab.selectedIndex == 2) {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    val droppedFiles = dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
                    for (file in droppedFiles) {
                        if (file is File) {
                            if (file.parentFile.absolutePath == instance.getModsPath().absolutePathString() || instance.getModsPath().resolve(file.name).exists()) continue
                            if (ModData.get(file) == null) continue
                            FileUtils.copyFile(file, instance.getModsPath().resolve(file.name).toFile())
                        }
                        updateMods()
                    }
                    dtde.dropComplete(true)
                } else {
                    dtde.rejectDrop()
                    dtde.dropComplete(false)
                }
            }
        }

        enableModButton.addActionListener {
            modsTable.selectedRows.map { mods[it] }.forEach { it.isEnabled = true }
            updateMods()
        }

        disableModButton.addActionListener {
            modsTable.selectedRows.map { mods[it] }.forEach { it.isEnabled = false }
            updateMods()
        }

        deleteModButton.addActionListener {
            modsTable.selectedRows.map { mods[it] }.forEach { it.delete() }
            updateMods()
        }
    }

    private fun initJavaTab() {
        val javaSettingsPanel = JavaSettingsPanel(this, instance.options, instance.options::save)

        javaLauncherSettingCheckBox.addActionListener {
            SwingUtils.setEnabledRecursively(javaSettingsPanel, !javaLauncherSettingCheckBox.isSelected)
            instance.options.useLauncherJavaOption = javaLauncherSettingCheckBox.isSelected
            instance.options.save()
        }
        javaLauncherSettingCheckBox.isSelected = instance.options.useLauncherJavaOption
        SwingUtils.setEnabledRecursively(javaSettingsPanel, !javaLauncherSettingCheckBox.isSelected)

        javaSettingsPane.layout = BorderLayout()
        javaSettingsPane.add(javaSettingsPanel, BorderLayout.CENTER)
        SwingUtils.fasterScroll(javaScrollPane)
    }

    private fun initLogTab() {
        logPanel.layout = BorderLayout()
        logPanel.add(LogViewerPanel(instance.getGamePath()).also { it.syncInstance(instance) }, BorderLayout.CENTER)
    }
}