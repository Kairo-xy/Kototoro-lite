package org.skepsun.kototoro.local.shared

import okio.FileSystem

internal actual val localFileSystem: FileSystem get() = FileSystem.SYSTEM
