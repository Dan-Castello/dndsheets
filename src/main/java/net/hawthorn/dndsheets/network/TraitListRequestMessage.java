package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TraitRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió a quién conceder un rasgo en PlayerPickerScreen, pide la lista de
//rasgos cargados (el registro solo vive en memoria del servidor) para abrir TraitGrantScreen.
public class TraitListRequestMessage {
	String targetUuid;

	public TraitListRequestMessage(String targetUuid) {
		this.targetUuid = targetUuid;
	}

	public TraitListRequestMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(TraitListRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(TraitListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			List<String> ids = new ArrayList<>(TraitRegistry.ids());
			List<String> names = new ArrayList<>();
			for (String id : ids) {
				TraitRegistry.Trait trait = TraitRegistry.get(id);
				names.add(trait != null ? trait.name() : id);
			}

			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new TraitListMessage(message.targetUuid, ids, names));
		});
		context.setPacketHandled(true);
	}
}
