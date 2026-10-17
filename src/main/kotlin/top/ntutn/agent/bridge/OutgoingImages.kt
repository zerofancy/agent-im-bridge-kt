package top.ntutn.agent.bridge

import java.io.*
import java.nio.channels.Channels
import java.nio.file.*
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlinx.coroutines.*

/** Only backend adapters create these descriptors; paths in answer text are never attachments. */
data class AgentImage(
    val sourceId: String,
    val backend: String,
    val requestId: String,
    val path: Path? = null,
    val base64: String? = null,
    val mime: String? = null,
    val allowedRoots: List<Path> = emptyList(),
)

enum class ImageIssue {
    UNKNOWN_OUTPUT,
    UNREADABLE,
    LIMIT,
    UNSUPPORTED,
}

data class PreparedImage(val path: Path, val mime: String)

class PreparedImages(
    val images: List<PreparedImage>,
    val issues: List<ImageIssue>,
    private val directory: Path,
) {
    suspend fun release(): Unit =
        withContext(NonCancellable + Dispatchers.IO) {
            Files.newDirectoryStream(directory).use { files ->
                files.forEach { Files.deleteIfExists(it) }
            }
            Files.deleteIfExists(directory)
        }
}

enum class ImageSendResult {
    SENT,
    REJECTED,
    UNCERTAIN,
}

fun interface ImageSender {
    suspend fun send(route: ReplyRoute, image: PreparedImage): ImageSendResult
}

/** A dedicated, environment-local staging directory. Construct before accepting any requests. */
class OutgoingImages(private val root: Path, private val sender: ImageSender) {
    companion object {
        const val MAX_IMAGE_BYTES = 10_000_000
        const val MAX_TOTAL_BYTES = 30_000_000
        const val MAX_IMAGES = 10
    }

