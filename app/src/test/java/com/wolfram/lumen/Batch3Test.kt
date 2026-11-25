package com.wolfram.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch3Test {
    @Test
    fun busFromDeLijn() {
        assertEquals("3", delijnEntity("301234"))
        assertNull(delijnEntity("12"))
        val json = JSONObject().put(
            "halteDoorkomsten",
            JSONArray().put(
                JSONObject().put(
                    "doorkomsten",
                    JSONArray()
                        .put(JSONObject().put("lijnnummer", "2").put("bestemming", "Leuven Station").put("dienstregelingTijdstip", "2026-11-26T07:42:00"))
                        .put(JSONObject().put("lijnnummer", "3").put("bestemming", "Heverlee").put("dienstregelingTijdstip", "2026-11-26T07:30:00").put("real-timeTijdstip", "2026-11-26T07:33:00")),
                ),
            ),
        )
        val all = parseDepartures(json)
        assertEquals(listOf("3", "2"), all.map { it.line })
        assertEquals(listOf("2"), parseDepartures(json, "2").map { it.line })
        assertEquals("Bus 2 → Leuven Station in 6 min", busText(BusDeparture("2", "Leuven Station", 1_000_000L + 6 * 60_000L), 1_000_000L))
    }

    @Test
    fun spaceStationMarketsAndXbox() {
        val passes = parsePasses(JSONObject().put("passes", JSONArray().put(JSONObject().put("startUTC", 1764100000L).put("startAzCompass", "SW").put("maxEl", 54))))
        assertEquals(1764100000000L, passes.first().start)
        assertEquals("SW", passes.first().direction)
        val quote = parseQuote(JSONObject().put("Global Quote", JSONObject().put("01. symbol", "ASML").put("05. price", "712.50").put("10. change percent", "-1.25%")))
        assertEquals("ASML 712.50 (-1.25%)", quoteText(quote!!))
        assertNull(parseQuote(JSONObject().put("Note", "limit")))
        val packet = xboxPowerOnPacket("F4001234")
        assertArrayEquals(byteArrayOf(0xDD.toByte(), 0x02, 0x00, 11, 0x00, 0x00, 0x00, 8), packet.copyOfRange(0, 8))
        assertEquals(17, packet.size)
        val presence = JSONArray().put(JSONObject().put("devices", JSONArray().put(JSONObject().put("titles", JSONArray()
            .put(JSONObject().put("name", "Home").put("placement", "Background"))
            .put(JSONObject().put("name", "Forza Horizon 6").put("placement", "Full"))))))
        assertEquals("Forza Horizon 6", parsePresence(presence))
        assertNull(parsePresence(JSONArray()))
    }

    @Test
    fun onlyTheMailThatMatters() {
        assertTrue(mailMatters("Mr. Peeters <peeters@school.be>", "Rapport", hasUnsubscribe = false))
        assertTrue(mailMatters("bpost <noreply@bpost.be>", "Je pakket is onderweg", hasUnsubscribe = true))
        assertTrue(mailMatters("Pieter <pieter@gmail.com>", "Opdracht ME", hasUnsubscribe = false))
        assertFalse(mailMatters("Zalando <newsletter@zalando.be>", "Black Friday", hasUnsubscribe = true))
        assertFalse(mailMatters("Spotify <no-reply@spotify.com>", "Your Wrapped", hasUnsubscribe = false))
        assertEquals("Mr. Peeters", senderName("\"Mr. Peeters\" <peeters@school.be>"))
        assertEquals("pieter", senderName("<pieter@gmail.com>"))
        assertEquals("Café ☕", decodeMimeWords("=?UTF-8?B?Q2Fmw6kg4piV?="))
        assertEquals("Café open", decodeMimeWords("=?ISO-8859-1?Q?Caf=E9_open?="))
    }

    @Test
    fun runsOnScreenOnly() {
        val runs = parseRuns(JSONArray()
            .put(JSONObject().put("sport_type", "Run").put("distance", 2520.0).put("moving_time", 739).put("start_date", "2026-11-22T09:00:00Z").put("id", 7))
            .put(JSONObject().put("sport_type", "Ride").put("distance", 20000.0).put("moving_time", 3600).put("start_date", "2026-11-23T09:00:00Z").put("id", 8)))
        assertEquals(1, runs.size)
        assertEquals("2.52 km at 4:53/km", runText(runs.first()))
    }

    @Test
    fun quizzesAndDrills() {
        assertEquals(listOf(QA("Wie?", "Karel")), parseQuiz("Here: [{\"q\":\"Wie?\",\"a\":\"Karel\"}] done"))
        assertEquals(emptyList<QA>(), parseQuiz("no json"))
        val local = localQuiz("Karel de Grote werd in het jaar 800 tot keizer gekroond in Rome. Kort.")
        assertEquals(1, local.size)
        assertTrue(local.first().q.contains("_____"))
        assertEquals(listOf(QA("hond", "le chien"), QA("kat", "le chat")), parseVocab("le chien = hond\nle chat - kat\nrubbish"))
        assertEquals("ecole", normalizeAnswer("L'École"))
        assertTrue(answerMatches("chien", "le chien"))
        assertTrue(answerMatches("le chein", "le chien"))
        assertTrue(answerMatches("voiture", "la voiture / l'auto"))
        assertFalse(answerMatches("chat", "le chien"))
        assertTrue(quizAnswerOk("hij werd keizer gekroond in Rome", "gekroond tot keizer in Rome"))
        assertFalse(quizAnswerOk("geen idee", "gekroond tot keizer in Rome"))
        assertEquals("nl", guessLanguage("De Franse revolutie is een periode van het einde van de monarchie"))
        assertEquals("fr", guessLanguage("La révolution est une période et les rois ne sont pas"))
        assertEquals("fr-FR", languageTag("fr"))
    }
}

class PhotoSceneTest {
    @Test
    fun photosAreCroppedTo16by9() {
        // A tall iPhone photo: the middle band.
        org.junit.Assert.assertArrayEquals(intArrayOf(0, 1165, 3024, 1701), coverCrop(3024, 4032))
        // A wide panorama: the middle part.
        org.junit.Assert.assertArrayEquals(intArrayOf(1440, 0, 2560, 1440), coverCrop(5440, 1440))
        org.junit.Assert.assertArrayEquals(intArrayOf(0, 0, 1920, 1080), coverCrop(1920, 1080))
    }
}
