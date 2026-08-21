package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.CharacterListScreen;
import net.hawthorn.dndsheets.client.gui.CompendiumEntryScreen;
import net.hawthorn.dndsheets.client.gui.CompendiumListScreen;
import net.hawthorn.dndsheets.client.gui.JournalScreen;
import net.hawthorn.dndsheets.client.gui.FeatScreen;
import net.hawthorn.dndsheets.client.gui.PartyScreen;
import net.hawthorn.dndsheets.client.gui.SubclassScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p>Servidor → cliente: la respuesta a {@link BrowseActionMessage}, y la que abre la pantalla que toque.
 * Dos listas paralelas (id y etiqueta ya formateada) en vez de un objeto por fila: el cliente no hace
 * nada con los datos salvo pintarlos, así que formatear en el servidor evita mandar media hoja de
 * personaje por la red solo para componer un texto.</p>
 */
public class BrowseListMessage {

	//Al final, igual que arriba: el ordinal viaja por la red.
	public enum Kind { MINE, PARTY, CONTENT, DETAIL, JOURNAL, SUBCLASS, FEAT }

	final Kind kind;
	final List<String> ids;
	final List<Component> labels;

	public BrowseListMessage(Kind kind, List<String> ids, List<Component> labels) {
		this.kind = kind;
		this.ids = ids;
		this.labels = labels;
	}

	public BrowseListMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.labels = buffer.readList(FriendlyByteBuf::readComponent);
	}

	public static void buffer(BrowseListMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.labels, FriendlyByteBuf::writeComponent);
	}

	public static void handler(BrowseListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> {
			switch (message.kind) {
				case MINE -> CharacterListScreen.open(message.ids, message.labels);
				case PARTY -> PartyScreen.open(message.labels);
				case CONTENT -> CompendiumListScreen.open(message.ids, message.labels);
				case JOURNAL -> JournalScreen.open(message.ids, message.labels);
				case SUBCLASS -> SubclassScreen.open(message.ids, message.labels);
				case FEAT -> FeatScreen.open(message.ids, message.labels);
				//Una ficha suelta viaja como una lista de un elemento: mismo mensaje, sin una clase nueva
				//para transportar un texto largo.
				case DETAIL -> CompendiumEntryScreen.open(
					message.labels.isEmpty() ? "" : message.labels.get(0).getString());
			}
		});
	}
}
