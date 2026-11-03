package com.univerflow.kaznu

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univerflow.kaznu.main.DownloadLauncher
import com.univerflow.kaznu.web.SitePolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingDownloadRotationTest {
    @Test fun permissionRequestSurvivesRecreationWithItsOriginalSessionAndNoCookies() {
        // The request waiting for the storage permission lives in the screen's download part (main/DownloadLauncher).
        val launcher = MainActivity::class.java.getDeclaredField("downloads").apply { isAccessible = true }
        val pending = DownloadLauncher::class.java.getDeclaredField("legacyRequest").apply { isAccessible = true }
        val epoch = MainActivity::class.java.getDeclaredField("sessionEpoch").apply { isAccessible = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                epoch.setInt(activity, 7)
                pending.set(launcher.get(activity), Bundle().apply { putString("url", SitePolicy.ORIGIN + "/fixture/download"); putInt("epoch", 7) })
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val restored = pending.get(launcher.get(activity)) as Bundle
                assertEquals(SitePolicy.ORIGIN + "/fixture/download", restored.getString("url"))
                assertEquals(7, restored.getInt("epoch"))
                assertEquals(7, epoch.getInt(activity))
                assertFalse(restored.containsKey("cookie"))
            }
        }
    }
}
