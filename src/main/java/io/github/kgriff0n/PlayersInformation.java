package io.github.kgriff0n;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

public class PlayersInformation {

    private static final HashMap<UUID, String> lastServer = new HashMap<>();
    private static final String SHARED_DIR = "shared_player_data/";

    public static void setLastServer(UUID player, String serverName) {
        lastServer.put(player, serverName);
    }

    public static String getLastServer(UUID player) {
        return lastServer.get(player);
    }

    public static void saveNbt(MinecraftServer server) {
        NbtCompound nbt = new NbtCompound();
        lastServer.forEach((uuid, name) -> nbt.putString(uuid.toString(), name));

        Path dataFile = server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("servers_link.nbt");

        try (OutputStream os = Files.newOutputStream(dataFile)) {
            NbtIo.writeCompressed(nbt, os);
        } catch (IOException e) {
            ServersLink.LOGGER.error("Unable to save data");
        }
    }

    public static void loadNbt(MinecraftServer server) {
        Path dataFile = server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("servers_link.nbt");

        try (InputStream is = Files.newInputStream(dataFile)) {
            NbtCompound nbt = NbtIo.readCompressed(is, NbtSizeTracker.ofUnlimitedBytes());
            for (String uuidStr : nbt.getKeys()) {
                NbtElement element = nbt.get(uuidStr);
                if (element instanceof NbtString nbtString) {
                    UUID player = UUID.fromString(uuidStr);
                    lastServer.put(player, nbtString.asString().orElse(""));
                }
            }
        } catch (IOException e) {
            ServersLink.LOGGER.error("Unable to load data");
        }
    }

    public static void loadPlayerData(ServerPlayerEntity player) {
        try {
            File file = new File(SHARED_DIR, player.getUuidAsString() + ".dat");
            if (file.exists()) {
                NbtCompound rootNbt = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());
                if (rootNbt != null) {
                    NbtElement itemsElement = rootNbt.get("Items");

                    if (itemsElement instanceof NbtList items) {
                        player.getInventory().clear();

                        for (int i = 0; i < items.size(); i++) {
                            NbtElement itemElement = items.get(i);

                            if (itemElement instanceof NbtCompound itemNbt) {
                                if (itemNbt.contains("Slot")) {
                                    int slot = itemNbt.getByte("Slot").orElse((byte)0) & 255;

                                    ItemStack.CODEC.parse(player.getRegistryManager().getOps(NbtOps.INSTANCE), itemNbt)
                                            .result()
                                            .ifPresent(stack -> player.getInventory().setStack(slot, stack));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            ServersLink.LOGGER.error("Failed to load player data", e);
        }
    }

    public static void savePlayerData(ServerPlayerEntity player) {
        try {
            File dir = new File(SHARED_DIR);
            if (!dir.exists()) dir.mkdirs();

            NbtCompound rootNbt = new NbtCompound();
            NbtList items = new NbtList();

            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (!stack.isEmpty()) {
                    // 修正点: ラムダ内で使用するために final なコピーを作成
                    final int slotIndex = i;

                    ItemStack.CODEC.encodeStart(player.getRegistryManager().getOps(NbtOps.INSTANCE), stack)
                            .result()
                            .ifPresent(encoded -> {
                                if (encoded instanceof NbtCompound itemNbt) {
                                    // コピーした slotIndex を使用
                                    itemNbt.putByte("Slot", (byte) slotIndex);
                                    items.add(itemNbt);
                                }
                            });
                }
            }
            rootNbt.put("Items", items);

            File file = new File(dir, player.getUuidAsString() + ".dat");
            NbtIo.writeCompressed(rootNbt, file.toPath());
        } catch (Exception e) {
            ServersLink.LOGGER.error("Failed to save player data", e);
        }
    }
}