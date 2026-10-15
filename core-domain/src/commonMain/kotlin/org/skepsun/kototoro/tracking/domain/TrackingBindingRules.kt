package org.skepsun.kototoro.tracking.domain

import org.skepsun.kototoro.core.db.dao.TrackingSiteDao
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity

/** One manual target per website and local work; callers provide the surrounding transaction. */
suspend fun TrackingSiteDao.confirmManualTrackingLink(
    service: Int,
    remoteId: Long,
    mangaId: Long,
    sourceName: String,
    now: Long,
) {
    require(service > 0 && remoteId > 0 && sourceName.isNotBlank())
    val previous = findLinksByManga(service, mangaId).firstOrNull { it.remoteId == remoteId }
    deleteLinksByManga(service, mangaId)
    upsertLink(TrackingSiteLinkEntity(service, remoteId, mangaId, sourceName, 1f, true,
        previous?.createdAt ?: now, now))
}
