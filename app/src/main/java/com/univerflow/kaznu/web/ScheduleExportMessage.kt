package com.univerflow.kaznu.web

import com.univerflow.kaznu.domain.ExportKind
import com.univerflow.kaznu.domain.SchedulePair

/** The schedule page's «В календарь» and «Картинкой»: JSON after [SchedulePair.MESSAGE_PREFIX], checked by [SchedulePair.of]. */
object ScheduleExportMessage {
    fun parse(message: String?): Pair<ExportKind, SchedulePair>? =
        PageJson.map(message, SchedulePair.MESSAGE_PREFIX, SchedulePair.MAX_LENGTH, maxDepth = 4)?.let(SchedulePair::of)
}
