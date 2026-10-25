# Device matrix

What has been checked on real hardware. One section per device: model and Android version only.

If you try Speed Compile on another TV or box, please fill in the [Device report form](https://github.com/k45sle/android-tv-speed-compiler/issues/new?template=device-report.yml).

## Box R 4K Plus, Android 14

Amlogic S905X4 (4x Cortex-A55), 3.5 GB RAM, Wi-Fi. Checked in October 2026 with release builds.

| Check | Result |
| --- | --- |
| Setup and pairing (release build, R8) | Works. Pairing through the phone page; no missing-class errors in logcat. |
| Optimize now | Works. Nuvio at `speed-profile` in about 25 s, at `speed` in 81-86 s. |
| Self compile | Works. Speed Compile compiles itself once. |
| Update detection | Works. A reinstalled app was detected and compiled after the TV went to standby. |
| Gate "When the TV is idle" | Works. Nothing ran for 10 minutes while the TV was awake; the job ran after standby. |
| Gate "When nothing is playing" | Works. While audio played in VLC, Home said "Waiting for playback to stop"; the job ran about 2 minutes after playback stopped. |
| `speed-profile` before the app's first launch | The compile prints Success but the app stays at `verify`. Speed Compile shows "Waiting for you to open the app once"; after one launch it compiles normally. |
| Debug builds | ART keeps a debuggable app at `verify` whatever filter is asked. |
| Collision with Android's own background compile | Once, right after standby, our compile ran at the same moment as Android's and was refused ("Failure"). Speed Compile now retries once after 15 minutes. Could not be reproduced on demand. |
| Activity Retry with the D-pad | Works. RIGHT focuses Retry, OK queues the app again. |
| Reset | Works. The app goes back to the TV's own compile choice. |
| Compile while playing an arcade game | Compile time unchanged (22-23 s against 23-24 s idle). The game had 0-0.4% janky frames, 90th percentile 11 ms. |
| Reboot (`adb reboot`, twice) | Works. Speed Compile reconnected by itself 69 s and 72 s after the reboot (30-35 s after boot finished), without being opened. Home then showed Connected. |
| Standby (TV switched off, box sleeps over HDMI-CEC) | Works. Speed Compile kept its connection while the box slept, and two queued apps had compiled within 11 minutes of it going to sleep. A wake-up key event sent over adb woke it. |
| Screensaver opened by the launcher | The AT4K launcher opens Aerial Views as a normal app that holds a screen-on wake lock, and Android stayed awake for the 60 minutes observed. Two apps reinstalled just before stayed uncompiled for that hour, and at the end of it Home showed them queued with "Waiting for the TV to be idle". |
| Screensaver opened by the launcher, after the app counts it as idle | Works. Unlike the check above (jobs waited an hour), with the screensaver counted as idle a queued Nuvio Arc job compiled while the Aerial Views activity was in front. |
| Shipped profile before the app has run (INSTALL_PROFILE broadcast) | Works. After an app update, Nuvio Arc reached `speed-profile` about 4 minutes after its queued job was forced, with a 7.1 MB profile dump and no launch in between. Cold start with only that profile: median 2993 ms (5 runs). |
| Refresh after use | Works once Speed Compile stays running. After about 2 minutes of use the profile grew from 7.1 to 12.8 MB; a screen-off recompiled the app within 32 seconds, and the cold-start median fell to 1192 ms (5 runs). |
| Screen-off while Speed Compile is in the background | Does not fire on this box: Speed Compile's process is frozen about 10 seconds after it leaves the front, and the screen-off broadcast never reached it. A fix, a foreground service that keeps the process uncached, is in a pull request that is not merged yet. |
| Frame sampler and advice line | The sampler read and stored frame counts once a minute for the app in front. Nuvio and Nuvio Arc scrolled about 9 minutes each ran 15-19% slow frames, which is between "runs smoothly" (under 15%) and "still choppy" (25% or more), so no advice line showed. A cold start of Nuvio Arc was far above the "choppy" line (92% slow frames in the first 20 seconds), but the 9-minute sessions stayed under it. |
| Real power-off, Keep trusting off | Not checked yet. |
| Ethernet, Wi-Fi network change, router restart | Not checked yet. |

Cold start, size and compile time per app are in the README under "Does it help?".
