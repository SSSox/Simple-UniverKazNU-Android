package com.univerflow.kaznu

import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app bar and drawer replace the floating gear; Back closes the drawer before leaving the page. */
@RunWith(AndroidJUnit4::class)
class MainChromeTest {
    @Test fun drawerOffersSectionsDownloadsAndSettingsAndBackClosesItFirst() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var ready = false
            val end = System.currentTimeMillis() + 10_000
            while (!ready && System.currentTimeMillis() < end) {
                scenario.onActivity { ready = it.findViewById<View>(R.id.main_column).visibility == View.VISIBLE }
                if (!ready) Thread.sleep(200)
            }
            assumeTrue("Needs the configured account; the first-launch form covers the app bar otherwise", ready)
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                assertNotNull(toolbar.menu.findItem(R.id.action_downloads))
                assertNotNull(toolbar.menu.findItem(R.id.action_print))
                assertTrue(toolbar.navigationContentDescription!!.isNotBlank())
                val menu = activity.findViewById<NavigationView>(R.id.navigation).menu
                listOf(R.id.nav_home, R.id.nav_schedule, R.id.nav_journal, R.id.nav_materials, R.id.nav_downloads, R.id.nav_settings, R.id.nav_browser)
                    .forEach { assertTrue(menu.findItem(it).title!!.isNotBlank()) }
                val drawer = activity.findViewById<DrawerLayout>(R.id.drawer)
                assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawer.getDrawerLockMode(GravityCompat.START))
                drawer.openDrawer(GravityCompat.START, false)
                assertTrue(drawer.isDrawerOpen(GravityCompat.START))
                activity.onBackPressedDispatcher.onBackPressed()
            }
            Thread.sleep(1000) // The drawer closes with an animation.
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<DrawerLayout>(R.id.drawer).isDrawerOpen(GravityCompat.START))
                assertFalse(activity.isFinishing)
            }
        }
    }
}
