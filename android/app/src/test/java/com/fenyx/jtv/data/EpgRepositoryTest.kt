package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Test

/**
 * Tests the XMLTV timestamp parser used by the EPG pipeline. The format carries an explicit timezone
 * offset, so parsing must yield an absolute instant independent of the device's default timezone.
 */
class EpgRepositoryTest {

    // A fresh formatter per test mirrors production's "one instance per parse" thread-safety rule.
    private fun fmt() = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ENGLISH)

    @Test
    fun parses_validTimestampToPositiveEpoch() {
        assertTrue(EpgRepository.parseXmltvMillis("20260711183000 +0530", fmt()) > 0)
    }

    @Test
    fun parses_timezoneOffsetAsAbsoluteInstant() {
        // 18:30:00 at +0530 is the same instant as 13:00:00 at +0000.
        val ist = EpgRepository.parseXmltvMillis("20260711183000 +0530", fmt())
        val utc = EpgRepository.parseXmltvMillis("20260711130000 +0000", fmt())
        assertEquals(utc, ist)
    }

    @Test
    fun returnsZero_forMalformedValue() {
        assertEquals(0L, EpgRepository.parseXmltvMillis("not-a-timestamp", fmt()))
    }

    @Test
    fun returnsZero_forNullOrBlank() {
        assertEquals(0L, EpgRepository.parseXmltvMillis(null, fmt()))
        assertEquals(0L, EpgRepository.parseXmltvMillis("   ", fmt()))
    }

    private fun at(xmltv: String) = EpgRepository.parseXmltvMillis(xmltv, fmt())
    private fun show(start: String, stop: String) = EpgProgram("t", "", at(start), at(stop))

    @Test
    fun istDay_rollsOverAtIndianMidnight() {
        val day = EpgRepository.istDay(at("20261104000000 +0530"))
        assertEquals(day - 1, EpgRepository.istDay(at("20261103235959 +0530")))
        // 20:00 UTC is already the next day in India.
        assertEquals(day, EpgRepository.istDay(at("20261103200000 +0000")))
    }

    @Test
    fun guideDay_isTheDayOfTheMiddleShow_evenWhenTheFirstStartsTheEveningBefore() {
        val guide = listOf(
            show("20261103233000 +0530", "20261104010000 +0530"),
            show("20261104010000 +0530", "20261104120000 +0530"),
            show("20261104120000 +0530", "20261104233000 +0530"),
        )
        assertEquals(EpgRepository.istDay(at("20261104120000 +0530")), EpgRepository.guideDay(guide))
        assertEquals(null, EpgRepository.guideDay(emptyList()))
    }
}
