package com.univerflow.kaznu.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.univerflow.kaznu.R
import com.univerflow.kaznu.data.LastGradesStore
import com.univerflow.kaznu.databinding.ActivityLastGradesBinding
import com.univerflow.kaznu.databinding.ItemLastGradeCourseBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.LastGrades
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Последние оценки»: the totals last seen, read from the phone only. Opened from the network error screen; like the
 * offline schedule it closes when the app locks and stays out of screenshots and recents.
 */
class LastGradesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLastGradesBinding
    private lateinit var language: AppLanguage
    private val words by lazy { language.context(this) }
    private val locale by lazy { Locale.forLanguageTag(language.localeCode) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLastGradesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppLockSession.secondaryScreen(this, binding.root)
        if (AppLockSession.locked) { finish(); return }
        language = AppLanguage.fromCode(intent.getStringExtra(EXTRA_LANGUAGE))
        binding.lastGradesToolbar.title = words.getString(R.string.last_grades)
        binding.lastGradesToolbar.navigationContentDescription = words.getString(R.string.back)
        binding.lastGradesToolbar.setNavigationOnClickListener { finish() }
        binding.lastGradesNote.text = words.getString(R.string.last_grades_note)
        binding.lastGradesEmpty.text = words.getString(R.string.last_grades_none)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { LastGradesStore.of(applicationContext).read() }
            if (!isFinishing) show(saved)
        }
    }

    override fun onStart() {
        super.onStart()
        if (AppLockSession.locked && !isFinishing) finish()
    }

    private fun show(saved: LastGrades?) {
        binding.lastGradesEmpty.isVisible = saved == null
        binding.lastGradesCard.isVisible = saved != null
        binding.lastGradesNote.isVisible = saved != null
        if (saved == null) return
        val pattern = DateFormat.getBestDateTimePattern(locale, "dMMMMjjmm")
        binding.lastGradesToolbar.subtitle = words.getString(R.string.timetable_saved,
            SimpleDateFormat(pattern, locale).format(Date(saved.saved)))
        binding.lastGradesList.removeAllViews()
        saved.byCourse().forEachIndexed { index, (course, rows) ->
            val item = ItemLastGradeCourseBinding.inflate(layoutInflater, binding.lastGradesList, false)
            item.gradeCourse.text = course
            item.gradeScores.text = rows.joinToString("  ·  ") { row ->
                if (row.label.isEmpty()) row.score else "${row.label} ${row.score}"
            }
            if (index > 0) item.root.setBackgroundResource(R.drawable.timetable_divider)
            binding.lastGradesList.addView(item.root)
        }
    }

    companion object {
        private const val EXTRA_LANGUAGE = "language"
        fun intent(context: Context, language: AppLanguage): Intent =
            Intent(context, LastGradesActivity::class.java).putExtra(EXTRA_LANGUAGE, language.siteCode)
    }
}
