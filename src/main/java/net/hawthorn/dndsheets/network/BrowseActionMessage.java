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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
	public enum Action { LIST_MINE, LIST_PARTY, SWITCH, LIST_CONTENT, CONTENT_DETAIL, JOURNAL_DETAIL, DELETE, CREATE, SKILL_TOGGLE, LIST_SUBCLASSES, SUBCLASS_CHOOSE, LIST_FEATS, FEAT_CHOOSE }

	final Action action;
	//Lo usan SWITCH y DELETE (un id), CREATE (el nombre del personaje nuevo) y SKILL_TOGGLE (el índice de
	//la habilidad); las demás lo mandan vacío.
	//El campo es un texto libre, así que CREATE cabe aquí sin registrar un mensaje más — invariante 3.
	final String characterId;

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
				case JOURNAL_DETAIL -> sendJournalEntry(sender, message.characterId);
				case CREATE -> {
					String name = message.characterId.trim();
					//Se valida en el servidor aunque la pantalla ya lo haga: un cliente puede mandar lo que
					//quiera, y un personaje sin nombre no se puede ni elegir después por nombre.
					if (name.isEmpty()) {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.needs_name").withStyle(ChatFormatting.RED));
						return;
					}
					String created = SheetLoader.createCharacter(sender.getStringUUID(), name);
					//Creado pero NO puesto: ponérselo es una acción aparte y deliberada (ver
					//SheetLoader.createCharacter). Se dice, porque si no parece que no ha pasado nada.
					sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.created", name).withStyle(ChatFormatting.GREEN));
					//Sin esto, un personaje recién creado es una hoja en blanco y ninguna pista de que hay
					//cuatro cosas que elegir ni de dónde están.
					sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.setup_hint").withStyle(ChatFormatting.GRAY));
					sendOwnCharacters(sender); //Reabre la lista ya con el nuevo dentro.
					DndsheetsMod.LOGGER.info("dndsheets: personaje {} creado para {}", created, sender.getName().getString());
				}
				case DELETE -> {
					//El permiso solo abre la puerta a los PNJ del DM; el propio SheetLoader sigue negándose a
					//borrar el personaje de otro jugador, tenga el permiso que tenga quien lo pida.
					String error = SheetLoader.deleteCharacter(sender, message.characterId, sender.hasPermissions(2));
					if (error == null) {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.deleted", message.characterId).withStyle(ChatFormatting.GREEN));
						sendOwnCharacters(sender);
					} else {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.delete_failed").withStyle(ChatFormatting.RED));
					}
				}
				//Elegir en qué eres competente es una acción sobre tu propio personaje, como cambiar de uno
				//a otro: no se gatea por operador. Lo que NO puede hacer un jugador es escribir la expresión
				//—"skills" es una clave de solo-operador en SheetServerMessage, justo para eso—, así que aquí
				//el cliente manda un índice y la regla la escribe el servidor. Un cliente modificado solo
				//puede pedir competencia en una habilidad suya, que es lo que la pantalla ya ofrece.
				case SKILL_TOGGLE -> toggleSkill(sender, message.characterId);
				//La subclase es de tu personaje, como el resto de esta pantalla: sin operador. Lo que decide
				//qué puedes elegir lo pone el servidor (tu preset y tu nivel), no la lista que tenga el cliente.
				case LIST_SUBCLASSES -> sendSubclasses(sender);
				case SUBCLASS_CHOOSE -> chooseSubclass(sender, message.characterId);
				//Las dotes se listan siempre; lo que decide si se puede coger una es tener una mejora
				//pendiente, y eso lo comprueba LevelUpManager al elegirla.
				case LIST_FEATS -> sendFeats(sender);
				case FEAT_CHOOSE -> {
					if (!net.hawthorn.dndsheets.LevelUpManager.applyFeat(sender, message.characterId)) {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.levelup.feat_unavailable").withStyle(ChatFormatting.RED));
					}
				}
				case SWITCH -> {
					if (SheetLoader.switchCharacter(sender, message.characterId)) {
						JsonObject sheet = SheetLoader.getCharacterSheet(message.characterId);
						String name = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : message.characterId;
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.now_playing", name).withStyle(ChatFormatting.GREEN));
						sendOwnCharacters(sender); //Reabre la lista con la marca ya movida, sin otro viaje de ida y vuelta.
					} else {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.no_such").withStyle(ChatFormatting.RED));
					}
				}
			}
		});
	}

	private static void sendFeats(ServerPlayer player) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		List<String> taken = net.hawthorn.dndsheets.FeatRegistry.takenBy(sheet);
		int level = SheetLoader.characterLevelOf(sheet);
		List<String> ids = new ArrayList<>();
		List<Component> labels = new ArrayList<>();
		for (String id : net.hawthorn.dndsheets.FeatRegistry.ids()) {
			net.hawthorn.dndsheets.FeatRegistry.Feat feat = net.hawthorn.dndsheets.FeatRegistry.get(id);
			//Las que aun no le tocan por nivel SI se quitan, al contrario que las ya cogidas: un Don Epico de
			//nivel 19 en la lista de un nivel 4 no es informacion, es una opcion que el servidor va a rechazar.
			if (!net.hawthorn.dndsheets.FeatRegistry.availableAt(feat, level)) continue;
			ids.add(id);
			//Las que ya tiene se mandan marcadas en vez de quitarlas: que una lista encoja sin explicación
			//se lee como que falta contenido, y esto es justo lo contrario.
			labels.add(Component.literal((taken.contains(id) ? "✔ " : "") + feat.name()));
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.FEAT, ids, labels));
	}

	private static void sendSubclasses(ServerPlayer player) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		List<String> ids = new ArrayList<>();
		List<Component> labels = new ArrayList<>();
		for (net.hawthorn.dndsheets.PresetRegistry.Subclass subclass
				: net.hawthorn.dndsheets.PresetRegistry.availableSubclasses(sheet)) {
			ids.add(subclass.id());
			labels.add(Component.literal(subclass.name()));
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.SUBCLASS, ids, labels));
	}

	private static void chooseSubclass(ServerPlayer player, String subclassId) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		if (!net.hawthorn.dndsheets.PresetRegistry.applySubclass(sheet, subclassId)) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.character.subclass_unavailable").withStyle(ChatFormatting.RED));
			return;
		}

		SheetLoader.saveServer(sheet, player.getStringUUID());
		player.sendSystemMessage(Component.translatable("chat.dndsheets.character.subclass_chosen",
			sheet.get("characterSubclass").getAsString()).withStyle(ChatFormatting.GREEN));
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	private static void toggleSkill(ServerPlayer player, String rawIndex) {
		int index;
		try {
			index = Integer.parseInt(rawIndex.trim());
		} catch (NumberFormatException e) {
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		SheetLoader.validateSheet(sheet); //Una hoja vieja puede no tener aún las 18 habilidades.

		boolean proficient = !net.hawthorn.dndsheets.RollIndex.isSkillProficient(sheet, index);
		if (!net.hawthorn.dndsheets.RollIndex.setSkillProficiency(sheet, index, proficient)) return;

		//Invariante 4: lo que cambia una hoja tiene que llegar a saveServer. El autoguardado es una red de
		//seguridad, no el camino de escritura.
		SheetLoader.saveServer(sheet, player.getStringUUID());
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	/**
	 * <p>El diario con lo que ESE jugador puede leer. El filtro se aplica en el servidor y no en el
	 * cliente: mandar entradas que luego se ocultan al pintarlas dejaría los secretos del DM en la memoria
	 * de quien no debe verlos, que no es ocultarlos.</p>
	 */
	public static void sendJournal(ServerPlayer player) {
		List<String> ids = new ArrayList<>();
		List<Component> labels = new ArrayList<>();
		boolean isDm = player.hasPermissions(2);
		for (net.hawthorn.dndsheets.JournalManager.Entry entry : net.hawthorn.dndsheets.JournalManager.readableBy(player)) {
			ids.add(entry.id());
			//La etiqueta de visibilidad solo se le enseña al DM: a un jugador no le aporta nada saber que
			//lo que acaba de recibir es "2 jugadores", y sí le dice que hay alguien más en el ajo.
			labels.add(isDm
				? Component.translatable("gui.dndsheets.journal.row", entry.title(), entry.visibilityLabel())
				: Component.literal(entry.title()));
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.JOURNAL, ids, labels));
	}

	//Se vuelve a comprobar la visibilidad al pedir la entrada concreta: la lista que tiene el cliente pudo
	//quedarse vieja, y un cliente modificado puede pedir cualquier id. Filtrar solo al listar no es filtrar.
	private static void sendJournalEntry(ServerPlayer player, String id) {
		net.hawthorn.dndsheets.JournalManager.Entry entry = net.hawthorn.dndsheets.JournalManager.get(id);
		if (entry == null || !entry.canRead(player)) return;
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.DETAIL, List.of(id),
				List.of(Component.literal(entry.title() + "\n" + entry.body()))));
	}

	/** Público: también lo usa {@code /dndchar} sin argumentos, que ya está del lado del servidor. */
	public static void sendOwnCharacters(ServerPlayer player) {
		String activeId = SheetLoader.activeCharacterOf(player.getStringUUID());
		List<String> ids = new ArrayList<>();
		List<Component> labels = new ArrayList<>();

		List<String> owned = SheetLoader.charactersOf(player.getStringUUID());
		for (String characterId : owned) {
			JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
			//Con el id detrás solo si otro se llama igual (ver CharacterRules.suggestionLabelFor): pulsar una
			//fila manda el id exacto, así que aquí no había ambigüedad que resolver — pero dos filas idénticas
			//obligan a elegir a ciegas cuál es cuál.
			String label = SheetLoader.suggestionLabelFor(owned, characterId);
			String characterClass = sheet != null && sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : "";
			ids.add(characterId);
			labels.add(Component.literal((characterId.equals(activeId) ? "▶ " : "   ") + label + (characterClass.isBlank() ? "" : " · " + characterClass)));
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.MINE, ids, labels));
	}

	/**
	 * <p>Vista de grupo: cada jugador conectado con el personaje que lleva puesto, y cada PNJ con cuerpo en
	 * el mundo, con sus PG y CA reales y sus condiciones activas. Los PG y la CA salen del
	 * {@link Combatant}, no de la hoja, porque la hoja solo los refleja — y esto se mira en mitad de un
	 * combate, cuando lo que importa es el número de verdad.</p>
	 *
	 * <p>Los PNJ entran aquí porque juegan con las reglas completas de un PJ (ver
	 * {@code Combatant.NpcCombatant}) y no salían en ninguna lista: para saber cómo iba el que acompaña al
	 * grupo había que ir a buscarlo y mirarlo. Se recorren las <b>entidades cargadas</b> y no las fichas
	 * ({@code SheetLoader.npcIds}) a propósito — una ficha sin cuerpo no está en la partida, y un PNJ con
	 * dos cuerpos son dos cosas distintas que atender. El recorrido se paga al abrir un menú, nunca en un
	 * bucle de combate, que es el mismo criterio con el que {@code npcIds} recorre todas las hojas.</p>
	 */
	private static void sendParty(ServerPlayer dm) {
		List<String> ids = new ArrayList<>();
		List<Component> labels = new ArrayList<>();

		for (ServerPlayer player : dm.server.getPlayerList().getPlayers()) {
			Combatant combatant = Combatant.of(player);
			if (combatant == null) continue; //Sin hoja cargada todavía: no hay nada que enseñar de él.
			ids.add(player.getStringUUID());
			labels.add(partyRow(combatant));
		}

		for (ServerLevel level : dm.server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				String characterId = Combatant.characterIdOf(entity);
				if (characterId == null) continue;
				//Ficha borrada con el cuerpo todavía en el mundo: Combatant.of cae a monstruo o a null. Sin
				//ficha ya no es un PNJ, así que tampoco es del grupo.
				if (!(Combatant.of(entity) instanceof Combatant.NpcCombatant combatant)) continue;
				ids.add(characterId);
				labels.add(partyRow(combatant).copy()
					.append(Component.translatable("gui.dndsheets.party.npc_tag").withStyle(ChatFormatting.DARK_GRAY)));
			}
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm),
			new BrowseListMessage(BrowseListMessage.Kind.PARTY, ids, labels));
	}

	/** Una fila del grupo: nombre, PG, CA y las condiciones que lleva encima. */
	private static Component partyRow(Combatant combatant) {
		StringBuilder label = new StringBuilder(combatant.name())
			.append(" · PG ").append(combatant.currentHp()).append('/').append(combatant.maxHp())
			.append(" · CA ").append(combatant.armorClass());

		//Las condiciones son lo que un DM necesita ver de un vistazo y lo que si no no se ve en ningún
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
		return Component.literal(label.toString());
	}
}
