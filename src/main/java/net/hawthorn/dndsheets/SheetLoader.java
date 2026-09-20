package net.hawthorn.dndsheets;

import com.google.gson.*;
import javax.annotation.Nullable;
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
import net.hawthorn.dndsheets.api.event.CharacterSwitchedEvent;
import net.hawthorn.dndsheets.api.event.SheetValidateEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.*;
import java.util.stream.Stream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
//No stability contract: this mod doesn't publish a versioned API (the DndSheetsApi facade was
//deleted — 233 lines not used by a single caller, not even the addons, which come in through here).
//An external mod calling these methods is exposed to their signature changing without warning. The
//only thing designed for external consumption is the api/event events, which do have real consumers.
public class SheetLoader {

	public static final Path GAME_DIR = FMLPaths.GAMEDIR.get();
	public static final Path SHEETS_DIR = GAME_DIR.resolve("charactersheets");

	//Without this there was no programmatic way to know if an on-disk sheet is from an earlier
	//version of the mod. Bumping this number only makes sense the day an existing field truly
	//changes shape (not when a new one is added: validateSheet already covers that on its own,
	//no version needed).
	public static final int CURRENT_SCHEMA_VERSION = 1;
	/**
	 * <p>All loaded sheets, <b>indexed by character id</b>, not by player UUID. Up through Phase 1 those
	 * were the same thing: one sheet per player, forever, so the concept of "character" didn't exist —
	 * and without it you couldn't have a second PC, an NPC sheet, switch characters, or archive a
	 * campaign. Now a character id is any string valid as a filename; a player's UUID is still a valid
	 * id, which is exactly what makes everything saved before this change keep working without migration:
	 * its file was already named that way.</p>
	 */
	private static HashMap<String, JsonObject> sheets = new HashMap<String, JsonObject>(); //Private: no confirmed external consumer, only read/written through getServerSheet/saveServer, which already validate/log.

	/**
	 * <p>Player UUID → id of the character they're currently playing. It's a cache derived from the sheets
	 * themselves (each one's {@code active} field), not a separate source of truth: it's rebuilt entirely
	 * in {@link #load()} and updated when switching characters. It exists only because
	 * {@code getServerSheet} is called on every hit of every combat, and iterating all sheets there would
	 * be absurd.</p>
	 */
	private static final Map<String, String> activeCharacter = new HashMap<>();
	private static JsonObject current = null; //Currently active character sheet. Important for populating GUIs when they're opened and knowing which to save to.

