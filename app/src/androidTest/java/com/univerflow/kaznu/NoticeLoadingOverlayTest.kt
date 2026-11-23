package com.univerflow.kaznu

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univerflow.kaznu.databinding.ActivityMainBinding
import com.univerflow.kaznu.ui.NoticeLoadingOverlay
import com.univerflow.kaznu.web.AutomationStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NoticeLoadingOverlayTest {
    @Test fun startupAndNoticeStayCoveredUntilSuccessAndFailuresRestoreAccess() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val binding = ActivityMainBinding.inflate(activity.layoutInflater)
                activity.setContentView(binding.root)
                val web = binding.webView
                val overlay = binding.noticeLoadingOverlay
                val loading = NoticeLoadingOverlay(web, overlay)
                try {
                    web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    loading.beginStartup()
                    loading.status(AutomationStatus.SIGNING_IN)
                    assertEquals(View.VISIBLE, overlay.visibility)
                    assertEquals(View.VISIBLE, web.visibility)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, web.importantForAccessibility)
                    loading.status(AutomationStatus.NOTICE_CONFIRMING)
                    assertEquals(View.VISIBLE, overlay.visibility)
                    loading.status(AutomationStatus.IDLE)
                    assertFalse(loading.covering)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, web.importantForAccessibility)
                    for (failure in listOf(AutomationStatus.NOTICE_FAILED, AutomationStatus.LOGIN_FAILED,
                        AutomationStatus.LANGUAGE_FAILED, AutomationStatus.AGREEMENT_FAILED)) {
                        loading.beginStartup(); loading.status(failure)
                        assertFalse(loading.covering)
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, web.importantForAccessibility)
                    }
                } finally { loading.close(); web.destroyFixture() }
            }
        }
    }

    @Test fun longLoadingRevealsThePageAndOnlyExplicitRestartCanCoverItAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var loading: NoticeLoadingOverlay
            // The overlay leaves with a fade after the timeout; a fixed delay raced that fade on a busy emulator.
            val revealed = CountDownLatch(1)
            scenario.onActivity { activity ->
                binding = ActivityMainBinding.inflate(activity.layoutInflater)
                activity.setContentView(binding.root)
                loading = NoticeLoadingOverlay(binding.webView, binding.noticeLoadingOverlay, 100)
                loading.beginStartup()
                assertEquals(View.VISIBLE, binding.noticeLoadingOverlay.visibility)
                binding.root.viewTreeObserver.addOnGlobalLayoutListener {
                    if (binding.noticeLoadingOverlay.visibility == View.GONE) revealed.countDown()
                }
            }
            try {
                assertTrue("the timeout alone reveals the page", revealed.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals(View.GONE, binding.noticeLoadingOverlay.visibility)
                    loading.status(AutomationStatus.NOTICE_CONFIRMING)
                    assertEquals(View.GONE, binding.noticeLoadingOverlay.visibility)
                    assertEquals(View.VISIBLE, binding.webView.visibility)
                    loading.beginStartup()
                    assertEquals(View.VISIBLE, binding.noticeLoadingOverlay.visibility)
                }
            } finally { scenario.onActivity { loading.close(); binding.webView.destroyFixture() } }
        }
    }
}
