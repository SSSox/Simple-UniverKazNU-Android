package com.univerflow.kaznu.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.univerflow.kaznu.R
import com.univerflow.kaznu.data.TimetableStore
import com.univerflow.kaznu.databinding.ActivityTimetableBinding
import com.univerflow.kaznu.databinding.ItemTimetableLessonBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.Timetable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * «Расписание без сети»: the saved week on this week's dates, a day at a time, read from the phone only. Opened from the
 * network error screen; like Downloads it closes when the app locks.
 */
class TimetableActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTimetableBinding
    private lateinit var language: AppLanguage
    private val words by lazy { language.context(this) }
    private val locale by lazy { Locale.forLanguageTag(language.localeCode) }
    private val zone: TimeZone = TimeZone.getDefault()
    private var timetable: Timetable? = null
    private var selected: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimetableBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppLockSession.secondaryScreen(this, binding.root)
        if (AppLockSession.locked) { finish(); return }
        language = AppLanguage.fromCode(intent.getStringExtra(EXTRA_LANGUAGE))
        selected = savedInstanceState?.getLong("day", -1) ?: -1
        binding.timetableToolbar.title = words.getString(R.string.offline_schedule)
        binding.timetableToolbar.navigationContentDescription = words.getString(R.string.back)
        binding.timetableToolbar.setNavigationOnClickListener { finish() }
        binding.timetableNote.text = words.getString(R.string.timetable_note)
        lifecycleScope.launch {
            timetable = withContext(Dispatchers.IO) { TimetableStore.read(applicationContext) }
            if (!isFinishing) show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (AppLockSession.locked && !isFinishing) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("day", selected)
    }

    private fun show() {
        val saved = timetable
        binding.timetableEmpty.text = words.getString(R.string.timetable_none)
        binding.timetableEmpty.isVisible = saved == null
        binding.timetableCard.isVisible = saved != null
        binding.timetableDaysScroll.isVisible = saved != null
        binding.timetableNote.isVisible = saved != null
        if (saved == null) return
        binding.timetableToolbar.subtitle = words.getString(R.string.timetable_saved, format("dMMMMjjmm", saved.saved))
        val today = Timetable.epochDay(System.currentTimeMillis(), zone)
        // This week from Monday; on a Sunday without classes, the coming week.
        val monday = today - Timetable.weekday(today) + if (Timetable.weekday(today) == 6 && saved.on(today).isEmpty()) 7 else 0
        val days = (0L..5L).map { monday + it } + listOf(monday + 6).filter { saved.on(it).isNotEmpty() }
        if (selected !in days) selected = days.firstOrNull { it == today } ?: days.first()
        binding.timetableDays.removeAllViews()
        days.forEach { day ->
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, binding.timetableDays, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.text = format("EEEd", noon(day)).replaceFirstChar { it.titlecase(locale) }
            chip.contentDescription = format("EEEEdMMMM", noon(day))
            chip.isChecked = day == selected
            chip.setOnClickListener { selected = day; showDay(saved, day, today) }
            binding.timetableDays.addView(chip)
        }
        showDay(saved, selected, today)
    }

    private fun showDay(saved: Timetable, day: Long, today: Long) {
        binding.timetableDay.text = format("EEEEdMMMM", noon(day)).replaceFirstChar { it.titlecase(locale) }
        val lessons = saved.times(day, zone)
        val now = System.currentTimeMillis()
        binding.timetableLessons.removeAllViews()
        binding.timetableFree.text = words.getString(R.string.timetable_free_day)
        binding.timetableFree.isVisible = lessons.isEmpty()
        lessons.forEachIndexed { index, time ->
            val row = ItemTimetableLessonBinding.inflate(layoutInflater, binding.timetableLessons, false)
            val lesson = time.lesson
            row.lessonStart.text = Timetable.clock(lesson.start)
            row.lessonEnd.text = Timetable.clock(lesson.end)
            row.lessonSubject.text = lesson.subject
            row.lessonFacts.text = listOf(lesson.type, lesson.room.takeIf { it.isNotEmpty() }?.let { words.getString(R.string.widget_room, it) }, lesson.faculty)
                .filter { !it.isNullOrEmpty() }.joinToString(" · ")
            row.lessonFacts.isVisible = row.lessonFacts.text.isNotEmpty()
            row.lessonTeacher.text = lesson.teacher
            row.lessonTeacher.isVisible = lesson.teacher.isNotEmpty()
            val current = day == today && time.startAt <= now && now < time.endAt
            row.lessonNow.text = words.getString(R.string.timetable_now)
            row.lessonNow.isVisible = current
            if (index > 0) row.root.setBackgroundResource(R.drawable.timetable_divider)
            binding.timetableLessons.addView(row.root)
        }
    }

    private fun noon(day: Long) = Timetable.at(day, 12 * 60, zone)

    private fun format(skeleton: String, at: Long): String =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply { timeZone = zone }.format(Date(at))

    companion object {
        private const val EXTRA_LANGUAGE = "language"
        fun intent(context: Context, language: AppLanguage): Intent =
            Intent(context, TimetableActivity::class.java).putExtra(EXTRA_LANGUAGE, language.siteCode)
    }
}
