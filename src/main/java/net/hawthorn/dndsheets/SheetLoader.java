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
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.hawthorn.dndsheets.api.event.SheetValidateEvent;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
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
	private static HashMap<String, JsonObject> sheets = new HashMap<String, JsonObject>(); //A list of all loaded character sheets. Privado: sin consumo externo confirmado (ver AUDIT_TECHNICAL.md M-API-1), solo se lee/escribe a través de getServerSheet/saveServer, que ya validan/loguean.
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
		Entity entity = event.getEntity();
		if (!(entity instanceof Player)) return;

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
		if (SheetLoader.getServerSheet(uuidString) == null) {
			makeNew("New Sheet", uuidString);
		};

		applyClassHitPoints((Player) entity, SheetLoader.getServerSheet(uuidString));

		try {
			Supplier<ServerPlayer> serverPlayer = () -> (ServerPlayer) entity;
			byte[] data = SheetLoader.getServerSheet(uuidString).toString().getBytes();
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(serverPlayer), new SheetClientMessage(data));
			DeathSaveManager.resendStateOnJoin((ServerPlayer) entity, SheetLoader.getServerSheet(uuidString));
			//Reconectarse durante un combate le da al jugador un entityId nuevo; sin esto quedaba bloqueado
			//sin poder actuar por el resto del encuentro (ver TurnManager.reconcilePlayerEntity).
			TurnManager.reconcilePlayerEntity((ServerPlayer) entity);
		}
		catch(Exception e) {
			DndsheetsMod.LOGGER.error("Fallo al enviar la hoja al jugador que se conectó.", e);
		}

	}

	private static final UUID CLASS_HP_MODIFIER_ID = UUID.fromString("6f2f8f0a-3b1a-4c8e-9d2a-1a2b3c4d5e6f");

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
		int level = Math.max(1, characterLevelOf(sheet, entity));
		int con = sheetInt(sheet, "constitution", 10);
		int hitDie = Config.hitDieFor(sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : "");
		int conMod = Math.floorDiv(con - 10, 2);

		int maxHp = hitDie + conMod;
		for (int lvl = 2; lvl <= level; lvl++) {
			maxHp += Math.max(1, (hitDie / 2 + 1) + conMod);
		}
		maxHp = Math.max(1, maxHp);

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

	public static JsonObject getClientSheet() {
		if (current == null) {
			DndsheetsMod.LOGGER.warn("Client sheet returned null. Are you sure you're not calling this from the server side?");
		}
		return current;
	}

	public static JsonObject getServerSheet(String uuid) {
		if (sheets.containsKey(uuid)) {
			return sheets.get(uuid);
		}
		else {
			DndsheetsMod.LOGGER.warn("Server character sheet retrieval failed. Make sure the UUID is correct and that you're not calling this from the client.");
			return null;
		}
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
	public static void saveServer(JsonObject sheet, String uuid) {
		sheets.put(uuid, sheet);
		
		Path file = SHEETS_DIR.resolve(uuid + ".json").toAbsolutePath();
		
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
