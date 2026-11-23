package com.univerflow.kaznu

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univerflow.kaznu.main.DownloadLauncher
import com.univerflow.kaznu.main.StorageWaiters
import com.univerflow.kaznu.web.SitePolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingDownloadRotationTest {
    @Test fun permissionRequestSurvivesRecreationWithItsOriginalSessionAndNoCookies() {
        // The requests waiting for the storage permission live in the screen's download part (main/DownloadLauncher, StorageWaiters).
        val launcher = MainActivity::class.java.getDeclaredField("downloads").apply { isAccessible = true }
        val waiting = DownloadLauncher::class.java.getDeclaredField("waiting").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") fun pending(activity: MainActivity) = waiting.get(launcher.get(activity)) as StorageWaiters<Bundle>
        val epoch = MainActivity::class.java.getDeclaredField("sessionEpoch").apply { isAccessible = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                epoch.setInt(activity, 7)
                pending(activity).add(Bundle().apply { putString("url", SitePolicy.ORIGIN + "/fixture/download"); putInt("epoch", 7) })
                pending(activity).add(Bundle().apply { putString("url", SitePolicy.ORIGIN + "/fixture/second"); putInt("epoch", 7) })
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val all = pending(activity).pending()
                assertEquals("every waiting download survives", listOf("/fixture/download", "/fixture/second").map { SitePolicy.ORIGIN + it }, all.map { it.getString("url") })
                val restored = all.first()
                assertEquals(SitePolicy.ORIGIN + "/fixture/download", restored.getString("url"))
                assertEquals(7, restored.getInt("epoch"))
                assertEquals(7, epoch.getInt(activity))
                assertFalse(restored.containsKey("cookie"))
            }
        }
    }
}