    init {
        require(root.isAbsolute)
        Files.createDirectories(root)
        // Only our own flat staging directories; never follow links when removing stale output.
        Files.newDirectoryStream(root, "reply-*").use { entries ->
            for (entry in entries) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.newDirectoryStream(entry).use { files ->
                        files.forEach { Files.deleteIfExists(it) }
                    }
                }
                Files.deleteIfExists(entry)
            }
        }
    }

    suspend fun prepare(candidates: List<AgentImage>): PreparedImages {
        var prepared: PreparedImages? = null
        try {
            return withContext(Dispatchers.IO) { prepareInIo(candidates).also { prepared = it } }
        } catch (e: Throwable) {
            try {
                prepared?.release()
            } catch (cleanup: IOException) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    private suspend fun prepareInIo(candidates: List<AgentImage>): PreparedImages {
        val context = currentCoroutineContext()
        val directory = Files.createTempDirectory(root, "reply-")
        val images = mutableListOf<PreparedImage>()
        val issues = mutableListOf<ImageIssue>()
        val hashes = mutableSetOf<String>()
        var total = 0
        var outputTotal = 0
        try {
            for ((index, candidate) in
                candidates
                    .distinctBy { Triple(it.backend, it.requestId, it.sourceId) }
                    .withIndex()) {
                context.ensureActive()
                if (index >= MAX_IMAGES) {
                    issues += ImageIssue.LIMIT
                    continue
                }
                try {
                    val source = runInterruptible { source(candidate) }
                    total += source.size
                    if (total > MAX_TOTAL_BYTES) {
                        issues += ImageIssue.LIMIT
                        break
                    }
                    val declared =
                        candidate.mime
                            ?: candidate.base64
                                ?.takeIf { it.startsWith("data:") }
                                ?.substringAfter("data:")
                                ?.substringBefore(';')
                    val bytes = validate(source, declared)
                    val hash =
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                            "%02x".format(it)
                        }
                    if (!hashes.add(hash)) {
                        continue
                    }
                    outputTotal += bytes.size
                    if (outputTotal > MAX_TOTAL_BYTES) {
                        issues += ImageIssue.LIMIT
                        break
                    }
                    val path = directory.resolve("image-${images.size}.png")
                    Files.write(path, bytes, StandardOpenOption.CREATE_NEW)
                    images += PreparedImage(path, "image/png")
                } catch (e: InvalidImage) {
                    issues += e.issue
                } catch (_: IOException) {
                    issues += ImageIssue.UNREADABLE
                }
            }
            return PreparedImages(images, issues, directory)
        } catch (e: Throwable) {
            try {
                PreparedImages(images, issues, directory).release()
            } catch (cleanup: IOException) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    suspend fun send(route: ReplyRoute, image: PreparedImage): ImageSendResult =
        sender.send(route, image)

    private class InvalidImage(val issue: ImageIssue) : IOException()

    private fun invalid(issue: ImageIssue = ImageIssue.UNSUPPORTED): Nothing =
        throw InvalidImage(issue)

    private fun source(image: AgentImage): ByteArray {
        image.path?.let { path ->
            try {
                return local(path, image.allowedRoots)
            } catch (e: IOException) {
                if (image.base64.isNullOrBlank()) {
                    throw e
                }
            }
        }
        var value = image.base64 ?: invalid(ImageIssue.UNREADABLE)
        if (value.startsWith("data:")) {
            val comma = value.indexOf(',')
            if (
                comma < 0 ||
                    value.substring(0, comma) !in
                        setOf(
                            "data:image/png;base64",
                            "data:image/jpeg;base64",
                            "data:image/webp;base64",
                        )
            ) {
                invalid()
            }
            val declared = value.substring(5, comma).substringBefore(';')
            if (image.mime != null && image.mime != declared) {
                invalid()
            }
            value = value.substring(comma + 1)
        }
        if (value.length > ((MAX_IMAGE_BYTES + 2) / 3) * 4) {
            invalid(ImageIssue.LIMIT)
        }
        return try {
            Base64.getDecoder().decode(value).also {
                if (it.size > MAX_IMAGE_BYTES) {
                    invalid(ImageIssue.LIMIT)
                }
            }
        } catch (_: IllegalArgumentException) {
            invalid(ImageIssue.UNREADABLE)
        }
    }

    private fun local(path: Path, roots: List<Path>): ByteArray {
        if (!path.isAbsolute) {
            invalid()
        }
        val real = path.toRealPath()
        if (roots.none { real.startsWith(it.toRealPath()) }) {
            invalid()
        }
        // Open every directory relative to an already-open descriptor. Replacing a path component
        // with a symlink cannot redirect this read outside the checked directory tree.
        val opened = mutableListOf<DirectoryStream<Path>>()
        try {
            val first = Files.newDirectoryStream(real.root)
            opened += first
            var parent = first as? SecureDirectoryStream<Path> ?: invalid()
            for (i in 0 until real.nameCount - 1) {
                parent = parent.newDirectoryStream(real.getName(i), LinkOption.NOFOLLOW_LINKS)
                opened += parent
            }
            val attributes =
                parent
                    .getFileAttributeView(
                        real.fileName,
                        java.nio.file.attribute.BasicFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    .readAttributes()
            if (!attributes.isRegularFile) {
                invalid()
            }
            if (attributes.size() > MAX_IMAGE_BYTES) {
                invalid(ImageIssue.LIMIT)
            }
            return parent
                .newByteChannel(
                    real.fileName,
                    setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                )
                .use { channel ->
                    Channels.newInputStream(channel).readNBytes(MAX_IMAGE_BYTES + 1).also {
                        if (it.size > MAX_IMAGE_BYTES) {
                            invalid(ImageIssue.LIMIT)
                        }
                        val after =
                            parent
                                .getFileAttributeView(
                                    real.fileName,
                                    java.nio.file.attribute.BasicFileAttributeView::class.java,
                                    LinkOption.NOFOLLOW_LINKS,
                                )
                                .readAttributes()
                        if (
                            attributes.fileKey() != after.fileKey() ||
                                attributes.size() != after.size() ||
                                attributes.lastModifiedTime() != after.lastModifiedTime()
                        ) {
                            invalid()
                        }
                    }
                }
        } finally {
            opened.asReversed().forEach { it.close() }
        }
    }

    private fun validate(bytes: ByteArray, declared: String?): ByteArray {
        val actual =
            when {
                bytes.size >= 8 &&
                    bytes
                        .copyOfRange(0, 8)
                        .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> {
                    "image/png"
                }
                bytes.size >= 3 &&
                    bytes[0] == (-1).toByte() &&
                    bytes[1] == (-40).toByte() &&
                    bytes[2] == (-1).toByte() -> {
                    "image/jpeg"
                }
                bytes.size >= 12 &&
                    String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> {
                    "image/webp"
                }
                else -> {
                    invalid()
                }
            }
        if (declared != null && declared != actual) {
            invalid()
        }
        // APNG is not reported as multiple frames by the JDK PNG reader.
        if (actual == "image/png") {
            var offset = 8
            while (offset + 12 <= bytes.size) {
                val length = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
                if (length < 0 || length.toLong() + offset + 12 > bytes.size) {
                    invalid()
                }
                if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == "acTL") {
                    invalid()
                }
                offset += length + 12
            }
        }
        if (actual == "image/webp") {
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val tag = String(bytes, offset, 4, Charsets.US_ASCII)
                if (tag == "ANIM" || tag == "ANMF") {
                    invalid()
                }
                val length =
                    java.nio.ByteBuffer.wrap(bytes, offset + 4, 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .int
                if (length < 0 || offset.toLong() + 8 + length > bytes.size) {
                    invalid()
                }
                offset += 8 + length + (length and 1)
            }
        }
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) {
                invalid()
            }
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (
                    width <= 0 ||
                        height <= 0 ||
                        width.toLong() * height > 40_000_000 ||
                        width.toLong() + height > 10_000 ||
                        maxOf(width, height).toLong() > minOf(width, height).toLong() * 20
                ) {
                    invalid(ImageIssue.LIMIT)
                }
                if (reader.getNumImages(true) != 1) {
                    invalid()
                }
                val image = reader.read(0) ?: invalid()
                val output = ByteArrayOutputStream()
                val limited =
                    object : FilterOutputStream(output) {
                        var count = 0

                        override fun write(b: Int) {
                            if (++count > MAX_IMAGE_BYTES) {
                                invalid(ImageIssue.LIMIT)
                            }
                            out.write(b)
                        }

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (len > MAX_IMAGE_BYTES - count) {
                                invalid(ImageIssue.LIMIT)
                            }
                            count += len
                            out.write(b, off, len)
                        }
                    }
                try {
                    // Explicit memory stream prevents ImageIO from creating unowned global temp
                    // files.
                    javax.imageio.stream.MemoryCacheImageOutputStream(limited).use { target ->
                        val writer = ImageIO.getImageWritersByFormatName("png").next()
                        try {
                            writer.output = target
                            writer.write(image)
                            target.flush()
                        } finally {
                            writer.dispose()
                        }
                    }
                    return output.toByteArray()
                } finally {
                    image.flush()
                }
            } finally {
                reader.dispose()
            }
        }
    }
}
