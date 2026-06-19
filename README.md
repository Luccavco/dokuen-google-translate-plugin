# Google Translate Dictionary Plugin (web endpoint)

A [Dokuen Reader](https://jitpack.io/#dokuen-dev/dokuen-reader) dictionary/translator
plugin that translates the selected word or phrase using **Google Translate’s free,
key-less web endpoint** — the same one the Google Translate site uses under the hood.

No Google Cloud project, no API key, and no billing are required.

## How it works

When you tap or sweep over text, the plugin sends a single GET request to:

```
https://translate.googleapis.com/translate_a/single
      ?client=gtx        ← selects the public, key-less mode
      &sl=ja             ← source language (or "auto")
      &tl=en             ← target language
      &dt=t              ← return translation segments
      &q=<text>          ← URL-encoded selection
```

The response is a nested JSON **array**. The translated text lives at `response[0][i][0]`
for each segment `i`, and the segments are concatenated to rebuild the full translation.
The result is wrapped in a `DictionaryEntry` and shown in the Dokuen overlay.

## Features

- **No API key / no billing** — works out of the box.
- `HANDLES_SEGMENTATION = true` — receives the raw selected text, not deinflected forms.
- `REQUIRES_INTERNET = true`.
- Source languages: Japanese (`ja`) and Chinese (`zh`).
- 30+ target languages (English, Spanish, French, German, Chinese, Korean, …); the list
  can be extended to any of Google Translate’s 100+ supported languages.
- Graceful handling of timeouts, rate limits, and service errors.

## Configuration

**None.** The plugin’s `configSchema` is empty — there is nothing for the user to fill in.

The host still provides the source language, target language, and UI theme automatically
via `PluginHostConfigKeys` at initialization.

## AndroidManifest.xml

Register the service with the translator category and the five required metadata fields:

```xml
<service
    android:name=".googletranslate.GoogleTranslateDictionaryPluginService"
    android:exported="true">
    <intent-filter>
        <action android:name="io.github.dokuendev.dokuenreader.dictionary.BIND_DICTIONARY_SERVICE" />
        <category android:name="io.github.dokuendev.dokuenreader.category.TRANSLATOR" />
    </intent-filter>

    <meta-data android:name="plugin_name" android:value="Google Translate (web)" />
    <meta-data android:name="plugin_version" android:value="1.0.0" />
    <meta-data android:name="plugin_author" android:value="luccavco" />
    <meta-data android:name="plugin_description" android:value="Translate via Google Translate, no API key required" />
    <meta-data android:name="plugin_license" android:value="Apache 2.0" />
</service>
```

Also declare internet access in the manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Error handling

|Situation                      |Error code                         |
|-------------------------------|-----------------------------------|
|Request timed out              |`NETWORK_ERROR`                    |
|Network unavailable / I/O error|`NETWORK_ERROR`                    |
|Blocked (HTTP 403)             |`QUOTA_EXCEEDED`                   |
|Rate limited (HTTP 429)        |`QUOTA_EXCEEDED`                   |
|Service unavailable (HTTP 503) |`NETWORK_ERROR`                    |
|Empty / unparseable response   |`WORD_NOT_FOUND` / `INTERNAL_ERROR`|

## ⚠️ Important caveats

This plugin relies on an **unofficial, undocumented** endpoint. It’s convenient, but:

- **No stability guarantee.** Google can change the response format or remove the endpoint
  at any time, which would break the plugin until the code is updated.
- **Rate limiting.** Heavy or automated traffic from one IP can be throttled (429) or
  temporarily blocked (403). These blocks are **temporary** — typically minutes to a few
  hours, and they often clear on their own or when your IP changes (common on mobile data).
  For light, personal reading use, hitting them is unlikely.
- **Terms of Service.** Programmatic use of the consumer endpoint may not align with
  Google’s Terms of Service. For production or commercial use, prefer the official
  [Cloud Translation API](https://cloud.google.com/translate/docs/overview) (which requires
  an API key). This is a practical/reliability consideration for low-volume personal use,
  not legal advice.

If you need reliability, SLAs, or higher volume, switch to the official Cloud Translation API.

## Building

### Option 1: Android Studio
1. Open this folder in Android Studio.
2. Click **Run → Run 'app'** to build and install to a connected device.
   > **Important:** Android Studio will likely display an error message such as *"Error running 'app': Default Activity not found"*. This is expected. The plugin is successfully installed and will now show up in Dokuen's plugin list.

### Option 2: Command Line (No Android Studio Required)

**Prerequisite:**
You must have a **Java Development Kit (JDK)** installed and your `JAVA_HOME` environment variable set.

**To Build and Install:**
Connect your Android device (with USB Debugging enabled), then run the following command from the root of the project:

* **Mac/Linux:**
```bash
  ./gradlew installDebug
  ```
* **Windows:**
```cmd
  gradlew.bat installDebug
  ```

*(If you only want to generate the APK file without installing it to a device, replace `installDebug` with `assembleDebug`. The generated APK will be located in `app/build/outputs/apk/debug/`)*

## License

Apache 2.0 (based on the original sample)