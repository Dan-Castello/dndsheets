package net.hawthorn.dndsheets;

import com.google.gson.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.hawthorn.dndsheets.network.TutorialOpenMessage;
import net.hawthorn.dndsheets.api.event.SheetValidateEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.*;
import java.util.stream.Stream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
//Interno: no forma parte de la API pública versionada del mod (ver net.hawthorn.dndsheets.api.DndSheetsApi
//y su API_VERSION). Un mod externo que llame estos métodos directo en vez de a través de la fachada se
//expone a que cambien de firma sin aviso.
public class SheetLoader {

	public static final Path GAME_DIR = FMLPaths.GAMEDIR.get();
	public static final Path SHEETS_DIR = GAME_DIR.resolve("charactersheets");

	//Sin esto no había forma programática de saber si una hoja en disco es de una versión anterior del
	//mod — ver AUDIT_TECHNICAL.md M-SEC-1. Subir este número solo tiene sentido el día que un campo
	//existente cambie de forma de verdad (no cuando se añade uno nuevo: eso ya lo cubre validateSheet
	//solo, sin necesidad de versión).
	public static final int CURRENT_SCHEMA_VERSION = 1;
	/**
	 * <p>Todas las hojas cargadas, <b>indexadas por id de personaje</b>, no por UUID de jugador. Hasta Fase 1
	 * eran lo mismo: una hoja por jugador, para siempre, así que no existía el concepto de "personaje" —
	 * y sin él no se podía tener un segundo PJ, ni una hoja de PNJ, ni cambiar de personaje, ni archivar
	 * una campaña. Ahora un id de personaje es cualquier cadena apta para nombre de archivo; el UUID del
	 * jugador sigue siendo un id válido, que es exactamente lo que hace que todo lo guardado antes de este
	 * cambio siga funcionando sin migración: su archivo ya se llamaba así.</p>
	 */
	private static HashMap<String, JsonObject> sheets = new HashMap<String, JsonObject>(); //Privado: sin consumo externo confirmado (ver AUDIT_TECHNICAL.md M-API-1), solo se lee/escribe a través de getServerSheet/saveServer, que ya validan/loguean.

	/**
	 * <p>UUID de jugador → id del personaje que lleva ahora mismo. Es una caché derivada de las propias
	 * hojas (el campo {@code active} de cada una), no una fuente de verdad aparte: se reconstruye entera en
	 * {@link #load()} y se actualiza al cambiar de personaje. Existe solo porque {@code getServerSheet} se
	 * llama en cada golpe de cada combate y recorrer todas las hojas ahí sería absurdo.</p>
	 */
	private static final Map<String, String> activeCharacter = new HashMap<>();
	private static JsonObject current = null; //Currently active character sheet. Important for populating GUIs when they're opened and knowing which to save to.

	@SubscribeEvent
	public static void init(FMLCommonSetupEvent event) {
		MinecraftForge.EVENT_BUS.register(new SheetLoader());
	}

	@SubscribeEvent
	public static void clientLoad(FMLClientSetupEvent event) {
		//There isn't much of a need to do anything here. From here, we can expect the client to be handed the character sheet associated with it through ClientSheetMessage.
		//After that, the client can update the server on its sheet through ServerSheetMessage.
		//The sheets need to be kept on the server in the first place for the /roll command and roll buttons to work (since those send serverwide messages).

	}

	@SubscribeEvent
	public void clientJoinedServer(EntityJoinLevelEvent event) {
		//This needs to do two things:
		//1. When a player joins, it should check for their sheet and then give them a packet with it.
		//2. If the player doesn't have one on the server, it'll make one with their UUID first and THEN give the packet.
		//En singleplayer/LAN este evento también dispara del lado del ClientLevel del propio cliente (el
		//jugador ahí es un LocalPlayer, no un ServerPlayer) — sin este filtro, castear más abajo lanzaba
		//ClassCastException cada vez que alguien se unía a un mundo integrado.
		if (event.getLevel().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer entity)) return;

