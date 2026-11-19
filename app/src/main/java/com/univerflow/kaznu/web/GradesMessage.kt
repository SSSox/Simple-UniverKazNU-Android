package com.univerflow.kaznu.web

import com.univerflow.kaznu.domain.GradeTotals
import org.json.JSONTokener

/**
 * The journal's totals: JSON after [GradeTotals.MESSAGE_PREFIX] from Кабинет's grades panel, or the bare JSON the
 * journal's reader gives when the app asks the journal page itself or a fetched copy ([READ]).
 */
object GradesMessage {
    /** Evaluated on a journal document: its totals as a JSON string, or null without the reader or the journal. */
    const val READ = "(function(){var r=window.__univerGradeReadV1;var t=r&&r.read(document,location.pathname);return t?JSON.stringify(t):null})()"

    fun parse(message: String?, prefix: String = GradeTotals.MESSAGE_PREFIX): GradeTotals? =
        PageJson.map(message, prefix, GradeTotals.MAX_LENGTH)?.let(GradeTotals::of)

    /** What evaluateJavascript returned for a reader call: a JSON-quoted string, or "null". */
    fun fromScript(value: String?): GradeTotals? =
        runCatching { JSONTokener(value ?: return null).nextValue() as? String }.getOrNull()?.let { parse(it, prefix = "") }
}
