# Lightious

Lightious is a small, text-first Invidious client for Light Phone III. It is
intended for private sideloading and keeps recommendations out of the launch
path: Home is an offline menu and never loads a video feed on its own.

## Features

- Search for a video or open a pasted YouTube URL.
- Open an optional signed-in account feed.
- Watch a low-bandwidth progressive stream (or HLS for live video) with Media3.
- Listen through the Light SDK audio player, including detached playback.
- Keep local search and watch histories with confirmed clear-all actions.
- Choose which pages appear on Home. Popular is hidden by default and is only
  requested if it is explicitly enabled and opened.
- Choose whether media is proxied through the configured Invidious instance.

There is no autoplay, comments, algorithmic Home feed, notifications, or
public-instance rotation.

## Account sign-in

Lightious uses Invidious's restricted bearer-token flow and never asks for
an account password.

1. Open **Settings → Account** on the phone.
2. On another device, sign in to the same Invidious server and open the
   authorization URL shown by Lightious.
3. Approve the requested access, copy the generated JSON token, then enter or
   paste it into **Enter generated token** on the phone.

The default requested scope is limited to account feed access. If watch sync is
enabled before authorization, the URL also requests permission to mark a video
watched. The token is validated against the selected instance and stored with
AES-GCM encryption backed by the Android Keystore. Changing instances or
signing out removes it.

Account watch sync starts off and is one-way: when enabled, future Watch or Listen actions
send only the watched video ID to Invidious. It does not upload old local
history or playback position. A failed sync is queued for a later playback and
never blocks local history or playback. Turning sync off or clearing local
watch history also discards pending watched IDs.

## History and privacy

Search history and watch history are stored only in the app's local Room
database. Repeated items move to the top instead of creating duplicates.
Search history is capped at 30 entries and watch history at 500 videos.

Both recording options can be disabled independently. Disabling recording
keeps existing entries; each history screen has a separate confirmed clear
action. Signing out or changing the Invidious instance does not erase local
history. A pasted YouTube URL is treated as navigation and is not saved as a
search query.

## Instance requirements

The HTTPS server must expose the Invidious v1 API, video metadata, and a
playable media URL. Saving a server performs a capability probe: search, video
metadata, and a one-byte range request against the selected media stream.

Plain HTTP instances are rejected because Android blocks cleartext traffic for
this target. Put a local instance behind HTTPS before using it on the phone.

`Proxy media` requests `local=true`. This keeps media traffic on the configured
Invidious host but consumes that server's bandwidth. Turning it off normally
uses the returned Google video CDN URL directly.

## Build

From the `light-sdk` repository root:

```sh
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
```

The debug APK is written under `tool/build/outputs/apk/debug/`. The checked-in
`lighttool.toml` targets a real Light Phone (`serverPackage = "com.lightos"`).
For the LightOS emulator, temporarily change that value to
`com.thelightphone.sdk.emulator` before building.

## Compatibility lane

The UI, navigation, input, theming, and audio path use Light SDK components.
Video rendering uses Media3 and an Android `SurfaceView`, so this is an
experimental sideload tool rather than a claim of Light approval.

The client is a clean-room Kotlin implementation of the documented Invidious
HTTP API. Materialious and Clipious were used only as behavioral references;
no source from either AGPL project is included.
