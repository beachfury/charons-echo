package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The Stygian Orchard — buy a seed from the Broker, plant a Withered Grove
 * tree in the overworld, harvest Tollfruit from its chains.
 *
 * Everything lives in world/charons_echo/orchard.dat. The tree drops nothing
 * as blocks, ever: felling (netherite axe, all-or-nothing) returns only its
 * seed. Only ripe fruit is individually breakable. Growth ticks only while
 * the chunk is loaded.
 */
public final class Orchard {

    // Fruit phases: 0 bud (root) .. 1-5 veined faces .. 6 sealed, then ripe.
    private static final int VEIN_FACES = 5;

    public static final class Fruit {
        BlockPos pos;
        int phase;       // 0..6
        boolean ripe;
        boolean harvested;
        long ticks;      // loaded ticks in current phase

        Fruit(BlockPos pos) { this.pos = pos; }
    }

    public static final class Tree {
        UUID id = UUID.randomUUID();
        UUID owner;              // null for wild trees
        String ownerName = "";
        ResourceKey<Level> dim;
        BlockPos base;           // the planted sensor's position (ground + 1)
        int stage;               // 0 seedling, 1 small, 2 grown
        String template = "";
        int rotIdx;              // 0..3 quarter turns, fixed at planting
        long totalTicks, vigilTicks;   // growth clock + planter presence
        long stageTicks;
        boolean elder;           // grew the 6-chain elder
        boolean motherTree;
        UUID lineage;            // lineage id of the seed it grew from (null = plain)
        boolean wild;            // graveyard scatter tree: fruits, never fells
        Set<Long> blocks = ConcurrentHashMap.newKeySet(); // recorded tree blocks (y >= base.y)
        List<Fruit> fruits = new CopyOnWriteArrayList<>();
        boolean dormant;
        long dormantTicks;
        List<BlockPos> anchors = new ArrayList<>(); // chain-end fruit spots, scanned once grown
        boolean anchorsScanned;
        transient long blockedWarnTicks; // throttle for the blocked-growth warning
    }

    private static final class FellJob {
        final Tree tree;
        int ticksLeft = 70;
        FellJob(Tree tree) { this.tree = tree; }
    }

    private static final List<Tree> TREES = new CopyOnWriteArrayList<>();
    private static final Map<UUID, FellJob> FELLING = new ConcurrentHashMap<>();

    // ---- lineage ledger (the Book of the Living, one entry long) ----
    static UUID motherId;          // the one true mother seed
    static UUID motherOwner;
    static String motherOwnerName = "";
    static long motherLastSeen;    // epoch millis
    static boolean lineAlive;
    static UUID motherTreeId;      // set while the mother stands planted

    static UUID brokerId;          // the Broker entity (see Broker)

    private static Path file;
    private static String elderTemplateName = ""; // big tree with the most chains

    private Orchard() {}

    // ---------------------------------------------------------------- setup

    public static void register() {
        UseBlockCallback.EVENT.register(Orchard::onUseBlock);
        PlayerBlockBreakEvents.BEFORE.register(Orchard::onBreak);
        ServerTickEvents.END_SERVER_TICK.register(Orchard::tick);
    }

