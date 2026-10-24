<p align="center">
  <img src="docs/media/icon.png" width="108" height="108" alt="Speed Compile icon: a pixel-art gold chevron ship over streaming cream dashes, on dark green">
</p>

<h1 align="center">Speed Compile</h1>

<p align="center">Compiles the Android TV apps you pick ahead of time, and again after each update.</p>

<p align="center">
  <a href="https://github.com/k45sle/android-tv-speed-compiler/actions/workflows/ci.yml"><img src="https://github.com/k45sle/android-tv-speed-compiler/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
  <img src="https://img.shields.io/badge/Android%20TV-11%2B-123524" alt="Android TV 11 or later">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-123524" alt="MIT license"></a>
</p>

> [!IMPORTANT]
> **This app was written entirely by AI.** All of its code, tests and documentation were written by Claude (Anthropic's AI) in Claude Code, and the original logo art was made with Google Gemini. I am not a programmer. What I did:
>
> - Came up with the idea, mostly so Nuvio and apps like it would run better on my TV.
> - Decided to drop the first version, a Docker service you ran on a computer, in favour of an app that runs on the TV itself.
> - Approved the screen designs before any of them were built.
> - Turned down "TV Speed" for sounding like a scam, and picked the name.
> - Made the logo, then rejected four fonts and one embossing experiment for the banner until it had no text left on it.
> - Asked for a retro pixel-art look, then picked the pixel logo and the banner with its pixel wordmark from Claude's candidates.
> - Set a budget of under $1 for the whole project and held Claude to it.
> - Reviewed the work and pushed back on anything that looked like AI slop.

<p align="center">
  <img src="docs/media/banner.gif" width="640" height="360" alt="Speed Compile banner: a gold pixel-art chevron rolls over streaming cream dashes next to the words SPEED COMPILE in pixel letters, on dark green">
</p>

<p align="center">
  <img src="docs/media/hero.gif" width="640" height="360" alt="Demo: from Apps, pick Nuvio and press Optimize now. The Working screen opens its arcade for a moment, the job finishes, and after Done, Home lists Nuvio as optimized">
  <br>
  <a href="docs/media/demo.mp4">Watch the 36-second demo (MP4)</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: light)" srcset="docs/media/home-light.png">
    <img src="docs/media/home-dark.png" width="800" alt="Home screen: auto-optimize is on and connected, two apps waiting, and a list of recently optimized apps">
  </picture>
</p>

<h3 align="center">Setup</h3>

<table align="center">
  <tr>
    <td><img src="docs/media/setup-welcome-dark.png" width="400" alt="Setup welcome page: Compile your apps ahead of time, with a Start button"></td>
    <td><img src="docs/media/setup-pair-dark.png" width="400" alt="Setup pairing page: a QR code and an address to open on your phone, with steps for typing the 6-digit pairing code"></td>
  </tr>
  <tr>
    <td><img src="docs/media/setup-apps-dark.png" width="400" alt="Setup app picker: a list of recent apps, with Nuvio selected"></td>
    <td><img src="docs/media/setup-done-dark.png" width="400" alt="Setup is done: Nuvio and 1 other will be optimized when nothing is playing, with Optimize now and Done buttons"></td>
  </tr>
</table>

<h3 align="center">Everyday</h3>

<table align="center">
  <tr>
    <td><picture><source media="(prefers-color-scheme: light)" srcset="docs/media/apps-light.png"><img src="docs/media/apps-dark.png" width="400" alt="Apps screen: a searchable list of apps with their banners, each marked Optimized or Queued"></picture></td>
    <td><img src="docs/media/appdetail-dark.png" width="400" alt="App detail for Nuvio: optimized 20 minutes ago, with Optimize now, Stop watching and an auto-optimize switch"></td>
  </tr>
  <tr>
    <td><img src="docs/media/activity-dark.png" width="400" alt="Activity screen: a queue with Cancel buttons, and a history of finished jobs, one failed with Connection lost and a Retry button"></td>
    <td><img src="docs/media/settings-plum.png" width="400" alt="Settings in the Plum colour preset: when to optimize, pause, theme and accent colour"></td>
  </tr>
