package com.univerflow.kaznu.web

import com.univerflow.kaznu.domain.GradeReport

/** The journal's «Поделиться успеваемостью» message: JSON after [GradeReport.MESSAGE_PREFIX], checked by [GradeReport.of]. */
object ReportMessage {
    fun parse(message: String?): GradeReport? =
        PageJson.map(message, GradeReport.MESSAGE_PREFIX, GradeReport.MAX_LENGTH)?.let(GradeReport::of)
}
