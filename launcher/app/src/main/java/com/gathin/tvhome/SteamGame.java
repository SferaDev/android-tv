package com.gathin.tvhome;

/** A game on the GameStream host to stream with Moonlight; appId is the host's app id, 0 for the Big Picture fallback. */
final class SteamGame {
    final int appId;
    final String name;

    SteamGame(int appId, String name) {
        this.appId = appId;
        this.name = name;
    }
}
