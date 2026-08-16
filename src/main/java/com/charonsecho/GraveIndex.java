package com.charonsecho;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Fast derived lookups over the append-only grave ledger. */
final class GraveIndex {

    private final Map<UUID, GraveManager.Grave> byId = new HashMap<>();
    private final Map<UUID, GraveManager.Grave> oldestUnclaimed = new HashMap<>();
    private final Map<UUID, GraveManager.Grave> latestClaimed = new HashMap<>();
    private final Map<Integer, GraveManager.Grave> byPlot = new HashMap<>();
    private int maximumPlot = -1;

    void clear() {
        byId.clear();
        oldestUnclaimed.clear();
        latestClaimed.clear();
        byPlot.clear();
        maximumPlot = -1;
    }

    void add(GraveManager.Grave grave) {
        byId.put(grave.id, grave);
        if (grave.plotIndex >= 0) {
            byPlot.put(grave.plotIndex, grave);
            maximumPlot = Math.max(maximumPlot, grave.plotIndex);
        }
        if (grave.claimed) {
            GraveManager.Grave current = latestClaimed.get(grave.owner);
            if (current == null || later(grave, current)) latestClaimed.put(grave.owner, grave);
        } else {
            GraveManager.Grave current = oldestUnclaimed.get(grave.owner);
            if (current == null || earlier(grave, current)) oldestUnclaimed.put(grave.owner, grave);
        }
    }

    void markClaimed(GraveManager.Grave grave, Iterable<GraveManager.Grave> all) {
        grave.claimed = true;
        GraveManager.Grave latest = latestClaimed.get(grave.owner);
        if (latest == null || later(grave, latest)) latestClaimed.put(grave.owner, grave);
        if (oldestUnclaimed.get(grave.owner) == grave) {
            GraveManager.Grave next = null;
            for (GraveManager.Grave candidate : all) {
                if (!candidate.claimed && candidate.owner.equals(grave.owner)
                        && (next == null || earlier(candidate, next))) {
                    next = candidate;
                }
            }
            if (next == null) oldestUnclaimed.remove(grave.owner);
            else oldestUnclaimed.put(grave.owner, next);
        }
    }

    void assignPlot(GraveManager.Grave grave, int plotIndex) {
        if (grave.plotIndex >= 0) byPlot.remove(grave.plotIndex);
        grave.plotIndex = plotIndex;
        byPlot.put(plotIndex, grave);
        maximumPlot = Math.max(maximumPlot, plotIndex);
    }

    Optional<GraveManager.Grave> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    Optional<GraveManager.Grave> oldestUnclaimed(UUID owner) {
        return Optional.ofNullable(oldestUnclaimed.get(owner));
    }

    Optional<GraveManager.Grave> latestClaimed(UUID owner) {
        return Optional.ofNullable(latestClaimed.get(owner));
    }

    boolean plotUsed(int plotIndex) {
        return byPlot.containsKey(plotIndex);
    }

    int nextPlotIndex() {
        return maximumPlot + 1;
    }

    int latestField(int plotsPerField) {
        return maximumPlot < 0 ? -1 : maximumPlot / plotsPerField;
    }

    private static boolean earlier(GraveManager.Grave a, GraveManager.Grave b) {
        int time = Long.compare(a.gameTime, b.gameTime);
        return time < 0 || (time == 0 && a.id.compareTo(b.id) < 0);
    }

    private static boolean later(GraveManager.Grave a, GraveManager.Grave b) {
        int time = Long.compare(a.gameTime, b.gameTime);
        return time > 0 || (time == 0 && a.id.compareTo(b.id) > 0);
    }
}
