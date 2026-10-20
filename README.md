# android-tv
<!-- fork-readme -->

[SferaDev](https://github.com/SferaDev)'s fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) with a Google TV style launcher.

## Features

### togglehome key

A remote button switches the stock launcher on and off through the TV's own adb. [Commit](https://github.com/SferaDev/android-tv/commit/ddf689e5617353e39bf46e47aca33ac4e49297ba) <!-- commit: Launcher: togglehome app key switches the stock launcher on and off -->

### On-TV settings

Remote buttons, Home, appearance and permissions set on the TV, with export and import as a JSON file. [Commit](https://github.com/SferaDev/android-tv/commit/bade8b1030fb770b57d6bb2eba24df8009f05da9) <!-- commit: Launcher: on-TV settings with backup and import -->

![Launcher settings](docs/fork/settings.jpg)

### Google TV style Home

Tabs, search and a notifications bell in the header, a hero whose backdrop follows the focused card, and strips you can hide. [Commit](https://github.com/SferaDev/android-tv/commit/9492d556d5abd0c424f16dd8b3be75c111215e85) <!-- commit: Launcher: Google TV style home -->

![Home](docs/fork/home.jpg)

### Continue watching

Shows in progress from other apps' Watch Next rows, with progress and an age limit. [Commit](https://github.com/SferaDev/android-tv/commit/21176e3198195b6124ec76649a5ff770a15af5f5) <!-- commit: Launcher: Continue watching from other apps' Watch Next rows -->

![Settings, Home page](docs/fork/settings-home.jpg)

### Recent apps

Every app by last use, then a See all card that opens the Apps tab. [Commit](https://github.com/SferaDev/android-tv/commit/8d6345e2deaeefa863397a312091764f62972281) <!-- commit: Launcher: Recent apps strip by last use, then See all -->

![Apps tab](docs/fork/apps.jpg)

### Games from a PC

Finds and pairs with a Sunshine PC, lists its games in a tab and a strip, and starts them in Moonlight. [Commit](https://github.com/SferaDev/android-tv/commit/33afd230e630df26cb3d949798e23b263cd45a76) <!-- commit: Launcher: games from a PC over GameStream and Moonlight -->

![Games tab](docs/fork/games.jpg)

### Now playing

Cover, title, progress and playback controls in the hero for whatever is playing. [Commit](https://github.com/SferaDev/android-tv/commit/6fce9aa2d2f591236f828933af8049136020d491) <!-- commit: Launcher: Now playing card -->

### TV tab and Recent TV

Inputs and channels with now and next in their own tab, a Home strip of what was watched last, and an opt-in start on the last input. [Commit](https://github.com/SferaDev/android-tv/commit/c8b4d561f713ddc96b6fe8874def21401ab7edad) <!-- commit: Launcher: TV tab, channels and Recent TV -->

![TV tab](docs/fork/tv.jpg)

### Reliability

Repeated crashes hand Home back to the stock launcher, boots and updates re-apply the takeover, and Home draws at once and starts over on wake. [Commit](https://github.com/SferaDev/android-tv/commit/004c140ee270f53a2775574dfbb5d305e775977c) <!-- commit: Launcher: reliability: crash safety net, boot/update check, instant start and a fresh Home on wake -->

### Header shortcuts

Up to four apps pinned to the header as round logo buttons, picked in Settings, Home. [Commit](https://github.com/SferaDev/android-tv/commit/7191bee6c66d4dfb60ceec7ddcb618b7853659c9) <!-- commit: Launcher: header shortcuts for up to four pinned apps -->

### Network warnings

The network button turns orange behind a captive portal and red while offline. [Commit](https://github.com/SferaDev/android-tv/commit/3f7148e26398100124210f552bff4a7f032d1356) <!-- commit: Launcher: network warnings on the header's network button -->

### Home at power-on

Home instead of Live TV, or always Home, when the TV turns on; it leaves a reboot to Start on last input. [Commit](https://github.com/SferaDev/android-tv/commit/69d64702b2d8b16f64b46b8ea3b3e8f1db014269) <!-- commit: Launcher: Home at power-on, picked in Settings, Home -->

### Controller passthrough

A switch on the Games page that lets every controller button through, which Moonlight needs for a second controller. [Commit](https://github.com/SferaDev/android-tv/commit/cd0dca50fa8ba93c98db9702bb8863405199407f) <!-- commit: Launcher: controller passthrough switch on the Games page -->

**Contributed upstream:** the MiTV-MOEU0 debloat list ([#651](https://github.com/Gathin23/cleanyourtv/pull/651)) and this fork's SHOWCASE row ([#664](https://github.com/Gathin23/cleanyourtv/pull/664)).

**Network:** with Online artwork on, TVmaze and Steam's public store and image CDN for hero backdrops; the GameStream (Sunshine) PC you pair with, on the local network; and the pictures other apps link in their Watch Next rows and now-playing sessions. Nothing else: no analytics, and settings stay on the TV.

---

MIT, like upstream ([LICENSE](LICENSE)); built on CleanYourTV by Gathin and its contributors. Show artwork from [TVmaze](https://www.tvmaze.com/) (CC BY-SA); the Outfit typeface is under the SIL Open Font License.
