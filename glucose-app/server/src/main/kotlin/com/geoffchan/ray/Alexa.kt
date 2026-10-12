package com.geoffchan.ray

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

/**
 * The private Alexa skill "Ray" (POST /v1/alexa). Every request is checked
 * the way Amazon requires for a self-hosted endpoint: the signing
 * certificate chain (from s3.amazonaws.com/echo.api/, valid, issued for
 * echo-api.amazon.com, chaining to a trusted root), the SHA-256 signature
 * of the exact body, a timestamp within 150 s, and our skill id. A skill in
 * development only works on Geoff's own Amazon account; ALEXA_USER_IDS
 * (optional) narrows it to given users.
 */
class Alexa(private val skillId: String?, private val userIds: Set<String>, private val ray: RayService, private val db: ServerDb? = null) {
    private val certs = ConcurrentHashMap<String, Pair<X509Certificate, Long>>()

    data class Reply(val code: Int, val json: String)

    fun handle(body: ByteArray, certUrl: String?, signature: String?): Reply {
        val why = verify(body, certUrl, signature)
        if (why != null) {
            log("alexa rejected: $why")
            return Reply(400, JSONObject().put("error", "bad request").toString())
        }
        val req = JSONObject(String(body))
        val request = req.getJSONObject("request")
        val type = request.optString("type")
        val text = when (type) {
            "LaunchRequest" -> "what's Francine's sugar"
            "IntentRequest" -> {
                val intent = request.getJSONObject("intent")
                when (intent.optString("name")) {
                    "SugarIntent" -> "what's Francine's sugar"
                    "LastDoseIntent" -> "when was Francine's last dose"
                    "LastMealIntent" -> "when did Francine last eat"
                    in QUERY_INTENTS -> intent.optJSONObject("slots")?.optJSONObject("query")?.optString("value")
                        ?.takeIf { it.isNotBlank() }?.let { QUERY_INTENTS.getValue(intent.optString("name")) + it }.orEmpty()
                    "AMAZON.HelpIntent" -> return speak("Ask me things like: what's Francine's sugar, when did she last take insulin, or she had 4 units of short acting. ", end = false)
                    "AMAZON.StopIntent", "AMAZON.CancelIntent", "AMAZON.NavigateHomeIntent" -> return speak("Okay.")
                    "AMAZON.FallbackIntent" -> {
                        db?.logVoice("alexa", "AMAZON.FallbackIntent", "", "fallback", 0, null) // no words: Alexa doesn't send them
                        return speak("Sorry, I didn't catch that. Try: ask Sugar Ray what's Francine's sugar.")
                    }
                    else -> ""
                }
            }
            "SessionEndedRequest" -> return Reply(200, JSONObject().put("version", "1.0").put("response", JSONObject()).toString())
            else -> ""
        }
        val intentName = if (type == "IntentRequest") request.getJSONObject("intent").optString("name") else type
        if (text.isBlank()) {
            db?.logVoice("alexa", intentName, "", "empty", 0, null)
            return speak("Sorry, I didn't catch that.")
        }
        val t0 = System.currentTimeMillis()
        val spoken = ray.voice(text, "Alexa", RayService.SOURCE_ALEXA, DEADLINE_MS)
        db?.logVoice("alexa", intentName, text, spoken.path, System.currentTimeMillis() - t0, spoken.interaction)
        log("alexa: ${type}${if (spoken.interaction != null) " record ${spoken.interaction}" else " (quick)"}")
        return speak(spoken.speech)
    }

    private fun speak(text: String, end: Boolean = true) = Reply(
        200,
        JSONObject().put("version", "1.0").put(
            "response",
            JSONObject()
                .put("outputSpeech", JSONObject().put("type", "PlainText").put("text", text))
                .put("shouldEndSession", end),
        ).toString(),
    )

    /** Null when the request is genuinely from Alexa for our skill; else why not. */
    fun verify(body: ByteArray, certUrl: String?, signature: String?, now: Instant = Instant.now()): String? {
        if (skillId == null) return "skill id not configured"
        if (certUrl == null || signature == null) return "missing signature headers"
        val uri = runCatching { URI(certUrl).normalize() }.getOrNull() ?: return "bad cert url"
        if (!uri.scheme.equals("https", true) || !uri.host.equals("s3.amazonaws.com", true) ||
            !uri.path.startsWith("/echo.api/") || (uri.port != -1 && uri.port != 443)
        ) return "cert url not Amazon's"
        val cert = runCatching { cert(uri.toString()) }.getOrElse { return "cert: ${it.message}" }
        val ok = runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(cert.publicKey); update(body); verify(Base64.getDecoder().decode(signature))
            }
        }.getOrDefault(false)
        if (!ok) return "signature mismatch"
        val req = runCatching { JSONObject(String(body)) }.getOrNull() ?: return "not json"
        val ts = runCatching { Instant.parse(req.getJSONObject("request").getString("timestamp")) }.getOrNull() ?: return "no timestamp"
        if (kotlin.math.abs(now.epochSecond - ts.epochSecond) > 150) return "stale timestamp"
        val appId = req.optJSONObject("context")?.optJSONObject("System")?.optJSONObject("application")?.optString("applicationId")
            ?: req.optJSONObject("session")?.optJSONObject("application")?.optString("applicationId")
        if (appId != skillId) return "wrong skill"
        val user = req.optJSONObject("context")?.optJSONObject("System")?.optJSONObject("user")?.optString("userId")
        if (userIds.isNotEmpty() && user !in userIds) return "user not allowed"
        return null
    }

    /** The signing cert, its chain checked against the JVM's trusted roots; cached until it expires. */
    private fun cert(url: String): X509Certificate {
        certs[url]?.let { (c, until) -> if (System.currentTimeMillis() < until) return c }
        val pem = java.net.URL(url).openStream().use { it.readBytes() }
        val cf = CertificateFactory.getInstance("X.509")
        val chain = cf.generateCertificates(ByteArrayInputStream(pem)).map { it as X509Certificate }
        val leaf = chain.firstOrNull() ?: error("empty chain")
        leaf.checkValidity()
        val sans = leaf.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1) as? String }
        if ("echo-api.amazon.com" !in sans) error("not issued for echo-api.amazon.com")
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val anchors = tmf.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
            .map { java.security.cert.TrustAnchor(it, null) }.toSet()
        val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(chain), params)
        certs[url] = leaf to minOf(leaf.notAfter.time, System.currentTimeMillis() + 24 * 3600_000L)
        return leaf
    }

    companion object {
        /**
         * Free-form intents: Alexa hands over only the words after a fixed
         * carrier phrase ("how {query}"), so each carrier is its own intent and
         * the sentence is put back together here. Must match server/alexa/.
         */
        val QUERY_INTENTS = mapOf(
            "HowIntent" to "how ", "WhyIntent" to "why ", "WhatIntent" to "what ", "WhenIntent" to "when ",
            "DidIntent" to "did ", "WasIntent" to "was ", "IsIntent" to "is ", "TellIntent" to "tell me ",
            "LogIntent" to "Log this: ",
        )

        /** Alexa waits 8 s for the whole round trip; leave room for the network. */
        const val DEADLINE_MS = 6_500L
    }
}
