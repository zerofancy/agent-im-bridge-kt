package top.ntutn.agent.bridge

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

/**
 * 平台感知的私有文件/目录创建与权限设置。
 *
 * - POSIX（macOS / Linux）：保持原有语义，目录 `rwx------`、文件 `rw-------`。
 * - Windows：文件系统无 posix 权限视图，创建时使用默认属性（继承父目录 ACL）；
 *   创建后对支持 ACL 的路径尽力收紧为仅当前用户完全控制（目录带继承标志）。
 *   收紧失败只记日志、不阻断启动——环境目录通常位于用户主目录下，其默认 ACL
 *   已保证仅本机用户可访问。
 */
internal object PlatformFiles {
    internal val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private const val DIR_MODE = "rwx------"
    private const val FILE_MODE = "rw-------"

    private fun directoryAttrs(): Array<FileAttribute<*>> = if (isWindows) emptyArray()
        else arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(DIR_MODE)))

    private fun fileAttrs(): Array<FileAttribute<*>> = if (isWindows) emptyArray()
        else arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_MODE)))

    fun createDirectories(path: Path) { Files.createDirectories(path, *directoryAttrs()) }

    fun createTempDirectory(dir: Path, prefix: String): Path = Files.createTempDirectory(dir, prefix, *directoryAttrs())

    fun createTempFile(dir: Path, prefix: String, suffix: String): Path =
        Files.createTempFile(dir, prefix, suffix, *fileAttrs())

    fun openChannel(path: Path, options: Set<OpenOption>): FileChannel =
        if (isWindows) FileChannel.open(path, options) else FileChannel.open(path, options, *fileAttrs())

    /** 收紧私有权限：POSIX 设置权限位；Windows 尽力设置 owner 完全控制的 ACL。 */
    fun setPrivatePermissions(path: Path, directory: Boolean) {
        if (isWindows) restrictAcl(path, directory)
        else Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) DIR_MODE else FILE_MODE))
    }

    private fun restrictAcl(path: Path, directory: Boolean) {
        // 尽力而为：ACL 收紧失败静默，不阻断启动（环境目录通常位于用户主目录下，默认 ACL 已隔离）。
        try {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
            val owner = view.owner
            val builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            if (directory) builder.setFlags(EnumSet.of(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT))
            view.acl = listOf(builder.build())
        } catch (_: Exception) {
            // 不记录路径内容：仅 Windows 使用，静默失败不影响启动。
        }
    }
}
