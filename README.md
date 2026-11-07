# Glass Home

A tvOS-style home screen for the Fire TV Stick. No root, fully reversible, no Apple-owned assets (no
Apple logo, no SF Pro, no Apple sounds or wallpapers) — just the layout, the glass, and the motion.

Created by [Levente Botos](https://github.com/LeventeBotos).

![Home screen](screenshots/home.png)

## What this is

Glass Home replaces the Fire TV's own launcher with one that looks and feels like Apple TV's "Glass"
home screen: a dock of 5:3 app icons that lift and glow on focus, a status pill with the clock and a
Control Center button, and an animated, colour-reactive gradient background behind everything.

It does this **without root** and **without replacing anything permanently**. It's a second app that
watches for the Home button (via a log-reading permission ADB can grant, no accessibility service
needed — Fire OS silently drops those) and opens itself instead of Amazon's launcher. Nothing is
uninstalled, nothing is disabled. One command puts it all back.

## Screenshots

| Home | Control Center | Long-press menu |
|---|---|---|
| ![Home](screenshots/home.png) | ![Control Center](screenshots/control-center.png) | ![Context menu](screenshots/context-menu.png) |

## Features

- **A real dock**, not a grid — icons scale up and cast a soft shadow on focus, exactly like tvOS
- **An animated backdrop**: two irregular, slow-drifting colour splatters anchored to the bottom
  corners, recoloured live from the **dominant colour of whichever app is focused**, with a faint film
  grain over the top
- **Control Center** (the status pill, top right): Sleep, Wi-Fi, Screensaver on/off, Audio output,
  Sleep Timer, AirPlay/casting, volume, accessibility and a brightness-style placeholder — opened with
  a click or the remote's Menu button
- **AirPods that just work**: open the case or put a bud in and they connect by themselves, a glass
  "Connected" banner slides in over whatever you're watching with live battery rings, taking both buds out
  pauses playback, and Control Center's Audio page switches outputs and shows left/right/case battery
- **The iPhone's own Remote works with it**: the stick shows up as an Apple TV called *Nappali* in Control
  Center's Remote — buttons, a swipe area, play/pause, Home — no app to install on the phone
  (see [iPhone Remote](#iphone-remote-optional) for what is, and is not, finished)
- **Move and hide apps**: long-press (or Menu) an icon for Move / Hide, just like tvOS's App Store page
- **A screensaver** using the same animated gradient, or free aerial nature footage (Pexels, optional,
  fetched once and cached) — no Apple Aerial content
- **Click/focus sounds**, a system font (no bundled typefaces), and a layout measured from a real
  tvOS 26 screenshot
- **Fully reversible**: one `--remove` command uninstalls the app and restores every setting Glass Home
  touched, recorded *before* the first change was made

## Requirements

- A Fire TV Stick. Built and tested on a **Fire TV Stick 4K (2018), Fire OS 6.7 / Android 7.1.2**; it
  should work on other Fire OS 5–7 sticks too (the APK targets Android 5.1+), but hasn't been verified
  on all of them.
- A computer on the **same Wi-Fi network** as the stick, with:
  - **Python 3.9+**
  - **Android SDK build-tools and a platform** (to compile the APK — no Gradle, no Android Studio
    project needed, just the command-line tools). The easiest way to get these:
    1. Install [Android Studio](https://developer.android.com/studio) once, or just the
       [command-line tools](https://developer.android.com/studio#command-tools)
    2. Open SDK Manager (or run `sdkmanager`) and install any one **"Android SDK Platform"** and the
       **"Android SDK Build-Tools"**
    3. Make sure `$ANDROID_HOME` (or `$ANDROID_SDK_ROOT`) points at the SDK folder, or that it's
       installed in the default location the scripts already look for

## Set it up with an AI assistant

Everything below is also written as a prompt, so a terminal-capable AI coding assistant (Claude Code,
OpenAI Codex CLI, ChatGPT with its desktop/agent mode, etc.) can do the install for you — cloning the
repo, installing the Android build tools if needed, pairing with the stick and running the install
script. Open a terminal in an empty folder, start your assistant there, and paste this:

```
Set up https://github.com/LeventeBotos/firetv-glass-home on my Fire TV Stick.

1. Read the repository's README.md in full before doing anything else.
2. Ask me for my Fire TV Stick's IP address if I haven't given it to you, and confirm I've turned on
   ADB Debugging under Settings → My Fire TV → Developer Options (tell me how to reveal that menu if
   it's hidden, and to also enable "Apps from Unknown Sources").
3. Clone the repo, then follow the README's "Installing it" steps exactly: set up the tools/venv
   Python environment, check whether I already have Android SDK build-tools and a platform installed
   (ANDROID_HOME/ANDROID_SDK_ROOT), and help me install them if not.
4. Ask whether I want the screensaver's free aerial nature footage (the default) or just the plain
   animated gradient (set `NO_AERIALS=1` if I say the gradient).
5. Run glasshome/install.sh with TV_HOST set to my stick's IP. The very first run needs me to accept
   an "Allow USB/ADB debugging" prompt on the TV itself — tell me to go look at the TV, then re-run
   the install once I've accepted it.
6. Ask whether I also want AirPlay set up, and if so run glasshome/airplay/setup.py per the README's
   "AirPlay (optional)" section, explaining that it disables (not uninstalls) the stick's built-in
   AirPlay receiver so the two don't fight over the same port.
7. Remind me to turn off Fire OS's own Navigation Sounds (Settings → Accessibility → Navigation
   Sounds) so they don't clash with Glass Home's own sounds, exactly as the README says.
8. Confirm the install finished and tell me to press Home on the remote.
9. Tell me the commands to fully uninstall Glass Home and, separately, AirPlay if I installed it,
   restoring my Fire TV to how it was before either one, and remind me it's safe to walk away from
   at any point — nothing is changed on the stick until a script actually runs.

Ask me before running anything that isn't one of the documented commands in the README.
```

Or follow the same steps yourself, by hand:

## Installing it

1. **Turn on ADB debugging on the stick.** Settings → My Fire TV (or Device) → Developer Options
   (if it's hidden, open Settings → My Fire TV → About and click the device name/build 7 times to
   reveal it) → turn on **ADB Debugging**. Also turn on **Apps from Unknown Sources** if it's off.
2. **Find the stick's IP address.** Settings → My Fire TV → About → Network.
3. **Clone this repository** on your computer:
   ```bash
   git clone https://github.com/LeventeBotos/firetv-glass-home.git
   cd firetv-glass-home
   ```
4. **Set up the Python side:**
   ```bash
   python3 -m venv tools/venv
   tools/venv/bin/pip install -r tools/requirements.txt
   ```
5. **Install Glass Home**, with `TV_HOST` set to the stick's IP from step 2:
   ```bash
   TV_HOST=192.168.1.50 glasshome/install.sh
   ```
   The first time, a prompt appears **on the TV** asking to allow ADB debugging from this computer —
   confirm it there, then re-run the command. This step also downloads a few seconds of free aerial
   footage for the screensaver; skip that with `NO_AERIALS=1 TV_HOST=... glasshome/install.sh` if you'd
   rather just use the animated gradient.
6. **Turn off Fire OS's own navigation sounds**, so they don't play on top of Glass Home's own
   focus/select sounds: Settings → Accessibility → Navigation Sounds → Off (the exact path varies a
   little by Fire OS version — look for "Navigation Sounds" or "Sound Effects" under Accessibility or
   Display & Sounds).
7. Press **Home** on the remote. That's it.

## Using it

- **Focus** an icon with the D-pad; it lifts and the background recolours to match it.
- **Click the status pill** (top right) or press **Menu** to open **Control Center**.
- **Long-press an icon**, or focus it and press **Menu**, for **Move** (pick it up, D-pad it to a new
  spot, press Select/OK to drop it) or **Hide**.
- The **screensaver** starts after the normal Fire TV idle timeout, and can be toggled from Control
  Center.

## The screensaver: aerial footage or the gradient alone

Glass Home comes with its own screensaver (`settings put secure screensaver_components` is pointed at
it during install, and put back to Amazon's own on `--remove`). It has two looks:

- **Aerial nature clips** — a handful of free Pexels videos (coastal cliffs, desert dunes, an alpine
  valley, a waterfall, a turquoise cove; see `glasshome/aerials/CREDITS.md` and `clips.txt`), downloaded
  once to `~/.cleanyourtv/aerials/` on your computer and sent to the stick over the local network during
  install. This is the default.
- **The animated gradient alone** (the same backdrop as the home screen) — used automatically if you
  install with `NO_AERIALS=1`, or if the aerial download/transfer is skipped for any reason.

To **switch an already-installed stick** from aerials back to the plain gradient, clear the clips it
was sent:
```bash
TV_HOST=192.168.1.50 tools/venv/bin/python -I glasshome/aerials/push.py --clear
```
To go the other way (add the aerials after installing with `NO_AERIALS=1`), just re-run
`TV_HOST=... glasshome/install.sh` without that flag.

Want your own footage instead? Drop 16:9 H.264 `.mp4` files into `~/.cleanyourtv/aerials/` yourself
(see the comment at the top of `glasshome/aerials/clips.txt`) and run `aerials/push.py` to send them.

## AirPlay (optional)

Control Center's casting tile works once an AirPlay receiver is installed — Glass Home doesn't bundle
one (it isn't Glass Home's own code, and it's a 27 MB download), but it can set one up for you:

```bash
TV_HOST=192.168.1.50 glasshome/airplay/setup.py
```

This downloads a specific, checksum-pinned release of [android-airplay-server](https://github.com/jqssun/android-airplay-server)
(GPL-3.0, by jqssun, built on UxPlay) and installs it. Fire TV's own built-in AirPlay receiver
("AirReceiver") is **disabled, not uninstalled** first, since two receivers can't share the AirPlay
port — whether it was already on is recorded, so removing the new one puts it back exactly as it was:
```bash
TV_HOST=192.168.1.50 glasshome/airplay/setup.py --remove
```
Once installed, open it from your iPhone/Mac's AirPlay picker like any other AirPlay receiver, or tap
the casting icon in Control Center to bring it to the front.

## AirPods

Glass Home gives the stick the parts of Apple TV's AirPods experience that stock Fire OS allows:

- **Connect on their own.** The stick listens for the Bluetooth LE adverts AirPods send when the case lid
  opens or a bud goes in an ear. If paired AirPods are nearby it connects them (Settings → Controllers &
  Bluetooth Devices → pair them once, or use the *Set Up AirPods* card below). Only AirPods within about 2 m
  count, so a neighbour's don't trigger it.
- **A banner, anywhere.** "AirPods Pro — Connected" slides down from the top with the earbuds popping in and
  battery rings (buds, case, a green bolt when charging) sweeping up. It also shows for Disconnected and
  Low Battery (20 % and 10 %). It's drawn in a window of its own, so it shows over Netflix or YouTube.
- **Ear detection.** Both buds out for a second pauses what's playing; a bud back in resumes it (only if Glass
  Home did the pausing). Switch it off in the Audio page.
- **Set Up AirPods.** With none paired, holding the case button until the light flashes white raises a card on
  the home screen with an animated case; *Connect* pairs them.
- **Control Center → Audio.** The tile shows the live output ("AirPods Pro · 80%") and opens a page with TV
  speakers and every paired audio device, battery rings, a volume bar (Left/Right) and Auto-Connect /
  Ear Detection switches. **Holding Play/Pause** on the remote opens it directly, the nearest thing to holding
  the TV button.

`glasshome/install.sh` grants what this needs: the location permission (Android requires it for any Bluetooth LE
scan) and permission to draw the banner over other apps. Both disappear with the app on `--remove`. If the
Audio page says to turn Location on, switch it on in Fire OS's settings, or the stick can't see AirPods adverts.
Watch what it sees with `adb logcat -s GlassAirPods`.

**What it can't do** (no root): Noise Control (Noise Cancellation / Transparency / Adaptive), spatial audio and
head tracking, Share Audio between two pairs, and the remote's own volume keys controlling the AirPods (they
control the TV). Noise Control needs Apple's private AirPods protocol, and Android's Bluetooth stack blocks
reaching it without root.

## iPhone Remote (optional)

The stick appears as an Apple TV named **Nappali** in the Remote that is already on your iPhone (Control Center →
the Remote button), and the phone controls it the way it would an Apple TV: Menu (Back), Select, the TV button
(Home), play/pause, the volume buttons and a touch area to swipe on. Nothing is installed on the phone, and once it
is set up the phone talks to the stick directly — your computer is not in the path.

**Using it.** Open the Remote on the iPhone, pick *Nappali* and type the four-digit code Glass Home draws on the TV
(over whatever is showing). That pairing is done once; afterwards the phone reconnects by itself. A swipe moves
one step per bit of travel and keeps moving if you keep swiping; Select is the button, not a tap.

**What a computer still has to do: start the key helper, after every reboot of the stick.** Only the ADB shell
user can press keys inside other apps on a stock Fire stick, so Glass Home keeps a small helper running as that
user (`glasshome/src/com/glasshome/remote/Injector.java`) and the phone's presses are handed to it — a key costs
milliseconds, where `input keyevent` costs over a second. Glass Home would start that helper itself through the
stick's own ADB if it could, but this Fire OS (6.7) ignores ADB connections that come from the stick itself: tested
with the app, with the stick's own shell and via the stick's LAN address, while a computer connected without
trouble at the same moment. So `install.sh` starts the helper from your computer, once, and from then on it runs by
itself: switch the computer off, restart Glass Home, reinstall it — it keeps going. It stops when the stick
reboots (a power cut, an update, a restart). Until it is started again the phone still pairs and connects, but its
buttons do nothing. To start it again:

```bash
TV_HOST=192.168.1.50 tools/venv/bin/python -I glasshome/remote/helper.py
```

The helper listens only on the stick's loopback and only obeys a client that knows a random token; the token reaches
it through a pipe (never on a command line), and the app is told where the helper is by a broadcast that only the ADB
shell may send.

**How it works.** `glasshome/src/com/glasshome/remote/`:
- `Advertiser` announces the stick over mDNS as `_companion-link._tcp` with the records an Apple TV publishes — which
  records the phone insists on was found by trying them against a real iPhone.
- `CompanionServer` speaks the Companion link: HomeKit-style pair-setup (a PIN, SRP-6a over a 3072-bit group) and
  pair-verify (X25519, Ed25519), then ChaCha20-Poly1305 encrypted OPACK messages carrying button presses (`_hidC`)
  and touches (`_hidT`). Android 7 has none of those primitives, so `remote/crypto/` has them (on `BigInteger`),
  checked in `tests/java/CryptoTest.java` against the RFC vectors and the JDK's own implementations.
- `Gestures` turns swipes into D-pad presses; `InputBridge` talks to the helper; `PairingStore` remembers the stick's
  identity and the paired phones; `PinOverlay` draws the code.
- The protocol facts come from reading [pyatv](https://github.com/postlund/pyatv) (MIT) and from what an iPhone actually
  sent; no Apple code or assets are used.

**Tested.** On a Fire TV Stick 4K (2018), Fire OS 6.7: discovery, the PIN on the TV, pairing, reconnecting without
the PIN and button presses reaching the TV (a Home press from the client came out as a real Home press), driven by
pyatv's client against the Glass Home service on the stick. An earlier prototype of the same protocol running on a
computer was paired from a real iPhone and controlled the stick through the helper (`tests/companion_client_check.py`
and `tests/java/HostServer.java` run the server on a computer against pyatv). The finished on-stick service has not
yet been paired from an iPhone by anyone but its author's test client.

**Limits.** The Remote shows only a touch area for a device that is not a real Apple TV: no Siri button, no
keyboard entry, no trackpad click. The volume buttons press the stick's own volume keys, which do not reach a TV
that is controlled over HDMI-CEC or infrared. The name is fixed to "Nappali" for now (`PairingStore.DEFAULT_NAME`).
Only one phone can be pairing at a time. To forget every paired phone, clear Glass Home's data in Settings →
Applications, or `pm clear com.glasshome` over ADB.

## Uninstalling

```bash
TV_HOST=192.168.1.50 glasshome/install.sh --remove
```

This uninstalls the app and restores the screensaver settings and permission to exactly what they
were before Glass Home was ever installed (the very first install run records that state, so this
works even after many re-installs). Amazon's own launcher and screensaver come back on the next
Home press.

## How it works, briefly

- **Taking over Home**: Fire OS always sends the Home button to `com.amazon.tv.launcher`, whatever else
  is installed, and it silently drops any accessibility service ADB tries to enable. It does let ADB
  grant `READ_LOGS`, though, and every Home press is logged by ActivityManager. `HomeWatcher` (a
  service, started at boot and by the app itself) follows that log and brings Glass Home's own
  `HomeActivity` to the front right after, so the Home press is effectively redirected.
- **Everything else** (the dock, Control Center, the context menu, the animated backdrop) is a normal
  Android app, built with `javac` + `d8` and signed with a debug key — no Gradle involved. See
  `glasshome/build.sh`.
- **The backdrop** (`GradientBackdrop.java`) samples the focused app icon's own pixels for a dominant
  colour, then paints two hand-built, randomly-shaped "splatter" blobs (not circles) with `BlurMaskFilter`
  for soft organic edges, plus a tileable stipple-noise overlay — all rendered at a tiny internal
  resolution and scaled up, which is what keeps it smooth on a Fire TV Stick's hardware.

## Known limits

- This **replaces the Home button and launcher UI only**. It cannot act as an HDMI-CEC or Bluetooth
  remote target or otherwise act like a jailbroken stick — none of that is possible without root on stock
  Fire OS. Pressing keys inside *other* apps is possible only for the iPhone Remote, through a helper that
  a computer has to start after each reboot of the stick (see [iPhone Remote](#iphone-remote-optional)).
- The animated backdrop and app tiles are drawn at a small internal resolution and scaled up for
  performance; this is intentional and matches how the real tvOS wallpaper engine is similarly cheap.
- The AirPods advert layout is from community reverse engineering, not Apple documentation; battery, ear and
  lid fields were unit-tested against the documented layout (`glasshome/build.sh --test`) but not yet verified
  against real AirPods on a stick.
- Built and verified against one specific device/Fire OS version (see Requirements). Other Fire TV
  models may need small tweaks — PRs welcome.

## Credits

- Built on [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin23 (MIT): the ADB client
  (`tools/tv.py`) that every script here talks to the stick through comes from there.
- That client reached this project by way of [whyashthakker/debloat-fire-stick-claude-code](https://github.com/whyashthakker/debloat-fire-stick-claude-code),
  a fork of CleanYourTV, which is what Glass Home was actually built and tested inside of before being
  split out into its own repository.
- The screensaver's aerial footage (optional) is free stock video from
  [Pexels](https://www.pexels.com), used under the [Pexels License](https://www.pexels.com/license/).
  See `glasshome/aerials/CREDITS.md`. Apple's own Aerial videos are **not** used anywhere.
- The optional AirPlay receiver is [android-airplay-server](https://github.com/jqssun/android-airplay-server)
  by jqssun (GPL-3.0, built on UxPlay) — `glasshome/airplay/setup.py` downloads and verifies it against a
  pinned checksum; it is not bundled in this repository.
- The iPhone Remote support was worked out from [pyatv](https://github.com/postlund/pyatv) (MIT, by Pierre Ståhl)
  and from the earlier [AndroidAppleRemote](https://github.com/sagiweizmann/AndroidAppleRemote) and
  [windows-apple-remote](https://github.com/aryakvn/windows-apple-remote) projects, which showed the idea works;
  none of their code is used here.
- No Apple software, fonts, sounds or imagery are included or downloaded. The look is a clean-room
  re-implementation from measurements of a real tvOS 26 screenshot, using the system font and
  originally made click/focus sounds.

## License

MIT — see [LICENSE](LICENSE). Third-party assets referenced above keep their own licenses.

PSA: please fork and upgrade this repo.