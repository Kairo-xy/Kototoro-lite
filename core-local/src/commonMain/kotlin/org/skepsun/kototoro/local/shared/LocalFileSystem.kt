package org.skepsun.kototoro.local.shared

import okio.FileSystem

/** Okio exposes the system filesystem in platform source sets, outside its common API. */
internal expect val localFileSystem: FileSystem
