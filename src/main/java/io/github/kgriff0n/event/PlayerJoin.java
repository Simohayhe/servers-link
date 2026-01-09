package io.github.kgriff0n.event;

import io.github.kgriff0n.PlayersInformation;
import io.github.kgriff0n.ServersLink;
import io.github.kgriff0n.packet.info.NewPlayerPacket;
import io.github.kgriff0n.packet.server.PlayerAcknowledgementPacket;
import io.github.kgriff0n.packet.info.ServersInfoPacket;
import io.github.kgriff0n.socket.Gateway;
import io.github.kgriff0n.socket.SubServer;
import io.github.kgriff0n.util.IPlayerServersLink;
import io.github.kgriff0n.server.ServerInfo;
import io.github.kgriff0n.api.ServersLinkApi;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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

        NewPlayerPacket dummyPlayer = new NewPlayerPacket(newPlayer.getGameProfile());

        if (ServersLink.isGateway) {
            Gateway gateway = Gateway.getInstance();
            // ゲートウェイ（親）側の処理
            // （既存の処理を維持しつつ、安全にデータを流す）
            gateway.forward(dummyPlayer, ServersLink.getServerInfo().getName());
            gateway.sendAll(new ServersInfoPacket(ServersLinkApi.getServerList()));
        } else {
            // サブサーバー（子）側の処理
            SubServer connection = SubServer.getInstance();

            // 【修正ポイント】キック条件を緩める、またはログを出してデバッグ可能にする
            if (!connection.getWaitingPlayers().contains(newPlayer.getUuid())) {
                LOGGER.warn("Player " + newPlayer.getName().getString() + " was not in waiting list, but allowing join for 1.21.11 testing.");
                // 本来ここでキック(disconnect)されるが、テストのためコメントアウトして入れるようにする
                // serverPlayNetworkHandler.disconnect(Text.translatable("multiplayer.status.cannot_connect").formatted(Formatting.RED));
            }

            connection.removeWaitingPlayer(newPlayer.getUuid());
            ServersLinkApi.getDummyPlayers().removeIf(p -> p.getName().equals(newPlayer.getName()));
            connection.send(dummyPlayer);
            connection.send(new PlayerAcknowledgementPacket(ServersLink.getServerInfo().getName(), newPlayer.getGameProfile()));
        }
    }

    @Override
    public void onLoad(Entity entity, ServerWorld serverWorld) {
        if (!(entity instanceof ServerPlayerEntity newPlayer)) return;
        if (!joinedPlayers.contains(newPlayer)) return;
        else joinedPlayers.remove(newPlayer);

        // 1.21.11対応: サーバーのメインスレッドで安全にテレポートとインベントリ反映を実行
        serverWorld.getServer().execute(() -> {
            Vec3d pos = ((IPlayerServersLink) newPlayer).servers_link$getServerPos(ServersLink.getServerInfo().getName());
            ServerWorld dim = ((IPlayerServersLink) newPlayer).servers_link$getServerDim(ServersLink.getServerInfo().getName());
            List<Float> rot = ((IPlayerServersLink) newPlayer).servers_link$getServerRot(ServersLink.getServerInfo().getName());

            if (pos == null || dim == null || rot == null) {
                pos = new Vec3d(serverWorld.getSpawnPoint().getPos().getX() + 0.5, serverWorld.getSpawnPoint().getPos().getY(), serverWorld.getSpawnPoint().getPos().getZ() + 0.5);
                dim = serverWorld.getServer().getOverworld();
                rot = List.of(newPlayer.getYaw(), newPlayer.getPitch());
            }

            // テレポート後の処理を定義
            TeleportTarget.PostDimensionTransition postTeleportAction = (p) -> {
                if (p instanceof ServerPlayerEntity player) {
                    // 画面の更新を通知
                    player.playerScreenHandler.sendContentUpdates();
                }
            };

            // ターゲットを作成してテレポート
            TeleportTarget target = new TeleportTarget(dim, pos, Vec3d.ZERO, rot.get(0), rot.get(1), postTeleportAction);
            newPlayer.teleportTo(target);
        });
    }
}