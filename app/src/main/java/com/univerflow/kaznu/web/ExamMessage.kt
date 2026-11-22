package com.univerflow.kaznu.web

import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.ExamPlan

/** The exam or midterm schedule's coming items: JSON after [ExamPlan.MESSAGE_PREFIX], checked by [ExamPlan.batch]. */
object ExamMessage {
    fun parse(message: String?, now: Long = System.currentTimeMillis()): Pair<ExamKind, List<ExamItem>>? =
        PageJson.map(message, ExamPlan.MESSAGE_PREFIX, ExamPlan.MAX_LENGTH, maxDepth = 3)?.let { ExamPlan.batch(it, now) }
}
