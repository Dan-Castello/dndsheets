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
//elegido antes en PlayerPickerScreen
//jugador solo existía como /dndpresets apply tecleado a mano).
public class PresetListRequestMessage {
	String targetUuid;
	//true: la lista vuelve a abrir PresetScreen en modo multiclase (sube un nivel EN la clase elegida)
	//en vez de reemplazar el preset entero — ver PresetScreen y MulticlassMessage. Solo tiene sentido con
	//targetUuid vacío: el botón "Multiclasear" de la ficha es sobre uno mismo, no sobre otro jugador.
	boolean multiclass;

	public PresetListRequestMessage() {
		this.targetUuid = "";
		this.multiclass = false;
	}

	public PresetListRequestMessage(String targetUuid) {
		this.targetUuid = targetUuid;
		this.multiclass = false;
	}

	/** Botón "Multiclasear" de la propia ficha: siempre self (targetUuid vacío). */
	public PresetListRequestMessage(boolean multiclass) {
		this.targetUuid = "";
		this.multiclass = multiclass;
	}

	public PresetListRequestMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.multiclass = buffer.readBoolean();
	}

	public static void buffer(PresetListRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeBoolean(message.multiclass);
	}

	public static void handler(PresetListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player == null) return;

			if (!message.targetUuid.isEmpty()) {
				if (!DndsheetsMod.canActAsDm(player)) return;
				try {
					if (player.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid)) == null) return;
				} catch (IllegalArgumentException e) {
					//UUID malformado de un operador con cliente roto/modificado: se descarta el mensaje en
					//vez de tumbar el hilo del servidor con una excepción sin capturar.
					return;
				}
			}

			List<String> ids = PresetManager.presetIds();
			List<String> names = PresetManager.presetNames(ids);
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new PresetListMessage(message.targetUuid, message.multiclass, ids, names));
		});
	}
}
