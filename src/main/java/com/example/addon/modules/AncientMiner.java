package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * AncientMiner - Minecraft 26.2 / Meteor 26.2 (Mojang mappings).
 *
 * Baritone wird ueber Meteors PathManagers gesteuert (kein Chat, keine Compile-Abhaengigkeit).
 * Baritone-Defaults genutzt: legitMine=false (Xray-artiges Scannen), avoidance=true (Lava/Mobs).
 *
 * Ablauf:
 *  MINING    -> Baritone mined ancient_debris
 *  REPAIR    -> Pickaxe niedrig: Pickaxe in Offhand, XP-Flaschen nach unten werfen (Mending)
 *  HOME_WAIT -> Stack voll: /home ancient
 *  CHEST     -> Kiste oeffnen, Debris rein, 1 Stueck behalten
 *  RTP_SEND  -> Teleport-Cooldown abwarten, dann /rtp nether
 *  RTP_WAIT  -> auf Teleport warten
 *  DESCEND   -> Baritone zu Y15 graben, danach wieder MINING
 */
public class AncientMiner extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> repairBelow = sg.add(new IntSetting.Builder()
        .name("repair-below-percent").description("Unter diesem Haltbarkeits-Prozentwert wird repariert.")
        .defaultValue(15).range(1, 90).sliderRange(1, 90).build());

    private final Setting<Integer> repairUntil = sg.add(new IntSetting.Builder()
        .name("repair-until-percent").description("Repariert bis zu diesem Haltbarkeits-Prozentwert.")
        .defaultValue(95).range(10, 100).sliderRange(10, 100).build());

    private final Setting<Integer> throwDelay = sg.add(new IntSetting.Builder()
        .name("xp-throw-delay-ticks").description("Ticks zwischen zwei XP-Flaschen.")
        .defaultValue(3).range(1, 20).build());

    private final Setting<Integer> stashAt = sg.add(new IntSetting.Builder()
        .name("stash-at-amount").description("Ab so vielen Ancient Debris im Inventar wird gelagert (64 = 1 Stack).")
        .defaultValue(64).range(1, 2304).sliderRange(1, 256).build());

    private final Setting<String> stashHome = sg.add(new StringSetting.Builder()
        .name("stash-home").description("Name des Homes bei der Kiste.")
        .defaultValue("ancient").build());

    private final Setting<String> rtpCommand = sg.add(new StringSetting.Builder()
        .name("rtp-command").description("Serverbefehl OHNE fuehrenden Slash.")
        .defaultValue("rtp nether").build());

    private final Setting<Integer> targetY = sg.add(new IntSetting.Builder()
        .name("mining-y").description("Auf diese Hoehe mined er sich nach dem RTP runter.")
        .defaultValue(15).range(1, 120).sliderRange(1, 120).build());

    private final Setting<Integer> tpCooldown = sg.add(new IntSetting.Builder()
        .name("tp-cooldown-sec").description("Cooldown zwischen zwei Teleports in Sekunden.")
        .defaultValue(15).range(0, 120).build());

    private final Setting<Integer> tpWarmup = sg.add(new IntSetting.Builder()
        .name("tp-warmup-sec").description("Dauer des Teleports selbst in Sekunden (stehen bleiben).")
        .defaultValue(3).range(0, 30).build());

    private final Setting<Integer> clickDelay = sg.add(new IntSetting.Builder()
        .name("click-delay-ticks").description("Ticks zwischen Kisten-Klicks.")
        .defaultValue(2).range(1, 10).build());

    private final Setting<Integer> minHealth = sg.add(new IntSetting.Builder()
        .name("disconnect-health")
        .description("Bei so wenig HP (Herzhaelften) UND ohne Totems: disconnecten. 0 = aus.")
        .defaultValue(6).range(0, 19).build());

    private final Setting<Integer> lavaTimeout = sg.add(new IntSetting.Builder()
        .name("lava-timeout-sec")
        .description("Kommt er so lange nicht aus der Lava, macht er einen neuen RTP.")
        .defaultValue(10).range(3, 60).build());

    private enum State { MINING, REPAIR_PREP, REPAIR, HOME_WAIT, CHEST, RTP_SEND, RTP_WAIT, DESCEND }

    private State state = State.MINING;
    private long ticks;
    private int timer;
    private int throwTimer;
    private int totemCooldown;
    private long lastTpTick;
    private int rtpAttempts;
    private int rtpWaitTicks;
    private int homeRetries;
    private int pickSlot = -1;
    private boolean returnToDescend;

    private boolean chestOpened;
    private boolean queueBuilt;
    private final Queue<Runnable> clicks = new ArrayDeque<>();

    // Teleport-Erkennung: grosser Positionssprung innerhalb eines Ticks
    private Vec3 prevPos;
    private boolean tpDetected;

    // Stillstand-Erkennung
    private Vec3 lastPos;
    private int idleTicks;
    private boolean descendStarted;

    // Lava-Ausweg
    private int lavaTicks;
    private int noLavaTicks;
    private boolean lavaActive;
    private boolean lavaRtp;
    private State lavaPrevState = State.MINING;
    private BlockPos safeSpot;

    public AncientMiner() {
        super(AddonTemplate.CATEGORY, "ancient-miner",
            "Mined automatisch Ancient Debris, repariert die Pickaxe und lagert ein.");
    }

    // ---------------------------------------------------------------- Lifecycle
    @Override
    public void onActivate() {
        if (!BaritoneUtils.IS_AVAILABLE) {
            error("Baritone wurde nicht gefunden! Installiere baritone-standalone-fabric in den mods-Ordner.");
            toggle();
            return;
        }
        state = State.MINING;
        clicks.clear();
        prevPos = null;
        tpDetected = false;
        lastTpTick = -100000;
        lavaTicks = 0;
        noLavaTicks = 0;
        lavaActive = false;
        lavaRtp = false;
        if (mc.player != null && !mc.player.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            warning("Du hast keine Feuerresistenz! In Lava stirbst du in wenigen Sekunden.");
        }
        startMining();
    }

    @Override
    public void onDeactivate() {
        if (BaritoneUtils.IS_AVAILABLE) PathManagers.get().stop();
        clicks.clear();
        releaseKeys();
    }

    private void startMining() {
        PathManagers.get().mine(Blocks.ANCIENT_DEBRIS);
        idleTicks = 0;
    }

    private void sendServerCommand(String cmd) {
        ChatUtils.sendPlayerMsg("/" + cmd);
    }

    // ---------------------------------------------------------------- Tick
    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (mc.player == null || mc.level == null) return;
        ticks++;

        // Teleport-Erkennung (laeuft jeden Tick)
        Vec3 pos = mc.player.position();
        if (prevPos != null && pos.distanceToSqr(prevPos.x, prevPos.y, prevPos.z) > 25) tpDetected = true;
        prevPos = pos;

        // Totem nach Pop wieder in die Offhand (nicht waehrend der Reparatur, da liegt dort die Pickaxe)
        if (totemCooldown > 0) totemCooldown--;
        if (totemCooldown == 0 && state != State.REPAIR && state != State.REPAIR_PREP
            && !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            && mc.gui.screen() == null) {
            FindItemResult t = InvUtils.find(Items.TOTEM_OF_UNDYING);
            if (t.found() && !t.isOffhand()) {
                InvUtils.move().from(t.slot()).toOffhand();
                totemCooldown = 5;
            }
        }

        // Notfall: wenig HP UND keine Totems mehr -> raus. Mit Totems ist ein Pop egal.
        if (minHealth.get() > 0 && mc.player.getHealth() <= minHealth.get() && totemCount() == 0) {
            PathManagers.get().stop();
            mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(
                Component.literal("[AncientMiner] HP niedrig und keine Totems mehr")));
            toggle();
            return;
        }

        // Lava: raus schwimmen, nach lava-timeout-sec neuer RTP. true = normale Logik pausieren
        if (handleLava()) return;

        if (timer > 0) { timer--; return; }

        switch (state) {
            case MINING -> tickMining();
            case REPAIR_PREP -> tickRepairPrep();
            case REPAIR -> tickRepair();
            case HOME_WAIT -> tickHomeWait();
            case CHEST -> tickChest();
            case RTP_SEND -> tickRtpSend();
            case RTP_WAIT -> tickRtpWait();
            case DESCEND -> tickDescend();
        }
    }

    // ---------------------------------------------------------------- MINING
    private void tickMining() {
        if (pickaxeLow()) { beginRepair(false); return; }

        if (countDebris() >= stashAt.get()) {
            PathManagers.get().stop();
            tpDetected = false;
            sendServerCommand("home " + stashHome.get());
            state = State.HOME_WAIT;
            homeRetries = 0;
            timer = 20;
            return;
        }

        // Steht er laenger still und Baritone pathet nicht, Mining neu anstossen
        trackIdle(5);
        if (idleTicks >= 200) startMining(); // ~10 s Stillstand
        timer = 5;
    }

    private void trackIdle(int step) {
        Vec3 p = mc.player.position();
        boolean still = lastPos != null && p.distanceToSqr(lastPos.x, lastPos.y, lastPos.z) < 0.01;
        if (still && !PathManagers.get().isPathing()) idleTicks += step; else idleTicks = 0;
        lastPos = p;
    }

    // ---------------------------------------------------------------- REPAIR
    private void beginRepair(boolean fromDescend) {
        PathManagers.get().stop();
        if (!InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE).found()) {
            error("Pickaxe niedrig, aber keine XP-Flaschen in der Hotbar! Stoppe.");
            toggle();
            return;
        }
        returnToDescend = fromDescend;
        state = State.REPAIR_PREP;
    }

    private void tickRepairPrep() {
        FindItemResult pick = findPickaxe();
        FindItemResult xp = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (!pick.found() || !xp.found()) {
            error("Pickaxe oder XP-Flaschen nicht in der Hotbar gefunden.");
            toggle();
            return;
        }
        pickSlot = pick.slot();
        InvUtils.move().fromHotbar(pickSlot).toOffhand(); // Pickaxe -> Offhand (Mending greift dort)
        InvUtils.swap(xp.slot(), false);                  // XP-Flaschen in die Haupthand
        state = State.REPAIR;
        throwTimer = 0;
        timer = 3;
    }

    private void tickRepair() {
        ItemStack off = mc.player.getOffhandItem();
        boolean repaired = off.getMaxDamage() > 0
            && (100.0 * (off.getMaxDamage() - off.getDamageValue()) / off.getMaxDamage()) >= repairUntil.get();
        boolean noXp = !mc.player.getMainHandItem().is(Items.EXPERIENCE_BOTTLE);
        boolean lowHp = minHealth.get() > 0 && mc.player.getHealth() <= minHealth.get();

        if (repaired || noXp || lowHp) {
            if (noXp && !repaired) warning("Keine XP-Flaschen mehr, Pickaxe nur teilweise repariert.");
            finishRepair();
            return;
        }

        if (throwTimer-- <= 0) {
            Rotations.rotate(mc.player.getYRot(), 90, () -> { // nach unten schauen und werfen
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                mc.player.swing(InteractionHand.MAIN_HAND);
            });
            throwTimer = throwDelay.get();
        }
    }

    private void finishRepair() {
        InvUtils.move().fromOffhand().toHotbar(pickSlot); // Pickaxe zurueck (Totem geht wieder in die Offhand)
        InvUtils.swap(pickSlot, false);
        descendStarted = false;
        if (returnToDescend || mc.player.getBlockY() > targetY.get() + 3) {
            state = State.DESCEND;
        } else {
            state = State.MINING;
            startMining();
        }
        timer = 10;
    }

    // ---------------------------------------------------------------- LAVA
    /** @return true, wenn die normale Zustandslogik in diesem Tick pausieren soll. */
    private boolean handleLava() {
        boolean inLava = mc.player.isInLava();
        boolean freeRoam = state == State.MINING || state == State.DESCEND
            || state == State.REPAIR_PREP || state == State.REPAIR;

        if (inLava) {
            noLavaTicks = 0;
            lavaTicks++;

            if (!lavaActive) {
                lavaActive = true;
                warning("In Lava! Versuche rauszukommen.");
                if (freeRoam) {
                    PathManagers.get().stop();
                    if (state == State.REPAIR) finishRepair();      // Pickaxe zurueck in die Hand
                    else if (state == State.REPAIR_PREP) state = State.MINING;
                }
            }

            // Waehrend Teleport-Warmup / Kiste stillhalten, sonst wird der Teleport abgebrochen
            if (state == State.CHEST || state == State.HOME_WAIT || state == State.RTP_WAIT) {
                releaseKeys();
                return false;
            }

            if (lavaTicks % 10 == 1) safeSpot = findSafeSpot();
            swimToSafety();

            if (freeRoam && lavaTicks >= lavaTimeout.get() * 20) {
                warning("Seit " + lavaTimeout.get() + " s in Lava - neuer RTP.");
                lavaPrevState = state == State.DESCEND ? State.DESCEND : State.MINING;
                lavaRtp = true;
                PathManagers.get().stop();
                rtpAttempts = 0;
                lavaTicks = 0;
                state = State.RTP_SEND;
                timer = 0;
                return false;
            }
            return freeRoam;
        }

        if (!lavaActive) {
            lavaTicks = 0;
            return false;
        }

        // Aus der Lava raus: kurz abwarten, dann weitermachen
        if (++noLavaTicks <= 20) return freeRoam;

        lavaActive = false;
        lavaTicks = 0;
        noLavaTicks = 0;
        releaseKeys();
        info("Aus der Lava raus.");

        if (lavaRtp && state == State.RTP_SEND) {
            // Er hat es selbst geschafft, bevor der RTP rausging -> RTP abbrechen
            lavaRtp = false;
            state = lavaPrevState;
        }
        if (state == State.MINING) startMining();
        else if (state == State.DESCEND) descendStarted = false;
        timer = 10;
        return false;
    }

    private void swimToSafety() {
        mc.options.keyJump.setDown(true);
        if (safeSpot == null) return;
        Vec3 c = Vec3.atCenterOf(safeSpot);
        Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c));
        mc.options.keyUp.setDown(true);
    }

    private void releaseKeys() {
        if (mc.options == null) return;
        mc.options.keyJump.setDown(false);
        mc.options.keyUp.setDown(false);
    }

    /** Naechster Block im Umkreis, auf dem man stehen kann (fester Boden, 2 Luft darueber, keine Lava). */
    private BlockPos findSafeSpot() {
        BlockPos p = mc.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    BlockPos pos = new BlockPos(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    var feet = mc.level.getBlockState(pos);
                    if (!feet.isAir()) continue;
                    if (!mc.level.getBlockState(pos.above()).isAir()) continue;
                    var below = mc.level.getBlockState(pos.below());
                    if (below.isAir() || !below.getFluidState().isEmpty()) continue;
                    double d = pos.distSqr(p);
                    if (d < bestDist) { bestDist = d; best = pos; }
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- HOME / CHEST
    private void tickHomeWait() {
        if (tpDetected) {
            lastTpTick = ticks;
            state = State.CHEST;
            chestOpened = false;
            queueBuilt = false;
            clicks.clear();
            homeRetries = 0;
            timer = 40; // Chunks laden lassen
            return;
        }
        if (++homeRetries > 15) {
            error("Teleport zu /home " + stashHome.get() + " fehlgeschlagen.");
            toggle();
            return;
        }
        timer = 20;
    }

    private void tickChest() {
        // 1) Kiste oeffnen
        if (!chestOpened) {
            BlockPos chest = findChest();
            if (chest == null) {
                error("Keine Kiste/Fass in 6 Bloecken Umkreis gefunden.");
                toggle();
                return;
            }
            Vec3 c = Vec3.atCenterOf(chest);
            Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c), () ->
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(c, Direction.UP, chest, false)));
            chestOpened = true;
            homeRetries = 0;
            timer = 15;
            return;
        }

        // 2) Warten bis der Container offen ist
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            if (++homeRetries > 40) { chestOpened = false; homeRetries = 0; }
            timer = 5;
            return;
        }

        // 3) Klick-Queue einmalig aufbauen und abarbeiten
        if (!queueBuilt) buildQueue(menu);
        if (!clicks.isEmpty()) {
            clicks.poll().run();
            timer = clickDelay.get();
            return;
        }

        // 4) fertig -> schliessen, dann RTP
        mc.gui.setScreen(null);
        queueBuilt = false;
        rtpAttempts = 0;
        state = State.RTP_SEND;
        timer = 5;
    }

    private void buildQueue(ChestMenu menu) {
        queueBuilt = true;
        int chestSize = menu.getRowCount() * 9;
        int sync = menu.containerId;
        boolean keptOne = false;

        int emptyChestSlot = -1;
        for (int i = 0; i < chestSize; i++) {
            if (menu.getSlot(i).getItem().isEmpty()) { emptyChestSlot = i; break; }
        }
        if (emptyChestSlot < 0) warning("Kiste hat keinen freien Slot - Ancient Debris wird nur zusammengelegt.");

        for (int i = chestSize; i < menu.slots.size(); i++) {
            if (!menu.getSlot(i).getItem().is(Items.ANCIENT_DEBRIS)) continue;
            final int idx = i;

            if (!keptOne && emptyChestSlot >= 0) {
                keptOne = true;
                final int target = emptyChestSlot;
                // Stack aufnehmen, 1 Stueck per Rechtsklick zurueck legen, Rest in den freien Kistenslot
                clicks.add(() -> click(sync, idx, 0, ContainerInput.PICKUP));
                clicks.add(() -> click(sync, idx, 1, ContainerInput.PICKUP));
                clicks.add(() -> click(sync, target, 0, ContainerInput.PICKUP));
            } else {
                clicks.add(() -> click(sync, idx, 0, ContainerInput.QUICK_MOVE));
            }
        }
    }

    private void click(int sync, int slot, int button, ContainerInput type) {
        mc.gameMode.handleContainerInput(sync, slot, button, type, mc.player);
    }

    // ---------------------------------------------------------------- RTP
    /** Wartet, bis der Cooldown seit dem letzten Teleport vorbei ist, und sendet dann den RTP. */
    private void tickRtpSend() {
        long cooldownTicks = tpCooldown.get() * 20L + 10; // 0,5 s Puffer
        long remaining = cooldownTicks - (ticks - lastTpTick);
        if (remaining > 0) { timer = (int) Math.min(remaining, 20); return; }

        if (++rtpAttempts > 4) {
            error("RTP schlaegt immer wieder fehl.");
            toggle();
            return;
        }
        tpDetected = false;
        sendServerCommand(rtpCommand.get());
        rtpWaitTicks = 0;
        state = State.RTP_WAIT;
        timer = 10;
    }

    private void tickRtpWait() {
        if (tpDetected) {
            lastTpTick = ticks;
            lavaRtp = false;
            state = State.DESCEND;
            descendStarted = false;
            timer = 40; // Chunks laden lassen
            return;
        }
        rtpWaitTicks += 10;
        // Warmup + 5 s Puffer, danach gilt der RTP als gescheitert -> Cooldown abwarten, erneut versuchen
        if (rtpWaitTicks > (tpWarmup.get() + 5) * 20) {
            lastTpTick = ticks;
            state = State.RTP_SEND;
        }
        timer = 10;
    }

    // ---------------------------------------------------------------- DESCEND
    /** Baritone gräbt sich zur Ziel-Y-Hoehe, danach normales Debris-Mining. */
    private void tickDescend() {
        if (pickaxeLow()) { beginRepair(true); return; }

        if (mc.player.getBlockY() <= targetY.get()) {
            PathManagers.get().stop();
            state = State.MINING;
            timer = 10;
            startMining();
            return;
        }

        trackIdle(10);
        if (!descendStarted || idleTicks >= 100) { // beim Start bzw. ~5 s Stillstand neu anstossen
            BlockPos here = mc.player.blockPosition();
            PathManagers.get().moveTo(new BlockPos(here.getX(), targetY.get(), here.getZ()));
            descendStarted = true;
            idleTicks = 0;
        }
        timer = 10;
    }

    // ---------------------------------------------------------------- Helfer
    private int totemCount() {
        int n = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? 1 : 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.TOTEM_OF_UNDYING)) n++;
        }
        return n;
    }

    private int countDebris() {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.ANCIENT_DEBRIS)) n += s.getCount();
        }
        return n;
    }

    private FindItemResult findPickaxe() {
        return InvUtils.findInHotbar(s -> s.is(ItemTags.PICKAXES) && s.isDamageableItem());
    }

    private boolean pickaxeLow() {
        FindItemResult r = findPickaxe();
        if (!r.found()) return false;
        ItemStack s = mc.player.getInventory().getItem(r.slot());
        if (s.getMaxDamage() <= 0) return false;
        double pct = 100.0 * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage();
        return pct <= repairBelow.get();
    }

    private BlockPos findChest() {
        BlockPos p = mc.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = -6; x <= 6; x++) {
            for (int y = -6; y <= 6; y++) {
                for (int z = -6; z <= 6; z++) {
                    BlockPos pos = new BlockPos(p.getX() + x, p.getY() + y, p.getZ() + z);
                    Block b = mc.level.getBlockState(pos).getBlock();
                    if (b instanceof ChestBlock || b instanceof BarrelBlock) {
                        double d = pos.distSqr(p);
                        if (d < bestDist) { bestDist = d; best = pos; }
                    }
                }
            }
        }
        return best;
    }
}
