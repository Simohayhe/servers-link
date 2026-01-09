package io.github.kgriff0n.event;

import io.github.kgriff0n.PlayersInformation;
import io.github.kgriff0n.ServersLink;
import io.github.kgriff0n.packet.info.NewPlayerPacket;
import io.github.kgriff0n.packet.server.PlayerAcknowledgementPacket;
import io.github.kgriff0n.util.IPlayerServersLink;
import io.github.kgriff0n.api.ServersLinkApi;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

import java.util.ArrayList;
import java.util.List;

import static io.github.kgriff0n.ServersLink.LOGGER;

public class PlayerJoin implements ServerPlayConnectionEvents.Join, ServerEntityEvents.Load {

    private static ArrayList<ServerPlayerEntity> joinedPlayers = new ArrayList<>();

    @Override
    public void onPlayReady(ServerPlayNetworkHandler serverPlayNetworkHandler, PacketSender packetSender, MinecraftServer minecraftServer) {
        ServerPlayerEntity newPlayer = serverPlayNetworkHandler.player;
        if (!joinedPlayers.contains(newPlayer)) joinedPlayers.add(newPlayer);

        // 【爆速化のポイント1】
        // 通信待ち（Gatewayへの確認）を一切せず、即座に共有フォルダからデータを読み込む
        PlayersInformation.loadPlayerData(newPlayer);

        // 偽プレイヤーの削除（これがないとタブリストに偽物が残る）
        ServersLinkApi.getDummyPlayers().removeIf(p -> p.getName().equals(newPlayer.getName()));

        // 【爆速化のポイント2】
        // ネットワークへの通知（NewPlayerPacketなど）は別スレッドで実行
        // これにより、通信ラグがログイン画面の停止（暗転）に影響しなくなります
        new Thread(() -> {
            try {
                NewPlayerPacket dummyPlayer = new NewPlayerPacket(newPlayer.getGameProfile());
                if (ServersLink.isGateway) {
                    io.github.kgriff0n.socket.Gateway.getInstance().forward(dummyPlayer, ServersLink.getServerInfo().getName());
                } else {
                    io.github.kgriff0n.socket.SubServer.getInstance().send(dummyPlayer);
                    io.github.kgriff0n.socket.SubServer.getInstance().send(new PlayerAcknowledgementPacket(ServersLink.getServerInfo().getName(), newPlayer.getGameProfile()));
                }
            } catch (Exception e) {
                LOGGER.error("Background network sync failed: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onLoad(Entity entity, ServerWorld serverWorld) {
        if (!(entity instanceof ServerPlayerEntity newPlayer)) return;
        if (!joinedPlayers.contains(newPlayer)) return;
        else joinedPlayers.remove(newPlayer);

        // サーバーのメインスレッドでテレポートを実行
        serverWorld.getServer().execute(() -> {
            Vec3d pos = ((IPlayerServersLink) newPlayer).servers_link$getServerPos(ServersLink.getServerInfo().getName());
            ServerWorld dim = ((IPlayerServersLink) newPlayer).servers_link$getServerDim(ServersLink.getServerInfo().getName());
            List<Float> rot = ((IPlayerServersLink) newPlayer).servers_link$getServerRot(ServersLink.getServerInfo().getName());

            if (pos == null || dim == null || rot == null) {
                pos = new Vec3d(serverWorld.getSpawnPoint().getPos().getX() + 0.5, serverWorld.getSpawnPoint().getPos().getY(), serverWorld.getSpawnPoint().getPos().getZ() + 0.5);
                dim = serverWorld.getServer().getOverworld();
                rot = List.of(newPlayer.getYaw(), newPlayer.getPitch());
            }

            // 1.21.11のテレポート方式
            TeleportTarget target = new TeleportTarget(dim, pos, Vec3d.ZERO, rot.get(0), rot.get(1), (p) -> {
                if (p instanceof ServerPlayerEntity player) {
                    player.playerScreenHandler.sendContentUpdates();
                }
            });
            newPlayer.teleportTo(target);
        });
    }
}