		UUID uuid = entity.getUUID();
		String uuidString = uuid.toString();
		//Antes se llamaba load() aquí sin condición, y este evento no es solo "el jugador entró al servidor
		//por primera vez": EntityJoinLevelEvent también dispara en cada respawn y cada cambio de dimensión
		//(portal Nether/End) de CUALQUIER jugador. Eso reparseaba TODAS las hojas del servidor desde disco
		//cada vez — I/O síncrona bloqueante en el hilo del servidor, repetida sin necesidad. sheets solo
		//está vacío antes de la primera carga real (útil para mundo integrado/LAN, donde
		//FMLDedicatedServerSetupEvent.serverLoad nunca dispara); en cualquier evento posterior las hojas ya
		//están en memoria (makeNew/saveServer las mantiene actualizadas ahí) y no hace falta releerlas.
		if (sheets.isEmpty()) load();
		//Verdadero solo la primerísima vez que esta UUID recibe una hoja — nunca vuelve a serlo (queda en
		//memoria/disco desde acá en adelante), y sigue siendo falso para un jugador ya existente incluso tras
		//reiniciar el servidor (load() ya repobló "sheets" desde disco antes de esta línea). Es, sin
		//necesidad de ningún campo nuevo en la hoja, exactamente la señal de "está entrando al mundo por
		//primera vez" que necesita el tutorial de abajo.
		boolean brandNew = SheetLoader.getServerSheet(uuidString) == null;
		if (brandNew) {
			makeNew("New Sheet", uuidString);
		};

		applyClassHitPoints(entity, SheetLoader.getServerSheet(uuidString));

