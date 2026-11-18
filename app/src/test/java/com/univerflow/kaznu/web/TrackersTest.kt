package com.univerflow.kaznu.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackersTest {
    @Test fun theSitesGoogleAnalyticsTagAndItsBeaconsAreBlocked() {
        listOf("www.googletagmanager.com", "googletagmanager.com", "www.google-analytics.com", "region1.google-analytics.com",
            "analytics.google.com", "region1.analytics.google.com", "WWW.GoogleTagManager.COM", "www.google-analytics.com.")
            .forEach { assertTrue(it, Trackers.blocked(it)) }
    }

    @Test fun univerAndOtherHostsStayUntouched() {
        listOf(null, "", "univer.kaznu.kz", "farabi.university", "www.google.com", "google.com", "docs.google.com",
            "accounts.google.com", "xgoogle-analytics.com", "google-analytics.com.evil.org", "notgoogletagmanager.com",
            "analytics.google.com.example", "googleanalytics.com")
            .forEach { assertFalse(it.toString(), Trackers.blocked(it)) }
    }
}
