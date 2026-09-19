package com.charonsecho.death;

import com.charonsecho.CharonsEcho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class GraveIndexTest {

    @Test
    void tracksOldestUnclaimedAndLatestClaimedWithoutLedgerScans() {
        UUID owner = UUID.randomUUID();
        GraveManager.Grave newestClaimed = grave(owner, 40, true, 13);
        GraveManager.Grave oldestOpen = grave(owner, 10, false, 3);
        GraveManager.Grave newerOpen = grave(owner, 30, false, 9);
        GraveManager.Grave olderClaimed = grave(owner, 20, true, 6);
        List<GraveManager.Grave> graves = new ArrayList<>(
                List.of(newestClaimed, oldestOpen, newerOpen, olderClaimed));

        GraveIndex index = new GraveIndex();
        graves.forEach(index::add);

        assertSame(oldestOpen, index.oldestUnclaimed(owner).orElseThrow());
        assertSame(newestClaimed, index.latestClaimed(owner).orElseThrow());
        assertSame(newerOpen, index.byId(newerOpen.id).orElseThrow());
        assertEquals(14, index.nextPlotIndex());
        assertTrue(index.plotUsed(6));
        assertFalse(index.plotUsed(7));

        index.markClaimed(oldestOpen, graves);
        assertSame(newerOpen, index.oldestUnclaimed(owner).orElseThrow());

        index.markClaimed(newerOpen, graves);
        assertTrue(index.oldestUnclaimed(owner).isEmpty());
        assertSame(newestClaimed, index.latestClaimed(owner).orElseThrow());
    }

    @Test
    void updatesPlotLookupWhenAPlotIsAssigned() {
        UUID owner = UUID.randomUUID();
        GraveManager.Grave grave = grave(owner, 10, false, -1);
        GraveIndex index = new GraveIndex();
        index.add(grave);

        index.assignPlot(grave, 72);

        assertEquals(72, grave.plotIndex);
        assertTrue(index.plotUsed(72));
        assertEquals(73, index.nextPlotIndex());
        assertEquals(2, index.latestField(36));
    }

    private static GraveManager.Grave grave(UUID owner, long gameTime,
            boolean claimed, int plot) {
        GraveManager.Grave grave = new GraveManager.Grave(
                UUID.randomUUID(), owner, "Soul", "minecraft:overworld",
                new BlockPos(0, 64, 0), "fell", gameTime, 0, 0, List.of(), claimed);
        grave.plotIndex = plot;
        return grave;
    }
}
