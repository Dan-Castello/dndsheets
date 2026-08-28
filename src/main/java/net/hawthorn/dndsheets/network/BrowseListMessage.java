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

	//Al final, igual que arriba: el ordinal viaja por la red. Desde GIVE_WEAPON hacia abajo son las
	//antiguas parejas List/ListRequest (ocho clases casi idénticas por lado, ver invariante 3),
	//fundidas aquí en la migración que borró ~16 clases de este paquete.
	public enum Kind { MINE, PARTY, CONTENT, DETAIL, JOURNAL, SUBCLASS, FEAT,
		GIVE_WEAPON, GIVE_SPELL, GRANT_TRAIT, PRESET, PRESET_MULTICLASS, SPAWN_MONSTER,
		MANAGE_OPTIONS, CONTENT_ENTRY, CHARACTER_OPTION, ENCOUNTER, ENCOUNTER_DESIGN }

	final Kind kind;
	final List<String> ids;
	final List<Component> labels;
	//Lo que la pantalla de destino necesita además de la lista: el uuid del jugador objetivo
	//(GIVE_*/GRANT_TRAIT/PRESET), la categoría (MANAGE_OPTIONS/CHARACTER_OPTION) o el nombre del
	//ContentType (CONTENT_ENTRY). Los kinds anteriores a la migración lo mandan vacío. Va al FINAL del
	//payload — invariante 2, los campos nuevos nunca se insertan en medio.
	final String context;

	public BrowseListMessage(Kind kind, List<String> ids, List<Component> labels) {
		this(kind, ids, labels, "");
	}

	public BrowseListMessage(Kind kind, List<String> ids, List<Component> labels, String context) {
		this.kind = kind;
		this.ids = ids;
		this.labels = labels;
		this.context = context;
	}

	public BrowseListMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.labels = buffer.readList(FriendlyByteBuf::readComponent);
		this.context = buffer.readUtf();
	}

	public static void buffer(BrowseListMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.labels, FriendlyByteBuf::writeComponent);
		buffer.writeUtf(message.context);
	}

	/** El envío que repetían las ocho parejas: al jugador que pidió, con el contexto que su pantalla necesita. */
	public static void send(net.minecraft.server.level.ServerPlayer to, Kind kind, List<String> ids,
							 List<Component> labels, String context) {
		net.hawthorn.dndsheets.DndsheetsMod.PACKET_HANDLER.send(
			net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> to),
			new BrowseListMessage(kind, ids, labels, context));
	}

	public static void handler(BrowseListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> {
			switch (message.kind) {
				case MINE -> CharacterListScreen.open(message.ids, message.labels);
				case PARTY -> PartyScreen.open(message.ids, message.labels);
				case CONTENT -> CompendiumListScreen.open(message.ids, message.labels);
				case JOURNAL -> JournalScreen.open(message.ids, message.labels);
				case SUBCLASS -> SubclassScreen.open(message.ids, message.labels);
				case FEAT -> FeatScreen.open(message.ids, message.labels);
				//Una ficha suelta viaja como una lista de un elemento: mismo mensaje, sin una clase nueva
				//para transportar un texto largo.
				case DETAIL -> CompendiumEntryScreen.open(
					message.labels.isEmpty() ? "" : message.labels.get(0).getString());
				case GIVE_WEAPON -> net.hawthorn.dndsheets.client.gui.WeaponGiveListScreen.open(message.context, message.ids);
				case GIVE_SPELL -> net.hawthorn.dndsheets.client.gui.SpellGiveListScreen.open(message.context, message.ids);
				case GRANT_TRAIT -> net.hawthorn.dndsheets.client.gui.TraitGrantScreen.open(message.context, message.ids, plainLabels(message.labels));
				case PRESET -> net.hawthorn.dndsheets.client.gui.PresetScreen.open(message.context, false, message.ids, plainLabels(message.labels));
				case PRESET_MULTICLASS -> net.hawthorn.dndsheets.client.gui.PresetScreen.open("", true, message.ids, plainLabels(message.labels));
				case SPAWN_MONSTER -> net.hawthorn.dndsheets.client.gui.MonsterSpawnListScreen.open(message.ids);
				//El array JSON crudo viaja como etiqueta única, mismo truco que DETAIL: el cliente solo lo
				//reenvía a la pantalla, que ya sabía parsearlo.
				case MANAGE_OPTIONS -> net.hawthorn.dndsheets.client.gui.OptionsManageScreen.open(message.context,
					message.labels.isEmpty() ? "[]" : message.labels.get(0).getString());
				case CONTENT_ENTRY -> openContentEntries(message);
				//Se captura la pantalla activa para volver a ELLA al elegir o cancelar — la lista de pasos
				//(CharacterSetupScreen) también pide opciones y no hay que echarla a media configuración.
				case CHARACTER_OPTION -> net.hawthorn.dndsheets.client.gui.CharacterOptionListScreen.open(
					net.minecraft.client.Minecraft.getInstance().screen, message.context, message.ids);
				case ENCOUNTER -> openEncounters(message);
				//Bestiario + presupuesto del grupo: la pantalla se queda con la carga y recalcula sola.
				case ENCOUNTER_DESIGN -> net.hawthorn.dndsheets.client.gui.EncounterDesignerScreen.open(
					message.ids, message.labels, message.context);
			}
		});
	}

	//Cada fila dispara el /dndencounters spawn de siempre: el permiso y el efecto siguen en el comando.
	private static void openEncounters(BrowseListMessage message) {
		List<net.hawthorn.dndsheets.client.gui.CommandListScreen.Row> rows = new java.util.ArrayList<>(message.ids.size());
		for (int i = 0; i < message.ids.size(); i++) {
			Component label = i < message.labels.size() ? message.labels.get(i) : Component.literal(message.ids.get(i));
			rows.add(new net.hawthorn.dndsheets.client.gui.CommandListScreen.Row(label,
				"dndencounters spawn " + message.ids.get(i)));
		}
		net.hawthorn.dndsheets.client.gui.CommandListScreen.open(
			Component.translatable("gui.dndsheets.dm_panel.encounters"), rows);
	}

	private static void openContentEntries(BrowseListMessage message) {
		net.hawthorn.dndsheets.ContentType type;
		try {
			type = net.hawthorn.dndsheets.ContentType.valueOf(message.context);
		} catch (IllegalArgumentException e) {
			return; //Un context que no es un ContentType: mensaje corrupto o versión cruzada, se descarta.
		}
		net.hawthorn.dndsheets.client.gui.ContentEntryListScreen.open(type,
			message.labels.isEmpty() ? "[]" : message.labels.get(0).getString(),
			message.labels.size() < 2 ? "[]" : message.labels.get(1).getString());
	}

	private static List<String> plainLabels(List<Component> labels) {
		List<String> plain = new java.util.ArrayList<>(labels.size());
		for (Component label : labels) plain.add(label.getString());
		return plain;
	}
}
