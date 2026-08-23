package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió a quién entregar un arma en PlayerPickerScreen, pide la lista de
//armas cargadas (el registro solo vive en memoria del servidor) para abrir WeaponGiveListScreen.
public class WeaponGiveListRequestMessage {
	String targetUuid;

	public WeaponGiveListRequestMessage(String targetUuid) {
		this.targetUuid = targetUuid;
	}

	public WeaponGiveListRequestMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(WeaponGiveListRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(WeaponGiveListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, (ServerPlayer dm) -> {
			List<String> ids = new ArrayList<>(Config.loadedWeaponIds());
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new WeaponGiveListMessage(message.targetUuid, ids));
		});
	}
}
