# Changelog

What changed in each version of Speed Compile, in plain words. Newest first.

Speed Compile prepares the code of the apps you pick ahead of time. This is called compiling, and it can make an app open and scroll more smoothly.

## 1.0.0 (25 October 2026)

The first full release. The main differences from the test build:

- A new look. Every screen is styled like an arcade, and each app's banner sits in a game cartridge.
- Home has an Arcade button, so you can play a game at any time, not only while a job runs.
- In Apps you can pick several apps at once and watch or optimize them all together.
- Reset puts an app back the way the TV had it, if you change your mind.
- Apps are prepared in a lighter way that covers the parts you actually use. On our test TV, apps started faster this way and took half the storage or less.
- Automatic mode: Speed Compile loads the profile an app ships with, so most apps are ready right after an update, and quietly refreshes them for a week. Apps without one wait for you to use them for about a minute, and are fully compiled after 2 days if you don't. App detail has an Open button for that. Under Advanced in Settings, "How to optimize" can be switched to Full compile for every app.
- Speed Compile prepares its own code too.
- App detail now says whether an app runs smoothly, measured while you use it. If an app is still choppy, a Smoother mode button compiles it more fully. It also says "Checking how smoothly it runs" while it has too little to go on, and "Mostly smooth" with the share of stuttering frames when an app is in between. If Smoother mode doesn't help an app, a Back to normal mode button undoes it.
- A small helper keeps running in the background, with a quiet "Watching for idle time" notification, so Speed Compile notices at once when the screen turns off or the screensaver starts, and can measure smoothness while you use your apps. It takes about 10 MB of memory on our test TV.
- A new setting, "Keep Wireless debugging on", lets you choose whether Speed Compile turns Wireless debugging back on by itself when it gets switched off.
- Each download comes with a file you can use to check it is the real one. The README explains how under "Checking a download".
- Many smaller fixes from testing on a real TV.

## 0.9.0-test1 (22 October 2026)

The first test build, for people who wanted to try it early.

- Pick the apps to watch. Speed Compile prepares them ahead of time, and again after each update.
- Setup runs on the TV, and you pair it with your phone.
- It waits until nothing is playing, or for a time window you set.
- You can play a game while a job runs.
