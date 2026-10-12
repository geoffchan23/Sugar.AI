package com.geoffchan.ray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AlexaTest {
    private val alexa = Alexa("amzn1.ask.skill.test", emptySet(), RayService(ServerDb(":memory:"), null, "m", "deno"))
    private val body = """{"request":{"type":"LaunchRequest","timestamp":"2026-10-12T12:00:00Z"}}""".toByteArray()

    @Test fun rejectsUnsignedAndForeignCerts() {
        assertEquals("missing signature headers", alexa.verify(body, null, null))
        assertEquals("cert url not Amazon's", alexa.verify(body, "https://evil.example.com/echo.api/cert.pem", "x"))
        assertEquals("cert url not Amazon's", alexa.verify(body, "http://s3.amazonaws.com/echo.api/cert.pem", "x"))
        assertEquals("cert url not Amazon's", alexa.verify(body, "https://s3.amazonaws.com/other/cert.pem", "x"))
        assertEquals("cert url not Amazon's", alexa.verify(body, "https://s3.amazonaws.com/echo.api/../other/cert.pem", "x"))
        assertEquals("cert url not Amazon's", alexa.verify(body, "https://s3.amazonaws.com:8443/echo.api/cert.pem", "x"))
    }

    @Test fun unsignedRequestIsRefused() {
        val r = alexa.handle(body, null, null)
        assertEquals(400, r.code)
    }

    @Test fun noSkillIdMeansOff() {
        val off = Alexa(null, emptySet(), RayService(ServerDb(":memory:"), null, "m", "deno"))
        assertNotNull(off.verify(body, "https://s3.amazonaws.com/echo.api/cert.pem", "x"))
    }
}
