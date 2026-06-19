/*
 * Apache 2.0
 *
 * This file is derived from the sample `GoogleTranslateDictionaryPluginService.kt`
 * found in the Dokuen Reader repository:
 * https://github.com/dokuen-dev/dokuen-reader/tree/main/sdk/sample-plugins/dictionary_sample_google_translate
 *
 * Modified by luccavco on 2026/06/18 to utilize the unauthenticated
 * Google Translate web endpoint (translate_a/single) instead of the billed
 * Google Cloud Translation API.
 */

package io.github.luccavco.dokuengoogletranslate

import android.os.Bundle
import android.util.Log
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import io.github.dokuendev.dokuenreader.dictionary.DictionaryErrorCode
import io.github.dokuendev.dokuenreader.dictionary.DictionaryException
import io.github.dokuendev.dokuenreader.dictionary.DictionaryPluginService
import io.github.dokuendev.dokuenreader.dictionary.DictionaryResult
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import io.github.dokuendev.dokuenreader.dictionary.StyledSpan
import io.github.dokuendev.dokuenreader.dictionary.StyledText
import io.github.dokuendev.dokuenreader.plugin.core.ConfigField
import io.github.dokuendev.dokuenreader.plugin.core.InitResult
import io.github.dokuendev.dokuenreader.plugin.core.InitResultFactory
import io.github.dokuendev.dokuenreader.plugin.core.PluginCapabilityKeys
import io.github.dokuendev.dokuenreader.plugin.core.PluginHostConfigKeys
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * Google Translate Dictionary Plugin (web endpoint)
 *
 * - Web-based translation using Google Translate's free, unauthenticated endpoint
 * - No API key required (no Google Cloud project, no billing)
 * - Multiple target language support
 * - Error handling for network and rate-limit failures
 *
 * ## API Used: translate_a/single (gtx client)
 *
 * Instead of the billed Cloud Translation API, this plugin calls the same endpoint the
 * Google Translate website/app uses under the hood:
 *
 * - Endpoint: https://translate.googleapis.com/translate_a/single
 * - Method: GET (parameters in the query string)
 * - Auth: none — the `client=gtx` parameter selects the public, key-less mode
 * - Cost: free
 * - Quality: the standard Google Translate (NMT) output
 *
 * **Example request:**
 * ```
 * GET https://translate.googleapis.com/translate_a/single
 *       ?client=gtx
 *       &sl=ja            (source language, or "auto")
 *       &tl=en            (target language)
 *       &dt=t             (return translation segments)
 *       &q=食べる          (URL-encoded text)
 * ```
 *
 * **Example response** (top level is a JSON *array*, not an object):
 * ```json
 * [[["to eat","食べる",null,null,10]],null,"ja",null,null,null,null,[]]
 * ```
 * The translated text lives at `response[0][i][0]` for each segment `i`; segments are
 * concatenated to rebuild the full translation.
 *
 * ## Important caveats of using the web endpoint
 *
 * This endpoint is **unofficial and undocumented**. It is convenient because it needs no
 * key, but it comes with trade-offs you should weigh for anything beyond personal use:
 *
 * - **No stability guarantee** — Google can change the response shape or remove the
 *   endpoint at any time, which would break this plugin without notice.
 * - **Rate limiting** — heavy or automated traffic from one IP can be throttled (HTTP 429)
 *   or temporarily blocked (HTTP 403). There is no quota you can raise.
 * - **Terms of Service** — programmatic use of the consumer endpoint may not align with
 *   Google's Terms of Service. For production / commercial plugins, prefer the official
 *   Cloud Translation API (which is what the API-key version of this sample used).
 *
 * If you need reliability, SLAs, or higher volume, switch back to the Cloud Translation
 * API. See: https://cloud.google.com/translate/docs/overview
 */
class GoogleTranslateDictionaryPluginService : DictionaryPluginService() {

