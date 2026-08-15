package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.CharacterListScreen;
import net.hawthorn.dndsheets.client.gui.PartyScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p>Servidor → cliente: la respuesta a {@link RosterActionMessage}, y la que abre la pantalla que toque.
 * Dos listas paralelas (id y etiqueta ya formateada) en vez de un objeto por fila: el cliente no hace
 * nada con los datos salvo pintarlos, así que formatear en el servidor evita mandar media hoja de
 * personaje por la red solo para componer un texto.</p>
 */
public class RosterListMessage {

	public enum Kind { MINE, PARTY }

	final Kind kind;
	final List<String> ids;
	final List<String> labels;

	public RosterListMessage(Kind kind, List<String> ids, List<String> labels) {
		this.kind = kind;
		this.ids = ids;
		this.labels = labels;
	}

	public RosterListMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.labels = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(RosterListMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.labels, FriendlyByteBuf::writeUtf);
	}

	public static void handler(RosterListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> {
			switch (message.kind) {
				case MINE -> CharacterListScreen.open(message.ids, message.labels);
				case PARTY -> PartyScreen.open(message.labels);
			}
		});
	}
}
