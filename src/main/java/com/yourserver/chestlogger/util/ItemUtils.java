package com.yourserver.chestlogger.util;

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

        if (item instanceof BlockItem blockItem) {
            try {
                Block block = blockItem.getBlock();
                if (block != null) {
                    ResourceLocation blockLoc = BuiltInRegistries.BLOCK.getKey(block);
                    if (blockLoc != null && !blockLoc.equals(BuiltInRegistries.BLOCK.getDefaultKey())) {
                        return blockLoc.toString();
                    }
                }
            } catch (Throwable ignored) {}
        }

        try {
            ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
            if (loc != null && !loc.equals(BuiltInRegistries.ITEM.getDefaultKey())) {
                return loc.toString();
            }
        } catch (Throwable ignored) {}

        try {
            if (item.builtInRegistryHolder() != null && item.builtInRegistryHolder().isBound()) {
                return item.builtInRegistryHolder().key().location().toString();
            }
        } catch (Throwable ignored) {}

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
        } catch (Throwable ignored) {}

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

    public static net.minecraft.core.component.DataComponentPatch extractComponents(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return net.minecraft.core.component.DataComponentPatch.EMPTY;
        return stack.getComponentsPatch();
    }

    public static byte[] serializeComponents(net.minecraft.core.component.DataComponentPatch patch, net.minecraft.core.HolderLookup.Provider registryAccess) {
        if (patch == null || patch.isEmpty() || registryAccess == null) return null;
        try {
            net.minecraft.nbt.Tag tag = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(
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

    public static net.minecraft.core.component.DataComponentPatch deserializeComponents(byte[] bytes, net.minecraft.core.HolderLookup.Provider registryAccess) {
        if (bytes == null || bytes.length == 0 || registryAccess == null) return net.minecraft.core.component.DataComponentPatch.EMPTY;
        try {
            java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            net.minecraft.nbt.Tag tag = net.minecraft.nbt.NbtIo.readAnyTag(dis, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return net.minecraft.core.component.DataComponentPatch.CODEC.parse(
                net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registryAccess),
                tag
            ).getOrThrow();
        } catch (Throwable t) {
            return net.minecraft.core.component.DataComponentPatch.EMPTY;
        }
    }
}
