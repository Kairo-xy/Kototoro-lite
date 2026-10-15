package org.skepsun.kototoro.cloudstream.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** The caller serializes archive replacement with runtime loading. */
internal fun replaceCloudstreamPluginArchive(download: File, target: File, activate: () -> Unit) {
    val directory = requireNotNull(target.parentFile)
    val staged = File.createTempFile(".${target.name}-", ".pending", directory)
    var backup: File? = null
    var originalMoved = false
    var replacementMoved = false
    var preserveBackup = false
    try {
        download.copyTo(staged, overwrite = true)
        if (target.exists()) {
            backup = File.createTempFile(".${target.name}-", ".backup", directory)
            Files.move(target.toPath(), backup.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            originalMoved = true
        }
        Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        replacementMoved = true
        activate()
    } catch (error: Throwable) {
        try {
            if (originalMoved) {
                Files.move(requireNotNull(backup).toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } else if (replacementMoved) {
                Files.deleteIfExists(target.toPath())
            }
        } catch (rollbackError: Throwable) {
            preserveBackup = true
            error.addSuppressed(rollbackError)
        }
        throw error
    } finally {
        staged.delete()
        if (!preserveBackup) backup?.delete()
    }
}
