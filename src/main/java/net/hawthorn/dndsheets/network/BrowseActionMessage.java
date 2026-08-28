package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.ContentNames;

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
	//Desde GIVE_WEAPONS hacia abajo son las antiguas parejas *ListRequestMessage (ocho clases casi
	//idénticas: pedir una lista que solo vive en memoria del servidor), fundidas aquí — invariante 3.
	//characterId, que ya era texto libre, carga el uuid del objetivo, la categoría o el ContentType.
	public enum Action { LIST_MINE, LIST_PARTY, SWITCH, LIST_CONTENT, CONTENT_DETAIL, JOURNAL_DETAIL, DELETE, CREATE, SKILL_TOGGLE, LIST_SUBCLASSES, SUBCLASS_CHOOSE, LIST_FEATS, FEAT_CHOOSE,
		GIVE_WEAPONS, GIVE_SPELLS, GRANT_TRAITS, LIST_PRESETS, LIST_PRESETS_MULTICLASS, SPAWN_MONSTERS,
		MANAGE_OPTIONS, CONTENT_ENTRIES, CHARACTER_OPTIONS, LIST_ENCOUNTERS,
		SPELL_PREPARE, SPELL_UNPREPARE, DESIGN_ENCOUNTER }

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
					if (DndsheetsMod.canActAsDm(sender)) sendParty(sender);
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
					String error = SheetLoader.deleteCharacter(sender, message.characterId, DndsheetsMod.canActAsDm(sender));
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
				//Los cuatro "dar/conceder a otro jugador" del Panel de DM: mismo candado servidor-side que
				//LIST_PARTY (un cliente modificado puede mandar lo que quiera), lista del registro que toca,
				//y el uuid del objetivo viaja de vuelta en context para la pantalla que se abre.
				case GIVE_WEAPONS -> {
					if (DndsheetsMod.canActAsDm(sender)) BrowseListMessage.send(sender, BrowseListMessage.Kind.GIVE_WEAPON,
						new ArrayList<>(net.hawthorn.dndsheets.Config.loadedWeaponIds()), List.of(), message.characterId);
				}
				case GIVE_SPELLS -> {
					if (DndsheetsMod.canActAsDm(sender)) BrowseListMessage.send(sender, BrowseListMessage.Kind.GIVE_SPELL,
						new ArrayList<>(net.hawthorn.dndsheets.SpellRegistry.ids()), List.of(), message.characterId);
				}
				case GRANT_TRAITS -> {
					if (DndsheetsMod.canActAsDm(sender)) sendTraits(sender, message.characterId);
				}
				case LIST_PRESETS -> sendPresets(sender, message.characterId, false);
				//Preparar y despreparar son acciones sobre TU propia lista, como cambiar de personaje o
				//marcar una competencia: sin operador. El límite lo pone el servidor (ver
				//CharacterRules.preparedLimitFor), no la pantalla — un cliente modificado no se salta nada.
				case SPELL_PREPARE -> setPrepared(sender, message.characterId, true);
				case SPELL_UNPREPARE -> setPrepared(sender, message.characterId, false);
				//El botón "Multiclasear" de la ficha es sobre uno mismo, nunca sobre otro jugador.
				case LIST_PRESETS_MULTICLASS -> sendPresets(sender, "", true);
				case SPAWN_MONSTERS -> {
					if (DndsheetsMod.canActAsDm(sender)) BrowseListMessage.send(sender, BrowseListMessage.Kind.SPAWN_MONSTER,
						new ArrayList<>(net.hawthorn.dndsheets.MonsterRegistry.ids()), List.of(), "");
				}
				case MANAGE_OPTIONS -> {
					if (DndsheetsMod.canActAsDm(sender)) sendOptions(sender, BrowseListMessage.Kind.MANAGE_OPTIONS, message.characterId);
				}
				case CONTENT_ENTRIES -> {
					if (DndsheetsMod.canActAsDm(sender)) sendContentEntries(sender, message.characterId);
				}
				//Sin permiso especial: cualquier jugador elige su propia raza/trasfondo/clase. Es el camino de
				//respaldo cuando el addon species (que delega en Origins) no está instalado.
				case CHARACTER_OPTIONS -> sendCharacterOptions(sender, message.characterId);
				case LIST_ENCOUNTERS -> {
					if (DndsheetsMod.canActAsDm(sender)) sendEncounters(sender);
				}
				//El diseñador arma un encuentro NUEVO, así que lo que necesita del servidor no es la lista de
				//encuentros sino el bestiario con su coste en PX y los umbrales del grupo: con eso el cliente
				//recalcula la dificultad a cada clic sin otro viaje por fila (ver EncounterDesignerScreen).
				case DESIGN_ENCOUNTER -> {
					if (DndsheetsMod.canActAsDm(sender)) sendEncounterDesign(sender);
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

	private static void sendTraits(ServerPlayer dm, String targetUuid) {
		List<String> ids = new ArrayList<>(net.hawthorn.dndsheets.TraitRegistry.ids());
		List<Component> names = new ArrayList<>(ids.size());
		for (String id : ids) {
			net.hawthorn.dndsheets.TraitRegistry.Trait trait = net.hawthorn.dndsheets.TraitRegistry.get(id);
			names.add(ContentNames.of(trait != null ? trait.name() : id));
		}
		BrowseListMessage.send(dm, BrowseListMessage.Kind.GRANT_TRAIT, ids, names, targetUuid);
	}

	//La validación que traía PresetListRequestMessage, intacta: con objetivo ajeno hace falta permiso de
	//DM y que el jugador exista; un uuid malformado de un cliente roto se descarta en vez de tumbar el
	//hilo del servidor con la excepción sin capturar.
	private static void sendPresets(ServerPlayer player, String targetUuid, boolean multiclass) {
		if (!targetUuid.isEmpty()) {
			if (!DndsheetsMod.canActAsDm(player)) return;
			try {
				if (player.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(targetUuid)) == null) return;
			} catch (IllegalArgumentException e) {
				return;
			}
		}
		List<String> ids = net.hawthorn.dndsheets.PresetManager.presetIds();
		List<Component> names = new ArrayList<>(ids.size());
		for (String name : net.hawthorn.dndsheets.PresetManager.presetNames(ids)) names.add(Component.literal(name));
		BrowseListMessage.send(player,
			multiclass ? BrowseListMessage.Kind.PRESET_MULTICLASS : BrowseListMessage.Kind.PRESET,
			ids, names, targetUuid);
	}

	/**
	 * <p>La lista viva de una categoría de {@code CharacterOptionsRegistry} como array JSON en etiqueta
	 * única (mismo truco que DETAIL). Público: también la reenvían como eco {@code OptionsSaveMessage} y
	 * los guardados del creador de contenido, que antes duplicaban este envío cada uno por su lado.</p>
	 */
	public static void sendOptions(ServerPlayer player, BrowseListMessage.Kind kind, String category) {
		if (!net.hawthorn.dndsheets.CharacterOptionsRegistry.isValidCategory(category)) return;
		com.google.gson.JsonArray array = new com.google.gson.JsonArray();
		for (String value : net.hawthorn.dndsheets.CharacterOptionsRegistry.get(category)) array.add(value);
		BrowseListMessage.send(player, kind, List.of(), List.of(Component.literal(array.toString())), category);
	}

	/** Público: también lo reenvían como eco los guardados/borrados del creador de contenido. */
	public static void sendContentEntries(ServerPlayer dm, String typeName) {
		net.hawthorn.dndsheets.ContentType type;
		try {
			type = net.hawthorn.dndsheets.ContentType.valueOf(typeName);
		} catch (IllegalArgumentException e) {
			return;
		}
		//Dos arrays: lo que creó el DM (editable y borrable) y lo que viene del pack (se enseña para poder
		//partir de ello — guardar una copia con el mismo id la deja mandando, ver ContentPackFile).
		String mine = net.hawthorn.dndsheets.ContentPackFile.readArrayText(type.dmCreatedFile());
		String fromPacks = net.hawthorn.dndsheets.ContentPackFile.readOtherArraysText(type.dir, type.dmCreatedFile());
		BrowseListMessage.send(dm, BrowseListMessage.Kind.CONTENT_ENTRY, List.of(),
			List.of(Component.literal(mine), Component.literal(fromPacks)), type.name());
	}

	//Cada fila viaja con su descripción de composición ("goblin x4, lobo x2") para que el DM elija
	//sabiendo qué invoca; el clic del cliente dispara el /dndencounters spawn de siempre.
	private static void sendEncounters(ServerPlayer dm) {
		List<String> ids = new ArrayList<>(net.hawthorn.dndsheets.EncounterRegistry.ids());
		java.util.Collections.sort(ids);
		List<Component> labels = new ArrayList<>(ids.size());
		for (String id : ids) {
			net.hawthorn.dndsheets.EncounterRegistry.Encounter encounter = net.hawthorn.dndsheets.EncounterRegistry.get(id);
			//El nombre del encuentro sale del pack, asi que va como Component; la composicion
			//("goblin x4, lobo x2") la arma describe() en texto plano — ver ContentNames.plain.
			if (encounter == null) {
				labels.add(Component.literal(id));
				continue;
			}
			net.minecraft.network.chat.MutableComponent label = ContentNames.of(encounter.name())
				.append(" · " + net.hawthorn.dndsheets.EncounterRegistry.describe(encounter));
			//Y qué tan dura le sale a los que están conectados ahora mismo: es la mitad de la pregunta al
			//elegir de una lista de encuentros preparados, y hasta acá solo se veía la composición.
			int rating = net.hawthorn.dndsheets.EncounterBudget.rate(encounter, dm.server);
			if (rating >= 0) label.append(" · ").append(difficultyName(rating));
			labels.add(label);
		}
		BrowseListMessage.send(dm, BrowseListMessage.Kind.ENCOUNTER, ids, labels, "");
	}

	/** La palabra del veredicto ("Media", "Mortal") — ver {@code EncounterBudget.RATINGS}. */
	public static Component difficultyName(int rating) {
		return Component.translatable("gui.dndsheets.encounter.difficulty." + net.hawthorn.dndsheets.EncounterBudget.RATINGS[rating]);
	}

	//El bestiario con su coste en PX estimado, más los umbrales del grupo conectado, en el context como
	//JSON (mismo truco que sendOptions/sendContentEntries: una carga que no es una lista de filas viaja
	//como texto en vez de inventarse un mensaje nuevo — invariante 3).
	private static void sendEncounterDesign(ServerPlayer dm) {
		List<String> ids = new ArrayList<>(net.hawthorn.dndsheets.MonsterRegistry.ids());
		java.util.Collections.sort(ids);
		List<Component> names = new ArrayList<>(ids.size());
		com.google.gson.JsonArray xp = new com.google.gson.JsonArray();
		for (String id : ids) {
			net.hawthorn.dndsheets.MonsterRegistry.MonsterStatBlock block = net.hawthorn.dndsheets.MonsterRegistry.get(id);
			names.add(ContentNames.of(block != null ? block.name() : id));
			xp.add(net.hawthorn.dndsheets.EncounterBudget.xp(id));
		}

		List<Integer> levels = net.hawthorn.dndsheets.EncounterBudget.partyLevels(dm.server);
		com.google.gson.JsonArray thresholds = new com.google.gson.JsonArray();
		for (int threshold : net.hawthorn.dndsheets.EncounterBudget.thresholds(levels)) thresholds.add(threshold);
		JsonObject payload = new JsonObject();
		payload.add("xp", xp);
		payload.add("t", thresholds);
		payload.addProperty("p", levels.size());

		BrowseListMessage.send(dm, BrowseListMessage.Kind.ENCOUNTER_DESIGN, ids, names, payload.toString());
	}

	private static void sendCharacterOptions(ServerPlayer player, String category) {
		if (!net.hawthorn.dndsheets.CharacterOptionsRegistry.isValidCategory(category)) return;
		BrowseListMessage.send(player, BrowseListMessage.Kind.CHARACTER_OPTION,
			net.hawthorn.dndsheets.CharacterOptionsRegistry.get(category), List.of(), category);
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
			labels.add(Component.literal(taken.contains(id) ? "✔ " : "").append(ContentNames.of(feat.name())));
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
			labels.add(ContentNames.of(subclass.name()));
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

	/**
	 * <p>Marca o desmarca un hechizo como preparado en la hoja de quien lo pide. Preparar por encima del
	 * límite se rechaza con un aviso; despreparar nunca se rechaza — bajar siempre es válido, y hace falta
	 * poder bajar para poder cambiar de lista.</p>
	 */
	private static void setPrepared(ServerPlayer sender, String spellId, boolean prepared) {
		JsonObject sheet = SheetLoader.getServerSheet(sender.getStringUUID());
		if (sheet == null) return;

		int limit = net.hawthorn.dndsheets.SpellRegistry.preparedLimitFor(sheet);
		//Límite 0 = no es una clase lanzadora, así que no hay lista que gestionar y la regla no se dispara.
		if (limit <= 0) return;
		if (prepared && net.hawthorn.dndsheets.SpellRegistry.preparedCount(sheet) >= limit) {
			sender.sendSystemMessage(Component.translatable("chat.dndsheets.spell.prepared_full", limit)
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!net.hawthorn.dndsheets.SpellRegistry.setPrepared(sheet, spellId, prepared)) return;
		//Invariante 4: la lista de preparados es estado de la hoja y se pierde en el reinicio si no se
		//guarda. saveAndSync y no saveServer porque la pantalla se repinta desde la hoja completa.
		SheetLoader.saveAndSync(sender, sheet);
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
		boolean isDm = DndsheetsMod.canActAsDm(player);
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
				//Id vacío a propósito: la fila de un PNJ no es clicable (Ajustes de hoja resuelve por
				//jugador conectado, y un PNJ no lo es) — ver PartyScreen.
				ids.add("");
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
