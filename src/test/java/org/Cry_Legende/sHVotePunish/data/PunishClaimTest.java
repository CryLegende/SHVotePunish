package org.Cry_Legende.sHVotePunish.data;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishClaimTest {

    /** Base commune : un INSERT IGNORE, qui n'a qu'un gagnant par joueur. */
    private static final class FakeDatabase implements SharedLists {
        final Set<UUID> rows = ConcurrentHashMap.newKeySet();
        volatile boolean down;

        @Override
        public void add(Kind kind, UUID uuid, String name) {
        }

        @Override
        public void remove(Kind kind, UUID uuid) {
        }

        @Override
        public CompletableFuture<Boolean> claimPunished(UUID uuid, String name) {
            if (down) {
                return CompletableFuture.failedFuture(new IllegalStateException("base injoignable"));
            }
            return CompletableFuture.completedFuture(rows.add(uuid));
        }
    }

    @Test
    void firstClaimWinsAndTheSameServerCannotClaimTwice() {
        FakeDatabase db = new FakeDatabase();
        Set<UUID> memory = ConcurrentHashMap.newKeySet();
        UUID target = UUID.randomUUID();
        assertTrue(PunishClaim.claim(memory, db, target, "Cible").join());
        assertFalse(PunishClaim.claim(memory, db, target, "Cible").join());
        assertTrue(memory.contains(target));
    }

    @Test
    void otherServerThatAlreadyPunishedTheTargetWinsEvenIfThisServerDidNotKnow() {
        FakeDatabase db = new FakeDatabase();
        UUID target = UUID.randomUUID();
        db.rows.add(target); // sanctionné par l'autre serveur, message du bus pas encore arrivé ici
        Set<UUID> memory = ConcurrentHashMap.newKeySet();
        assertFalse(PunishClaim.claim(memory, db, target, "Cible").join());
        assertTrue(memory.contains(target));
    }

    @Test
    void unreachableDatabaseStillLetsThePunishmentThrough() {
        FakeDatabase db = new FakeDatabase();
        db.down = true;
        UUID target = UUID.randomUUID();
        assertTrue(PunishClaim.claim(ConcurrentHashMap.newKeySet(), db, target, "Cible").join());
    }

    @Test
    void severalServersVotingAtOnceSanctionTheTargetExactlyOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            FakeDatabase db = new FakeDatabase();
            UUID target = UUID.randomUUID();
            int servers = 8;
            AtomicInteger sanctions = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(servers);
            for (int i = 0; i < servers; i++) {
                Set<UUID> ownMemory = ConcurrentHashMap.newKeySet(); // chaque serveur a sa liste en mémoire
                pool.execute(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (PunishClaim.claim(ownMemory, db, target, "Cible").join()) {
                        sanctions.incrementAndGet();
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, sanctions.get());
        }
    }
}
