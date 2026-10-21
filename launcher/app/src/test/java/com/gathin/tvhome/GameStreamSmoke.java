package com.gathin.tvhome;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Pairs from a JVM with a GameStream host and lists its apps; not a unit test. Arguments: host address, state
 * directory (keys and certificates are written there, keep it outside the repo). Waits for the PIN it prints to be
 * entered in the host's web UI.
 */
public final class GameStreamSmoke {
    private GameStreamSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: GameStreamSmoke <host> <state-dir>");
            System.exit(2);
        }
        String host = args[0];
        GameStreamIdentity identity = GameStreamIdentity.load(new File(args[1]));
        GameStreamClient client = new GameStreamClient(host, identity);
        GameStreamClient.ServerInfo info = client.serverInfo();
        System.out.println("host " + info.hostname + " " + info.uuid + " generation " + info.majorVersion);
        if (!identity.paired()) {
            String pin = GameStreamCrypto.generatePin();
            System.out.println("Enter PIN " + pin + " in the host's web UI (PIN tab); waiting up to 2 minutes");
            GameStreamClient.PairResult result = client.pair(pin, 120_000);
            System.out.println("pairing: " + result);
            if (result != GameStreamClient.PairResult.PAIRED) System.exit(1);
            identity.savePaired();
        }
        List<GameStreamClient.App> apps = client.appList();
        for (GameStreamClient.App app : apps) System.out.println(app.id + "  " + app.title);
        if (!apps.isEmpty()) {
            byte[] art = client.boxArt(apps.get(0).id);
            Files.write(new File(args[1], "boxart-" + apps.get(0).id + ".png").toPath(), art);
            System.out.println("box art of " + apps.get(0).title + ": " + art.length + " bytes");
        }
    }
}
