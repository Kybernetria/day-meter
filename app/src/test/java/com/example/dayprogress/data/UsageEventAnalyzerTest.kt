package com.example.dayprogress.data

import com.example.dayprogress.data.UsageEventAnalyzer.Event
import com.example.dayprogress.data.UsageEventAnalyzer.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageEventAnalyzerTest {
    @Test
    fun foregroundQualifiesWhenDeviceTransitionAndInteractionEventsAreAbsent() {
        val result = analyze(
            now = 120,
            threshold = 60,
            events = listOf(resumed(0, "app", "Main#1"))
        )

        assertEquals(120L, result.qualifyingUsageMillis)
        assertEquals(60L, result.thresholdCrossedAtMillis)
    }

    @Test
    fun pausingOneActivityDoesNotClearAnotherResumedActivityInSamePackage() {
        val result = analyze(
            now = 200,
            threshold = 150,
            events = listOf(
                resumed(0, "app", "First#1"),
                resumed(100, "app", "Second#2"),
                paused(100, "app", "First#1"),
                stopped(110, "app", "First#1")
            )
        )

        assertEquals(200L, result.qualifyingUsageMillis)
        assertEquals(150L, result.thresholdCrossedAtMillis)
    }

    @Test
    fun delayedStopDoesNotClearAnotherResumedActivityOfSameClass() {
        val result = analyze(
            now = 100,
            threshold = 75,
            events = listOf(
                resumed(0, "app", "SharedActivity"),
                resumed(50, "app", "SharedActivity"),
                paused(50, "app", "SharedActivity"),
                stopped(60, "app", "SharedActivity")
            )
        )

        assertEquals(100L, result.qualifyingUsageMillis)
        assertEquals(75L, result.thresholdCrossedAtMillis)
    }

    @Test
    fun knownScreenAndKeyguardStatesGateUsageIndependently() {
        val result = analyze(
            now = 100,
            threshold = 45,
            events = listOf(
                resumed(0, "app", "Main#1"),
                event(20, EventType.SCREEN_NON_INTERACTIVE),
                event(50, EventType.SCREEN_INTERACTIVE),
                event(70, EventType.KEYGUARD_SHOWN),
                event(90, EventType.KEYGUARD_HIDDEN)
            )
        )

        // [0,20), [50,70), and [90,100) qualify.
        assertEquals(50L, result.qualifyingUsageMillis)
        assertEquals(95L, result.thresholdCrossedAtMillis)
    }

    @Test
    fun overlappingPackagesAreCountedAsAUnionAndCrossingIsPrecise() {
        val result = analyze(
            now = 60,
            threshold = 25,
            events = listOf(
                resumed(0, "one", "One#1"),
                resumed(5, "two", "Two#1"),
                paused(10, "one", "One#1"),
                paused(20, "two", "Two#1"),
                resumed(30, "three", "Three#1"),
                paused(50, "three", "Three#1")
            )
        )

        // The foreground union is [0,20) plus [30,50), not the sum of each app.
        assertEquals(40L, result.qualifyingUsageMillis)
        assertEquals(35L, result.thresholdCrossedAtMillis)
    }

    @Test
    fun usageBeforeIgnoreBoundaryWarmsStateButIsNotCounted() {
        val result = analyze(
            now = 40,
            ignoreBefore = 10,
            threshold = 15,
            events = listOf(
                resumed(0, "app", "Main#1"),
                paused(30, "app", "Main#1")
            )
        )

        assertEquals(20L, result.qualifyingUsageMillis)
        assertEquals(25L, result.thresholdCrossedAtMillis)
    }

    @Test
    fun excludedPackagesNeverQualify() {
        val result = analyze(
            now = 100,
            threshold = 10,
            excludedPackages = setOf("launcher"),
            events = listOf(resumed(0, "launcher", "Home#1"))
        )

        assertEquals(0L, result.qualifyingUsageMillis)
        assertNull(result.thresholdCrossedAtMillis)
    }

    private fun analyze(
        now: Long,
        threshold: Long,
        events: List<Event>,
        ignoreBefore: Long = 0,
        excludedPackages: Set<String> = emptySet()
    ) = UsageEventAnalyzer.analyze(
        queryStartMillis = 0,
        ignoreBeforeMillis = ignoreBefore,
        nowMillis = now,
        thresholdMillis = threshold,
        excludedPackages = excludedPackages,
        events = events
    )

    private fun resumed(timestamp: Long, packageName: String, activityId: String) = Event(
        timestampMillis = timestamp,
        type = EventType.ACTIVITY_RESUMED,
        packageName = packageName,
        activityId = activityId
    )

    private fun paused(timestamp: Long, packageName: String, activityId: String) = Event(
        timestampMillis = timestamp,
        type = EventType.ACTIVITY_PAUSED,
        packageName = packageName,
        activityId = activityId
    )

    private fun stopped(timestamp: Long, packageName: String, activityId: String) = Event(
        timestampMillis = timestamp,
        type = EventType.ACTIVITY_STOPPED,
        packageName = packageName,
        activityId = activityId
    )

    private fun event(timestamp: Long, type: EventType) = Event(timestampMillis = timestamp, type = type)
}
