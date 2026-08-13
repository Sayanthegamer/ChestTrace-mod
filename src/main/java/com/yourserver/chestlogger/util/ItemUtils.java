package com.yourserver.chestlogger.util;

import com.yourserver.chestlogger.ChestLoggerMod;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

public final class ItemUtils {

    private ItemUtils() {}

    public static String getItemIdentifier(Item item) {
        if (item == null || item == Items.AIR) {
            return "minecraft:air";
        }

        // Tier 1: Direct BuiltInRegistries.ITEM.getKey
        try {
            ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
            if (loc != null && !loc.getPath().equals("air")) {
                return loc.toString();
            }
        } catch (Throwable e) {
            ChestLoggerMod.LOGGER.error("ItemUtils: Tier 1 (ITEM.getKey) failed for item {}: ", item, e);
        }

        // Tier 2: BlockItem -> BuiltInRegistries.BLOCK.getKey
        if (item instanceof BlockItem blockItem) {
            try {
                Block block = blockItem.getBlock();
                if (block != null) {
                    ResourceLocation blockLoc = BuiltInRegistries.BLOCK.getKey(block);
                    if (blockLoc != null && !blockLoc.getPath().equals("air")) {
                        return blockLoc.toString();
                    }
                }
            } catch (Throwable e) {
                ChestLoggerMod.LOGGER.error("ItemUtils: Tier 2 (BLOCK.getKey) failed for item {}: ", item, e);
            }
        }

        // Tier 3: BuiltInRegistries.ITEM.getResourceKey
        try {
            var optKey = BuiltInRegistries.ITEM.getResourceKey(item);
            if (optKey != null && optKey.isPresent()) {
                ResourceLocation loc = optKey.get().location();
                if (loc != null && !loc.getPath().equals("air")) {
                    return loc.toString();
                }
            }
        } catch (Throwable e) {
            ChestLoggerMod.LOGGER.error("ItemUtils: Tier 3 (ITEM.getResourceKey) failed for item {}: ", item, e);
        }

        // Tier 4: Holder location
        try {
            if (item.builtInRegistryHolder() != null && item.builtInRegistryHolder().isBound()) {
                ResourceLocation loc = item.builtInRegistryHolder().key().location();
                if (loc != null && !loc.getPath().equals("air")) {
                    return loc.toString();
                }
            }
        } catch (Throwable e) {
            ChestLoggerMod.LOGGER.error("ItemUtils: Tier 4 (holder) failed for item {}: ", item, e);
        }

        ChestLoggerMod.LOGGER.error("ItemUtils: All resolution tiers failed for item class {}; returning minecraft:air", item.getClass().getName());
        return "minecraft:air";
    }

    public static Item getItemFromIdentifier(String itemIdStr, Item fallback) {
        if (itemIdStr == null || itemIdStr.isEmpty() || itemIdStr.equals("minecraft:air")) {
            return fallback;
        }

        try {
            ResourceLocation id = ResourceLocation.tryParse(itemIdStr);
            if (id != null) {
                Item resolved = BuiltInRegistries.ITEM.getValue(id);
                if (resolved != null && resolved != Items.AIR) {
                    return resolved;
                }

                Block block = BuiltInRegistries.BLOCK.getValue(id);
                if (block != null && block.asItem() != null && block.asItem() != Items.AIR) {
                    return block.asItem();
                }
            }
        } catch (Throwable e) {
            ChestLoggerMod.LOGGER.error("ItemUtils: getItemFromIdentifier failed for {}: ", itemIdStr, e);
        }

        return fallback;
    }

    public static String getItemDisplayName(Item item) {
        if (item == null || item == Items.AIR) return "Air";
        try {
            return item.getName(new ItemStack(item)).getString();
        } catch (Throwable ignored) {
            return item.toString();
        }
    }

    public static DataComponentPatch extractComponents(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return DataComponentPatch.EMPTY;
        return stack.getComponentsPatch();
    }

    public static byte[] serializeComponents(DataComponentPatch patch, net.minecraft.core.HolderLookup.Provider registryAccess) {
        if (patch == null || patch.isEmpty() || registryAccess == null) return null;
        try {
            net.minecraft.nbt.Tag tag = DataComponentPatch.CODEC.encodeStart(
                net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registryAccess),
                patch
            ).getOrThrow();

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream nbtOut = new java.io.DataOutputStream(baos);
            net.minecraft.nbt.NbtIo.writeAnyTag(tag, nbtOut);
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    public static DataComponentPatch deserializeComponents(byte[] bytes, net.minecraft.core.HolderLookup.Provider registryAccess) {
        if (bytes == null || bytes.length == 0 || registryAccess == null) return DataComponentPatch.EMPTY;
        try {
            java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            net.minecraft.nbt.Tag tag = net.minecraft.nbt.NbtIo.readAnyTag(dis, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return DataComponentPatch.CODEC.parse(
                net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registryAccess),
                tag
            ).getOrThrow();
        } catch (Throwable t) {
            return DataComponentPatch.EMPTY;
        }
    }
}