		try {
			byte[] data = SheetLoader.getServerSheet(uuidString).toString().getBytes();
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> entity), new SheetClientMessage(data));
			DeathSaveManager.resendStateOnJoin(entity, SheetLoader.getServerSheet(uuidString));
			//Reconectarse durante un combate le da al jugador un entityId nuevo; sin esto quedaba bloqueado
			//sin poder actuar por el resto del encuentro (ver TurnManager.reconcilePlayerEntity).
			TurnManager.reconcilePlayerEntity(entity);
		}
		catch(Exception e) {
			DndsheetsMod.LOGGER.error("Fallo al enviar la hoja al jugador que se conectó.", e);
		}

		//Primer ingreso al mundo: abre la Guía sola, con un pequeño retraso para no pelear con la pantalla
		//de carga del mundo (todavía visible en el cliente justo cuando dispara este evento). Reengancha al
		//jugador por UUID al disparar en vez de capturar "entity" directo — mismo patrón que
		//BarbarianRageManager.activate — por si se desconecta durante los ~3 segundos de espera.
		if (brandNew) {
			UUID playerId = uuid;
			MinecraftServer server = entity.getServer();
			boolean isDm = entity.hasPermissions(2);
			DndsheetsMod.queueServerWork(60, () -> {
				ServerPlayer stillHere = server.getPlayerList().getPlayer(playerId);
				if (stillHere != null) {
					DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> stillHere), new TutorialOpenMessage(isDm));
				}
			});
		}
	}

	//Sin esto no había ninguna forma de enterarse de la tecla H (u P para operadores) salvo que alguien te
	//lo dijera aparte — se manda una sola vez por login real (no en cada respawn/cambio de dimensión, que
	//también dispara EntityJoinLevelEvent, por eso esto vive en PlayerLoggedInEvent y no ahí).
	@SubscribeEvent
	public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		player.sendSystemMessage(Component.translatable("chat.dndsheets.welcome.sheet_key").withStyle(ChatFormatting.GRAY));
		if (player.hasPermissions(2)) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.welcome.dm_key").withStyle(ChatFormatting.GRAY));
		}
	}

	private static final UUID CLASS_HP_MODIFIER_ID = UUID.fromString("6f2f8f0a-3b1a-4c8e-9d2a-1a2b3c4d5e6f");

	/**
	 * <p>PG máximos de una hoja por clase, nivel y Constitución (regla de media del SRD: dado completo a
	 * nivel 1, media+1 por nivel siguiente). Extraído de {@link #applyClassHitPoints}, que solo sabía
	 * aplicárselos a un {@code Player} real: una ficha de PNJ necesita el <em>número</em>, porque no tiene
	 * atributo de salud de Minecraft donde reflejarlo.</p>
	 */
	public static int maxHitPointsFor(JsonObject sheet, int level) {
		return CharacterRules.maxHitPointsFor(sheet, level);
	}

	/**
	 * <p>Nivel de personaje de una hoja sin jugador detrás. La sobrecarga con {@code Player} cae al XP real
	 * de Minecraft cuando el DM no fijó un nivel; una ficha de PNJ no tiene XP del que caer, así que empieza
	 * en 1 — en 5e ningún personaje es de nivel 0.</p>
	 */
	public static int characterLevelOf(JsonObject sheet) {
		return CharacterRules.levelOf(sheet);
	}

	private static int sheetInt(JsonObject sheet, String key, int fallback) {
		if (!sheet.has(key)) return fallback;
		try {
			return Integer.parseInt(sheet.get(key).getAsString());
		} catch (RuntimeException e) {
			//RuntimeException, no solo NumberFormatException: sheet.get(key) puede ser un JsonObject/JsonArray
			//si una hoja vieja quedó corrupta antes de que SheetServerMessage empezara a validar tipos, y
			//.getAsString() sobre eso lanza UnsupportedOperationException, no NumberFormatException.
			return fallback;
		}
	}

	/**
	 * <p>Sets the player's real Minecraft max health from their D&D class/level/constitution,
	 * so hit points stop being a flat vanilla 20 no matter what class they picked (5e average-HP rule).</p>
	 */
	public static void applyClassHitPoints(Player entity, JsonObject sheet) {
		if (entity == null || sheet == null) return;
		AttributeInstance maxHealthAttr = entity.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealthAttr == null) return;

		//characterLevelOf, no sheetInt(sheet, "level", ...): "level" es el XP real de Minecraft reflejado en
		//la hoja, pero en cuanto el DM fija un nivel de personaje con /dndsheet setlevel (characterLevel),
		//el PG máximo debe escalar con ESE nivel — si no, "desacoplar el nivel del XP" no desacoplaba nada
		//para el PG máximo, justo la razón más obvia de tener un nivel de personaje en 5e.
		int maxHp = maxHitPointsFor(sheet, Math.max(1, characterLevelOf(sheet, entity)));

		maxHealthAttr.removeModifier(CLASS_HP_MODIFIER_ID);
		maxHealthAttr.addPermanentModifier(new AttributeModifier(CLASS_HP_MODIFIER_ID, "dndsheets class hit points", maxHp - maxHealthAttr.getBaseValue(), AttributeModifier.Operation.ADDITION));

		if (entity.getHealth() > maxHealthAttr.getValue())
			entity.setHealth((float) maxHealthAttr.getValue());
	}

	@SubscribeEvent
	public static void serverLoad(FMLDedicatedServerSetupEvent event) {
		//This sets up the SheetLoader on the server side via loading all the sheets saved there.
		load();
	}

	//Guardado periódico + al apagar el servidor: saveServer (el ÚNICO punto de todo el mod que escribe una
	//hoja a disco) antes solo se llamaba al crear una hoja nueva o cuando el propio jugador reabría su
	//pantalla de hoja de personaje (ver network.SheetServerMessage) — cualquier otro cambio (gastar un
	//espacio de conjuro, un descanso, /dndsheet setslots, aplicar un preset, recursos de clase...) solo
	//tocaba la copia en memoria y se perdía en silencio en cuanto el servidor se reiniciaba, no solo los
	//espacios de conjuro. Guardar TODAS las hojas periódicamente y al apagar cierra el hueco de raíz, sin
	//tener que acordarse de llamar a saveServer en cada uno de los ~10 sitios que cambian una hoja.
	private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 60 * 5; //5 minutos.

	//Instancia, no static: ServerStartingEvent/ServerStoppingEvent son eventos del bus FORGE, no del bus
	//MOD al que apunta la anotación @Mod.EventBusSubscriber de esta clase (de ahí que serverLoad/init/
	//clientLoad de arriba sí puedan ser static — son eventos de ciclo de vida del MOD). Se enganchan igual
	//que clientJoinedServer, vía el mismo MinecraftForge.EVENT_BUS.register(new SheetLoader()) que init()
	//ya hace más abajo — sin esto, Forge rechaza el método entero al cargar el mod (IllegalArgumentException,
	//"takes an argument that is not a subtype of ... IModBusEvent").
	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event) {
		scheduleAutosave();
	}

	private static void scheduleAutosave() {
		DndsheetsMod.queueServerWork(AUTOSAVE_INTERVAL_TICKS, () -> {
			saveAll();
			scheduleAutosave();
		});
	}

	//Último respaldo: si el reinicio es limpio (/stop, reinicio de la instancia), esto se dispara ANTES de
	//que el proceso muera, así que ningún cambio hecho en los últimos minutos (menos que el intervalo de
	//arriba) se pierde por mala suerte de temporización.
	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		saveAll();
	}

	private static void saveAll() {
		//saveCharacter y NO saveServer: las claves de "sheets" ya son ids de personaje, y saveServer las
		//volvería a pasar por activeCharacterOf. Para un jugador con un segundo personaje puesto, eso
		//escribiría el contenido de su hoja vieja encima del archivo del personaje activo.
		for (Map.Entry<String, JsonObject> entry : sheets.entrySet()) {
			saveCharacter(entry.getKey(), entry.getValue());
		}
	}

	public static JsonObject getClientSheet() {
		if (current == null) {
			DndsheetsMod.LOGGER.warn("Client sheet returned null. Are you sure you're not calling this from the server side?");
		}
		return current;
	}

	/**
	 * <p>Hoja del personaje que ese jugador lleva ahora mismo. La firma no cambió al separar personaje de
	 * jugador (Fase 1) a propósito: los ~68 sitios que la llaman quieren "la hoja de quien está jugando",
	 * y esa pregunta sigue teniendo la misma respuesta — solo que ahora pasa por una indirección.</p>
	 *
	 * <p>También acepta un id de personaje directo (un PNJ, o un PJ que su dueño no lleva puesto): un id
	 * sin binding se resuelve a sí mismo, así que no hace falta un método aparte para ese caso.</p>
	 */
	public static JsonObject getServerSheet(String uuid) {
		String characterId = activeCharacterOf(uuid);
		if (sheets.containsKey(characterId)) {
			return sheets.get(characterId);
		}
		else {
			DndsheetsMod.LOGGER.warn("Server character sheet retrieval failed. Make sure the UUID is correct and that you're not calling this from the client.");
			return null;
		}
	}

	/**
	 * <p>Id del personaje activo de ese jugador, o el propio argumento si no hay ninguno registrado. Ese
	 * fallback es lo que hace que una hoja anterior a Fase 1 (archivo llamado {@code <uuid>.json}, sin
	 * campo {@code active}) siga resolviéndose sola, y también lo que permite pasar un id de personaje
	 * directo a {@link #getServerSheet}.</p>
	 */
	public static String activeCharacterOf(String playerUuid) {
		String characterId = activeCharacter.get(playerUuid);
		return characterId != null ? characterId : playerUuid;
	}

	/** Hoja de un personaje por su id exacto, sin pasar por el binding de jugador activo. */
	public static JsonObject getCharacterSheet(String characterId) {
		return sheets.get(characterId);
	}

	/** Ids de las fichas sin dueño (PNJ), en orden estable. Para autocompletado y menús de DM. */
	public static List<String> npcIds() {
		List<String> npcs = new ArrayList<>();
		for (Map.Entry<String, JsonObject> entry : sheets.entrySet()) {
			if (ownerOf(entry.getKey(), entry.getValue()) == null) npcs.add(entry.getKey());
		}
		Collections.sort(npcs);
		return npcs;
	}

	/**
	 * <p>Ids de todos los personajes de ese jugador, el activo incluido, en orden estable por id. Recorre
	 * todas las hojas en vez de mantener un índice: se llama al abrir un menú o escribir un comando, nunca
	 * en un bucle de combate, y un índice más que mantener es justo el tipo de estado que se desincroniza.</p>
	 */
	public static List<String> charactersOf(String playerUuid) {
		return CharacterRules.ownedBy(sheets, playerUuid);
	}

	/** Ver {@link CharacterRules#ownerOf} — la regla vive ahí para poder comprobarse fuera del juego. */
	public static String ownerOf(String characterId, JsonObject sheet) {
		return CharacterRules.ownerOf(characterId, sheet);
	}

	//Nombres por defecto que deja el propio mod cuando el jugador nunca escribió el suyo (ver
	//validateSheet/makeNew y el placeholder del campo en la hoja) - no sirven para identificar a nadie
	//en el chat, así que en ese caso se usa el nombre real de Minecraft en su lugar.
	private static final Set<String> DEFAULT_CHARACTER_NAMES = Set.of("New Sheet", "John Doe", "Fulano de Tal");

	public static String characterNameOf(JsonObject sheet, Entity fallbackEntity) {
		if (sheet != null && sheet.has("characterName")) {
			String name = sheet.get("characterName").getAsString();
			if (!name.isBlank() && !DEFAULT_CHARACTER_NAMES.contains(name)) {
				return name;
			}
		}
		return fallbackEntity.getName().getString();
	}

	/**
	 * <p>Nivel de personaje, desacoplado del XP real de Minecraft en cuanto el DM lo fija a mano con
	 * {@code /dndsheet setlevel} (guarda "characterLevel" en la hoja). Hasta entonces, sigue reflejando el
	 * XP real de Minecraft, exactamente como antes — así que esto no cambia nada para una hoja que nunca
	 * usó el comando. Usado tanto por el servidor (rasgos/recursos que escalan por nivel) como por
	 * {@code CharacterSheetScreen} en el cliente (para no seguir mostrando el XP si ya se fijó un nivel).</p>
	 */
	public static int characterLevelOf(JsonObject sheet, Player fallbackEntity) {
		if (sheet != null && sheet.has("characterLevel")) {
			return sheet.get("characterLevel").getAsInt();
		}
		return Math.max(1, fallbackEntity.experienceLevel); //Los PJ de D&D nunca son nivel 0, pero el XP de Minecraft empieza en 0.
	}

	//Save the given sheet into a JSON file, making a new one if it doesn't exist, and updates the "sheets" HashMap.
	//El id se resuelve por activeCharacterOf igual que en la lectura: los 3 llamadores pasan un UUID de
	//jugador, y sin esto guardarían siempre sobre la hoja legacy en vez de sobre el personaje que lleva
	//puesto. Un id de personaje que no sea de nadie (un PNJ) se resuelve a sí mismo y se guarda tal cual.
	public static void saveServer(JsonObject sheet, String uuid) {
		String characterId = activeCharacterOf(uuid);
		sheets.put(characterId, sheet);

		Path file = SHEETS_DIR.resolve(characterId + ".json").toAbsolutePath();
		
		try {
			Files.createDirectories(SHEETS_DIR);
			boolean overwritten = Files.deleteIfExists(file);
			Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
			String prettyJson = prettyGson.toJson(sheet);
			
			try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE)) {
				out.write(prettyJson.getBytes());
			}
		} catch (IOException e) {
			//El mapa en memoria ya se actualizó arriba, así que sin este log el jugador ve su hoja "guardada"
			//mientras el archivo real en disco puede no reflejarlo, sin ningún aviso.
			DndsheetsMod.LOGGER.error("No se pudo guardar la hoja de " + uuid + " en disco.", e);
		}
	}

	//Loads each JSON file under the "charactersheets" folder in the Minecraft instance into JSON objects, filling the "sheets" HashMap.
	private static void load() {
		sheets = new HashMap<String, JsonObject>();
		ArrayList<Path> files = new ArrayList<Path>();
		
		try {
			Files.createDirectories(SHEETS_DIR);
			try (Stream<Path> paths = Files.walk(SHEETS_DIR)) {
			    paths.filter(f -> !Files.isDirectory(f) && f.getFileName().toString().endsWith(".json"))
			    .forEach(path -> {
			    	if (Files.isDirectory(path))
						return;

					files.add(path);
			    });
			} 
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("No se pudo listar el directorio de hojas de personaje.", e);
		}

		//Por archivo, no por el lote entero: una hoja corrupta en disco (JSON inválido, o válido pero no un
		//objeto, p.ej. un crash a mitad de escritura) no debe tumbar la carga de TODAS las demás hojas —
		//antes, una excepción de parseo (JsonSyntaxException/IllegalStateException, ninguna de las dos es
		//IOException) se propagaba sin capturar y rompía el join de cualquier jugador desde ese momento.
		for (Path path : files) {
			try {
				InputStream in = Files.newInputStream(path, StandardOpenOption.READ);
				Scanner s = new Scanner(in).useDelimiter("\\A");
				String result = s.hasNext() ? s.next() : "";
				JsonObject json = JsonParser.parseString(result).getAsJsonObject();
				migrateIfNeeded(json);
				sheets.put(path.getFileName().normalize().toString().replace(".json",""), json);
			} catch (Exception e) {
				DndsheetsMod.LOGGER.warn("Skipping corrupt character sheet file " + path + ": " + e);
			}
		}

		rebuildActiveCharacters();
	}

	/**
	 * <p>Reconstruye el binding jugador → personaje activo mirando el campo {@code active} de cada hoja.
	 * Derivado, en vez de un índice guardado aparte: un índice puede desincronizarse de las hojas y dejar
	 * a alguien sin poder jugar; el campo dentro de la propia hoja no puede contradecirse a sí mismo.</p>
	 *
	 * <p>Si un jugador acabara con dos hojas marcadas activas (por edición manual del JSON), gana la de id
	 * menor y se avisa por log, en vez de elegir en silencio una distinta en cada arranque.</p>
	 */
	private static void rebuildActiveCharacters() {
		activeCharacter.clear();
		List<String> ids = new ArrayList<>(sheets.keySet());
		Collections.sort(ids); //Orden estable: sin esto, dos hojas activas darían un ganador distinto en cada arranque.
		for (String characterId : ids) {
			JsonObject sheet = sheets.get(characterId);
			if (sheet == null || !sheet.has("active") || !sheet.get("active").getAsBoolean()) continue;
			String owner = ownerOf(characterId, sheet);
			if (owner == null) continue; //PNJ: no lo lleva puesto ningún jugador.
			String previous = activeCharacter.putIfAbsent(owner, characterId);
			if (previous != null) {
				DndsheetsMod.LOGGER.warn("El jugador {} tiene varias hojas marcadas como activas ({} y {}); se usa {}.", owner, previous, characterId, previous);
			}
		}
	}

	//--- Personajes múltiples (Fase 1) ------------------------------------------------------------------

	//Id derivado del UUID del dueño: único entre jugadores sin necesitar un contador global, y sigue siendo
	//un nombre de archivo válido en cualquier sistema.
	private static String nextCharacterId(String playerUuid) {
		return CharacterRules.nextCharacterId(sheets.keySet(), playerUuid);
	}

	/**
	 * <p>Crea un personaje más para ese jugador, creado pero <b>no</b> activo: ponérselo es una acción
	 * aparte y deliberada ({@link #switchCharacter}), no un efecto secundario de crearlo.</p>
	 *
	 * @return el id del personaje nuevo.
	 */
	public static String createCharacter(String playerUuid, String characterName) {
		String characterId = nextCharacterId(playerUuid);
		JsonObject sheet = new JsonObject();
		sheet.addProperty("characterName", characterName);
		sheet.addProperty("ownerUuid", playerUuid);
		sheet.addProperty("active", false);
		validateSheet(sheet);
		sheets.put(characterId, sheet);
		saveCharacter(characterId, sheet);
		return characterId;
	}

	/**
	 * <p>Hoja de PNJ: un personaje sin dueño, que nadie lleva puesto. Es lo que permite que el DM tenga
	 * fichas de aliados y secundarios con las mismas reglas que un PJ, en vez de tener que convertirlos en
	 * monstruos con bloque de estadísticas.</p>
	 */
	public static String createNpc(String characterName) {
		String characterId = CharacterRules.npcIdFor(sheets.keySet(), characterName);

		JsonObject sheet = new JsonObject();
		sheet.addProperty("characterName", characterName);
		sheet.addProperty("ownerUuid", ""); //Vacío, no ausente: "de nadie" tiene que distinguirse de "hoja legacy".
		sheet.addProperty("active", false);
		validateSheet(sheet);
		sheets.put(characterId, sheet);
		saveCharacter(characterId, sheet);
		return characterId;
	}

	/**
	 * <p>Pone a ese jugador a llevar otro de sus personajes. Devuelve false si el personaje no existe o no
	 * es suyo — nadie puede ponerse la hoja de otro.</p>
	 */
	public static boolean switchCharacter(ServerPlayer player, String characterId) {
		String playerUuid = player.getStringUUID();
		JsonObject target = sheets.get(characterId);
		if (target == null || !playerUuid.equals(ownerOf(characterId, target))) return false;

		//Se desmarca el anterior y se marca el nuevo, para que rebuildActiveCharacters() reconstruya
		//exactamente este mismo estado tras un reinicio.
		for (String owned : charactersOf(playerUuid)) {
			JsonObject sheet = sheets.get(owned);
			boolean shouldBeActive = owned.equals(characterId);
			if (sheet.has("active") && sheet.get("active").getAsBoolean() == shouldBeActive) continue;
			sheet.addProperty("active", shouldBeActive);
			//La hoja legacy no tenía ownerUuid; al tocarla hay que estampárselo, o dejaría de reconocerse como
			//suya en cuanto el jugador lleve puesto un personaje con otro id.
			if (!sheet.has("ownerUuid")) sheet.addProperty("ownerUuid", playerUuid);
			saveCharacter(owned, sheet);
		}
		activeCharacter.put(playerUuid, characterId);

		//El personaje nuevo tiene sus propios PG máximos (clase, nivel, Constitución) y su propia hoja en el
		//cliente: sin estas dos líneas, cambiar de personaje dejaba al jugador con el cuerpo del anterior.
		applyClassHitPoints(player, target);
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(target.toString().getBytes()));
		return true;
	}

	/**
	 * <p>Le da cuerpo a una ficha de PNJ: invoca una entidad en el mundo ligada a ese personaje. Sin esto,
	 * {@link #createNpc} producía una hoja perfectamente válida que nadie podía usar para nada.</p>
	 *
	 * <p>El mob va sin IA, igual que un monstruo invocado: lo lleva el DM, no se mueve solo. La entidad es
	 * el cuerpo; el personaje —PG, condiciones, características— vive en la hoja y le sobrevive.</p>
	 *
	 * @return la entidad invocada, o {@code null} si el personaje o el tipo de entidad no existen.
	 */
	public static net.minecraft.world.entity.Entity spawnNpc(net.minecraft.server.level.ServerLevel level,
			double x, double y, double z, String characterId, String baseEntityId) {
		JsonObject sheet = sheets.get(characterId);
		if (sheet == null) return null;

		net.minecraft.resources.ResourceLocation entityLoc = net.minecraft.resources.ResourceLocation.tryParse(baseEntityId);
		net.minecraft.world.entity.EntityType<?> type = entityLoc == null ? null
			: net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(entityLoc);
		if (type == null) return null;

		net.minecraft.world.entity.Entity entity = type.create(level);
		if (entity == null) return null;

		String name = sheet.has("characterName") ? sheet.get("characterName").getAsString() : characterId;
		entity.moveTo(x, y, z, 0, 0);
		entity.setCustomName(Component.literal(name));
		entity.setCustomNameVisible(true);
		if (entity instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(true);
		Combatant.tagAsCharacter(entity, characterId);

		level.addFreshEntity(entity);

		//Si se le da cuerpo a mitad de un combate ya en marcha, entra al orden de turnos ya mismo: si no,
		//quedaría plantado sin poder actuar durante todo el encuentro.
		TurnManager.addLateMonster(level, entity, name);
		return entity;
	}

	//Mismo cuerpo que saveServer pero sin resolver el id: aquí ya se sabe sobre qué personaje se escribe, y
	//pasarlo por activeCharacterOf lo redirigiría al personaje activo de su dueño.
	private static void saveCharacter(String characterId, JsonObject sheet) {
		sheets.put(characterId, sheet);
		Path file = SHEETS_DIR.resolve(characterId + ".json").toAbsolutePath();
		try {
			Files.createDirectories(SHEETS_DIR);
			Files.deleteIfExists(file);
			String prettyJson = new GsonBuilder().setPrettyPrinting().create().toJson(sheet);
			try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE)) {
				out.write(prettyJson.getBytes());
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("No se pudo guardar el personaje " + characterId + " en disco.", e);
		}
	}

	/**
	 * <p>This method validates a character sheet JsonObject. This essentially makes sure it has the expected properties and fixes it if it doesn't.</p>
	 * @param sheet
	 */
	public static void validateSheet(JsonObject sheet) {

		//Checking basics
		if (!sheet.has("characterName")) sheet.addProperty("characterName", "John Doe");
		if (!sheet.has("strength")) sheet.addProperty("strength", "10");
		if (!sheet.has("dexterity")) sheet.addProperty("dexterity", "10");
		if (!sheet.has("constitution")) sheet.addProperty("constitution", "10");
		if (!sheet.has("intelligence")) sheet.addProperty("intelligence", "10");
		if (!sheet.has("wisdom")) sheet.addProperty("wisdom", "10");
		if (!sheet.has("charisma")) sheet.addProperty("charisma", "10");
		if (!sheet.has("proficiencyBonus")) sheet.addProperty("proficiencyBonus", "2");

		//Checking roll expressions
		if (!sheet.has("checks")) {
			JsonArray checks = new JsonArray();
			checks.add("1d20 + $str");
			checks.add("1d20 + $dex");
			checks.add("1d20 + $con");
			checks.add("1d20 + $int");
			checks.add("1d20 + $wis");
			checks.add("1d20 + $cha");
			checks.add("1d20 + $dex"); //Initiative
			sheet.add("checks", checks);
		}
		if (!sheet.has("saves")) {
			JsonArray saves = new JsonArray();
			saves.add("1d20 + $str");
			saves.add("1d20 + $dex");
			saves.add("1d20 + $con");
			saves.add("1d20 + $int");
			saves.add("1d20 + $wis");
			saves.add("1d20 + $cha");
			sheet.add("saves", saves);
		}
		if (!sheet.has("skills")) {
			JsonArray skills = new JsonArray();
			skills.add("1d20 + $str");
			skills.add("1d20 + $dex");
			skills.add("1d20 + $dex");
			skills.add("1d20 + $dex");
			skills.add("1d20 + $int");
			skills.add("1d20 + $int");
			skills.add("1d20 + $int");
			skills.add("1d20 + $int");
			skills.add("1d20 + $int");
			skills.add("1d20 + $wis");
			skills.add("1d20 + $wis");
			skills.add("1d20 + $wis");
			skills.add("1d20 + $wis");
			skills.add("1d20 + $wis");
			skills.add("1d20 + $cha");
			skills.add("1d20 + $cha");
			skills.add("1d20 + $cha");
			skills.add("1d20 + $cha");
			sheet.add("skills", skills);
		}
		if (!sheet.has("attacks")) {
			JsonArray attacks = new JsonArray();
			sheet.add("attacks", attacks);
		}
		if (!sheet.has("spells")) {
			JsonArray spells = new JsonArray();
			sheet.add("spells", spells);
		}
		if (!sheet.has("traits")) {
			JsonArray traits = new JsonArray();
			sheet.add("traits", traits);
		}
		if (!sheet.has("spellSlotsCurrent")) sheet.addProperty("spellSlotsCurrent", 0);
		if (!sheet.has("spellSlotsMax")) sheet.addProperty("spellSlotsMax", 0);

		migrateIfNeeded(sheet);
		MinecraftForge.EVENT_BUS.post(new SheetValidateEvent(sheet));
	}

	//ponytail: sin migraciones reales que aplicar todavía (ningún campo ha cambiado de forma entre
	//versiones) — este método solo estampa la versión actual en hojas antiguas que no la tenían. Cuando
	//haga falta una migración de verdad, añadir un caso más aquí por versión, antes de subir
	//CURRENT_SCHEMA_VERSION.
	private static void migrateIfNeeded(JsonObject sheet) {
		int version = sheet.has("schemaVersion") ? sheet.get("schemaVersion").getAsInt() : 0;
		if (version < CURRENT_SCHEMA_VERSION) {
			sheet.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
		}
	}

	//Makes a new sheet, adds it to the "sheets" HashMap, and then calls Save() to make a file from it.
	//Delega en validateSheet para los valores por defecto en vez de reconstruirlos a mano: antes cualquier
	//cambio a una expresión de tirada por defecto había que hacerlo en los dos sitios a la vez.
	public static void makeNew(String characterName, String uuid) {
		JsonObject newSheet = new JsonObject();
		newSheet.addProperty("characterName", characterName);
		validateSheet(newSheet);
		saveServer(newSheet, uuid);
	}

	//Sets a new "current" sheet from the "sheets" HashSet. Ideally some GUI letting you choose from the loaded list will call this.
	public static void setClient(JsonObject sheet) {
		current = sheet;
		//lol it's really that simple
	}

	//Aplica un parche parcial (ver network.SheetFieldUpdateMessage) sobre la hoja cacheada del cliente, en
	//vez de reemplazarla entera como setClient — JsonNull en un valor significa "borrar esta clave", igual
	//que el servidor la borró con JsonObject.remove(...). Ver AUDIT_TECHNICAL.md M-NET-1.
	public static void applyClientDelta(JsonObject patch) {
		if (current == null) return;
		for (String key : patch.keySet()) {
			JsonElement value = patch.get(key);
			if (value.isJsonNull()) current.remove(key);
			else current.add(key, value);
		}
	}



}
