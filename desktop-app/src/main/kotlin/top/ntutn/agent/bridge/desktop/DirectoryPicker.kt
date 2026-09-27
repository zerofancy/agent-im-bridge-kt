package top.ntutn.agent.bridge.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** Called on the UI thread; the chooser belongs to the workbench window. */
internal fun chooseDirectory(owner: Frame, current: String): String? {
    val initial = File(current.ifBlank { System.getProperty("user.home") })
        .takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
    val selected = if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        chooseMacDirectory(owner, initial)
    } else {
        val chooser = JFileChooser(initial).apply {
            dialogTitle = "选择工作目录"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            isAcceptAllFileFilterUsed = false
            approveButtonText = "选择此文件夹"
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
    return selected?.takeIf { it.isDirectory }?.absolutePath
}

private fun chooseMacDirectory(owner: Frame, initial: File): File? {
    // macOS AWT exposes NSOpenPanel's directory-only mode through this property.
    // Restore the prior setting so a future file picker is not accidentally changed.
    val property = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(property)
    val dialog = FileDialog(owner, "选择工作目录", FileDialog.LOAD)
    try {
        System.setProperty(property, "true")
        dialog.directory = initial.absolutePath
        dialog.isMultipleMode = false
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    } finally {
        try { dialog.dispose() }
        finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