    /** The big tree with the most chains is the elder; the other is common. */
    public static void detectElder(MinecraftServer server) {
        var manager = server.getStructureManager();
        List<String> bigs = StudioMode.approvedTemplates("big_tree", manager, "default");
        int best = -1;
        for (String name : bigs) {
            var t = manager.get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, name));
            if (t.isEmpty()) continue;
            int chains = t.get().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(),
                    Blocks.IRON_CHAIN).size();
            if (chains > best) {
                best = chains;
                elderTemplateName = name;
            }
        }
        System.out.println("[CharonsEcho] orchard: elder template is '" + elderTemplateName
                + "' (" + best + " chain blocks)");
    }

    public static String elderTemplate() {
        return elderTemplateName;
    }

    // ---------------------------------------------------------------- planting

    private static InteractionResult onUseBlock(net.minecraft.world.entity.player.Player player,
            Level world, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!StygianItems.isSeed(held)) return InteractionResult.PASS;
        // Client forwards; the server decides (the house rule).
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        ServerLevel level = (ServerLevel) world;

        if (world.dimension() == CharonsEcho.GRAVEYARD_DIM
                || world.dimension() == CharonsEcho.STUDIO_DIM) {
            sp.sendSystemMessage(Component.literal("The dead do not tend orchards.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return InteractionResult.FAIL;
        }

        BlockPos clicked = hit.getBlockPos();
        BlockPos placeAt = level.getBlockState(clicked).canBeReplaced()
                ? clicked : clicked.relative(hit.getDirection());
        BlockPos below = placeAt.below();
        if (!level.getBlockState(placeAt).canBeReplaced()
                || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return InteractionResult.FAIL;
        }

        long owned = TREES.stream().filter(t -> !t.wild && sp.getUUID().equals(t.owner)).count();
        if (owned >= CharonConfig.orchardTreeCap) {
            sp.sendSystemMessage(Component.literal(
                    "The river grants each soul only " + CharonConfig.orchardTreeCap + " trees.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        Tree tree = new Tree();
        tree.owner = sp.getUUID();
        tree.ownerName = sp.getName().getString();
        tree.dim = level.dimension();
        tree.base = placeAt.immutable();
        tree.rotIdx = level.getRandom().nextInt(4);
        tree.lineage = StygianItems.lineageOf(held);
        UUID mother = StygianItems.isMotherSeed(held) ? tree.lineage : null;

        level.setBlock(placeAt, Blocks.SCULK_SENSOR.defaultBlockState(), 3);
        tree.blocks.add(placeAt.asLong());
        if (!sp.isCreative()) held.shrink(1);
        TREES.add(tree);

        // The mother seed reclaims her ground the moment she is planted —
        // if the ledger recognizes her and the line still lives.
        if (mother != null && mother.equals(motherId) && lineAlive && motherTreeId == null) {
            motherTreeId = tree.id;
            tree.motherTree = true;
        }
        save();

        level.playSound(null, placeAt, SoundEvents.SCULK_BLOCK_PLACE, SoundSource.BLOCKS, 1f, 0.6f);
        if (!clearanceAt(level, tree, 15, 16, tree.base)) {
            sp.sendSystemMessage(Component.literal(
                    "The ground here is crowded — the tree will wait for room.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            sp.sendSystemMessage(Component.literal("The seed settles in. Be patient.")
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- ticking

    private static void tick(MinecraftServer server) {
        // Felling rituals run every tick; growth breathes every 20.
        for (Map.Entry<UUID, FellJob> e : FELLING.entrySet()) {
            FellJob job = e.getValue();
            ServerLevel level = server.getLevel(job.tree.dim);
            if (level == null) { FELLING.remove(e.getKey()); continue; }
            if (job.ticksLeft % 10 == 0) {
                shudder(level, job.tree);
            }
            if (--job.ticksLeft <= 0) {
                FELLING.remove(e.getKey());
                collapse(level, job.tree);
            }
        }

        if (server.getTickCount() % 20 != 0) return;
        long now = System.currentTimeMillis();

        // The mother's owner walking the world keeps the line alive.
        if (motherOwner != null && server.getPlayerList().getPlayer(motherOwner) != null) {
            motherLastSeen = now;
        }
        if (lineAlive && motherLastSeen > 0
                && now - motherLastSeen > CharonConfig.motherAbsenceDays * 86_400_000L) {
            lineAlive = false;
            save();
            System.out.println("[CharonsEcho] orchard: the mother has died — the line is ended.");
        }

        for (Tree tree : TREES) {
            ServerLevel level = server.getLevel(tree.dim);
            if (level == null) continue;
            if (!level.hasChunk(tree.base.getX() >> 4, tree.base.getZ() >> 4)) continue;
            if (FELLING.containsKey(tree.id)) continue;
            if (tree.stage < 2 && !tree.wild) {
                growTick(level, tree, 20);
            } else {
                fruitTick(level, tree, 20);
            }
        }
    }

    private static void growTick(ServerLevel level, Tree tree, int ticks) {
        tree.totalTicks += ticks;
        tree.stageTicks += ticks;
        if (tree.stage == 0) {
            seedlingCreep(level, tree);
        }
        ServerPlayer planter = level.getServer().getPlayerList().getPlayer(tree.owner);
        if (planter != null && planter.level() == level && inVigilArea(tree, planter)) {
            tree.vigilTicks += ticks;
        }
        long need = tree.stage == 0 ? CharonConfig.orchardStage1Ticks : CharonConfig.orchardStage2Ticks;
        if (tree.stageTicks >= need) {
            advanceStage(level, tree, true);
        }
    }

    /**
     * The ground claims its own: sculk veins creep outward around the planted
     * sensor, reaching further as the seedling nears its first stage. Purely
     * cosmetic — the veins are swept away when the tree rises through them.
     */
    private static void seedlingCreep(ServerLevel level, Tree tree) {
        if (level.getRandom().nextFloat() > 0.15f) return;
        double progress = Math.min(1.0,
                (double) tree.stageTicks / Math.max(1, CharonConfig.orchardStage1Ticks));
        int r = 1 + (int) Math.round(progress * 4);
        int dx = level.getRandom().nextInt(r * 2 + 1) - r;
        int dz = level.getRandom().nextInt(r * 2 + 1) - r;
        if (dx == 0 && dz == 0) return;
        // Find the surface near the sensor's level and lay a vein on it.
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos pos = tree.base.offset(dx, dy, dz);
            BlockPos ground = pos.below();
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) {
                level.setBlock(pos, Blocks.SCULK_VEIN.defaultBlockState()
                        .setValue(BlockStateProperties.DOWN, true), 3);
                return;
            }
        }
    }

    private static boolean inVigilArea(Tree tree, ServerPlayer p) {
        BlockPos b = tree.base;
        return Math.abs(p.getX() - (b.getX() + 0.5)) <= 9.5
                && Math.abs(p.getZ() - (b.getZ() + 0.5)) <= 9.5
                && p.getY() > b.getY() - 4 && p.getY() < b.getY() + 20;
    }

    /**
     * Advance seedling→small or small→grown. `natural` marks real growth —
     * only real growth ever faces the big-stage roll; the admin command grows
     * the common tree, always (lineage seeds excepted — they are determinism,
     * not fortune).
     */
    static void advanceStage(ServerLevel level, Tree tree, boolean natural) {
        var manager = level.getServer().getStructureManager();
        String next;
        if (tree.stage == 0) {
            List<String> options = StudioMode.approvedTemplates("tree", manager, "default");
            if (options.isEmpty()) return;
            next = options.get(Math.floorMod(tree.id.hashCode(), options.size()));
        } else {
            next = rollBigTemplate(level, tree, natural);
            if (next.isEmpty()) return;
        }
        String category = tree.stage == 0 ? "tree" : "big_tree";
        var template = manager.get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, next));
        if (template.isEmpty()) return;
        if (!clearanceAt(level, tree, StudioMode.widthOfCategory(category),
                StudioMode.heightOfCategory(category), tree.base)) {
            tree.stageTicks = Long.MAX_VALUE / 2; // stays due; retried each breath
            // A silent wait looks like a bug — tell the owner once a minute.
            tree.blockedWarnTicks += 20;
            if (tree.blockedWarnTicks >= 1200) {
                tree.blockedWarnTicks = 0;
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(tree.owner);
                if (owner != null) {
                    owner.sendSystemMessage(Component.literal(
                            "Your tree strains against something in its space — it needs "
                            + StudioMode.widthOfCategory(category) + "x"
                            + StudioMode.widthOfCategory(category) + " open ground, "
                            + StudioMode.heightOfCategory(category) + " high.")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            return;
        }

        if (tree.stage == 0) {
            // Sweep the seedling's creep — the tree rises through it.
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos pos = tree.base.offset(dx, dy, dz);
                        if (level.getBlockState(pos).is(Blocks.SCULK_VEIN)) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
        clearRecorded(level, tree);
        pasteStage(level, tree, template.get(), category, next);
        tree.stage++;
        tree.stageTicks = 0;
        tree.anchorsScanned = false;
        level.playSound(null, tree.base, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 1.2f, 0.7f);

        if (tree.stage == 2 && tree.elder && tree.lineage == null) {
            // A vigil-grown elder. The first while no line lives FOUNDS the house.
            if (!lineAlive || motherId == null) {
                motherId = UUID.randomUUID();
                motherOwner = tree.owner;
                motherOwnerName = tree.ownerName;
                motherLastSeen = System.currentTimeMillis();
                lineAlive = true;
                motherTreeId = tree.id;
                tree.motherTree = true;
                tree.lineage = motherId;
            } else {
                tree.lineage = UUID.randomUUID(); // a barren child of no house
            }
        }
        save();
    }

    /** The big-stage roll: elder seeds breed true; plain seeds face the curve. */
    private static String rollBigTemplate(ServerLevel level, Tree tree, boolean natural) {
        var manager = level.getServer().getStructureManager();
        List<String> bigs = StudioMode.approvedTemplates("big_tree", manager, "default");
        if (bigs.isEmpty()) return "";
        String common = bigs.stream().filter(n -> !n.equals(elderTemplateName)).findFirst()
                .orElse(bigs.get(0));
        if (elderTemplateName.isEmpty()) return common;

        if (tree.lineage != null) {
            // Elder-lineage seed: breeds true while the ledger honors it.
            if (lineAlive) {
                tree.elder = true;
                return elderTemplateName;
            }
            tree.lineage = null; // relic of a dead house — grows ordinary
        }
        if (!natural) return common; // commands never roll fortune
        double f = tree.totalTicks == 0 ? 0 : (double) tree.vigilTicks / tree.totalTicks;
        double p = 0.025 + 0.725 * Math.pow(Math.min(1.0, f / 0.95), 6);
        if (level.getRandom().nextDouble() < p) {
            tree.elder = true;
            return elderTemplateName;
        }
        return common;
    }

    private static void pasteStage(ServerLevel level, Tree tree, StructureTemplate template,
                                   String category, String name) {
        int w = StudioMode.widthOfCategory(category);
        int below = StudioMode.belowGradeOf(template, category);
        BlockPos origin = new BlockPos(tree.base.getX() - w / 2,
                tree.base.getY() - below, tree.base.getZ() - w / 2);
        Rotation rot = switch (tree.rotIdx) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        var settings = new StructurePlaceSettings().setRotation(rot)
                .setRotationPivot(new BlockPos(w / 2, 0, w / 2));
        template.placeInWorld(level, origin, origin, settings,
                RandomSource.create(tree.id.getLeastSignificantBits()), 2);
        tree.template = name;

        // Record what the paste actually produced: the tree's box was clear
        // before, so any solid block in it now IS the tree. Grass and other
        // replaceables inside the box stay unrecorded — stepping on flowers
        // never fells anyone's tree.
        tree.blocks.clear();
        int h = StudioMode.heightOfCategory(category);
        for (int x = origin.getX(); x < origin.getX() + w; x++) {
            for (int z = origin.getZ(); z < origin.getZ() + w; z++) {
                for (int y = tree.base.getY(); y < tree.base.getY() + h; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(pos);
                    if (!s.isAir() && !s.canBeReplaced()) {
                        tree.blocks.add(pos.asLong());
                    }
                }
            }
        }
    }

    /** Everything above ground in the tree's box must be replaceable (or its own old self). */
    private static boolean clearanceAt(ServerLevel level, Tree tree, int w, int h, BlockPos base) {
        for (int x = base.getX() - w / 2; x < base.getX() - w / 2 + w; x++) {
            for (int z = base.getZ() - w / 2; z < base.getZ() - w / 2 + w; z++) {
                for (int y = base.getY(); y < base.getY() + h; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(pos);
                    if (s.isAir() || s.canBeReplaced()) continue;
                    // The seedling's own creep never blocks its tree.
                    if (s.is(Blocks.SCULK_VEIN)) continue;
                    if (tree.blocks.contains(pos.asLong())) continue;
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- fruit

    private static void fruitTick(ServerLevel level, Tree tree, int ticks) {
        if (tree.stage < 2 && !tree.wild) return;
        if (!tree.anchorsScanned) {
            scanAnchors(level, tree);
        }
        if (tree.anchors.isEmpty()) return;

        if (tree.fruits.isEmpty()) {
            if (tree.dormant) {
                tree.dormantTicks += ticks;
                if (tree.dormantTicks < CharonConfig.orchardDormancyTicks) return;
                tree.dormant = false;
                tree.dormantTicks = 0;
            }
            startCycle(level, tree);
            return;
        }

        boolean allDone = true;
        for (Fruit f : tree.fruits) {
            if (f.harvested) continue;
            allDone = false;
            if (f.ripe) continue;
            f.ticks += ticks;
            if (f.phase < VEIN_FACES && f.ticks >= CharonConfig.orchardFruitFaceTicks) {
                f.ticks = 0;
                f.phase++;
                placeVein(level, f.pos, f.phase);
            } else if (f.phase >= VEIN_FACES && f.ticks >= CharonConfig.orchardFruitSealTicks) {
                ripen(level, f);
            }
        }
        if (allDone) {
            tree.fruits.clear();
            tree.dormant = true;
            tree.dormantTicks = 0;
            save();
        }
    }

    /** Roll the bud count and hang roots on the chains. Elders always set every chain. */
    private static void startCycle(ServerLevel level, Tree tree) {
        int count;
        if (tree.elder) {
            count = tree.anchors.size();
        } else {
            int roll = level.getRandom().nextInt(100);
            count = roll < 40 ? 1 : roll < 70 ? 2 : roll < 90 ? 3 : 4;
            count = Math.min(count, tree.anchors.size());
        }
        List<BlockPos> spots = new ArrayList<>(tree.anchors);
        while (spots.size() > count) {
            spots.remove(level.getRandom().nextInt(spots.size()));
        }
        for (BlockPos pos : spots) {
            if (!level.getBlockState(pos).canBeReplaced() && !level.getBlockState(pos).isAir()) continue;
            level.setBlock(pos, Blocks.MANGROVE_ROOTS.defaultBlockState(), 3);
            Fruit f = new Fruit(pos.immutable());
            tree.fruits.add(f);
        }
        save();
    }

    /**
     * Fruit anchors: the cell below the bottom link of any chain run no longer
     * than two links whose top link hangs from something solid.
     */
    private static void scanAnchors(ServerLevel level, Tree tree) {
        tree.anchors.clear();
        Set<Long> seen = new HashSet<>();
        for (long l : tree.blocks) {
            BlockPos pos = BlockPos.of(l);
            if (!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.CHAINS)) continue;
            // Walk to the run's top link.
            BlockPos top = pos;
            while (level.getBlockState(top.above()).is(net.minecraft.tags.BlockTags.CHAINS)) top = top.above();
            if (!seen.add(top.asLong())) continue;
            BlockState above = level.getBlockState(top.above());
            if (above.isAir() || above.canBeReplaced()) continue;
            int len = 1;
            BlockPos bottom = top;
            while (level.getBlockState(bottom.below()).is(net.minecraft.tags.BlockTags.CHAINS)) {
                bottom = bottom.below();
                len++;
            }
            if (len > 2) continue;
            BlockPos anchor = bottom.below();
            BlockState at = level.getBlockState(anchor);
            if (at.isAir() || at.canBeReplaced() || at.is(Blocks.MANGROVE_ROOTS)
                    || at.is(Blocks.OCHRE_FROGLIGHT)) {
                tree.anchors.add(anchor.immutable());
            }
        }
        tree.anchorsScanned = true;
    }

    /** Sculk closes over the fruit one face at a time — sides first, then below. Never the top. */
    private static void placeVein(ServerLevel level, BlockPos fruit, int face) {
        record Spot(Direction toNeighbor, net.minecraft.world.level.block.state.properties.BooleanProperty backFace) {}
        List<Spot> spots = List.of(
                new Spot(Direction.WEST, BlockStateProperties.EAST),
                new Spot(Direction.EAST, BlockStateProperties.WEST),
                new Spot(Direction.NORTH, BlockStateProperties.SOUTH),
                new Spot(Direction.SOUTH, BlockStateProperties.NORTH),
                new Spot(Direction.DOWN, BlockStateProperties.UP));
        Spot s = spots.get(Math.min(face, spots.size()) - 1);
        BlockPos at = fruit.relative(s.toNeighbor());
        BlockState existing = level.getBlockState(at);
        if (existing.isAir() || existing.is(Blocks.SCULK_VEIN)) {
            BlockState vein = existing.is(Blocks.SCULK_VEIN) ? existing
                    : Blocks.SCULK_VEIN.defaultBlockState();
            level.setBlock(at, vein.setValue(s.backFace(), true), 3);
        }
        level.playSound(null, fruit, SoundEvents.SCULK_VEIN_PLACE, SoundSource.BLOCKS, 0.6f, 0.8f);
    }

    private static void ripen(ServerLevel level, Fruit f) {
        clearVeins(level, f.pos);
        level.setBlock(f.pos, Blocks.OCHRE_FROGLIGHT.defaultBlockState(), 3);
        f.ripe = true;
        level.playSound(null, f.pos, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 0.8f, 1.4f);
        save();
    }

    private static void clearVeins(ServerLevel level, BlockPos fruit) {
        for (Direction d : Direction.values()) {
            BlockPos at = fruit.relative(d);
            if (level.getBlockState(at).is(Blocks.SCULK_VEIN)) {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    // ---------------------------------------------------------------- breaking

    public static boolean isRipeFruit(Level world, BlockPos pos) {
        for (Tree tree : TREES) {
            if (tree.dim != world.dimension()) continue;
            for (Fruit f : tree.fruits) {
                if (f.ripe && !f.harvested && f.pos.equals(pos)) return true;
            }
        }
        return false;
    }

    private static boolean onBreak(Level world, net.minecraft.world.entity.player.Player player,
            BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(player instanceof ServerPlayer sp)) return true;
        ServerLevel level = (ServerLevel) world;
        for (Tree tree : TREES) {
            if (tree.dim != world.dimension()) continue;

            // Ripe fruit is the one block anyone may take, on any tree.
            for (Fruit f : tree.fruits) {
                if (f.ripe && !f.harvested && f.pos.equals(pos)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    drop(level, pos, StygianItems.tollfruit(1));
                    f.harvested = true;
                    level.playSound(null, pos, SoundEvents.FROGLIGHT_BREAK, SoundSource.BLOCKS, 1f, 1f);
                    save();
                    return false;
                }
            }
            if (tree.wild) continue; // the rest of a wild tree is the graveyard's business

            boolean treeBlock = tree.blocks.contains(pos.asLong()) || fruitPart(tree, pos);
            if (!treeBlock) continue;

            if (tree.stage == 0) {
                // A lone seedling: pull it up, take the seed back. No ritual.
                level.setBlock(tree.base, Blocks.AIR.defaultBlockState(), 3);
                drop(level, tree.base, seedFor(tree, false));
                TREES.remove(tree);
                save();
                return false;
            }
            if (FELLING.containsKey(tree.id)) return false;
            if (!sp.getMainHandItem().is(Items.NETHERITE_AXE)) {
                sp.sendSystemMessage(Component.literal("The tree does not yield.")
                        .withStyle(ChatFormatting.DARK_GRAY));
                level.sendParticles(ParticleTypes.SCULK_SOUL, pos.getX() + 0.5,
                        pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.02);
                return false;
            }
            FELLING.put(tree.id, new FellJob(tree));
            level.playSound(null, tree.base, SoundEvents.SCULK_SHRIEKER_SHRIEK,
                    SoundSource.BLOCKS, 1f, 0.5f);
            return false;
        }
        return true;
    }

    private static boolean fruitPart(Tree tree, BlockPos pos) {
        for (Fruit f : tree.fruits) {
            if (f.harvested) continue;
            if (f.pos.equals(pos)) return true;
            for (Direction d : Direction.values()) {
                if (f.pos.relative(d).equals(pos)) return true; // its veins
            }
        }
        return false;
    }

    private static void shudder(ServerLevel level, Tree tree) {
        RandomSource r = level.getRandom();
        Long[] blocks = tree.blocks.toArray(new Long[0]);
        for (int i = 0; i < 12 && blocks.length > 0; i++) {
            BlockPos pos = BlockPos.of(blocks[r.nextInt(blocks.length)]);
            level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, pos.getX() + 0.5,
                    pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.4, 0.4, 0.4, 0.01);
        }
        level.playSound(null, tree.base, SoundEvents.SCULK_BLOCK_BREAK, SoundSource.BLOCKS, 0.8f, 0.6f);
    }

    /** The whole tree crumbles to nothing and gives back only its seed. */
    private static void collapse(ServerLevel level, Tree tree) {
        boolean mint = tree.motherTree && !tree.fruits.isEmpty()
                && tree.fruits.stream().allMatch(f -> f.ripe && !f.harvested)
                && tree.fruits.size() >= tree.anchors.size() && tree.anchors.size() >= 6;
        for (Fruit f : tree.fruits) {
            clearVeins(level, f.pos);
            level.setBlock(f.pos, Blocks.AIR.defaultBlockState(), 3);
        }
        for (long l : tree.blocks) {
            level.setBlock(BlockPos.of(l), Blocks.AIR.defaultBlockState(), 3);
        }
        level.playSound(null, tree.base, SoundEvents.SCULK_BLOCK_BREAK, SoundSource.BLOCKS, 1.2f, 0.4f);
        level.sendParticles(ParticleTypes.SCULK_SOUL, tree.base.getX() + 0.5,
                tree.base.getY() + 2, tree.base.getZ() + 0.5, 40, 2.5, 3.0, 2.5, 0.03);

        drop(level, tree.base, seedFor(tree, mint));
        if (mint) {
            // The mint: felling the fruit-laden mother births one child seed.
            drop(level, tree.base, StygianItems.elderSeed(UUID.randomUUID()));
        }
        if (tree.motherTree) {
            motherTreeId = null; // the mother walks the world as a seed again
        }
        TREES.remove(tree);
        save();
    }

    private static ItemStack seedFor(Tree tree, boolean mint) {
        if (tree.motherTree && lineAlive) {
            return StygianItems.motherSeed(motherId);
        }
        if (tree.elder && tree.lineage != null && lineAlive) {
            return StygianItems.elderSeed(tree.lineage);
        }
        return StygianItems.seed(1);
    }

    private static void drop(ServerLevel level, BlockPos pos, ItemStack stack) {
        level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5,
                pos.getZ() + 0.5, stack));
    }

    // ---------------------------------------------------------------- wild trees

    /** A scatter-placed big tree in the graveyard: it fruits, and that is all. */
    public static void registerWild(ServerLevel level, BlockPos base, String template) {
        Tree tree = new Tree();
        tree.wild = true;
        tree.dim = level.dimension();
        tree.base = base.immutable();
        tree.stage = 2;
        tree.template = template;
        tree.elder = template.equals(elderTemplateName); // never true: wilds exclude the elder
        // Record its blocks from the world so anchors can be found.
        int w = StudioMode.widthOfCategory("big_tree");
        int h = StudioMode.heightOfCategory("big_tree");
        for (int x = base.getX() - w / 2; x < base.getX() - w / 2 + w; x++) {
            for (int z = base.getZ() - w / 2; z < base.getZ() - w / 2 + w; z++) {
                for (int y = base.getY(); y < base.getY() + h; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(pos);
                    if (!s.isAir() && !s.canBeReplaced()) tree.blocks.add(pos.asLong());
                }
            }
        }
        TREES.add(tree);
        save();
    }

    /** rebuild-decor re-scatters the world: wild trees start from nothing again. */
    public static void clearWild() {
        TREES.removeIf(t -> t.wild);
        save();
    }

    public static List<Tree> trees() {
        return List.copyOf(TREES);
    }

    public static Tree nearest(ServerLevel level, BlockPos pos) {
        Tree best = null;
        double bestD = Double.MAX_VALUE;
        for (Tree t : TREES) {
            if (t.dim != level.dimension()) continue;
            double d = t.base.distSqr(pos);
            if (d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    // ---------------------------------------------------------------- admin/test hooks

    /** /charon orchard grow — skip the wait on the nearest tree's next stage. */
    static String debugGrow(ServerLevel level, Tree tree) {
        if (tree.wild) return "That tree is wild — it was born grown.";
        if (tree.stage >= 2) return "Already fully grown.";
        int before = tree.stage;
        advanceStage(level, tree, false);
        return tree.stage > before ? "Stage " + before + " -> " + tree.stage + " (" + tree.template + ")"
                : "It cannot grow — something blocks its space.";
    }

    /** /charon orchard fruit — hurry the nearest tree's fruit along. */
    static String debugFruit(ServerLevel level, Tree tree) {
        if (tree.stage < 2) return "It has no chains yet.";
        if (!tree.anchorsScanned) scanAnchors(level, tree);
        if (tree.anchors.isEmpty()) return "No chains fit for fruit on this tree.";
        if (tree.fruits.isEmpty()) {
            tree.dormant = false;
            tree.dormantTicks = 0;
            startCycle(level, tree);
            return "Buds set: " + tree.fruits.size() + " of " + tree.anchors.size() + " chains.";
        }
        int ripened = 0;
        for (Fruit f : tree.fruits) {
            if (f.harvested || f.ripe) continue;
            while (f.phase < VEIN_FACES) {
                f.phase++;
                placeVein(level, f.pos, f.phase);
            }
            ripen(level, f);
            ripened++;
        }
        return ripened > 0 ? ripened + " fruit ripened." : "All fruit already ripe or taken.";
    }

    static String describe(Tree tree) {
        double f = tree.totalTicks == 0 ? 0 : (double) tree.vigilTicks / tree.totalTicks;
        return (tree.wild ? "wild " : "") + "tree at " + tree.base.toShortString()
                + " stage=" + tree.stage
                + (tree.template.isEmpty() ? "" : " (" + tree.template + ")")
                + (tree.elder ? " ELDER" : "")
                + (tree.motherTree ? " MOTHER" : "")
                + (tree.lineage != null ? " lineage" : "")
                + (tree.wild ? "" : " owner=" + tree.ownerName)
                + (tree.stage < 2 && !tree.wild
                        ? String.format(" growth=%.0f%% vigil=%.0f%%",
                                Math.min(100.0, 100.0 * tree.stageTicks
                                        / (tree.stage == 0 ? CharonConfig.orchardStage1Ticks
                                                           : CharonConfig.orchardStage2Ticks)),
                                f * 100)
                        : " fruits=" + tree.fruits.size() + "/" + tree.anchors.size()
                          + (tree.dormant ? " (resting)" : ""));
    }

    // ---------------------------------------------------------------- persistence

    public static void load(MinecraftServer server) {
        TREES.clear();
        FELLING.clear();
        motherId = null; motherOwner = null; motherOwnerName = "";
        motherLastSeen = 0; lineAlive = false; motherTreeId = null; brokerId = null;
        file = server.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("orchard.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (root.getStringOr("motherId", "").length() > 0) {
                motherId = UUID.fromString(root.getStringOr("motherId", ""));
                motherOwner = UUID.fromString(root.getStringOr("motherOwner", ""));
                motherOwnerName = root.getStringOr("motherOwnerName", "");
                motherLastSeen = root.getLongOr("motherLastSeen", 0);
                lineAlive = root.getBooleanOr("lineAlive", false);
                String mt = root.getStringOr("motherTreeId", "");
                motherTreeId = mt.isEmpty() ? null : UUID.fromString(mt);
            }
            String broker = root.getStringOr("broker", "");
            brokerId = broker.isEmpty() ? null : UUID.fromString(broker);
            for (Tag t : root.getListOrEmpty("trees")) {
                if (!(t instanceof CompoundTag c)) continue;
                Tree tree = new Tree();
                tree.id = UUID.fromString(c.getStringOr("id", UUID.randomUUID().toString()));
                String owner = c.getStringOr("owner", "");
                tree.owner = owner.isEmpty() ? null : UUID.fromString(owner);
                tree.ownerName = c.getStringOr("ownerName", "");
                tree.dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        Identifier.parse(c.getStringOr("dim", "minecraft:overworld")));
                tree.base = BlockPos.of(c.getLongOr("base", 0));
                tree.stage = c.getIntOr("stage", 0);
                tree.template = c.getStringOr("template", "");
                tree.rotIdx = c.getIntOr("rot", 0);
                tree.totalTicks = c.getLongOr("totalTicks", 0);
                tree.vigilTicks = c.getLongOr("vigilTicks", 0);
                tree.stageTicks = c.getLongOr("stageTicks", 0);
                tree.elder = c.getBooleanOr("elder", false);
                tree.motherTree = c.getBooleanOr("motherTree", false);
                String lin = c.getStringOr("lineage", "");
                tree.lineage = lin.isEmpty() ? null : UUID.fromString(lin);
                tree.wild = c.getBooleanOr("wild", false);
                tree.dormant = c.getBooleanOr("dormant", false);
                tree.dormantTicks = c.getLongOr("dormantTicks", 0);
                for (long l : c.getLongArray("blocks").orElse(new long[0])) tree.blocks.add(l);
                for (Tag ft : c.getListOrEmpty("fruits")) {
                    if (!(ft instanceof CompoundTag fc)) continue;
                    Fruit f = new Fruit(BlockPos.of(fc.getLongOr("pos", 0)));
                    f.phase = fc.getIntOr("phase", 0);
                    f.ripe = fc.getBooleanOr("ripe", false);
                    f.harvested = fc.getBooleanOr("harvested", false);
                    f.ticks = fc.getLongOr("ticks", 0);
                    tree.fruits.add(f);
                }
                for (long l : c.getLongArray("anchors").orElse(new long[0])) {
                    tree.anchors.add(BlockPos.of(l));
                }
                tree.anchorsScanned = c.getBooleanOr("anchorsScanned", false);
                TREES.add(tree);
            }
        } catch (Exception e) {
            System.out.println("[CharonsEcho] failed to load orchard.dat: " + e);
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            if (motherId != null) {
                root.putString("motherId", motherId.toString());
                root.putString("motherOwner", motherOwner == null ? "" : motherOwner.toString());
                root.putString("motherOwnerName", motherOwnerName);
                root.putLong("motherLastSeen", motherLastSeen);
                root.putBoolean("lineAlive", lineAlive);
                root.putString("motherTreeId", motherTreeId == null ? "" : motherTreeId.toString());
            }
            if (brokerId != null) root.putString("broker", brokerId.toString());
            ListTag list = new ListTag();
            for (Tree tree : TREES) {
                CompoundTag c = new CompoundTag();
                c.putString("id", tree.id.toString());
                c.putString("owner", tree.owner == null ? "" : tree.owner.toString());
                c.putString("ownerName", tree.ownerName);
                c.putString("dim", tree.dim.identifier().toString());
                c.putLong("base", tree.base.asLong());
                c.putInt("stage", tree.stage);
                c.putString("template", tree.template);
                c.putInt("rot", tree.rotIdx);
                c.putLong("totalTicks", tree.totalTicks);
                c.putLong("vigilTicks", tree.vigilTicks);
                c.putLong("stageTicks", Math.min(tree.stageTicks, Long.MAX_VALUE / 2));
                c.putBoolean("elder", tree.elder);
                c.putBoolean("motherTree", tree.motherTree);
                c.putString("lineage", tree.lineage == null ? "" : tree.lineage.toString());
                c.putBoolean("wild", tree.wild);
                c.putBoolean("dormant", tree.dormant);
                c.putLong("dormantTicks", tree.dormantTicks);
                c.putLongArray("blocks", tree.blocks.stream().mapToLong(Long::longValue).toArray());
                ListTag fl = new ListTag();
                for (Fruit f : tree.fruits) {
                    CompoundTag fc = new CompoundTag();
                    fc.putLong("pos", f.pos.asLong());
                    fc.putInt("phase", f.phase);
                    fc.putBoolean("ripe", f.ripe);
                    fc.putBoolean("harvested", f.harvested);
                    fc.putLong("ticks", f.ticks);
                    fl.add(fc);
                }
                c.put("fruits", fl);
                c.putLongArray("anchors", tree.anchors.stream().mapToLong(BlockPos::asLong).toArray());
                c.putBoolean("anchorsScanned", tree.anchorsScanned);
                list.add(c);
            }
            root.put("trees", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save orchard.dat: " + e);
        }
    }

    private static void clearRecorded(ServerLevel level, Tree tree) {
        for (long l : tree.blocks) {
            level.setBlock(BlockPos.of(l), Blocks.AIR.defaultBlockState(), 2);
        }
        tree.blocks.clear();
    }
}