    companion object {
        private const val TAG = "GoogleTranslatePlugin"
        private const val TRANSLATE_API_URL = "https://translate.googleapis.com/translate_a/single"
        private const val TIMEOUT_MS = 30000 // 30 seconds

        // A browser-like User-Agent reduces the chance of the request being rejected.
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private var sourceLanguage: String = "ja"
    private var targetLanguage: String = "en"
    private var isDarkTheme: Boolean = false

    override val capabilities = Bundle().apply {
        putBoolean(PluginCapabilityKeys.HANDLES_SEGMENTATION, true)
        putBoolean(PluginCapabilityKeys.REQUIRES_DICTIONARY_FORM, false)
        putBoolean(PluginCapabilityKeys.REQUIRES_INTERNET, true)
        putStringArray(PluginCapabilityKeys.SUPPORTED_SOURCE_LANGUAGES, arrayOf("ja", "zh"))

        // Common target languages - this list can be expanded arbitrarily
        // Google Translate supports 100+ languages, but we list the most common ones here
        // Full list: https://cloud.google.com/translate/docs/languages
        putStringArray(
            PluginCapabilityKeys.SUPPORTED_TARGET_LANGUAGES,
            arrayOf(
                "en", // English
                "es", // Spanish
                "fr", // French
                "de", // German
                "it", // Italian
                "pt", // Portuguese
                "ru", // Russian
                "zh-CN", // Chinese (Simplified)
                "zh-TW", // Chinese (Traditional)
                "ko", // Korean
                "ar", // Arabic
                "hi", // Hindi
                "th", // Thai
                "vi", // Vietnamese
                "id", // Indonesian
                "nl", // Dutch
                "pl", // Polish
                "tr", // Turkish
                "sv", // Swedish
                "no", // Norwegian
                "da", // Danish
                "fi", // Finnish
                "cs", // Czech
                "ro", // Romanian
                "hu", // Hungarian
                "el", // Greek
                "he", // Hebrew
                "uk", // Ukrainian
                "ms", // Malay
                "fa"  // Persian
                // NOTE: This list can be expanded to include any of the 100+ languages
                // supported by Google Translate. See the full list at:
                // https://cloud.google.com/translate/docs/languages
            )
        )
    }

    // No configuration is required: the web endpoint needs no API key.
    override val configSchema = emptyList<ConfigField>()

    override suspend fun onInitialize(config: Bundle?): InitResult {
        super.onInitialize(config)

        if (config == null) {
            return InitResultFactory.failure("Configuration is missing")
        }

        // Read host-provided configuration
        sourceLanguage = config.getString(PluginHostConfigKeys.LANGUAGE) ?: "ja"
        targetLanguage = config.getString(PluginHostConfigKeys.TARGET_LANGUAGE) ?: "en"

        // Determine the UI Theme
        val themeString = config.getString(PluginHostConfigKeys.UI_THEME) ?: "light"
        isDarkTheme = (themeString == "dark")

        Log.d(
            TAG,
            "Initialized with source language: $sourceLanguage, target language: $targetLanguage, dark theme: $isDarkTheme"
        )
        return InitResultFactory.success()
    }

    /**
     * Perform translation lookup using the Google Translate web endpoint.
     *
     * Since HANDLES_SEGMENTATION = true, this method will receive the raw text
     * as selected by the user, rather than segmented or deinflected forms.
     *
     * @param contextText The full text context
     * @param cursorStartIndex Start index of the query word
     * @param cursorEndIndex End index of the query word
     * @return DictionaryResult containing translation
     */
    override suspend fun onLookup(
        contextText: String,
        cursorStartIndex: Int,
        cursorEndIndex: Int
    ): DictionaryResult {
        // Extract the query word
        val queryWord = contextText.substring(cursorStartIndex, cursorEndIndex)

        if (queryWord.isBlank()) {
            return createMessageResult(queryWord, "No word was selected to look up.")
        }

        Log.d(TAG, "Translating '$queryWord' from $sourceLanguage to $targetLanguage")

        // Call Google Translate. Any failure (network error or DictionaryException raised by
        // callTranslateApi) is caught here and turned into a user-facing message instead of
        // being propagated, so onLookup always returns a successful DictionaryResult.
        val translation = try {
            callTranslateApi(queryWord, sourceLanguage, targetLanguage)
        } catch (e: SocketTimeoutException) {
            return createMessageResult(
                queryWord,
                "The translation request timed out. Please check your connection and try again."
            )
        } catch (e: IOException) {
            return createMessageResult(
                queryWord,
                "A network error occurred while contacting Google Translate. Please try again later."
            )
        } catch (e: DictionaryException) {
            return createMessageResult(queryWord, e.toUserMessage())
        }

        // Create dictionary entry from translation
        val entry = createTranslationEntry(queryWord, translation, sourceLanguage, targetLanguage)

        return DictionaryResult(entries = arrayOf(entry))
    }

    /**
     * Call the free Google Translate web endpoint.
     *
     * Unlike the Cloud Translation API, this is a GET request with all parameters in the
     * query string and no authentication.
     *
     * @param text Text to translate
     * @param sourceLang Source language code (use "auto" to let Google detect it)
     * @param targetLang Target language code
     * @return Translated text
     */
    private suspend fun callTranslateApi(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String {
        val url = URL(buildRequestUrl(text, sourceLang, targetLang))
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS

            // Check for cancellation
            currentCoroutineContext().ensureActive()

            // Handle response
            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = InputStreamReader(connection.inputStream, Charsets.UTF_8).use { reader ->
                    reader.readText()
                }
                return parseTranslationResponse(response)
            } else {
                val errorBody = try {
                    InputStreamReader(connection.errorStream, Charsets.UTF_8).use { it.readText() }
                } catch (e: Exception) {
                    "Unable to read error response"
                }

                throw when (responseCode) {
                    HttpURLConnection.HTTP_FORBIDDEN -> {
                        // The web endpoint returns 403 when it (temporarily) blocks a client.
                        DictionaryException(
                            DictionaryErrorCode.QUOTA_EXCEEDED,
                            "Google Translate blocked the request (HTTP 403). Try again later."
                        )
                    }

                    HttpURLConnection.HTTP_BAD_REQUEST -> {
                        DictionaryException(
                            DictionaryErrorCode.INVALID_ARGUMENT,
                            "Invalid request: $errorBody"
                        )
                    }

                    429 -> { // Too Many Requests
                        DictionaryException(
                            DictionaryErrorCode.QUOTA_EXCEEDED,
                            "Rate limit reached. Please slow down and try again later."
                        )
                    }

                    HttpURLConnection.HTTP_UNAVAILABLE -> {
                        DictionaryException(
                            DictionaryErrorCode.NETWORK_ERROR,
                            "Translation service temporarily unavailable"
                        )
                    }

                    else -> {
                        DictionaryException(
                            DictionaryErrorCode.INTERNAL_ERROR,
                            "Translation error (HTTP $responseCode): $errorBody"
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Build the GET request URL for the web endpoint, URL-encoding every parameter.
     */
    private fun buildRequestUrl(text: String, sourceLang: String, targetLang: String): String {
        fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        return buildString {
            append(TRANSLATE_API_URL)
            append("?client=gtx")
            append("&sl=").append(enc(sourceLang))
            append("&tl=").append(enc(targetLang))
            append("&dt=t")
            append("&q=").append(enc(text))
        }
    }

    /**
     * Parse the JSON response from the Google Translate web endpoint.
     *
     * The top-level value is a JSON array. With `dt=t`, element 0 is an array of
     * translation segments, and each segment's element 0 is the translated text:
     *
     * ```json
     * [
     *   [
     *     ["to eat","食べる",null,null,10]
     *   ],
     *   null,
     *   "ja"
     * ]
     * ```
     *
     * Multi-sentence input produces multiple segments, which are concatenated here.
     */
    private fun parseTranslationResponse(response: String): String {
        try {
            val root = JSONArray(response)

            if (root.length() == 0 || root.isNull(0)) {
                throw DictionaryException(
                    DictionaryErrorCode.WORD_NOT_FOUND,
                    "No translation returned"
                )
            }

            val segments = root.getJSONArray(0)
            val builder = StringBuilder()

            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i) ?: continue
                if (!segment.isNull(0)) {
                    builder.append(segment.getString(0))
                }
            }

            val translation = builder.toString()
            if (translation.isBlank()) {
                throw DictionaryException(
                    DictionaryErrorCode.WORD_NOT_FOUND,
                    "No translation returned"
                )
            }

            return translation
        } catch (e: JSONException) {
            throw DictionaryException(
                DictionaryErrorCode.INTERNAL_ERROR,
                "Failed to parse translation response: ${e.message}"
            )
        }
    }

    /**
     * Create a dictionary entry from the translation result.
     */
    private fun createTranslationEntry(
        originalText: String,
        translation: String,
        sourceLang: String,
        targetLang: String
    ): DictionaryEntry {
        return DictionaryEntry(
            headword = originalText,
            pronunciation = null,
            body = StyledText(
                text = translation,
                styledSpans = emptyArray(),
                rubySpans = emptyArray()
            )
        )
    }

    /**
     * Translate a [DictionaryException] raised internally (e.g. by [callTranslateApi] or
     * [parseTranslationResponse]) into a short, user-facing explanation. [onLookup] uses this
     * to convert every exception into a successful [DictionaryResult] instead of letting it
     * propagate.
     */
    private fun DictionaryException.toUserMessage(): String {
        return when (errorCode) {
            DictionaryErrorCode.WORD_NOT_FOUND ->
                "No translation was found for this word."

            DictionaryErrorCode.QUOTA_EXCEEDED ->
                "Google Translate is rate-limiting requests from this device. Please try again later."

            DictionaryErrorCode.NETWORK_ERROR ->
                "The translation service is temporarily unavailable. Please try again later."

            DictionaryErrorCode.INVALID_ARGUMENT, DictionaryErrorCode.INVALID_QUERY ->
                "Google Translate could not process this request. The selected text may be unsupported."

            else ->
                "Translation failed. Please try again later."
        }
    }

    /**
     * Build a successful [DictionaryResult] whose single entry surfaces a user-facing
     * explanation in place of a translation. Used for every failure path (empty query,
     * network error, rate limiting, malformed response, no translation available, etc.)
     * so that `onLookup` never throws a [DictionaryException] or returns
     * [DictionaryErrorCode.WORD_NOT_FOUND] — it always returns a successful result.
     */
    private fun createMessageResult(
        headword: String,
        message: String
    ): DictionaryResult {
        return DictionaryResult(
            entries = arrayOf(
                DictionaryEntry(
                    headword = headword,
                    pronunciation = null,
                    body = StyledText(
                        text = message,
                        styledSpans = emptyArray(),
                        rubySpans = emptyArray()
                    )
                )
            )
        )
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.d(TAG, "Plugin shut down")
    }
}
