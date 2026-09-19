package com.charonsecho;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Safe storage for Charon's world ledgers. Snapshots are built on the server
 * thread, then compression and disk I/O happen on one background writer.
 * Repeated writes to the same file coalesce to the newest snapshot.
 */
public final class CharonStorage {

    private static final long MAX_NBT_BYTES = 256L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static final Map<Path, CompoundTag> PENDING = new HashMap<>();
    private static final Set<Path> ACTIVE = new HashSet<>();
    private static final Set<Path> INVALID_PRIMARY = new HashSet<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "charons-echo-storage");
        thread.setDaemon(true);
        return thread;
    });

    private CharonStorage() {}

    public static boolean hasData(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return Files.exists(normalized) || Files.exists(backupOf(normalized));
    }

    /** Read the primary ledger, falling back to its last known-good backup. */
    public static CompoundTag read(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            return NbtIo.readCompressed(normalized, NbtAccounter.create(MAX_NBT_BYTES));
        } catch (Exception primary) {
            Path backup = backupOf(normalized);
            if (!Files.exists(backup)) {
                if (primary instanceof IOException io) throw io;
                throw new IOException("Invalid NBT in " + file, primary);
            }
            System.out.println("[CharonsEcho] " + file.getFileName()
                    + " could not be read; recovering from " + backup.getFileName());
            try {
                CompoundTag recovered = NbtIo.readCompressed(
                        backup, NbtAccounter.create(MAX_NBT_BYTES));
                synchronized (LOCK) {
                    INVALID_PRIMARY.add(normalized);
                }
                return recovered;
            } catch (Exception backupFailure) {
                IOException failure = new IOException(
                        "Both " + file + " and its backup are unreadable", backupFailure);
                failure.addSuppressed(primary);
                throw failure;
            }
        }
    }

    /** Queue an immutable NBT snapshot. A newer snapshot replaces pending work. */
    public static void write(Path file, CompoundTag snapshot) {
        Path normalized = file.toAbsolutePath().normalize();
        synchronized (LOCK) {
            PENDING.put(normalized, snapshot);
            if (!ACTIVE.add(normalized)) return;
        }
        WRITER.execute(() -> drain(normalized));
    }

    private static void drain(Path file) {
        while (true) {
            CompoundTag snapshot;
            synchronized (LOCK) {
                snapshot = PENDING.remove(file);
                if (snapshot == null) {
                    ACTIVE.remove(file);
                    return;
                }
            }
            try {
                writeAtomic(file, snapshot);
            } catch (IOException e) {
                System.out.println("[CharonsEcho] failed to save " + file.getFileName() + ": " + e);
            }
        }
    }

    private static void writeAtomic(Path file, CompoundTag snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Path backup = backupOf(file);
        NbtIo.writeCompressed(snapshot, temporary);
        boolean preserveBackup;
        synchronized (LOCK) {
            preserveBackup = INVALID_PRIMARY.remove(file);
        }
        if (Files.exists(file) && !preserveBackup) {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /** Wait for every snapshot queued before this call (used during shutdown). */
    static void flush() {
        CountDownLatch barrier = new CountDownLatch(1);
        WRITER.execute(barrier::countDown);
        try {
            if (!barrier.await(30, TimeUnit.SECONDS)) {
                System.out.println("[CharonsEcho] timed out waiting for ledger saves");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
