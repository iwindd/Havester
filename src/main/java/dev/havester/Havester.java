package dev.havester;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
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

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public final class Havester implements ClientModInitializer {
    private static final int PADDING = 6;
    private static final int COLOR = 0xFFFFFF;
    private static final int STATUS_COLOR = 0x55FF55;
    private static final int SEARCH_RADIUS = 32;
    private static final double BREAK_RANGE = 3.0D;
    private static final double BREAK_RANGE_SQUARED = BREAK_RANGE * BREAK_RANGE;
    private static final double COLLECT_DISTANCE_SQUARED = 1.5D;
    private static final int SKIP_TICKS = 200;
    private static final int NO_BAMBOO_RETRY_TICKS = 200;
    private static final int BAMBOO_BLACKLIST_TICKS = 600;
    private static final int WALK_STUCK_SEC = 5;
    private static final Map<BlockPos, Long> bambooSkipMap = new HashMap<>();
    private static final Map<BlockPos, Long> bambooBlacklistMap = new HashMap<>();
    private static final Map<Integer, Long> itemSkipMap = new HashMap<>();
    private static long currentTick;

    private static KeyBinding bambooToggleKey;
    private static KeyBinding bambooSettingsKey;
    private static boolean unpauseEnabled = true;
    private static boolean autoSellEnabled = true;
    private static boolean collectingEnabled = true;
    private static boolean bambooCutterActive;
    private static boolean holdWalkEnabled = true;
    private static boolean holdJumpEnabled = true;
    private static boolean holdSprintEnabled = true;
    private static int minBambooHeight = 3;
    private static int sellThresholdStacks = 5;
    private static CutterState cutterState = CutterState.IDLE;
    private static BlockPos bambooTarget;
    private static BlockPos breakingTarget;
    private static BlockPos scanCorner1;
    private static BlockPos scanCorner2;
    private static Vec3d collectTarget;
    private static int swingCooldown;
    private static int stuckTicks;
    private static int cuttingTicks;
    private static int stuckThreshold;
    private static int sellWaitTicks;
    private static int sellAttempts;
    private static int noBambooRetryTicks;
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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (bambooToggleKey.wasPressed()) {
                toggleBambooCutter(client);
            }
            while (bambooSettingsKey.wasPressed()) {
                client.setScreen(new BambooSettingsScreen());
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

                if (bambooCutterActive && cutterState == CutterState.CUTTING) {
                    String debugText = "CUTTING tick:" + cuttingTicks + " target:" + (bambooTarget != null ? (bambooTarget.getX() + "," + bambooTarget.getY() + "," + bambooTarget.getZ()) : "null");
                    int dx = (client.getWindow().getScaledWidth() - client.textRenderer.getWidth(debugText)) / 2;
                    drawContext.drawTextWithShadow(client.textRenderer, debugText, dx, PADDING + 24, 0xFF5555);
                } else if (bambooCutterActive && cutterState == CutterState.WALK_TO_BAMBOO) {
                    String debugText = "WALKING stuck:" + stuckTicks + "/" + stuckThreshold + " target:" + (bambooTarget != null ? (bambooTarget.getX() + "," + bambooTarget.getY() + "," + bambooTarget.getZ()) : "null");
                    int dx = (client.getWindow().getScaledWidth() - client.textRenderer.getWidth(debugText)) / 2;
                    drawContext.drawTextWithShadow(client.textRenderer, debugText, dx, PADDING + 24, 0xFF5555);
                }
            }
        });

        WorldRenderEvents.LAST.register(Havester::renderScanZone);
    }

    private static void renderScanZone(WorldRenderContext context) {
        if (scanCorner1 == null || scanCorner2 == null) return;

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        Vec3d cameraPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        double minX = Math.min(scanCorner1.getX(), scanCorner2.getX()) - cameraPos.x;
        double minY = Math.min(scanCorner1.getY(), scanCorner2.getY()) - cameraPos.y;
        double minZ = Math.min(scanCorner1.getZ(), scanCorner2.getZ()) - cameraPos.z;
        double maxX = Math.max(scanCorner1.getX(), scanCorner2.getX()) + 1.0D - cameraPos.x;
        double maxY = Math.max(scanCorner1.getY(), scanCorner2.getY()) + 1.0D - cameraPos.y;
        double maxZ = Math.max(scanCorner1.getZ(), scanCorner2.getZ()) + 1.0D - cameraPos.z;
        WorldRenderer.drawBox(matrices, lines, minX, minY, minZ, maxX, maxY, maxZ, 0.2F, 1.0F, 0.2F, 1.0F);
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
        currentTick++;

        if (!autoSellEnabled && isSellingState(cutterState)) {
            finishSelling(client, "Auto Sell: OFF");
            return;
        }

        if (autoSellEnabled && !isSellingState(cutterState) && countBamboo(client) >= getSellThresholdBamboo()) {
            stopMovement(client);
            breakingTarget = null;
            sellWaitTicks = 0;
            sellAttempts = 0;
            cutterState = CutterState.OPEN_SHOP;
            showStatus(client, "Auto Sell: READY", 40);
            return;
        }

        switch (cutterState) {
            case FIND_TARGET -> findTarget(client);
            case WAITING_FOR_BAMBOO -> waitForBamboo(client);
            case WALK_TO_BAMBOO -> walkToBamboo(client);
            case CUTTING -> cutTarget(client);
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

        if (!isMovingState(cutterState)) {
            stopMovement(client);
        }
    }

    private static void findTarget(MinecraftClient client) {
        bambooTarget = findNearestBambooCutTarget(client, true);
        breakingTarget = null;
        collectTarget = null;

        if (bambooTarget != null) {
            stopMovement(client);
            cutterState = CutterState.CUTTING;
            return;
        }

        ItemEntity nearestDrop = collectingEnabled ? findNearestBambooItemEntity(client) : null;
        if (nearestDrop != null) {
            collectTarget = nearestDrop.getPos();
            stuckTicks = 0;
            stuckThreshold = 0;
            showStatus(client, "Bamboo Cutter: COLLECTING", 8);
            startMovement(client);
            cutterState = CutterState.COLLECTING;
            return;
        }

        bambooTarget = findNearestBambooCutTarget(client, false);

        if (bambooTarget == null) {
            stopMovement(client);
            showStatus(client, "Bamboo Cutter: NO BAMBOO (retry 10s)", 40);
            noBambooRetryTicks = NO_BAMBOO_RETRY_TICKS;
            cutterState = CutterState.WAITING_FOR_BAMBOO;
            return;
        }

        if (isInBreakRange(client, bambooTarget)) {
            stopMovement(client);
            cutterState = CutterState.CUTTING;
            return;
        }

        stuckTicks = 0;
        stuckThreshold = WALK_STUCK_SEC * 20;
        showStatus(client, "Bamboo Cutter: WALKING", 8);
        startMovement(client);
        cutterState = CutterState.WALK_TO_BAMBOO;
    }

    private static void waitForBamboo(MinecraftClient client) {
        stopMovement(client);
        showStatus(client, "Bamboo Cutter: WAITING BAMBOO", 20);
        if (noBambooRetryTicks-- <= 0) {
            cutterState = CutterState.FIND_TARGET;
        }
    }

    private static void walkToBamboo(MinecraftClient client) {
        if (bambooTarget == null || !isValidBambooCutTarget(client, bambooTarget)) {
            cutterState = CutterState.FIND_TARGET;
            return;
        }

        if (isInBreakRange(client, bambooTarget)) {
            stopMovement(client);
            cutterState = CutterState.CUTTING;
            return;
        }

        showStatus(client, "Bamboo Cutter: WALKING", 8);
        lookAt(client, Vec3d.ofCenter(bambooTarget));

        Vec3d playerPos = client.player.getPos();
        if (lastPlayerPos == null || playerPos.squaredDistanceTo(lastPlayerPos) > 0.02D) {
            lastPlayerPos = playerPos;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }

        client.options.forwardKey.setPressed(holdWalkEnabled);
        client.options.sprintKey.setPressed(holdSprintEnabled);

        if (stuckTicks > 20) {
            boolean strafeLeft = (stuckTicks / 20) % 2 == 0;
            client.options.leftKey.setPressed(strafeLeft);
            client.options.rightKey.setPressed(!strafeLeft);
            client.options.jumpKey.setPressed(true);
            if (stuckTicks % 60 == 0) {
                float yaw = client.player.getYaw() + (Math.random() > 0.5F ? 90.0F : -90.0F);
                client.player.setYaw(yaw);
            }
        } else {
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(holdJumpEnabled);
        }

        if (stuckTicks > stuckThreshold) {
            bambooBlacklistMap.put(bambooTarget.toImmutable(), currentTick + BAMBOO_BLACKLIST_TICKS);
            bambooTarget = null;
            cutterState = CutterState.FIND_TARGET;
            showStatus(client, "Bamboo Cutter: STUCK - BLACKLIST 30s", 60);
        }
    }

    private static void cutTarget(MinecraftClient client) {
        if (bambooTarget == null || !isValidBambooCutTarget(client, bambooTarget)) {
            breakingTarget = null;
            cuttingTicks = 0;
            tryFastNextTarget(client);
            return;
        }

        if (!bambooTarget.equals(breakingTarget)) {
            Vec3d targetCenter = Vec3d.ofCenter(bambooTarget);
            lookAt(client, targetCenter);
        }
        if (!isInBreakRange(client, bambooTarget)) {
            bambooSkipMap.put(bambooTarget.toImmutable(), currentTick + SKIP_TICKS);
            breakingTarget = null;
            cuttingTicks = 0;
            cutterState = CutterState.FIND_TARGET;
            return;
        }

        stopMovement(client);
        showStatus(client, "Bamboo Cutter: CUTTING", 8);
        if (!bambooTarget.equals(breakingTarget)) {
            client.interactionManager.attackBlock(bambooTarget, Direction.UP);
            breakingTarget = bambooTarget;
            cuttingTicks = 0;
            swingCooldown = 0;
        }
        client.interactionManager.updateBlockBreakingProgress(bambooTarget, Direction.UP);
        if (swingCooldown-- <= 0) {
            client.player.swingHand(Hand.MAIN_HAND);
            swingCooldown = 4;
        }

        cuttingTicks++;
        if (cuttingTicks > 60) {
            bambooSkipMap.put(bambooTarget.toImmutable(), currentTick + SKIP_TICKS);
            breakingTarget = null;
            cuttingTicks = 0;
            cutterState = CutterState.FIND_TARGET;
            showStatus(client, "Bamboo Cutter: SKIP STUCK", 40);
        }
    }

    private static void tryFastNextTarget(MinecraftClient client) {
        bambooTarget = findNearestBambooCutTarget(client, true);
        if (bambooTarget != null) {
            breakingTarget = null;
            cuttingTicks = 0;
            swingCooldown = 0;
            cutterState = CutterState.CUTTING;
            return;
        }

        if (collectingEnabled) {
            ItemEntity nearestDrop = findNearestBambooItemEntity(client);
            if (nearestDrop != null) {
                collectTarget = nearestDrop.getPos();
                stuckTicks = 0;
                stuckThreshold = 0;
                startMovement(client);
                cutterState = CutterState.COLLECTING;
                return;
            }
        }

        cutterState = CutterState.FIND_TARGET;
    }

    private static void collectDrops(MinecraftClient client) {
        if (!collectingEnabled) {
            stopMovement(client);
            collectTarget = null;
            cutterState = CutterState.FIND_TARGET;
            return;
        }

        ItemEntity nearest = findNearestBambooItemEntity(client);
        if (nearest == null) {
            stopMovement(client);
            cutterState = CutterState.FIND_TARGET;
            return;
        }
        collectTarget = nearest.getPos();

        showStatus(client, "Bamboo Cutter: COLLECTING", 8);
        lookAt(client, collectTarget);
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

    private static BlockPos findNearestBambooCutTarget(MinecraftClient client, boolean onlyInBreakRange) {
        BlockPos playerPos = client.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        bambooSkipMap.entrySet().removeIf(entry -> entry.getValue() <= currentTick);
        bambooBlacklistMap.entrySet().removeIf(entry -> entry.getValue() <= currentTick);

        int verticalSearch = minBambooHeight + 8;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -verticalSearch; y <= verticalSearch; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    mutable.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    if (!isBamboo(client, mutable) || isBamboo(client, mutable.down())) continue;

                    BlockPos cutTarget = getCutTargetForBambooBase(client, mutable);
                    if (cutTarget == null) continue;
                    if (!isInsideScanBounds(cutTarget)) continue;
                    if (bambooSkipMap.containsKey(cutTarget)) continue;
                    if (bambooBlacklistMap.containsKey(cutTarget)) continue;

                    double distance = client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(cutTarget));
                    if (onlyInBreakRange && distance > BREAK_RANGE_SQUARED) continue;
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = cutTarget;
                    }
                }
            }
        }
        return nearest;
    }

    private static ItemEntity findNearestBambooItemEntity(MinecraftClient client) {
        itemSkipMap.entrySet().removeIf(entry -> entry.getValue() <= currentTick);
        return client.world.getEntitiesByClass(ItemEntity.class, client.player.getBoundingBox().expand(8.0D), item -> item.getStack().isOf(Items.BAMBOO))
                .stream()
                .filter(item -> isInsideScanBounds(BlockPos.ofFloored(item.getPos())))
                .filter(item -> !itemSkipMap.containsKey(item.getId()))
                .min(Comparator.comparingDouble(item -> item.squaredDistanceTo(client.player)))
                .orElse(null);
    }

    private static boolean isInsideScanBounds(BlockPos pos) {
        if (scanCorner1 == null || scanCorner2 == null) return true;

        int minX = Math.min(scanCorner1.getX(), scanCorner2.getX());
        int maxX = Math.max(scanCorner1.getX(), scanCorner2.getX());
        int minY = Math.min(scanCorner1.getY(), scanCorner2.getY());
        int maxY = Math.max(scanCorner1.getY(), scanCorner2.getY());
        int minZ = Math.min(scanCorner1.getZ(), scanCorner2.getZ());
        int maxZ = Math.max(scanCorner1.getZ(), scanCorner2.getZ());
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
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

    private static void startMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(holdWalkEnabled);
        client.options.sprintKey.setPressed(holdSprintEnabled);
        client.options.jumpKey.setPressed(holdJumpEnabled);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    private static void stopMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    private static void resetWork() {
        cutterState = CutterState.IDLE;
        bambooTarget = null;
        breakingTarget = null;
        collectTarget = null;
        swingCooldown = 0;
        stuckTicks = 0;
        cuttingTicks = 0;
        stuckThreshold = 0;
        sellWaitTicks = 0;
        sellAttempts = 0;
        noBambooRetryTicks = 0;
        lastPlayerPos = null;
    }

    private static void showStatus(MinecraftClient client, String message, int ticks) {
        statusText = message;
        statusTicks = Math.max(statusTicks, ticks);
        if (message.endsWith("START") || message.endsWith("END") || message.endsWith("NO BAMBOO")) {
            client.inGameHud.setOverlayMessage(Text.literal(message), false);
        }
    }

    private enum CutterState {
        IDLE,
        WAITING_FOR_BAMBOO,
        FIND_TARGET,
        WALK_TO_BAMBOO,
        CUTTING,
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

    private static boolean isMovingState(CutterState state) {
        return state == CutterState.WALK_TO_BAMBOO || state == CutterState.COLLECTING;
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
            drawActionRow(context, "Scan Corner 1: " + formatCorner(scanCorner1), leftX, y);
            y += ROW_HEIGHT;
            drawActionRow(context, "Scan Corner 2: " + formatCorner(scanCorner2), leftX, y);
            y += ROW_HEIGHT;
            drawToggleRow(context, "Unpause When Tabbed Out", y);
            y += ROW_HEIGHT;
            drawToggleRow(context, "Auto Sell", y);
            y += ROW_HEIGHT;
            drawToggleRow(context, "Collect Drops", y);
            y += ROW_HEIGHT;
            drawToggleRow(context, "Hold W On Walk", y);
            y += ROW_HEIGHT;
            drawToggleRow(context, "Hold Jump On Walk", y);
            y += ROW_HEIGHT;
            drawToggleRow(context, "Hold Sprint On Walk", y);
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
            addActionButton(y, rightX, "Record 1", b -> recordScanCorner(1));
            y += ROW_HEIGHT;
            addActionButton(y, rightX, "Record 2", b -> recordScanCorner(2));
            y += ROW_HEIGHT;
            addToggleButton(y, rightX, unpauseEnabled ? "ON" : "OFF", b -> {
                unpauseEnabled = !unpauseEnabled;
                b.setMessage(Text.literal(unpauseEnabled ? "ON" : "OFF"));
            });
            y += ROW_HEIGHT;
            addToggleButton(y, rightX, autoSellEnabled ? "ON" : "OFF", b -> {
                autoSellEnabled = !autoSellEnabled;
                b.setMessage(Text.literal(autoSellEnabled ? "ON" : "OFF"));
            });
            y += ROW_HEIGHT;
            addToggleButton(y, rightX, collectingEnabled ? "ON" : "OFF", b -> {
                collectingEnabled = !collectingEnabled;
                b.setMessage(Text.literal(collectingEnabled ? "ON" : "OFF"));
            });
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
            y += ROW_HEIGHT;
            addToggleButton(y, rightX, holdSprintEnabled ? "ON" : "OFF", b -> {
                holdSprintEnabled = !holdSprintEnabled;
                b.setMessage(Text.literal(holdSprintEnabled ? "ON" : "OFF"));
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

        private void addActionButton(int y, int rightX, String label, ButtonWidget.PressAction onPress) {
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

        private void drawActionRow(DrawContext context, String label, int leftX, int y) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(label), leftX, y + 6, 0xFFFFFF);
        }

        private void recordScanCorner(int corner) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            BlockPos pos = client.player.getBlockPos().toImmutable();
            if (corner == 1) {
                scanCorner1 = pos;
            } else {
                scanCorner2 = pos;
            }
            showStatus(client, "Scan Corner " + corner + ": " + formatCorner(pos), 60);
        }

        private String formatCorner(BlockPos pos) {
            return pos == null ? "Not Set" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
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
            return TITLE_GAP + ROW_HEIGHT * 10 + DONE_TOP_GAP + BUTTON_HEIGHT;
        }

        private int getMaxScroll() {
            int availableHeight = this.height - TOP_Y - BOTTOM_PADDING;
            return Math.max(0, getContentHeight() - availableHeight);
        }
    }
}