	/**
	 * <p>Character ids with changes pending a disk write. {@link #saveServer} adds to this and the end
	 * of the tick writes them out ({@link #flushDirty}).</p>
	 *
	 * <p><b>Why it isn't written immediately.</b> {@code saveServer} serializes the ENTIRE sheet with
	 * indentation and dumps it with a synchronous {@code Files.writeString}, on the server thread. It has
	 * 25 callers, and two are on combat's hot path: {@code Combatant.setConditionSources} (every condition
	 * gain or loss, for any combatant) and {@code setTemporaryHp}. One round with six combatants meant
	 * dozens of serializations and dozens of disk writes, several of them on the SAME sheet within the
	 * resolution of a single attack.</p>
	 *
	 * <p><b>Why it's safe.</b> What had to be avoided was relying on the 5-minute autosave, which already
	 * cost lost DM changes (bug #5). This isn't that: the window goes from "immediate" to "the end of
	 * this tick", 50 ms, and all writes from a single resolution collapse into one. READS don't change at
	 * all — {@code sheets} is updated synchronously, same as before, so {@code getServerSheet} never sees
	 * anything stale.</p>
	 *
	 * <p>{@code LinkedHashSet}: doesn't repeat an id (which is the point) and preserves marking order, so
	 * a write-failure log comes out in the order things happened.</p>
	 */
	private static final Set<String> dirty = new LinkedHashSet<>();

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
		//In singleplayer/LAN this event also fires on the client's own ClientLevel side (the
		//player there is a LocalPlayer, not a ServerPlayer) — without this filter, casting below threw
		//ClassCastException every time someone joined an integrated world.
		if (event.getLevel().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer entity)) return;

		UUID uuid = entity.getUUID();
		String uuidString = uuid.toString();
		//load() used to be called here unconditionally, and this event isn't only "the player joined the
		//server for the first time": EntityJoinLevelEvent also fires on every respawn and every dimension
		//change (Nether/End portal) for ANY player. That reparsed ALL of the server's sheets from disk
		//every time — synchronous blocking I/O on the server thread, repeated needlessly. sheets is only
		//empty before the first real load (useful for integrated/LAN worlds, where
		//FMLDedicatedServerSetupEvent.serverLoad never fires); on any later event the sheets are already
		//in memory (makeNew/saveServer keeps them updated there) and don't need re-reading.
		if (sheets.isEmpty()) load();
		//True only the very first time this UUID receives a sheet — it never becomes true again (it stays
		//in memory/disk from then on), and it's still false for an existing player even after a server
		//restart (load() already repopulated "sheets" from disk before this line). It's, with no need for
		//any new field on the sheet, exactly the "entering the world for the first time" signal the
		//tutorial below needs.
		boolean brandNew = SheetLoader.getServerSheet(uuidString) == null;
		if (brandNew) {
			makeNew("New Sheet", uuidString);
		};

		applyClassHitPoints(entity, SheetLoader.getServerSheet(uuidString));

		try {
			byte[] data = SheetLoader.getServerSheet(uuidString).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> entity), new SheetClientMessage(data));
			DeathSaveManager.resendState(entity, SheetLoader.getServerSheet(uuidString));
			//Reconnecting during combat gives the player a new entityId; without this they'd stay locked
			//out of acting for the rest of the encounter (see TurnManager.reconcilePlayerEntity).
			TurnManager.reconcilePlayerEntity(entity);
		}
		catch(Exception e) {
			DndsheetsMod.LOGGER.error("Failed to send the sheet to the connecting player.", e);
		}

		//First time entering the world: a corner toast with the sheet key (see TutorialOpenMessage —
		//it used to open the entire Guide by itself, which got closed reflexively without being read).
		//The delay stays: don't fight the world's loading screen. Re-fetches the player by UUID when it
		//fires instead of capturing "entity" directly — same pattern as BarbarianRageManager.activate —
		//in case they disconnect during the ~3 second wait.
		if (brandNew) {
			UUID playerId = uuid;
			MinecraftServer server = entity.getServer();
			boolean isDm = DndsheetsMod.canActAsDm(entity);
			DndsheetsMod.queueServerWork(60, () -> {
				ServerPlayer stillHere = server.getPlayerList().getPlayer(playerId);
				if (stillHere != null) {
					DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> stillHere), new TutorialOpenMessage(isDm, true));
				}
			});
		}
	}

	/**
	 * <p>The SINGLE cleanup point on disconnect. All these states live indexed by player in RAM (they
	 * aren't sheet data), so without removing them the UUID stays in there forever: a community server
	 * accumulates one entry per player who ever passed through and never came back.</p>
	 *
	 * <p>Exists because it used to be done halfway. Of ten per-player collections only three were being
	 * cleaned ({@code BardInspirationManager}, {@code SpellCastManager}, {@code RestManager}), each with
	 * its own {@code @SubscribeEvent}; and {@code BardInspirationManager} even documented the problem in a
	 * comment naming the four that still had it. It's centralized here the same way cleanup on character
	 * switch was already centralized (see switchCharacter), instead of spreading seven identical
	 * handlers.</p>
	 *
	 * <p>{@code clearFor} is called and NOT {@code ConcentrationManager.stopConcentrating}: on disconnect
	 * only the memory needs releasing, not reverting zones or world summons.</p>
	 */
	@SubscribeEvent
	public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		ConcentrationManager.clearFor(player);
		CastingManager.clearFor(player);
		BarbarianRageManager.clearFor(player);
		DruidWildShapeManager.clearFor(player);
		RangerHunterMarkManager.clearFor(player);
		DeathSaveManager.clearFor(player);
		//DungeonToolManager.clearFor(player) used to live here — it moved to the dungeon addon, which now
		//listens to this same PlayerLoggedOutEvent on its own (see Modularity Map).
		MonsterActionManager.clearFor(player);
	}

	//Without this there was no way to learn about the H key (or P for operators) unless someone told you
	//separately — sent exactly once per real login (not on every respawn/dimension change, which also
	//fires EntityJoinLevelEvent, which is why this lives in PlayerLoggedInEvent instead of there).
	//
	//With a short delay (20 ticks) rather than on the same tick as login: for a player joining via LAN
	//(not the host, whose integrated world was already loaded) the message would arrive BEFORE their
	//client finished opening the chat screen — the host saw it because their game was already running,
	//whoever actually connected missed it silently. The same gap that
	//DndsheetsMod.queueServerWork resolves for spell chain effects.
	@SubscribeEvent
	public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		DndsheetsMod.queueServerWork(20, () -> {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.welcome.sheet_key").withStyle(ChatFormatting.GRAY));
			if (DndsheetsMod.canActAsDm(player)) {
				player.sendSystemMessage(Component.translatable("chat.dndsheets.welcome.dm_key").withStyle(ChatFormatting.GRAY));
			}
		});
	}

	private static final UUID CLASS_HP_MODIFIER_ID = UUID.fromString("6f2f8f0a-3b1a-4c8e-9d2a-1a2b3c4d5e6f");

	/**
	 * <p>Max HP for a sheet by class, level and Constitution (SRD average rule: full die at level 1,
	 * average+1 per subsequent level). Extracted from {@link #applyClassHitPoints}, which only knew how
	 * to apply it to a real {@code Player}: an NPC sheet needs the <em>number</em>, because it has no
	 * Minecraft health attribute to reflect it in.</p>
	 */
	public static int maxHitPointsFor(JsonObject sheet, int level) {
		return CharacterRules.maxHitPointsFor(sheet, level);
	}

	/**
	 * <p>Character level for a sheet with no player behind it. The {@code Player} overload falls back to
	 * real Minecraft XP when the DM hasn't set a level; an NPC sheet has no XP to fall back to, so it
	 * starts at 1 — in 5e no character is level 0.</p>
	 */
	public static int characterLevelOf(JsonObject sheet) {
		return CharacterRules.levelOf(sheet);
	}

	private static int sheetInt(JsonObject sheet, String key, int fallback) {
		if (!sheet.has(key)) return fallback;
		try {
			return Integer.parseInt(sheet.get(key).getAsString());
		} catch (RuntimeException e) {
			//RuntimeException, not just NumberFormatException: sheet.get(key) can be a JsonObject/JsonArray
			//if an old sheet got corrupted before SheetServerMessage started validating types, and
			//.getAsString() on that throws UnsupportedOperationException, not NumberFormatException.
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

		//characterLevelOf, not sheetInt(sheet, "level", ...): "level" is the real Minecraft XP reflected on
		//the sheet, but as soon as the DM sets a character level with /dndsheet setlevel (characterLevel),
		//max HP must scale with THAT level — otherwise "decoupling level from XP" wouldn't decouple
		//anything for max HP, exactly the most obvious reason to have a character level in 5e.
		int level = Math.max(1, characterLevelOf(sheet, entity));
		int maxHp = maxHitPointsFor(sheet, level);

		//The proficiency bonus comes from the same level, and it's here because this is the only place
		//that already resolves it. It's stored as text because that's how the sheet reads it and how its
		//field writes it.
		sheet.addProperty("proficiencyBonus", String.valueOf(CharacterRules.proficiencyBonusFor(level)));

		//And spell slots, for the same reason. It's idempotent: if class and level haven't changed it
		//doesn't touch whatever's left spent, and on a sheet coming from the single legacy pool it
		//populates the new table the first time.
		String characterClass = sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : "";
		SpellSlots.applyProgression(sheet, characterClass, level);

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

	//Periodic save + on server shutdown: saveServer (the ONLY place in the whole mod that writes a
	//sheet to disk) used to only be called on creating a new sheet or when the player themselves
	//reopened their character sheet screen (see network.SheetServerMessage) — any other change (spending
	//a spell slot, a rest, /dndsheet setslots, applying a preset, class resources...) only touched the
	//in-memory copy and was silently lost as soon as the server restarted, not just spell slots. Saving
	//ALL sheets periodically and on shutdown closes the gap at its root, without having to remember to
	//call saveServer at each of the ~10 places that change a sheet.
	private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 60 * 5; //5 minutes.

	//Instance, not static: ServerStartingEvent/ServerStoppingEvent are FORGE bus events, not the MOD
	//bus that this class's @Mod.EventBusSubscriber annotation points to (which is why serverLoad/init/
	//clientLoad above CAN be static — they're MOD lifecycle events). They hook in the same way as
	//clientJoinedServer, via the same MinecraftForge.EVENT_BUS.register(new SheetLoader()) that init()
	//already does below — without this, Forge rejects the whole method when loading the mod
	//(IllegalArgumentException, "takes an argument that is not a subtype of ... IModBusEvent").
	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event) {
		scheduleAutosave();
	}

	//Instance and not static, for the same reason as the two next to it: ServerTickEvent is on the FORGE bus.
	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		flushDirty();
	}

	private static void scheduleAutosave() {
		DndsheetsMod.queueServerWork(AUTOSAVE_INTERVAL_TICKS, () -> {
			saveAll();
			scheduleAutosave();
		});
	}

	//Last-resort backup: if shutdown is clean (/stop, instance restart), this fires BEFORE the process
	//dies, so no change made in the last few minutes (less than the interval above) is lost to bad
	//timing luck.
	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		saveAll();
	}

	//Writes ALL of them, not just the ones marked "dirty", and that's on purpose: this autosave's safety
	//net exists precisely for the places that change a sheet in memory WITHOUT going through
	//saveServer — which is the very gap it exists to cover (see the comment above) — and those don't
	//mark anything. Dumping only what's marked would turn it into a no-op and bring back the exact
	//failure it came to patch.
	private static void saveAll() {
		//saveCharacter and NOT saveServer: "sheets"'s keys are already character ids, and saveServer
		//would run them through activeCharacterOf again. For a player with a second character equipped,
		//that would write their old sheet's contents over the active character's file.
		for (Map.Entry<String, JsonObject> entry : sheets.entrySet()) {
			saveCharacter(entry.getKey(), entry.getValue());
		}
	}

	//A single complaint per gap, not one per frame. Called by ResourceHudOverlay.render, which ALWAYS
	//runs (the HUD needs no menu open): with current==null, at 120 fps that was 120 log lines per
	//second, orders of magnitude more expensive than the render itself. The window genuinely exists —
	//between entering the world and SheetClientMessage arriving — so the warning isn't unwarranted, only
	//repeating it is.
	private static boolean warnedNullClientSheet = false;

	public static JsonObject getClientSheet() {
		if (current == null) {
			if (!warnedNullClientSheet) {
				warnedNullClientSheet = true;
			DndsheetsMod.LOGGER.warn("Client sheet returned null. Are you sure you're not calling this from the server side?");
			}
		} else {
			warnedNullClientSheet = false; //Rearmed: if it goes missing again later, it's a NEW gap and deserves its own warning.
		}
		return current;
	}

	/**
	 * <p>Sheet of the character that player is currently playing. The signature didn't change when
	 * character was separated from player (Phase 1) on purpose: the ~68 call sites want "the sheet of
	 * whoever's playing", and that question still has the same answer — it just goes through one more
	 * indirection now.</p>
	 *
	 * <p>Also accepts a direct character id (an NPC, or a PC its owner isn't currently wearing): an id
	 * with no binding resolves to itself, so no separate method is needed for that case.</p>
	 */
	@Nullable
	public static JsonObject getServerSheet(String uuid) {
		String characterId = activeCharacterOf(uuid);
		if (sheets.containsKey(characterId)) {
			return sheets.get(characterId);
		}
		else {
			//debug, not warn: this is reachable from Combatant.of, which runs at 20 Hz via
			//MovementAnchorTracker. A player in combat with no sheet generated 20 lines per second.
			DndsheetsMod.LOGGER.debug("Server character sheet retrieval failed. Make sure the UUID is correct and that you're not calling this from the client.");
			return null;
		}
	}

	/**
	 * <p>Active character id for that player, or the argument itself if none is registered. That
	 * fallback is what makes a pre-Phase-1 sheet (a file named {@code <uuid>.json}, no {@code active}
	 * field) keep resolving on its own, and also what allows passing a direct character id to
	 * {@link #getServerSheet}.</p>
	 */
	public static String activeCharacterOf(String playerUuid) {
		String characterId = activeCharacter.get(playerUuid);
		return characterId != null ? characterId : playerUuid;
	}

	/** A character's sheet by its exact id, without going through the active-player binding. */
	public static JsonObject getCharacterSheet(String characterId) {
		return sheets.get(characterId);
	}

	/** Ids of ownerless sheets (NPCs), in stable order. For autocomplete and DM menus. */
	public static List<String> npcIds() {
		List<String> npcs = new ArrayList<>();
		for (Map.Entry<String, JsonObject> entry : sheets.entrySet()) {
			if (ownerOf(entry.getKey(), entry.getValue()) == null) npcs.add(entry.getKey());
		}
		Collections.sort(npcs);
		return npcs;
	}

	/**
	 * <p>Ids of all of that player's characters, the active one included, in stable id order. Iterates
	 * all sheets instead of keeping an index: it's called when opening a menu or typing a command, never
	 * in a combat loop, and one more index to maintain is exactly the kind of state that drifts out of
	 * sync.</p>
	 */
	public static List<String> charactersOf(String playerUuid) {
		return CharacterRules.ownedBy(sheets, playerUuid);
	}

	/**
	 * <p>Resolves a NAME (or an id) to the character id, among the ones passed in. See
	 * {@link CharacterRules#resolveCharacter} — the rule lives there so it can be checked outside the
	 * game.</p>
	 */
	public static String resolveCharacter(List<String> candidateIds, String query) {
		return CharacterRules.resolveCharacter(sheets, candidateIds, query);
	}

	/**
	 * <p>How to offer that character in a list: the name, or {@code Name [id]} if another entry in the
	 * list shares the name. See {@link CharacterRules#suggestionLabelFor}.</p>
	 */
	public static String suggestionLabelFor(List<String> candidateIds, String characterId) {
		return CharacterRules.suggestionLabelFor(sheets, candidateIds, characterId);
	}

	/** A character's name by its id, or the id itself if it has no name: used for menus and messages. */
	public static String nameOfCharacter(String characterId) {
		String name = CharacterRules.nameOf(sheets.get(characterId));
		return name != null && !name.isBlank() ? name : characterId;
	}

	/** See {@link CharacterRules#ownerOf} — the rule lives there so it can be checked outside the game. */
	public static String ownerOf(String characterId, JsonObject sheet) {
		return CharacterRules.ownerOf(characterId, sheet);
	}

	//Default names the mod itself leaves when the player never wrote their own (see
	//validateSheet/makeNew and the field's placeholder on the sheet) — they don't work to identify
	//anyone in chat, so the real Minecraft name is used instead in that case.
	private static final Set<String> DEFAULT_CHARACTER_NAMES = Set.of("New Sheet", "John Doe", "Fulano de Tal"); //"Fulano de Tal" is the old Spanish default, kept so old sheets are still recognized.

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
	 * <p>Character level, decoupled from real Minecraft XP as soon as the DM sets it by hand with
	 * {@code /dndsheet setlevel} (stores "characterLevel" on the sheet). Until then, it keeps reflecting
	 * real Minecraft XP, exactly as before — so this changes nothing for a sheet that never used the
	 * command. Used both by the server (traits/resources that scale by level) and by
	 * {@code CharacterSheetScreen} on the client (so it stops showing XP once a level's been set).</p>
	 */
	public static int characterLevelOf(JsonObject sheet, Player fallbackEntity) {
		if (sheet != null && sheet.has("characterLevel")) {
			return sheet.get("characterLevel").getAsInt();
		}
		return Math.max(1, fallbackEntity.experienceLevel); //D&D PCs are never level 0, but Minecraft XP starts at 0.
	}

	/**
	 * <p>Persists the sheet AND sends it to the client, in that order. It's the pair <b>invariant 4</b>
	 * requires: whoever mutates a sheet must reach {@link #saveServer}, and whoever mutates it from the
	 * server almost always also needs the player to see it.</p>
	 *
	 * <p>Exists because the pair was being written halfway. Four armed-flag managers
	 * ({@code CounterspellManager}, {@code PaladinSmiteManager}, {@code ShieldManager},
	 * {@code SorcererMetamagicManager}) mutated the sheet and <b>only</b> notified the client, and three
	 * of them had their own identical private {@code sendSheetUpdate}. The state was left hanging on the
	 * 5-minute autosave, which is the safety net and not the write path — shutting down the server before
	 * it fired reverted the shield, smite, or spell slot already spent.</p>
	 */
	public static void saveAndSync(ServerPlayer player, JsonObject sheet) {
		saveServer(sheet, player.getStringUUID());
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	//Save the given sheet into a JSON file, making a new one if it doesn't exist, and updates the "sheets" HashMap.
	//The id is resolved via activeCharacterOf just like on read: the 3 callers pass a player UUID,
	//and without this they'd always save over the legacy sheet instead of the character currently
	//equipped. A character id belonging to no one (an NPC) resolves to itself and is saved as-is.
	public static void saveServer(JsonObject sheet, String uuid) {
		String characterId = activeCharacterOf(uuid);
		sheets.put(characterId, sheet);
		//To disk at the end of the tick, not here: see the "dirty" comment. The id is resolved NOW and
		//not at flush time, so a character switch in between doesn't redirect the write to another sheet.
		dirty.add(characterId);
	}

	/**
	 * <p>Writes the marked sheets and empties the list. Runs at the end of every server tick and does
	 * nothing — doesn't even touch the disk — if nothing's marked, which is the vast majority of ticks.</p>
	 */
	private static void flushDirty() {
		for (String characterId : dirty) {
			JsonObject sheet = sheets.get(characterId);
			//Deleted between marking and flushing. Without this, writing here would recreate the file
			//deleteCharacter just moved aside and the character would "resurrect" — the same failure
			//ensureHasCharacter already documents, via a different path.
			if (sheet == null) continue;
			writeCharacterFile(characterId, sheet);
		}
		dirty.clear();
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
			DndsheetsMod.LOGGER.error("Could not list the character sheet directory.", e);
		}

		//Per file, not for the whole batch: a sheet corrupted on disk (invalid JSON, or valid but not an
		//object, e.g. a crash mid-write) shouldn't take down the loading of ALL the other sheets —
		//previously, a parse exception (JsonSyntaxException/IllegalStateException, neither of which is
		//IOException) propagated uncaught and broke every player's join from that point on.
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
	 * <p>Rebuilds the player → active character binding by looking at each sheet's {@code active} field.
	 * Derived, instead of a separately stored index: an index can drift out of sync with the sheets and
	 * leave someone unable to play; the field inside the sheet itself can't contradict itself.</p>
	 *
	 * <p>If a player ended up with two sheets marked active (from manually editing the JSON), the one with
	 * the lower id wins and a warning is logged, instead of silently picking a different one on every startup.</p>
	 */
	private static void rebuildActiveCharacters() {
		activeCharacter.clear();
		List<String> ids = new ArrayList<>(sheets.keySet());
		Collections.sort(ids); //Stable order: without this, two active sheets would give a different winner on every startup.
		for (String characterId : ids) {
			JsonObject sheet = sheets.get(characterId);
			if (sheet == null || !sheet.has("active") || !sheet.get("active").getAsBoolean()) continue;
			String owner = ownerOf(characterId, sheet);
			if (owner == null) continue; //NPC: no player has it equipped.
			String previous = activeCharacter.putIfAbsent(owner, characterId);
			if (previous != null) {
				DndsheetsMod.LOGGER.warn("Player {} has several sheets marked as active ({} and {}); using {}.", owner, previous, characterId, previous);
			}
		}
	}

	//--- Multiple characters (Phase 1) ------------------------------------------------------------------

	//Id derived from the owner's UUID: unique across players without needing a global counter, and it's
	//still a valid filename on any system.
	private static String nextCharacterId(String playerUuid) {
		return CharacterRules.nextCharacterId(sheets.keySet(), playerUuid);
	}

	/**
	 * <p>Creates one more character for that player, created but <b>not</b> active: equipping it is a
	 * separate, deliberate action ({@link #switchCharacter}), not a side effect of creating it.</p>
	 *
	 * @return the new character's id.
	 */
	public static String createCharacter(String playerUuid, String characterName) {
		String characterId = nextCharacterId(playerUuid);
		JsonObject sheet = new JsonObject();
		sheet.addProperty("characterName", characterName);
		sheet.addProperty("ownerUuid", playerUuid);
		sheet.addProperty("active", false);
		//EXPLICIT level 1. Without it, characterLevelOf falls back to the Minecraft XP level of whoever
		//creates it, which belongs to the PLAYER and not the character: a freshly made character was born
		//at level 12 from mining stone, with the HP, proficiency, and spell slots of a level 12, and every
		//character of the same person came out identical to each other. Reported exactly like this while playing.
		sheet.addProperty("characterLevel", 1);
		validateSheet(sheet);
		sheets.put(characterId, sheet);
		saveCharacter(characterId, sheet);
		return characterId;
	}

	/** Suffix of the copy left behind when a character is deleted. Doesn't end in .json: it won't be reloaded. */
	public static final String DELETED_SUFFIX = ".json.deleted";

	/**
	 * <p>Deletes a character. Returns {@code null} on success, or the reason it couldn't be done.</p>
	 *
	 * <p><b>Doesn't delete the file: renames it</b> to {@code <id>.json.deleted}. Deleting a character is
	 * the mod's only action that destroys hours of play with no undo, and a copy the DM can put back in
	 * place by hand costs one line. It stops ending in {@code .json}, so it won't be reloaded on startup.</p>
	 *
	 * <p><b>Never leaves anyone without a character.</b> If the deleted one was the equipped one, another
	 * of theirs gets equipped; and if none were left, a blank sheet is created on the spot. That branch is
	 * exactly what turns "delete" into "reset" for someone with only one character, without needing two
	 * concepts: without it, hitting zero leaves the player with {@code getServerSheet} returning null until
	 * they reconnect, and half a dozen combat paths silently skip whoever has no sheet.</p>
	 *
	 * @param isDm whether the requester can delete sheets that aren't theirs (a DM's NPC).
	 */
	@Nullable
	public static String deleteCharacter(ServerPlayer requester, String characterId, boolean isDm) {
		JsonObject sheet = sheets.get(characterId);
		if (sheet == null) return "not_found";

		String owner = ownerOf(characterId, sheet);
		String requesterUuid = requester.getStringUUID();
		boolean own = requesterUuid.equals(owner);
		//An NPC (no owner) belongs to the DM; nobody deletes ANOTHER player's character, not even the DM:
		//that would mean throwing away someone's sheet behind their back to say no.
		if (!own && !(owner == null && isDm)) return "not_yours";

		sheets.remove(characterId);
		//Alongside the remove, not just in flushDirty's guard: what doesn't exist in memory doesn't get written.
		dirty.remove(characterId);
		activeCharacter.remove(requesterUuid, characterId);
		Path file = SHEETS_DIR.resolve(characterId + ".json").toAbsolutePath();
		try {
			Files.move(file, SHEETS_DIR.resolve(characterId + DELETED_SUFFIX).toAbsolutePath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			//The sheet is already out of memory, so the deletion still counts; only the backup copy is lost.
			DndsheetsMod.LOGGER.warn("Could not set aside the backup copy of deleted character {}: {}", characterId, e.getMessage());
		}

		//Always, not just if the equipped one was deleted: the client needs to be left with a sheet that
		//exists, and checking "was it the active one?" here would be a third copy of that same question.
		if (own) ensureHasCharacter(requester);
		return null;
	}

	/**
	 * <p>Leaves the player with a character equipped no matter what: another of theirs if any are left, or
	 * a blank sheet if they hit zero. Same path as the first connection, which already creates a sheet for
	 * whoever had none.</p>
	 */
	private static void ensureHasCharacter(ServerPlayer player) {
		String playerUuid = player.getStringUUID();
		//The EXPLICIT binding is asked, not getServerSheet: that function falls back to the player's own
		//UUID when none is set (compatibility with pre-character sheets), so it would answer "yes, has a
		//character" as soon as a file with that id existed. The result was that deleting the equipped
		//character didn't notify the client, the sheet opened with H was still the deleted one, and saving
		//wrote it back to disk — the character "resurrected".
		String wear = CharacterRules.characterToWearAfter(sheets.keySet(), activeCharacter.get(playerUuid), charactersOf(playerUuid));
		if (wear != null) {
			//switchCharacter even if it was already the equipped one: it leaves the "active" flag consistent
			//on disk and, above all, sends the sheet to the client. A client left holding a deleted sheet
			//rewrites it as soon as it touches anything on the screen.
			switchCharacter(player, wear);
			return;
		}
		makeNew("New Sheet", playerUuid);
		JsonObject fresh = getServerSheet(playerUuid);
		applyClassHitPoints(player, fresh);
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(fresh.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	/**
	 * <p>NPC sheet: a character with no owner, that no one has equipped. It's what lets the DM have sheets
	 * for allies and minor characters under the same rules as a PC, instead of having to turn them into
	 * monsters with a stat block.</p>
	 */
	public static String createNpc(String characterName) {
		String characterId = CharacterRules.npcIdFor(sheets.keySet(), characterName);

		JsonObject sheet = new JsonObject();
		sheet.addProperty("characterName", characterName);
		sheet.addProperty("ownerUuid", ""); //Empty, not absent: "belongs to no one" must be distinguishable from "legacy sheet".
		sheet.addProperty("active", false);
		sheet.addProperty("characterLevel", 1); //Same as a PC: born at level 1, not from anyone's XP.
		validateSheet(sheet);
		sheets.put(characterId, sheet);
		saveCharacter(characterId, sheet);
		return characterId;
	}

	/**
	 * <p>Switches that player over to another of their characters. Returns false if the character doesn't
	 * exist or isn't theirs — nobody can equip someone else's sheet.</p>
	 */
	public static boolean switchCharacter(ServerPlayer player, String characterId) {
		String playerUuid = player.getStringUUID();
		JsonObject target = sheets.get(characterId);
		if (target == null || !playerUuid.equals(ownerOf(characterId, target))) return false;

		//CURRENT health belongs to the character, not the body wearing it. It used to live solely in the
		//entity's health — which belongs to the player — so switching characters left you with the
		//previous one's wounds, and switching back you'd find the new one's. The outgoing character's
		//health is saved BEFORE touching anything.
		String previousId = activeCharacter.get(playerUuid);
		JsonObject previous = previousId == null ? null : sheets.get(previousId);
		if (previous != null && previous != target) {
			previous.addProperty("hitPoints", String.valueOf((int) Math.ceil(player.getHealth())));
			saveCharacter(previousId, previous);
		}

		//And the inventory, which also belongs to the character: the wizard's staff doesn't travel to the
		//fighter. This goes here, before moving the binding, so the outgoing character's sheet gets saved
		//with its gear inside.
		CharacterInventory.swap(player, previousId, previous, target);

		//Whatever the previous character was DOING ends with them. All of these live indexed by player
		//(they're live state, not sheet data), so without clearing them the new character inherited the
		//previous one's concentration, rage, wild shape, and mark: it stayed enraged without having entered
		//a rage. Concentration goes first because it also drags along zones, weapon buffs, and summons (see
		//ConcentrationManager.stopConcentrating).
		if (previous != null && previous != target) {
			ConcentrationManager.stopConcentrating(player);
			CastingManager.clearFor(player);
			BarbarianRageManager.clearFor(player);
			DruidWildShapeManager.clearFor(player);
			RangerHunterMarkManager.clearFor(player);
		}

		//The previous one is unmarked and the new one marked, so rebuildActiveCharacters() reconstructs
		//exactly this same state after a restart.
		for (String owned : charactersOf(playerUuid)) {
			JsonObject sheet = sheets.get(owned);
			boolean shouldBeActive = owned.equals(characterId);
			if (sheet.has("active") && sheet.get("active").getAsBoolean() == shouldBeActive) continue;
			sheet.addProperty("active", shouldBeActive);
			//The legacy sheet had no ownerUuid; touching it means stamping it on, or it would stop being
			//recognized as theirs as soon as the player has a character with a different id equipped.
			if (!sheet.has("ownerUuid")) sheet.addProperty("ownerUuid", playerUuid);
			saveCharacter(owned, sheet);
		}
		activeCharacter.put(playerUuid, characterId);

		//Migrating a pre-characters sheet: it gets stamped with the level it HAD at this moment (the XP
		//one, if none was ever set) so that from now on it belongs to the character and not the player.
		//Without this, two characters of the same person would share a level forever, because both pulled
		//it from the same place. It's frozen at its current value instead of set to 1: lowering the level
		//of someone who's been playing with it would be destroying their character to fix an inconsistency.
		if (!target.has("characterLevel")) {
			target.addProperty("characterLevel", Math.max(1, characterLevelOf(target, player)));
			saveCharacter(characterId, target);
		}

		//The new character has its own max HP (class, level, Constitution) and its own sheet on the
		//client: without these two lines, switching characters left the player with the previous one's body.
		applyClassHitPoints(player, target);
		restoreHitPoints(player, target);
		//"Downed" belongs to the character (it lives in their sheet), so the death saves screen has to
		//follow whoever you're wearing: leaving a dying character to switch to another closes it, and
		//switching back reopens it. Without this, the state was correct in the data and invisible on screen.
		DeathSaveManager.resendState(player, target);
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(target.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		MinecraftForge.EVENT_BUS.post(new CharacterSwitchedEvent(player, characterId, target));
		return true;
	}

	/**
	 * <p>Gives a body to an NPC sheet: spawns a world entity bound to that character. Without this,
	 * {@link #createNpc} produced a perfectly valid sheet nobody could actually use for anything.</p>
	 *
	 * <p>The mob spawns with no AI, same as a summoned monster: the DM drives it, it doesn't move on its
	 * own. The entity is the body; the character — HP, conditions, ability scores — lives in the sheet and
	 * outlives it.</p>
	 *
	 * @return the spawned entity, or {@code null} if the character or the entity type don't exist.
	 */
	public static net.minecraft.world.entity.Entity spawnNpc(net.minecraft.server.level.ServerLevel level,
			double x, double y, double z, String characterId, String baseEntityId) {
		return spawnNpc(level, x, y, z, characterId, baseEntityId, false);
	}

	/**
	 * @param keepsOwnAi keeps the base entity's AI alive instead of spawning it frozen. It's the same as
	 *                   {@code "ai": true} in a monster stat block (see {@code MonsterRegistry}): useful
	 *                   for NPC-mod entities, which bring their own goals — patrolling, following the
	 *                   group — and are useless frozen. In combat the mod still takes over regardless:
	 *                   {@code TurnManager.freeze} shuts that AI off for the duration of the encounter.
	 */
	@Nullable
	public static net.minecraft.world.entity.Entity spawnNpc(net.minecraft.server.level.ServerLevel level,
			double x, double y, double z, String characterId, String baseEntityId, boolean keepsOwnAi) {
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
		if (entity instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(!keepsOwnAi);
		Combatant.tagAsCharacter(entity, characterId);

		level.addFreshEntity(entity);

		//If given a body mid-combat while one's already underway, it joins the turn order right away:
		//otherwise it would stand there unable to act for the whole encounter.
		TurnManager.addLateMonster(level, entity, name);
		return entity;
	}

	/**
	 * <p>Gives the incoming character back the health they had when they were last unequipped. With no
	 * prior sheet (freshly created) they come in at full, which is what's expected of a new character.</p>
	 *
	 * <p>Runs AFTER {@code applyClassHitPoints}: that sets the max based on class and level, and restoring
	 * before it would clamp the health against the PREVIOUS character's max.</p>
	 */
	private static void restoreHitPoints(ServerPlayer player, JsonObject sheet) {
		float max = player.getMaxHealth();
		float restored = max;
		if (sheet.has("hitPoints")) {
			try {
				restored = Float.parseFloat(sheet.get("hitPoints").getAsString());
			} catch (RuntimeException e) {
				restored = max; //Old sheet with garbage in the field: comes in at full instead of dying.
			}
		}
		//Never below 1: a downed character stays frozen at 1 HP (see DeathSaveManager), so a stored 0 can
		//only come from a weird sheet, and restoring it would kill the player on switch.
		player.setHealth(Math.max(1f, Math.min(max, restored)));
	}

	/**
	 * <p>Saves a SPECIFIC character's sheet, without going through the active-player binding.
	 * {@link CharacterInventory} needs it to persist the outgoing character before clearing the body's
	 * inventory: at that instant the active one is already the other one, and {@code saveServer} would
	 * write over the wrong sheet.</p>
	 */
	static void saveCharacterSheet(String characterId, JsonObject sheet) {
		saveCharacter(characterId, sheet);
	}

	//Like saveServer but without resolving the id OR deferring: here it's already known which character is
	//being written (routing it through activeCharacterOf would redirect it to its owner's active
	//character), and its callers — create, delete, switch character, server shutdown — are one-off moments
	//where the file needs to be on disk before proceeding, not combat changes worth batching.
	private static void saveCharacter(String characterId, JsonObject sheet) {
		sheets.put(characterId, sheet);
		//Already written, so a pending mark for this same id has nothing left to contribute.
		dirty.remove(characterId);
		writeCharacterFile(characterId, sheet);
	}

	/** The single place in the whole mod that touches disk for a sheet. */
	private static void writeCharacterFile(String characterId, JsonObject sheet) {
		Path file = SHEETS_DIR.resolve(characterId + ".json").toAbsolutePath();
		try {
			Files.createDirectories(SHEETS_DIR);
			//writeString rather than deleteIfExists + newOutputStream(CREATE): bare CREATE does NOT
			//truncate (it only implies TRUNCATE_EXISTING when no options are passed), so without deleting
			//first, a sheet that SHRINKS — removing a condition, spending a slot that deletes the key —
			//left the tail of the previous content stuck on and the JSON ended up corrupted. writeString
			//truncates on its own, in one call instead of three, and without the extra copy getBytes() made.
			Files.writeString(file, DndsheetsMod.PRETTY_GSON.toJson(sheet));
		} catch (IOException e) {
			//The in-memory map is already updated, so without this log the player sees their sheet
			//"saved" while the actual file on disk may not reflect it, with no warning at all.
			DndsheetsMod.LOGGER.error("Could not save character " + characterId + " to disk.", e);
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

	//ponytail: no real migrations to apply yet (no field has changed shape between versions) — this method
	//only stamps the current version onto old sheets that didn't have one. Once an actual migration is
	//needed, add one more case here per version, before bumping CURRENT_SCHEMA_VERSION.
	private static void migrateIfNeeded(JsonObject sheet) {
		int version = sheet.has("schemaVersion") ? sheet.get("schemaVersion").getAsInt() : 0;
		if (version < CURRENT_SCHEMA_VERSION) {
			sheet.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
		}
	}

	//Makes a new sheet, adds it to the "sheets" HashMap, and then calls Save() to make a file from it.
	//Delegates to validateSheet for the default values instead of rebuilding them by hand: previously any
	//change to a default roll expression had to be made in both places at once.
	public static void makeNew(String characterName, String uuid) {
		JsonObject newSheet = new JsonObject();
		newSheet.addProperty("characterName", characterName);
		validateSheet(newSheet);
		saveServer(newSheet, uuid);
	}

	//Sets a new "current" sheet from the "sheets" HashSet. Ideally some GUI letting you choose from the loaded list will call this.
	public static void setClient(JsonObject sheet) {
		current = sheet;
		clientSheetVersion++;
		//lol it's really that simple
	}

	/**
	 * <p>Increments on every change to the client sheet, whether by full replacement or by patch. It lets
	 * the client side know "this is stale now" without having to compare the sheet, which is a JSON tree:
	 * recomputing its hashCode costs as much as redoing the work you'd want to avoid.</p>
	 *
	 * <p>Used by {@code ResourceHudOverlay}, which is the most-frequently-run thing in the mod: it's an
	 * always-visible HUD, so per frame (60-240 Hz) with no menu needing to be open. It used to build four
	 * strings every time — walking the conditions array and slicing it up — when those strings only change
	 * when the sheet changes.</p>
	 */
	public static int clientSheetVersion() {
		return clientSheetVersion;
	}

	private static int clientSheetVersion = 0;

	//Applies a partial patch (see network.SheetFieldUpdateMessage) onto the client's cached sheet, instead
	//of replacing it whole like setClient — JsonNull as a value means "delete this key", the same way the
	//server deleted it with JsonObject.remove(...).
	public static void applyClientDelta(JsonObject patch) {
		if (current == null) return;
		for (String key : patch.keySet()) {
			JsonElement value = patch.get(key);
			if (value.isJsonNull()) current.remove(key);
			else current.add(key, value);
		}
		clientSheetVersion++;
	}



}
