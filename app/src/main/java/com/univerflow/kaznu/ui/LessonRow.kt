package com.univerflow.kaznu.ui

import android.content.Context
import androidx.core.view.isVisible
import com.univerflow.kaznu.R
import com.univerflow.kaznu.databinding.ItemTimetableLessonBinding
import com.univerflow.kaznu.domain.LessonTime
import com.univerflow.kaznu.domain.Timetable

/** One class of the offline schedule or the offline screen: times, name, what and where, who; [current] marks it «Идёт». */
fun ItemTimetableLessonBinding.show(time: LessonTime, words: Context, current: Boolean) {
    val lesson = time.lesson
    lessonStart.text = Timetable.clock(lesson.start)
    lessonEnd.text = Timetable.clock(lesson.end)
    lessonSubject.text = lesson.subject
    lessonFacts.text = listOf(lesson.type, lesson.room.takeIf { it.isNotEmpty() }?.let { words.getString(R.string.widget_room, it) }, lesson.faculty)
        .filter { !it.isNullOrEmpty() }.joinToString(" · ")
    lessonFacts.isVisible = lessonFacts.text.isNotEmpty()
    lessonTeacher.text = lesson.teacher
    lessonTeacher.isVisible = lesson.teacher.isNotEmpty()
    lessonNow.text = words.getString(R.string.timetable_now)
    lessonNow.isVisible = current
}
