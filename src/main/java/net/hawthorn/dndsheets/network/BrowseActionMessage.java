package net.hawthorn.dndsheets.network;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.Combatant;
import net.hawthorn.dndsheets.CompendiumQuery;
import net.hawthorn.dndsheets.Condition;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * <p>Cliente → servidor: "enséñame una lista que solo conoce el servidor". Empezó siendo solo el roster
 * de personajes y se ensanchó al compendio, que tiene exactamente la misma forma — el cliente no guarda
 * ni las hojas ni los registros de contenido, así que en ambos casos pide, el servidor formatea y el
 * cliente pinta. Se renombró de {@code RosterActionMessage} al ensancharse: un nombre que ya no describe
 * lo que hace la clase es deuda, no un detalle.</p>
 *
 * <p>Un solo mensaje parametrizado por {@link Action} en vez de una clase por consulta, siguiendo el
 * mismo patrón que {@link SheetAdjustMessage} (que ya agrupa siete acciones) y {@link ScreenActionMessage}.
 * El id de red no cambia al renombrar: se asigna por orden de registro, no por nombre.</p>
 *
 * <p>{@code LIST_PARTY} es la única que exige operador: ver la ficha de todo el mundo es información de
 * DM. Listar los personajes propios y cambiar entre ellos son acciones sobre lo tuyo, sin nada que gatear.</p>
 */
public class BrowseActionMessage {

	//Al final, nunca en medio: writeEnum viaja por ordinal (ver la invariante 2 de PROJECT_CONTEXT.md).
	public enum Action { LIST_MINE, LIST_PARTY, SWITCH, LIST_CONTENT, CONTENT_DETAIL }

	final Action action;
	final String characterId; //Solo lo usa SWITCH; las otras dos lo mandan vacío.

	public BrowseActionMessage(Action action) {
		this(action, "");
	}

	public BrowseActionMessage(Action action, String characterId) {
		this.action = action;
		this.characterId = characterId;
	}

	public BrowseActionMessage(FriendlyByteBuf buffer) {
		this.action = buffer.readEnum(Action.class);
		this.characterId = buffer.readUtf();
	}

	public static void buffer(BrowseActionMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.action);
		buffer.writeUtf(message.characterId);
	}

	public static void handler(BrowseActionMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer sender = context.getSender();
			if (sender == null) return;
			switch (message.action) {
				case LIST_MINE -> sendOwnCharacters(sender);
				case LIST_PARTY -> {
					//Se comprueba aquí y no solo al pintar el botón: un cliente modificado puede mandar el
					//mensaje igual, y el permiso tiene que valer del lado del servidor para significar algo.
					if (sender.hasPermissions(2)) sendParty(sender);
				}
				//El compendio es de consulta y no revela nada que el jugador no pueda ver ya en su Grimorio
				//o en la ficha de un monstruo al pelearlo: no se gatea por operador.
				case LIST_CONTENT -> CompendiumQuery.sendList(sender, message.characterId);
				case CONTENT_DETAIL -> CompendiumQuery.sendDetail(sender, message.characterId);
				case SWITCH -> {
					if (SheetLoader.switchCharacter(sender, message.characterId)) {
						JsonObject sheet = SheetLoader.getCharacterSheet(message.characterId);
						String name = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : message.characterId;
						sender.sendSystemMessage(Component.literal("Ahora llevas a " + name + ".").withStyle(ChatFormatting.GREEN));
						sendOwnCharacters(sender); //Reabre la lista con la marca ya movida, sin otro viaje de ida y vuelta.
					} else {
						sender.sendSystemMessage(Component.literal("Ese personaje no existe o no es tuyo.").withStyle(ChatFormatting.RED));
					}
				}
			}
		});
	}

	/** Público: también lo usa {@code /dndchar} sin argumentos, que ya está del lado del servidor. */
	public static void sendOwnCharacters(ServerPlayer player) {
		String activeId = SheetLoader.activeCharacterOf(player.getStringUUID());
		List<String> ids = new ArrayList<>();
		List<String> labels = new ArrayList<>();

		for (String characterId : SheetLoader.charactersOf(player.getStringUUID())) {
			JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
			String name = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : characterId;
			String characterClass = sheet != null && sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : "";
			ids.add(characterId);
			labels.add((characterId.equals(activeId) ? "▶ " : "   ") + name + (characterClass.isBlank() ? "" : " · " + characterClass));
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.MINE, ids, labels));
	}

	/**
	 * <p>Vista de grupo: cada jugador conectado con el personaje que lleva puesto, sus PG y CA reales y sus
	 * condiciones activas. Los PG y la CA salen del {@link Combatant}, no de la hoja, porque la hoja solo
	 * los refleja — y esto se mira en mitad de un combate, cuando lo que importa es el número de verdad.</p>
	 */
	private static void sendParty(ServerPlayer dm) {
		List<String> ids = new ArrayList<>();
		List<String> labels = new ArrayList<>();

		for (ServerPlayer player : dm.server.getPlayerList().getPlayers()) {
			Combatant combatant = Combatant.of(player);
			if (combatant == null) continue; //Sin hoja cargada todavía: no hay nada que enseñar de él.

			StringBuilder label = new StringBuilder(combatant.name())
				.append(" · PG ").append(combatant.currentHp()).append('/').append(combatant.maxHp())
				.append(" · CA ").append(combatant.armorClass());

			//Las condiciones son lo que un DM necesita ver de un vistazo y lo que hoy no se ve en ningún
			//sitio sin abrir la ficha de cada uno por separado.
			if (!combatant.conditions().isEmpty()) {
				label.append(" · ");
				boolean first = true;
				for (Condition condition : combatant.conditions()) {
					if (!first) label.append(", ");
					label.append(condition.label());
					first = false;
				}
			}

			ids.add(player.getStringUUID());
			labels.add(label.toString());
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm),
			new BrowseListMessage(BrowseListMessage.Kind.PARTY, ids, labels));
	}
}
