package com.charonsecho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.nbt.CompoundTag;

class CharonStorageTest {

    @TempDir
    Path directory;

    @Test
    void coalescesWritesToTheNewestSnapshot() throws Exception {
        Path file = directory.resolve("ledger.dat");
        for (int i = 0; i < 50; i++) {
            CompoundTag snapshot = new CompoundTag();
            snapshot.putInt("revision", i);
            CharonStorage.write(file, snapshot);
        }

        CharonStorage.flush();

        assertEquals(49, CharonStorage.read(file).getIntOr("revision", -1));
    }

    @Test
    void recoversFromBackupWithoutOverwritingTheGoodCopy() throws Exception {
        Path file = directory.resolve("graves.dat");
        CompoundTag first = new CompoundTag();
        first.putString("state", "first");
        CharonStorage.write(file, first);
        CharonStorage.flush();

        CompoundTag second = new CompoundTag();
        second.putString("state", "second");
        CharonStorage.write(file, second);
        CharonStorage.flush();
        Files.writeString(file, "not nbt");

        assertEquals("first", CharonStorage.read(file).getStringOr("state", ""));

        CompoundTag recovered = new CompoundTag();
        recovered.putString("state", "recovered");
        CharonStorage.write(file, recovered);
        CharonStorage.flush();

        assertEquals("recovered", CharonStorage.read(file).getStringOr("state", ""));
        assertTrue(Files.exists(file.resolveSibling("graves.dat.bak")));
    }
}
