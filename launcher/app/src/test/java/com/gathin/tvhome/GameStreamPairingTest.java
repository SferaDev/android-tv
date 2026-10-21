package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static com.gathin.tvhome.GameStreamClient.PairResult.ALREADY_IN_PROGRESS;
import static com.gathin.tvhome.GameStreamClient.PairResult.FAILED;
import static com.gathin.tvhome.GameStreamClient.PairResult.PAIRED;
import static com.gathin.tvhome.GameStreamClient.PairResult.PIN_WRONG;
import static com.gathin.tvhome.GameStreamPairing.Next;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class GameStreamPairingTest {
    private static final long QUICK = GameStreamPairing.INSTANT_MS - 1, SLOW = GameStreamPairing.INSTANT_MS;

    @Test public void aWaitThatRunsOutAsksAgainWithTheSamePin() {
        assertEquals(Next.ASK_AGAIN, GameStreamPairing.next(null, 300_000, false));
        assertEquals(Next.ASK_AGAIN, GameStreamPairing.next(null, 300_000, true));
    }

    @Test public void anInstantRefusalTriesOnceMoreUnderANewId() {
        assertEquals(Next.NEW_ID, GameStreamPairing.next(FAILED, QUICK, false));
        assertEquals(Next.NEW_ID, GameStreamPairing.next(ALREADY_IN_PROGRESS, QUICK, false));
        assertEquals(Next.GIVE_UP, GameStreamPairing.next(FAILED, QUICK, true));
    }

    @Test public void aSlowFailureOrAWrongPinGivesUp() {
        assertEquals(Next.GIVE_UP, GameStreamPairing.next(FAILED, SLOW, false));
        assertEquals(Next.GIVE_UP, GameStreamPairing.next(PIN_WRONG, QUICK, false));
        assertEquals(Next.DONE, GameStreamPairing.next(PAIRED, QUICK, true));
    }

    @Test public void aNewIdKeepsTheKeyAndCertificate() throws Exception {
        File dir = Files.createTempDirectory("gs-newid").toFile();
        GameStreamIdentity before = GameStreamIdentity.load(dir);
        GameStreamIdentity.newUniqueId(dir);
        GameStreamIdentity after = GameStreamIdentity.load(dir);
        assertNotEquals(before.uniqueId, after.uniqueId);
        assertTrue(after.uniqueId.matches("[0-9a-f]{16}"));
        assertEquals(before.certificate, after.certificate);
    }
}
