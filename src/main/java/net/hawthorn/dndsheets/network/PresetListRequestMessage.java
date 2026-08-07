package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.PresetManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

//Cliente -> servidor: pide la lista de presets cargados, o bien para el jugador que la pide (botón
//"Presets" de su propia hoja, targetUuid vacío) o bien, si es un DM, para aplicársela a OTRO jugador
//elegido antes en PlayerPickerScreen (ver AUDIT_UX.md, DM #2: antes de esto aplicar un preset a otro
//jugador solo existía como /dndpresets apply tecleado a mano).
public class PresetListRequestMessage {
	String targetUuid;

	public PresetListRequestMessage() {
		this.targetUuid = "";
	}

	public PresetListRequestMessage(String targetUuid) {
		this.targetUuid = targetUuid;
	}

	public PresetListRequestMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(PresetListRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(PresetListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer player = context.getSender();
			if (player == null) return;

			if (!message.targetUuid.isEmpty()) {
				if (!player.hasPermissions(2)) return;
				if (player.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid)) == null) return;
			}

			List<String> ids = PresetManager.presetIds();
			List<String> names = PresetManager.presetNames(ids);
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new PresetListMessage(message.targetUuid, ids, names));
		});
		context.setPacketHandled(true);
	}
}
