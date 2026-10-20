# android-tv
<!-- fork-readme -->

[SferaDev](https://github.com/SferaDev)'s fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) with a Google TV style launcher.

## Features

### togglehome key

A remote button switches the stock launcher on and off through the TV's own adb. [Commit](https://github.com/SferaDev/android-tv/commit/c39cdc586112b81854a3d5055ad3aaac294686a6) <!-- commit: Launcher: togglehome app key switches the stock launcher on and off -->

### On-TV settings

Remote buttons, Home, appearance and permissions set on the TV, with export and import as a JSON file. [Commit](https://github.com/SferaDev/android-tv/commit/089e2661149d5cb2ef33e67102b6c3cfc3ea548a) <!-- commit: Launcher: on-TV settings with backup and import -->

![Launcher settings](docs/fork/settings.jpg)

### Google TV style Home

Tabs, search and a notifications bell in the header, a hero whose backdrop follows the focused card, and strips you can hide. [Commit](https://github.com/SferaDev/android-tv/commit/109276bfa5b41e8a5a910417c6101f2efa5b725c) <!-- commit: Launcher: Google TV style home -->

![Home](docs/fork/home.jpg)

### Continue watching

Shows in progress from other apps' Watch Next rows, with progress and an age limit. [Commit](https://github.com/SferaDev/android-tv/commit/0cffcc73f4dc6c52b53d11bf99409124884f244e) <!-- commit: Launcher: Continue watching from other apps' Watch Next rows -->

![Settings, Home page](docs/fork/settings-home.jpg)

### Recent apps

Every app by last use, then a See all card that opens the Apps tab. [Commit](https://github.com/SferaDev/android-tv/commit/22113dda0abed5e15ea331ae7f8c71e049880679) <!-- commit: Launcher: Recent apps strip by last use, then See all -->

![Apps tab](docs/fork/apps.jpg)

### Games from a PC

Finds and pairs with a Sunshine PC, lists its games in a tab and a strip, and starts them in Moonlight. [Commit](https://github.com/SferaDev/android-tv/commit/57bbebae2af291bb82968bd925a895824a5ea731) <!-- commit: Launcher: games from a PC over GameStream and Moonlight -->

![Games tab](docs/fork/games.jpg)

### Now playing

Cover, title, progress and playback controls in the hero for whatever is playing. [Commit](https://github.com/SferaDev/android-tv/commit/be7e0ec386af8d5c3256a831f83a66f721e2f2c9) <!-- commit: Launcher: Now playing card -->

### TV tab and Recent TV

Inputs and channels with now and next in their own tab, a Home strip of what was watched last, and an opt-in start on the last input. [Commit](https://github.com/SferaDev/android-tv/commit/e72143c0434a9fe4280e46b27976f842df397c0e) <!-- commit: Launcher: TV tab, channels and Recent TV -->

![TV tab](docs/fork/tv.jpg)

### Reliability

Repeated crashes hand Home back to the stock launcher, boots and updates re-apply the takeover, and Home draws at once and starts over on wake. [Commit](https://github.com/SferaDev/android-tv/commit/fbe7121f68bfa2879b26001dadd1dea779cef6af) <!-- commit: Launcher: reliability: crash safety net, boot/update check, instant start and a fresh Home on wake -->

### Header shortcuts

Up to four apps pinned to the header as round logo buttons, picked in Settings, Home. [Commit](https://github.com/SferaDev/android-tv/commit/940b484090af042ec271b688f18a66c75279b368) <!-- commit: Launcher: header shortcuts for up to four pinned apps -->

### Network warnings

The network button turns orange behind a captive portal and red while offline. [Commit](https://github.com/SferaDev/android-tv/commit/19269f959c83d46a9c3f0ef124ed2d4e5b11bfa2) <!-- commit: Launcher: network warnings on the header's network button -->

### Home at power-on

Home instead of Live TV, or always Home, when the TV turns on; it leaves a reboot to Start on last input. [Commit](https://github.com/SferaDev/android-tv/commit/a68fb6bb268552382c2ef7cbefeee12086d15ac8) <!-- commit: Launcher: Home at power-on, picked in Settings, Home -->

### Controller passthrough

A switch on the Games page that lets every controller button through, which Moonlight needs for a second controller. [Commit](https://github.com/SferaDev/android-tv/commit/88fc8e367c35dc115462661d297ea4bc466c6f01) <!-- commit: Launcher: controller passthrough switch on the Games page -->

**Contributed upstream:** the MiTV-MOEU0 debloat list ([#651](https://github.com/Gathin23/cleanyourtv/pull/651)) and this fork's SHOWCASE row ([#664](https://github.com/Gathin23/cleanyourtv/pull/664)).

**Network:** with Online artwork on, TVmaze and Steam's public store and image CDN for hero backdrops; the GameStream (Sunshine) PC you pair with, on the local network; and the pictures other apps link in their Watch Next rows and now-playing sessions. Nothing else: no analytics, and settings stay on the TV.

---

MIT, like upstream ([LICENSE](LICENSE)); built on CleanYourTV by Gathin and its contributors. Show artwork from [TVmaze](https://www.tvmaze.com/) (CC BY-SA); the Outfit typeface is under the SIL Open Font License.
