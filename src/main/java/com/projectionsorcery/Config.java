package com.projectionsorcery;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    static { B.push("replay"); }
    public static final ModConfigSpec.IntValue REPLAY_FRAMES_PER_TICK = B
            .comment("How many planned frames are replayed per server tick")
            .defineInRange("replayFramesPerTick", 4, 1, 1000);
    public static final ModConfigSpec.DoubleValue ATTACK_DAMAGE = B
            .comment("Damage dealt by each attack frame")
            .defineInRange("attackDamage", 40.0, 0.0, 1_000_000.0);
    public static final ModConfigSpec.DoubleValue ATTACK_REACH = B
            .comment("Reach of an attack frame in blocks (vanilla is 3)")
            .defineInRange("attackReach", 3.5, 1.0, 16.0);
    public static final ModConfigSpec.DoubleValue MAX_BLAST_RESISTANCE = B
            .comment("Blocks with a blast resistance above this (and unbreakable blocks) cannot be insta-mined")
            .defineInRange("maxBlastResistance", 32.0, 0.0, 100_000.0);
    public static final ModConfigSpec.DoubleValue QUICK_MOVE_DISTANCE = B
            .comment("Max distance of the quick move, in blocks")
            .defineInRange("quickMoveDistance", 32.0, 1.0, 256.0);
    public static final ModConfigSpec.IntValue EXPLOSION_MIN_FRAMES = B
            .comment("Replay explosions (TNT / charged creeper) only happen when the plan has at least this many frames")
            .defineInRange("explosionMinFrames", 1000, 0, 1_000_000);
    public static final ModConfigSpec.DoubleValue FRAME_SOUND_VOLUME = B
            .comment("Volume of the 'frame passed' sound during replay")
            .defineInRange("frameSoundVolume", 0.5, 0.0, 2.0);
    static { B.pop(); B.push("planning"); }
    public static final ModConfigSpec.DoubleValue JUMP_HUNGER_COST = B
            .comment("Hunger points drained by each in-air jump while planning")
            .defineInRange("jumpHungerCost", 0.5, 0.0, 20.0);
    public static final ModConfigSpec.DoubleValue GHOST_SPACING = B
            .comment("Minimum distance (blocks) between afterimage ghosts")
            .defineInRange("ghostSpacing", 2.0, 0.5, 16.0);
    public static final ModConfigSpec.IntValue MAX_GHOSTS = B
            .comment("Maximum number of afterimage ghosts rendered at once")
            .defineInRange("maxGhosts", 60, 1, 500);
    public static final ModConfigSpec.DoubleValue TARGET_RANGE = B
            .comment("How far (blocks) the Target key can reach")
            .defineInRange("targetRange", 50.0, 1.0, 256.0);
    public static final ModConfigSpec.IntValue TARGET_PATH_UPDATE_TICKS = B
            .comment("How often (ticks) the shown target path is recomputed while you or the target move")
            .defineInRange("targetPathUpdateTicks", 5, 1, 200);
    public static final ModConfigSpec.DoubleValue GHOST_CLEAR_DISTANCE = B
            .comment("A replayed ghost disappears once the player is within this many blocks")
            .defineInRange("ghostClearDistance", 2.0, 0.5, 16.0);
    static { B.pop(); B.push("cursedEnergy"); }
    public static final ModConfigSpec.BooleanValue CE_INFINITE = B
            .comment("Infinite cursed energy. If false, the costs below apply")
            .define("infinite", true);
    public static final ModConfigSpec.IntValue CE_MAX = B
            .defineInRange("max", 10000, 1, 1_000_000_000);
    public static final ModConfigSpec.IntValue CE_REGEN_PER_TICK = B
            .defineInRange("regenPerTick", 5, 0, 1_000_000);
    public static final ModConfigSpec.IntValue FRAME_COST = B
            .comment("CE per replayed frame").defineInRange("frameCost", 15, 0, 1_000_000);
    public static final ModConfigSpec.IntValue ATTACK_COST = B
            .comment("Extra CE per attack frame").defineInRange("attackCost", 45, 0, 1_000_000);
    public static final ModConfigSpec.IntValue BLOCK_COST = B
            .comment("Extra CE per broken block").defineInRange("blockCost", 150, 0, 1_000_000);
    static { B.pop(); B.push("multiplayerLimits"); }
    public static final ModConfigSpec.IntValue LIMIT_PLAYER_THRESHOLD = B
            .comment("Limits below apply when MORE than this many players are online")
            .defineInRange("playerThreshold", 2, 0, 1000);
    public static final ModConfigSpec.IntValue LIMIT_COOLDOWN_SECONDS = B
            .defineInRange("cooldownSeconds", 5, 0, 3600);
    public static final ModConfigSpec.IntValue LIMIT_MAX_PLAN_SECONDS = B
            .defineInRange("maxPlanSeconds", 3, 1, 3600);
    static { B.pop(); B.push("touch"); }
    public static final ModConfigSpec.DoubleValue TOUCH_RANGE = B
            .comment("Max distance (blocks) to the victim for a touch to land / to start a touch plan")
            .defineInRange("range", 3.0, 1.0, 16.0);
    public static final ModConfigSpec.DoubleValue TOUCH_MAX_BLOCKS = B
            .comment("Max distance the victim's plan may cover")
            .defineInRange("maxBlocks", 10.0, 1.0, 100.0);
    public static final ModConfigSpec.IntValue TOUCH_AWARE_TICKS = B
            .comment("Ticks a player victim gets to realize what happened (40 = 2s)")
            .defineInRange("awareTicks", 40, 0, 1200);
    public static final ModConfigSpec.IntValue TOUCH_STEP_TICKS = B
            .comment("Ticks allowed per planned block (20 = 1s)")
            .defineInRange("stepTicks", 20, 1, 1200);
    static { B.pop(); B.push("pathValidation"); }
    public static final ModConfigSpec.BooleanValue PATH_VALIDATION = B
            .comment("Server checks every replayed frame against a simulated, physically possible course")
            .define("enabled", true);
    public static final ModConfigSpec.DoubleValue PATH_TOLERANCE = B
            .comment("Max blocks a frame may be away from the simulated course")
            .defineInRange("tolerance", 3.0, 0.0, 64.0);
    public static final ModConfigSpec.DoubleValue PATH_TOLERANCE_JUMPING = B
            .comment("Same, while the player is jumping / in the air")
            .defineInRange("toleranceJumping", 5.0, 0.0, 64.0);
    static { B.pop(); B.push("freeze"); }
    public static final ModConfigSpec.IntValue FREEZE_TICKS = B
            .comment("Duration of the glass freeze (20 = 1s)")
            .defineInRange("ticks", 20, 1, 1200);
    static { B.pop(); }

    public static final ModConfigSpec SPEC = B.build();
}
