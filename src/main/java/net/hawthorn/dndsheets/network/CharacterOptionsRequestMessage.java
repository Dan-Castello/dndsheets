package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Cliente -> servidor: el jugador clicó el campo de Raza/Trasfondo/Clase en su hoja, pide la lista de
//opciones de esa categoría (ver CharacterOptionsRegistry, solo vive en memoria del servidor). Sin
//permiso especial: cualquier jugador puede elegir su propia raza/trasfondo/clase, a diferencia de los
//mensajes del Panel de DM.
public class CharacterOptionsRequestMessage {
	String category;

	public CharacterOptionsRequestMessage(String category) {
		this.category = category;
	}

	public CharacterOptionsRequestMessage(FriendlyByteBuf buffer) {
		this.category = buffer.readUtf();
	}

	public static void buffer(CharacterOptionsRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.category);
	}

	public static void handler(CharacterOptionsRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player == null || !CharacterOptionsRegistry.isValidCategory(message.category)) return;
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
				new CharacterOptionsListMessage(message.category, CharacterOptionsRegistry.get(message.category)));
		});
	}
}