</table>

<h3 align="center">While you wait</h3>

<table align="center">
  <tr>
    <td><img src="docs/media/working-dark.png" width="400" alt="Working screen: Nuvio being optimized, with a timer, a Leave button and an arcade room of three game cabinets"></td>
    <td><img src="docs/media/alienrun-dark.png" width="400" alt="Alien Run: a green alien in a space helmet runs across a pixel-art desert, jumping cacti, while a strip at the top says Nuvio is still optimizing"></td>
  </tr>
  <tr>
    <td><img src="docs/media/stararrow-dark.png" width="400" alt="Star Arrow: a gold chevron ship flies between columns of tumbling asteroids past a ringed planet, with a round shield gauge at 100 percent"></td>
    <td><img src="docs/media/cosmicworm-dark.png" width="400" alt="Cosmic Worm: a blue worm crawls across a purple space board toward a star, with gates on the walls"></td>
  </tr>
</table>

<p align="center"><sub>The Nuvio name and logo belong to the Nuvio project (GPL-3.0, <a href="https://github.com/NuvioMedia/NuvioTV">github.com/NuvioMedia/NuvioTV</a>) and the art is used only by the screenshot tests. Other apps in the screenshots are made up.</sub></p>

## About this app

Android normally compiles an app bit by bit, based on how you use it. Speed Compile asks the TV to compile the apps you choose fully, using Android's `speed` profile, and does it again whenever one of them updates. It talks to the TV through the TV's own Wireless debugging, so it needs no computer once it is set up.

Results vary by app and by TV. Some apps open or scroll more smoothly; some show no difference. Each compiled app takes a bit more storage.

The app was written with the Nuvio community in mind, but it works with any app on the TV.

## What it does

- Watches the apps you pick and queues a compile when one of them updates.
- Waits until nothing is playing, or until a time window you set, so it does not slow down what you are watching.
- Reconnects by itself after the TV restarts, when you open the app, or when a job loses its connection, and turns Wireless debugging back on at those moments (you can turn this off in Settings).
- Lets you play Alien Run, Star Arrow or Cosmic Worm while a compile runs.
- Shows a history of each job, with the reason when one fails.
- Has eight accent colours plus a custom one, and light and dark themes.

## Data safety

- No account, no ads, no analytics.
- The app connects only to the TV it runs on, and to your phone on the same network when you pair or download diagnostics. Nothing is sent anywhere else unless you share a diagnostics file yourself.
- The debugging key is created on the TV and stays in the app's private storage.
- A diagnostics export holds settings, the queue, recent events and the package names of the apps involved. Addresses, codes, serial numbers and device names are removed before it is written.
- The phone pairing page uses plain HTTP on your local network, so someone on the same Wi-Fi could in theory read the 6-digit code while you type it. The code works only for a short time. Pair on a network you trust.

## Install

