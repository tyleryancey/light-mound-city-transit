# Mound City Transit

A departure board for St. Louis regional transit (MetroBus, MetroLink), for the
Light Phone 3. You type the number printed on the stop sign and it tells you what
is coming and when — offline by default from a bundled schedule, live bus delays
and service alerts when you ask, and honest about its own age on every screen. It
never plans trips, draws maps, asks where you are, or scrolls forever.

Full documentation — data sources, Terms of Use, accuracy notes, and the vetting
defense — lives in [`tool/README.md`](tool/README.md).

## Screenshots

| Home | Departures | Route viewer | Alerts |
|---|---|---|---|
| ![Home](tool/screenshots/home.png) | ![Departures](tool/screenshots/departures.png) | ![Route viewer](tool/screenshots/route-viewer.png) | ![Alerts](tool/screenshots/alerts.png) |

Captured on a physical Light Phone 3, 2026-08-06.

## Install (sideload)

Download the latest release APK from
[Releases](https://github.com/tyleryancey/light-mound-city-transit/releases) and
sideload it via Android Studio / adb, per the LightOS developer docs.

## LP3 / LightOS compatibility

Tested on a physical Light Phone III (TLP301) and the LightOS emulator AVD, against
the SDK pinned at `lightphone/light-sdk@9aed6ff`. Every phase was QA'd on hardware:
stop entry, departures, saved stops, live refresh, alerts, browse, the route viewer,
the daily background schedule refresh (verified rebuilding the index on-device from
the agency's live feed), process-death recovery, and airplane mode. Tool ID:
`moundcity.transit`.

## Build from source

```
git clone https://github.com/tyleryancey/light-mound-city-transit.git
cd light-mound-city-transit
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :tool:assembleRelease
```

No credentials are required: all dependencies resolve from Google, Maven Central,
and JitPack. `./gradlew check` runs the full test-and-scan gate.

## Attribution

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk). This repo
inherits its MIT license. Transit data is the property of Bi-State Development
Agency dba Metro, used under the Terms quoted in `tool/README.md`; this tool is not
affiliated with or endorsed by the agency.
