package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * SETS — style families. A set is a bordered area of the Studio holding a
 * coherent collection of builds (stones, gates, trees, ...) that always
 * generate TOGETHER: each region of Charon's Echo draws from exactly one set,
 * so styles never mix mid-field.
 *
 * Lifecycle: created OPEN (any gravekeeper may build) → admin may TRUST it
 * (only the steward + invited builders may build) → admin APPROVES it (locked
 * into generation; pieces exported at approval time ship). Trusted members may
 * REOPEN to add pieces — nothing new ships until approved again. The default
 * set is the mod's shipped baseline and is always in the rotation; categories
 * a set doesn't cover fall back to default.
 */
public final class StudioSets {

    public static int defaultSize() {
        return CharonConfig.setDefaultSize;
    }
    /** One style region is ~this many blocks across in Charon's Echo. */
    private static final int REGION = 512;
    /** Custom set areas start east of the default grid. */
    private static final int FIRST_ORIGIN_X = 320;

    public static final class SetInfo {
        public final String name;
        public final int originX, size;
        public UUID steward;
        public String stewardName;
        public boolean trusted, approved, dirty;
        public final Set<UUID> invited = new HashSet<>();

        SetInfo(String name, int originX, int size, UUID steward, String stewardName) {
            this.name = name;
            this.originX = originX;
            this.size = size;
            this.steward = steward;
            this.stewardName = stewardName;
        }

        public boolean contains(int x, int z) {
            return x >= originX && x < originX + size && z >= 0 && z < size;
        }

        public boolean isMember(ServerPlayer p) {
            return p.getUUID().equals(steward) || invited.contains(p.getUUID());
        }
    }

    private static final List<SetInfo> SETS = new CopyOnWriteArrayList<>();
    private static Path file;

    private StudioSets() {}

    public static List<SetInfo> all() {
        return List.copyOf(SETS);
    }

    public static SetInfo get(String name) {
        for (SetInfo s : SETS) {
            if (s.name.equals(name)) return s;
        }
        return null;
    }

    /** The custom set containing this Studio position, or null (default grid / open ground). */
    public static SetInfo at(int x, int z) {
        for (SetInfo s : SETS) {
            if (s.contains(x, z)) return s;
        }
        return null;
    }

    public static SetInfo create(ServerLevel studio, String name, int size, ServerPlayer steward) {
        if (get(name) != null || name.equals("default")) return null;
        size = Math.max(32, Math.min(size, CharonConfig.setMaxSize));
        int origin = FIRST_ORIGIN_X;
        for (SetInfo s : SETS) {
            origin = Math.max(origin, s.originX + s.size + 64);
        }
        SetInfo set = new SetInfo(name, origin, size, steward.getUUID(), steward.getName().getString());
        SETS.add(set);
        save();
        stampBorder(studio, set);
        return set;
    }

    /**
     * May this player build at a Studio position? Admins always. Inside a
     * custom set: members always; non-members only while the set is neither
     * trusted nor approved. Outside any set (default grid + open ground):
     * any gravekeeper.
     */
    public static boolean canBuildAt(ServerPlayer player, int x, int z) {
        if (GraveyardRules.isGamemaster(player)) return true;
        if (!Gravekeepers.isKeeper(player.getUUID())) return false;
        SetInfo set = at(x, z);
        if (set == null) return true;
        if (set.isMember(player)) return true;
        return !set.trusted && !set.approved;
    }

    /**
     * The set a graveyard region draws from: seed-deterministic pick among
     * default + all approved sets. One style per ~REGION-block cell.
     */
    public static String setForRegion(int x, int z) {
        List<String> pool = new java.util.ArrayList<>();
        pool.add("default");
        for (SetInfo s : SETS) {
            if (s.approved) pool.add(s.name);
        }
        if (pool.size() == 1) return "default";
        long cellX = Math.floorDiv(x, REGION), cellZ = Math.floorDiv(z, REGION);
        long h = cellX * 341873128712L + cellZ * 132897987541L
                + GraveyardTerrain.seed() * 0x9E3779B97F4A7C15L + 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return pool.get((int) Math.floorMod(h, pool.size()));
    }

    /** Gold border at ground level + a glowing name sign at the SW corner. */
    static void stampBorder(ServerLevel studio, SetInfo set) {
        var gold = Blocks.CONCRETE.yellow().defaultBlockState();
        int x0 = set.originX - 1, x1 = set.originX + set.size, z0 = -1, z1 = set.size;
        for (int x = x0; x <= x1; x++) {
            setGround(studio, gold, x, z0);
            setGround(studio, gold, x, z1);
        }
        for (int z = z0; z <= z1; z++) {
            setGround(studio, gold, x0, z);
            setGround(studio, gold, x1, z);
        }
        BlockPos signPos = new BlockPos(set.originX + 1, surfaceY(studio, set.originX + 1, z1) + 2, z1);
        studio.setBlock(signPos, Blocks.PALE_OAK_SIGN.defaultBlockState(), 3);
        if (studio.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            SignText text = new SignText()
                    .setMessage(0, Component.literal("Set: " + set.name))
                    .setMessage(1, Component.literal("steward: " + set.stewardName))
                    .setMessage(2, Component.literal(set.size + "x" + set.size))
                    .setHasGlowingText(true);
            sign.setText(text, true);
            sign.setText(text, false);
            sign.setChanged();
        }
    }

    static void stampAllBorders(ServerLevel studio) {
        for (SetInfo s : SETS) {
            stampBorder(studio, s);
        }
    }

    private static void setGround(ServerLevel level, net.minecraft.world.level.block.state.BlockState state,
                                  int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        level.setBlock(new BlockPos(x, surfaceY(level, x, z), z), state, 2);
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    }

    // ---- persistence (world/charons_echo/sets.dat) ----

    public static void load(MinecraftServer server) {
        SETS.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("sets.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            for (Tag t : root.getListOrEmpty("sets")) {
                if (!(t instanceof CompoundTag c)) continue;
                SetInfo s = new SetInfo(
                        c.getStringOr("name", "?"),
                        c.getIntOr("originX", FIRST_ORIGIN_X),
                        c.getIntOr("size", defaultSize()),
                        UUID.fromString(c.getStringOr("steward", new UUID(0, 0).toString())),
                        c.getStringOr("stewardName", "?"));
                s.trusted = c.getBooleanOr("trusted", false);
                s.approved = c.getBooleanOr("approved", false);
                s.dirty = c.getBooleanOr("dirty", false);
                for (Tag inv : c.getListOrEmpty("invited")) {
                    try {
                        s.invited.add(UUID.fromString(inv.asString().orElse("")));
                    } catch (IllegalArgumentException ignored) {}
                }
                SETS.add(s);
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load sets.dat: " + e);
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            ListTag list = new ListTag();
            for (SetInfo s : SETS) {
                CompoundTag c = new CompoundTag();
                c.putString("name", s.name);
                c.putInt("originX", s.originX);
                c.putInt("size", s.size);
                c.putString("steward", s.steward.toString());
                c.putString("stewardName", s.stewardName);
                c.putBoolean("trusted", s.trusted);
                c.putBoolean("approved", s.approved);
                c.putBoolean("dirty", s.dirty);
                ListTag invited = new ListTag();
                for (UUID u : s.invited) {
                    invited.add(StringTag.valueOf(u.toString()));
                }
                c.put("invited", invited);
                list.add(c);
            }
            CompoundTag root = new CompoundTag();
            root.put("sets", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save sets.dat: " + e);
        }
    }
}