1. Download the APK from [Releases](https://github.com/k45sle/android-tv-speed-compiler/releases) onto the TV, for example with the Downloader app, or send it from your phone with Send Files to TV.
2. Open the APK on the TV and allow installs from that source if asked.
3. Open Speed Compile and follow the setup. It walks you through Developer options and Wireless debugging, then shows a QR code: scan it with your phone and type the 6-digit pairing code from the TV.

## Setup help

If setup gets stuck, these are the usual causes.

| What you see | What to do |
| --- | --- |
| No Developer options in Settings | Open Settings, then System, then About. Press OK on Build 7 times. |
| The pairing code did not work | Codes expire after a short time. Open Pair device with pairing code again on the TV and type the new 6 digits. |
| The phone can't open the pairing page | Keep the phone on the same Wi-Fi as the TV. If the QR code will not scan, type the short address shown on the TV into the phone's browser. |
| The TV asks to allow debugging | Choose Always allow from this computer, then OK. If you turned off Keep this TV trusting the app in Settings, Android can ask again after a while. |
| Wireless debugging is off | It can switch off after a network change. With Keep Wireless debugging on (the default), the app turns it back on as soon as you open it, after the TV restarts, or when an optimization or weekly check loses its connection. If you turned that setting off, turn Wireless debugging on again in Developer options and the app reconnects by itself. |
| The TV forgot this app | Open the app and choose Fix connection. |

Inside the app, Help lists the same fixes, and Settings > Export diagnostics makes a file you can attach to an issue.

## Compatibility

Speed Compile needs Android TV or Google TV 11 or later, because Wireless debugging arrived in Android 11.

So far it has been tested on the Android TV emulator (API 34) only. Reports from real TVs and streaming boxes are welcome in [Issues](https://github.com/k45sle/android-tv-speed-compiler/issues).

## FAQ

**Will it make my TV faster?**
It can make the apps you pick start or run more smoothly. It does not touch system apps or the TV's software, and some apps show no difference.

**Why does it need Developer options?**
Only the debugging connection can ask Android to compile another app. Speed Compile connects to Wireless debugging on the TV itself. Through that connection it also changes two TV settings so it keeps working on its own. Both are on by default:

- **Keep Wireless debugging on.** Each time the app reconnects (after a restart, when you open the app, or when an optimization or weekly check loses its connection), it turns Wireless debugging back on if it is off, even if you switched it off yourself.
- **Keep this TV trusting the app.** Android normally forgets that you allowed debugging after a while. This stops it from forgetting, so the TV doesn't ask again.

You can turn either one off in the app's Settings. That's optional, and it can make things less convenient later. With Keep Wireless debugging on off, optimizing stops each time Wireless debugging goes off, until you turn it back on in Developer options. With Keep this TV trusting the app off, the TV may ask you to allow debugging again, and nothing gets optimized until you do.

**Is it safe?**
It runs Android's own compiler through the standard `cmd package compile` command, only for the apps you pick. You can stop it at any time by uninstalling the app, or by turning off Keep Wireless debugging on in Settings and then switching off Wireless debugging. Wireless debugging and the trust setting stay as they are after an uninstall; switch them off in Developer options if you no longer want them.

**How do I undo it?**
Open the app in Apps and choose Reset. The app also stops being watched. Uninstalling Speed Compile does not undo it.

## Build from source

```bash
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. You need JDK 17, the Android SDK and network access on the first build, because one dependency (libadb-android) comes from JitPack.

To run the same checks as CI on every pull request (lint, the unit tests and the debug build):

```bash
./gradlew lint testDebugUnitTest assembleDebug
```

Release builds are signed only when `releaseStoreFile`, `releaseStorePassword` and `releaseKeyAlias` are set in `~/.gradle/gradle.properties`. Without them the release APK is unsigned.

The README screenshots and demo clip are Robolectric tests that run only with `-Pmedia`. To record the screenshots again:

```bash
README_SHOTS_DIR=$PWD/docs/media ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests '*ReadmeScreenshotsTest' -Pmedia -Proborazzi.record
```

`tools/demo.sh` records the README clips and writes their GIFs and posters into `docs/media`, and the MP4s outside the repo. See the script for how to attach the MP4s to a release.

## License

[MIT](LICENSE). The bundled Geist and VT323 fonts are under the SIL Open Font License ([Geist](third_party/GEIST-OFL.txt), [VT323](third_party/VT323-OFL.txt)). The Alien Run character, hearts and some game tiles are adapted from [Kenney's Pixel Platformer](third_party/KENNEY-CC0.txt) (CC0). The Nuvio art in the screenshots is Nuvio's own, under GPL-3.0 ([its notice](app/src/test/resources/nuvio/NOTICE)). Only the screenshot tests use it, and it is not part of the app.
