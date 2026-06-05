package dev.havester;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class Havester implements ClientModInitializer {
    private static final int PADDING = 6;
    private static final int COLOR = 0xFFFFFF;
    private static final int STATUS_COLOR = 0x55FF55;
    private static final int SEARCH_RADIUS = 32;
    private static final double BREAK_RANGE = 5.0D;
    private static final double BREAK_RANGE_SQUARED = BREAK_RANGE * BREAK_RANGE;
    private static final double COLLECT_DISTANCE_SQUARED = 1.8D;
    private static final int MAX_PATH_NODES = 6000;
    private static final int STUCK_TICKS = 100;
    private static final int SKIP_TICKS = 200;
    private static final int NO_BAMBOO_RETRY_TICKS = 200;
    private static final Map<BlockPos, Long> bambooSkipMap = new HashMap<>();
    private static final Map<Integer, Long> itemSkipMap = new HashMap<>();
    private static long currentTick;

    private static KeyBinding bambooToggleKey;
    private static KeyBinding bambooSettingsKey;
    private static KeyBinding unpauseToggleKey;
    private static boolean unpauseEnabled = true;
    private static boolean bambooCutterActive;
    private static boolean holdWalkEnabled = true;
    private static boolean holdJumpEnabled = true;
    private static int minBambooHeight = 3;
    private static int sellThresholdStacks = 5;
    private static CutterState cutterState = CutterState.IDLE;
    private static BlockPos bambooTarget;
    private static BlockPos breakingTarget;
    private static BlockPos lockedBambooTarget;
    private static Vec3d collectTarget;
    private static List<BlockPos> currentPath = List.of();
    private static int pathIndex;
    private static int repathCooldown;
    private static int swingCooldown;
    private static int stuckTicks;
    private static int sellWaitTicks;
    private static int sellAttempts;
    private static int noBambooRetryTicks;
    private static int cutLockTicks;
    private static Vec3d lastPlayerPos;
    private static String statusText = "";
    private static int statusTicks;

    @Override
    public void onInitializeClient() {
        System.out.println("[Havester] FPS HUD loaded");

        bambooToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.havester.bamboo_toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.havester"
        ));
        bambooSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.havester.bamboo_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.havester"
        ));
        unpauseToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.havester.unpause_toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "category.havester"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (bambooToggleKey.wasPressed()) {
                toggleBambooCutter(client);
            }
            while (bambooSettingsKey.wasPressed()) {
                client.setScreen(new BambooSettingsScreen());
            }
            while (unpauseToggleKey.wasPressed()) {
                unpauseEnabled = !unpauseEnabled;
                showStatus(client, unpauseEnabled ? "Unpause: ON" : "Unpause: OFF", 40);
            }

            if (unpauseEnabled && client.options.pauseOnLostFocus) {
                client.options.pauseOnLostFocus = false;
            }

            if (statusTicks > 0) statusTicks--;
            if (bambooCutterActive) tickBambooCutter(client);

        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options.hudHidden || client.textRenderer == null) return;

            String fps = client.getCurrentFps() + " FPS";
            int fpsX = client.getWindow().getScaledWidth() - client.textRenderer.getWidth(fps) - PADDING;
            drawContext.drawTextWithShadow(client.textRenderer, fps, fpsX, PADDING, COLOR);

            if (statusTicks > 0 && !statusText.isEmpty()) {
                int statusX = (client.getWindow().getScaledWidth() - client.textRenderer.getWidth(statusText)) / 2;
                drawContext.drawTextWithShadow(client.textRenderer, statusText, statusX, PADDING, STATUS_COLOR);
            }

            if (client.player != null) {
                String bambooText = "Bamboo: " + countBamboo(client) + "/" + getSellThresholdBamboo();
                int bambooX = (client.getWindow().getScaledWidth() - client.textRenderer.getWidth(bambooText)) / 2;
                drawContext.drawTextWithShadow(client.textRenderer, bambooText, bambooX, PADDING + 12, 0xFFFF55);
            }
        });
    }

    private static void toggleBambooCutter(MinecraftClient client) {
        bambooCutterActive = !bambooCutterActive;
        resetWork();
        if (bambooCutterActive) {
            cutterState = CutterState.FIND_TARGET;
            showStatus(client, "Bamboo Cutter: START", 60);
        } else {
            stopMovement(client);
            showStatus(client, "Bamboo Cutter: END", 60);
        }
    }

    private static void tickBambooCutter(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) return;
        if (repathCooldown > 0) repathCooldown--;
        if (cutLockTicks > 0) cutLockTicks--;
        currentTick++;

        if (!isSellingState(cutterState) && countBamboo(client) >= getSellThresholdBamboo()) {
            stopMovement(client);
            breakingTarget = null;
            currentPath = List.of();
            pathIndex = 0;
            sellWaitTicks = 0;
            sellAttempts = 0;
            cutterState = CutterState.OPEN_SHOP;
            showStatus(client, "Auto Sell: READY", 40);
            return;
        }

        switch (cutterState) {
            case FIND_TARGET -> findTarget(client);
            case WAITING_FOR_BAMBOO -> waitForBamboo(client);
            case PATH_TO_BAMBOO -> pathToBamboo(client);
            case CUTTING -> cutTarget(client);
            case PATH_TO_DROPS -> pathToDrops(client);
            case COLLECTING -> collectDrops(client);
            case OPEN_SHOP -> openShop(client);
            case WAIT_SHOP_GUI -> waitShopGui(client);
            case CLICK_CATEGORY_SLOT -> clickCategorySlot(client);
            case WAIT_BAMBOO_GUI -> waitBambooGui(client);
            case SELL_BAMBOO -> sellBamboo(client);
            case WAIT_SELL_DONE -> waitSellDone(client);
            case IDLE -> {
            }
        }

        if (bambooCutterActive && holdWalkEnabled && cutterState != CutterState.CUTTING) {
            client.options.forwardKey.setPressed(true);
        }

        if (bambooCutterActive && holdJumpEnabled && cutterState != CutterState.CUTTING) {
            client.options.jumpKey.setPressed(true);
        }
    }

    private static void findTarget(MinecraftClient client) {
        bambooTarget = findNearestBambooCutTarget(client);
        breakingTarget = null;
        collectTarget = null;
        currentPath = List.of();
        pathIndex = 0;

        ItemEntity nearestDrop = findNearestBambooItemEntity(client);
        if (nearestDrop != null) {
            collectTarget = nearestDrop.getPos();
            BlockPos goal = BlockPos.ofFloored(collectTarget);
            currentPath = findPath(client, client.player.getBlockPos(), goal);
            pathIndex = 0;
            cutterState = currentPath.isEmpty() ? CutterState.COLLECTING : CutterState.PATH_TO_DROPS;
            return;
        }

        if (bambooTarget == null) {
            stopMovement(client);
            showStatus(client, "Bamboo Cutter: NO BAMBOO (retry 10s)", 40);
            noBambooRetryTicks = NO_BAMBOO_RETRY_TICKS;
            cutterState = CutterState.WAITING_FOR_BAMBOO;
            return;
        }

        if (lockedBambooTarget != null && lockedBambooTarget.equals(bambooTarget) && cutLockTicks > 0) {
            bambooTarget = null;
            cutterState = CutterState.FIND_TARGET;
            return;
        }

        if (isInBreakRange(client, bambooTarget)) {
            stopMovement(client);
            currentPath = List.of();
            pathIndex = 0;
            cutterState = CutterState.CUTTING;
            return;
        }

        BlockPos standPos = findStandPosition(client, bambooTarget);
        if (standPos == null) {
            cutterState = CutterState.FIND_TARGET;
            bambooTarget = null;
            showStatus(client, "Bamboo Cutter: NO PATH", 40);
            return;
        }

        currentPath = findPath(client, client.player.getBlockPos(), standPos);
        pathIndex = 0;
        if (currentPath.isEmpty()) {
            cutterState = CutterState.FIND_TARGET;
            bambooTarget = null;
            showStatus(client, "Bamboo Cutter: NO PATH", 40);
            return;
        }

        cutterState = CutterState.PATH_TO_BAMBOO;
    }

    private static void waitForBamboo(MinecraftClient client) {
        stopMovement(client);
        showStatus(client, "Bamboo Cutter: WAITING BAMBOO", 20);
        if (noBambooRetryTicks-- <= 0) {
            cutterState = CutterState.FIND_TARGET;
        }
    }

    private static void pathToBamboo(MinecraftClient client) {
        if (bambooTarget == null || !isValidBambooCutTarget(client, bambooTarget)) {
            cutterState = CutterState.FIND_TARGET;
            return;
        }
        if (isInBreakRange(client, bambooTarget)) {
            stopMovement(client);
            cutterState = CutterState.CUTTING;
            return;
        }

        showStatus(client, "Bamboo Cutter: PATHING", 8);
        if (!followPath(client)) {
            if (repathCooldown == 0) {
                repathCooldown = 20;
                cutterState = CutterState.FIND_TARGET;
            }
        }
    }

    private static void cutTarget(MinecraftClient client) {
        if (bambooTarget == null || !isValidBambooCutTarget(client, bambooTarget)) {
            breakingTarget = null;
            lockedBambooTarget = bambooTarget;
            cutLockTicks = 20;
            cutterState = CutterState.FIND_TARGET;
            return;
        }

        if (!bambooTarget.equals(breakingTarget)) {
            Vec3d targetCenter = Vec3d.ofCenter(bambooTarget);
            lookAt(client, targetCenter);
        }
        if (!isInBreakRange(client, bambooTarget)) {
            breakingTarget = null;
            lockedBambooTarget = bambooTarget;
            cutLockTicks = 20;
            cutterState = CutterState.FIND_TARGET;
            return;
        }

        stopMovement(client);
        showStatus(client, "Bamboo Cutter: CUTTING", 8);
        if (!bambooTarget.equals(breakingTarget)) {
            client.interactionManager.attackBlock(bambooTarget, Direction.UP);
            breakingTarget = bambooTarget;
            swingCooldown = 0;
            lockedBambooTarget = bambooTarget;
            cutLockTicks = 20;
        }
        client.interactionManager.updateBlockBreakingProgress(bambooTarget, Direction.UP);
        if (swingCooldown-- <= 0) {
            client.player.swingHand(Hand.MAIN_HAND);
            swingCooldown = 4;
        }
    }

    private static void pathToDrops(MinecraftClient client) {
        if (collectTarget == null) {
            ItemEntity nearest = findNearestBambooItemEntity(client);
            if (nearest == null) {
                cutterState = CutterState.FIND_TARGET;
                return;
            }
            collectTarget = nearest.getPos();
        }
        if (client.player.getPos().squaredDistanceTo(collectTarget) <= COLLECT_DISTANCE_SQUARED) {
            cutterState = CutterState.COLLECTING;
            return;
        }

        showStatus(client, "Bamboo Cutter: COLLECTING", 8);
        if (!followPath(client) && repathCooldown == 0) {
            ItemEntity nearest = findNearestBambooItemEntity(client);
            if (nearest == null) {
                cutterState = CutterState.FIND_TARGET;
                return;
            }
            repathCooldown = 20;
            currentPath = findPath(client, client.player.getBlockPos(), BlockPos.ofFloored(nearest.getPos()));
            pathIndex = 0;
        }
    }

    private static void collectDrops(MinecraftClient client) {
        ItemEntity nearest = findNearestBambooItemEntity(client);
        if (nearest == null) {
            stopMovement(client);
            cutterState = CutterState.FIND_TARGET;
            return;
        }
        collectTarget = nearest.getPos();

        showStatus(client, "Bamboo Cutter: COLLECTING", 8);
        lookAt(client, collectTarget);
        client.options.forwardKey.setPressed(true);
        if (client.player.getPos().squaredDistanceTo(collectTarget) <= COLLECT_DISTANCE_SQUARED) {
            stopMovement(client);
            cutterState = CutterState.FIND_TARGET;
        }
    }

    private static void openShop(MinecraftClient client) {
        stopMovement(client);
        showStatus(client, "Auto Sell: /shop", 20);
        client.player.networkHandler.sendChatCommand("shop");
        sellWaitTicks = 20;
        cutterState = CutterState.WAIT_SHOP_GUI;
    }

    private static void waitShopGui(MinecraftClient client) {
        stopMovement(client);
        showStatus(client, "Auto Sell: SHOP", 8);
        if (isValidContainerSlot(client, 31)) {
            cutterState = CutterState.CLICK_CATEGORY_SLOT;
            return;
        }
        if (sellWaitTicks-- <= 0) {
            finishSelling(client, "Auto Sell: NO SHOP");
        }
    }

    private static void clickCategorySlot(MinecraftClient client) {
        if (!isValidContainerSlot(client, 31)) {
            finishSelling(client, "Auto Sell: BAD SLOT 31");
            return;
        }

        client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 31, 0, SlotActionType.PICKUP, client.player);
        sellWaitTicks = 10;
        cutterState = CutterState.WAIT_BAMBOO_GUI;
        showStatus(client, "Auto Sell: SLOT 31", 20);
    }

    private static void waitBambooGui(MinecraftClient client) {
        stopMovement(client);
        showStatus(client, "Auto Sell: BAMBOO GUI", 8);
        if (findContainerSlot(client, Items.BAMBOO) >= 0) {
            cutterState = CutterState.SELL_BAMBOO;
            return;
        }
        if (sellWaitTicks-- <= 0) {
            finishSelling(client, "Auto Sell: NO BAMBOO ITEM");
        }
    }

    private static void sellBamboo(MinecraftClient client) {
        int slot = findContainerSlot(client, Items.BAMBOO);
        if (slot < 0) {
            finishSelling(client, "Auto Sell: NO BAMBOO ITEM");
            return;
        }

        showStatus(client, "Auto Sell: SELLING", 20);
        client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, slot, 1, SlotActionType.QUICK_MOVE, client.player);
        sellWaitTicks = 30;
        cutterState = CutterState.WAIT_SELL_DONE;
    }

    private static void waitSellDone(MinecraftClient client) {
        stopMovement(client);
        showStatus(client, "Auto Sell: WAIT", 8);
        if (countBamboo(client) < getSellThresholdBamboo()) {
            finishSelling(client, "Auto Sell: DONE");
            return;
        }
        if (sellWaitTicks-- <= 0) {
            if (++sellAttempts < 3 && findContainerSlot(client, Items.BAMBOO) >= 0) {
                cutterState = CutterState.SELL_BAMBOO;
            } else {
                finishSelling(client, "Auto Sell: END");
            }
        }
    }

    private static void finishSelling(MinecraftClient client, String message) {
        stopMovement(client);
        if (client.currentScreen != null) {
            client.player.closeHandledScreen();
        }
        showStatus(client, message, 60);
        sellWaitTicks = 0;
        sellAttempts = 0;
        cutterState = CutterState.FIND_TARGET;
    }

    private static boolean followPath(MinecraftClient client) {
        if (currentPath.isEmpty() || pathIndex >= currentPath.size()) return false;

        Vec3d playerPos = client.player.getPos();
        if (lastPlayerPos != null && playerPos.squaredDistanceTo(lastPlayerPos) < 0.0009D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPlayerPos = playerPos;
        if (stuckTicks > STUCK_TICKS) {
            stuckTicks = 0;
            showStatus(client, "Bamboo Cutter: STUCK, refreshing...", 40);
            markCurrentTargetSkipped();
            currentPath = List.of();
            pathIndex = 0;
            stopMovement(client);
            return false;
        }

        BlockPos next = currentPath.get(pathIndex);
        Vec3d nextCenter = Vec3d.ofBottomCenter(next);
        if (playerPos.squaredDistanceTo(nextCenter) < 0.45D) {
            pathIndex++;
            if (pathIndex >= currentPath.size()) {
                stopMovement(client);
                return true;
            }
            next = currentPath.get(pathIndex);
            nextCenter = Vec3d.ofBottomCenter(next);
        }

        lookAt(client, nextCenter.add(0.0D, 1.0D, 0.0D));
        client.options.forwardKey.setPressed(true);
        BlockPos playerFeet = client.player.getBlockPos();
        boolean movingUp = next.getY() > playerFeet.getY();
        boolean movingDown = next.getY() < playerFeet.getY() && isFallableLanding(client, next);
        client.options.jumpKey.setPressed(movingUp || movingDown && playerFeet.getY() - next.getY() <= 1);
        return true;
    }

    private static void markCurrentTargetSkipped() {
        if (bambooTarget != null) {
            bambooSkipMap.put(bambooTarget, currentTick + SKIP_TICKS);
            bambooTarget = null;
        }
        if (collectTarget != null) {
            ItemEntity nearest = findNearestBambooItemEntity(MinecraftClient.getInstance());
            if (nearest != null) {
                itemSkipMap.put(nearest.getId(), currentTick + SKIP_TICKS);
            }
            collectTarget = null;
        }
    }

    private static BlockPos findNearestBambooCutTarget(MinecraftClient client) {
        BlockPos playerPos = client.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        bambooSkipMap.entrySet().removeIf(entry -> entry.getValue() <= currentTick);

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -6; y <= 6; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    mutable.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    if (!isBamboo(client, mutable) || isBamboo(client, mutable.down())) continue;

                    BlockPos cutTarget = getCutTargetForBambooBase(client, mutable);
                    if (cutTarget == null) continue;
                    if (bambooSkipMap.containsKey(cutTarget)) continue;

                    double distance = client.player.getPos().squaredDistanceTo(Vec3d.ofCenter(cutTarget));
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = cutTarget;
                    }
                }
            }
        }
        return nearest;
    }

    private static Vec3d findNearestBambooItem(MinecraftClient client) {
        itemSkipMap.entrySet().removeIf(entry -> entry.getValue() <= currentTick);
        return client.world.getEntitiesByClass(ItemEntity.class, client.player.getBoundingBox().expand(8.0D), item -> item.getStack().isOf(Items.BAMBOO))
                .stream()
                .filter(item -> !itemSkipMap.containsKey(item.getId()))
                .min(Comparator.comparingDouble(item -> item.squaredDistanceTo(client.player)))
                .map(ItemEntity::getPos)
                .orElse(null);
    }

    private static ItemEntity findNearestBambooItemEntity(MinecraftClient client) {
        itemSkipMap.entrySet().removeIf(entry -> entry.getValue() <= currentTick);
        return client.world.getEntitiesByClass(ItemEntity.class, client.player.getBoundingBox().expand(8.0D), item -> item.getStack().isOf(Items.BAMBOO))
                .stream()
                .filter(item -> !itemSkipMap.containsKey(item.getId()))
                .min(Comparator.comparingDouble(item -> item.squaredDistanceTo(client.player)))
                .orElse(null);
    }

    private static int countBamboo(MinecraftClient client) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.BAMBOO)) {
                count += client.player.getInventory().getStack(i).getCount();
            }
        }
        return count;
    }

    private static int getSellThresholdBamboo() {
        return sellThresholdStacks * 64;
    }

    private static int findContainerSlot(MinecraftClient client, net.minecraft.item.Item item) {
        if (client.player == null || client.player.currentScreenHandler == null) return -1;

        for (Slot slot : client.player.currentScreenHandler.slots) {
            if (slot.inventory == client.player.getInventory()) continue;
            if (slot.hasStack() && slot.getStack().isOf(item)) {
                return slot.id;
            }
        }
        return -1;
    }

    private static boolean isValidContainerSlot(MinecraftClient client, int slotId) {
        if (client.player == null || client.player.currentScreenHandler == null) return false;
        if (slotId < 0 || slotId >= client.player.currentScreenHandler.slots.size()) return false;

        Slot slot = client.player.currentScreenHandler.slots.get(slotId);
        return slot.inventory != client.player.getInventory() && slot.isEnabled();
    }

    private static BlockPos findStandPosition(MinecraftClient client, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos candidate = target.add(dx, dy, dz);
                    if (candidate.getSquaredDistance(target) > BREAK_RANGE_SQUARED) continue;
                    if (dy >= 0) {
                        if (isWalkable(client, candidate)) candidates.add(candidate);
                    } else {
                        if (isFallableLanding(client, candidate)) candidates.add(candidate);
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(client.player.getBlockPos())));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static List<BlockPos> findPath(MinecraftClient client, BlockPos start, BlockPos goal) {
        start = normalizeWalkable(client, start);
        goal = normalizeWalkable(client, goal);
        if (start == null || goal == null) return List.of();
        if (start.equals(goal)) return List.of(goal);

        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.score));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        BlockPos origin = client.player.getBlockPos();

        cost.put(start, 0.0D);
        open.add(new PathNode(start, heuristic(start, goal)));
        int visited = 0;

        while (!open.isEmpty() && visited++ < MAX_PATH_NODES) {
            BlockPos current = open.poll().pos;
            if (!closed.add(current)) continue;
            if (current.equals(goal)) return reconstructPath(cameFrom, current);

            for (BlockPos next : neighbors(client, current, origin)) {
                if (closed.contains(next)) continue;
                double nextCost = cost.get(current) + 1.0D + Math.max(0, next.getY() - current.getY()) * 0.5D;
                if (nextCost < cost.getOrDefault(next, Double.MAX_VALUE)) {
                    cameFrom.put(next, current);
                    cost.put(next, nextCost);
                    open.add(new PathNode(next, nextCost + heuristic(next, goal)));
                }
            }
        }
        return List.of();
    }

    private static Iterable<BlockPos> neighbors(MinecraftClient client, BlockPos pos, BlockPos origin) {
        List<BlockPos> result = new ArrayList<>(20);
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos base = pos.offset(direction);
            for (int dy : new int[]{0, 1, -1, -2, -3}) {
                BlockPos candidate = base.add(0, dy, 0);
                if (Math.abs(candidate.getX() - origin.getX()) > SEARCH_RADIUS || Math.abs(candidate.getZ() - origin.getZ()) > SEARCH_RADIUS) continue;
                if (dy >= 0) {
                    if (isWalkable(client, candidate)) result.add(candidate);
                } else {
                    if (isFallableLanding(client, candidate)) result.add(candidate);
                }
            }
        }
        return result;
    }

    private static boolean isFallableLanding(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        BlockState feet = client.world.getBlockState(pos);
        BlockState head = client.world.getBlockState(pos.up());
        if (!feet.getCollisionShape(client.world, pos).isEmpty()) return false;
        if (!head.getCollisionShape(client.world, pos.up()).isEmpty()) return false;
        for (int i = 1; i <= 3; i++) {
            BlockState below = client.world.getBlockState(pos.down(i));
            if (!below.getCollisionShape(client.world, pos.down(i)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos normalizeWalkable(MinecraftClient client, BlockPos pos) {
        if (isWalkable(client, pos)) return pos.toImmutable();
        for (int dy : new int[]{1, -1, 2, -2}) {
            BlockPos candidate = pos.add(0, dy, 0);
            if (isWalkable(client, candidate)) return candidate.toImmutable();
        }
        return null;
    }

    private static boolean isWalkable(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        BlockState feet = client.world.getBlockState(pos);
        BlockState head = client.world.getBlockState(pos.up());
        BlockState ground = client.world.getBlockState(pos.down());
        return !isBamboo(client, pos)
                && !isBamboo(client, pos.up())
                && !isBamboo(client, pos.down())
                && !isNearBamboo(client, pos)
                && !ground.getCollisionShape(client.world, pos.down()).isEmpty()
                && feet.getCollisionShape(client.world, pos).isEmpty()
                && head.getCollisionShape(client.world, pos.up()).isEmpty();
    }

    private static boolean isNearBamboo(MinecraftClient client, BlockPos pos) {
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (isBamboo(client, pos.offset(direction)) || isBamboo(client, pos.offset(direction).up())) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> cameFrom, BlockPos current) {
        ArrayDeque<BlockPos> path = new ArrayDeque<>();
        path.addFirst(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.addFirst(current);
        }
        List<BlockPos> result = new ArrayList<>(path);
        if (!result.isEmpty()) result.remove(0);
        return result;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static boolean isInBreakRange(MinecraftClient client, BlockPos pos) {
        return client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= BREAK_RANGE_SQUARED;
    }

    private static boolean isBamboo(MinecraftClient client, BlockPos pos) {
        return client.world != null && client.world.getBlockState(pos).isOf(Blocks.BAMBOO);
    }

    private static boolean isValidBambooCutTarget(MinecraftClient client, BlockPos pos) {
        if (!isBamboo(client, pos)) return false;
        BlockPos base = pos;
        while (isBamboo(client, base.down())) base = base.down();
        BlockPos cutTarget = getCutTargetForBambooBase(client, base);
        return pos.equals(cutTarget);
    }

    private static BlockPos getCutTargetForBambooBase(MinecraftClient client, BlockPos base) {
        int height = 0;
        BlockPos cursor = base;
        while (isBamboo(client, cursor)) {
            height++;
            cursor = cursor.up();
        }
        return height < minBambooHeight ? null : base.up().toImmutable();
    }

    private static void lookAt(MinecraftClient client, Vec3d target) {
        Vec3d eyePos = client.player.getEyePos();
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0F / Math.PI)) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * (180.0F / Math.PI));
        client.player.setYaw(yaw);
        client.player.setPitch(MathHelper.clamp(pitch, -89.0F, 89.0F));
    }

    private static void stopMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private static void resetWork() {
        cutterState = CutterState.IDLE;
        bambooTarget = null;
        breakingTarget = null;
        lockedBambooTarget = null;
        collectTarget = null;
        currentPath = List.of();
        pathIndex = 0;
        repathCooldown = 0;
        swingCooldown = 0;
        stuckTicks = 0;
        sellWaitTicks = 0;
        sellAttempts = 0;
        noBambooRetryTicks = 0;
        cutLockTicks = 0;
        lastPlayerPos = null;
    }

    private static void showStatus(MinecraftClient client, String message, int ticks) {
        statusText = message;
        statusTicks = Math.max(statusTicks, ticks);
        if (message.endsWith("START") || message.endsWith("END") || message.endsWith("NO BAMBOO") || message.endsWith("NO PATH")) {
            client.inGameHud.setOverlayMessage(Text.literal(message), false);
        }
    }

    private enum CutterState {
        IDLE,
        WAITING_FOR_BAMBOO,
        FIND_TARGET,
        PATH_TO_BAMBOO,
        CUTTING,
        PATH_TO_DROPS,
        COLLECTING,
        OPEN_SHOP,
        WAIT_SHOP_GUI,
        CLICK_CATEGORY_SLOT,
        WAIT_BAMBOO_GUI,
        SELL_BAMBOO,
        WAIT_SELL_DONE
    }

    private static boolean isSellingState(CutterState state) {
        return state == CutterState.OPEN_SHOP
                || state == CutterState.WAIT_SHOP_GUI
                || state == CutterState.CLICK_CATEGORY_SLOT
                || state == CutterState.WAIT_BAMBOO_GUI
                || state == CutterState.SELL_BAMBOO
                || state == CutterState.WAIT_SELL_DONE;
    }

    private record PathNode(BlockPos pos, double score) {
    }

    private static final class BambooSettingsScreen extends Screen {
        private int scrollY;
        private static final int TOP_Y = 42;
        private static final int BOTTOM_PADDING = 24;
        private static final int MAX_CONTENT_WIDTH = 360;
        private static final int ROW_HEIGHT = 34;
        private static final int TITLE_GAP = 28;
        private static final int DONE_TOP_GAP = 18;
        private static final int SMALL_BUTTON_WIDTH = 24;
        private static final int VALUE_WIDTH = 72;
        private static final int CONTROL_GAP = 8;
        private static final int TOGGLE_WIDTH = 112;
        private static final int BUTTON_HEIGHT = 20;
        private static final int SCROLL_STEP = 20;

        private BambooSettingsScreen() {
            super(Text.literal("Bamboo Cutter Settings"));
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            int maxScroll = getMaxScroll();
            if (maxScroll <= 0) return false;

            scrollY = MathHelper.clamp(scrollY + (int) (verticalAmount * SCROLL_STEP), -maxScroll, 0);
            relayout();
            return true;
        }

        @Override
        protected void init() {
            scrollY = MathHelper.clamp(scrollY, -getMaxScroll(), 0);
            addControls();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            int leftX = getLeftX();
            int rightX = getRightX();
            int y = getContentY();

            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y, 0xFFFFFF);
            y += TITLE_GAP;

            drawNumberRow(context, "Min Bamboo Height", String.valueOf(minBambooHeight), leftX, rightX, y);
            y += ROW_HEIGHT;
            drawNumberRow(context, "Sell Threshold Stacks", sellThresholdStacks + " stacks (" + getSellThresholdBamboo() + ")", leftX, rightX, y);
            y += ROW_HEIGHT;
            drawToggleRow(context, "Hold W Always", y);
            y += ROW_HEIGHT;
            drawToggleRow(context, "Hold Space Always", y);
        }

        private void relayout() {
            clearChildren();
            addControls();
        }

        private void addControls() {
            int rightX = getRightX();
            int y = getContentY() + TITLE_GAP;

            addNumberControls(y, rightX, () -> minBambooHeight = Math.max(2, minBambooHeight - 1), () -> minBambooHeight = Math.min(10, minBambooHeight + 1));
            y += ROW_HEIGHT;
            addNumberControls(y, rightX, () -> sellThresholdStacks = Math.max(1, sellThresholdStacks - 1), () -> sellThresholdStacks = Math.min(10, sellThresholdStacks + 1));
            y += ROW_HEIGHT;
            addToggleButton(y, rightX, holdWalkEnabled ? "ON" : "OFF", b -> {
                holdWalkEnabled = !holdWalkEnabled;
                b.setMessage(Text.literal(holdWalkEnabled ? "ON" : "OFF"));
            });
            y += ROW_HEIGHT;
            addToggleButton(y, rightX, holdJumpEnabled ? "ON" : "OFF", b -> {
                holdJumpEnabled = !holdJumpEnabled;
                b.setMessage(Text.literal(holdJumpEnabled ? "ON" : "OFF"));
            });
            y += DONE_TOP_GAP + ROW_HEIGHT;
            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                    .dimensions(rightX - TOGGLE_WIDTH, y, TOGGLE_WIDTH, BUTTON_HEIGHT)
                    .build());
        }

        private void addNumberControls(int y, int rightX, Runnable onDecrease, Runnable onIncrease) {
            int plusX = rightX - SMALL_BUTTON_WIDTH;
            int valueX = plusX - CONTROL_GAP - VALUE_WIDTH;
            int minusX = valueX - CONTROL_GAP - SMALL_BUTTON_WIDTH;
            addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> onDecrease.run())
                    .dimensions(minusX, y, SMALL_BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> onIncrease.run())
                    .dimensions(plusX, y, SMALL_BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
        }

        private void addToggleButton(int y, int rightX, String label, ButtonWidget.PressAction onPress) {
            addDrawableChild(ButtonWidget.builder(Text.literal(label), onPress)
                    .dimensions(rightX - TOGGLE_WIDTH, y, TOGGLE_WIDTH, BUTTON_HEIGHT)
                    .build());
        }

        private void drawNumberRow(DrawContext context, String label, String value, int leftX, int rightX, int y) {
            int plusX = rightX - SMALL_BUTTON_WIDTH;
            int valueX = plusX - CONTROL_GAP - VALUE_WIDTH;
            context.drawTextWithShadow(this.textRenderer, Text.literal(label), leftX, y + 6, 0xFFFFFF);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(value), valueX + VALUE_WIDTH / 2, y + 6, 0x55FF55);
        }

        private void drawToggleRow(DrawContext context, String label, int y) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(label), getLeftX(), y + 6, 0xFFFFFF);
        }

        private int getLeftX() {
            return (this.width - getContentWidth()) / 2;
        }

        private int getRightX() {
            return getLeftX() + getContentWidth();
        }

        private int getContentWidth() {
            return Math.min(MAX_CONTENT_WIDTH, this.width - 40);
        }

        private int getContentY() {
            return TOP_Y + scrollY;
        }

        private int getContentHeight() {
            return TITLE_GAP + ROW_HEIGHT * 4 + DONE_TOP_GAP + BUTTON_HEIGHT;
        }

        private int getMaxScroll() {
            int availableHeight = this.height - TOP_Y - BOTTOM_PADDING;
            return Math.max(0, getContentHeight() - availableHeight);
        }
    }
}
