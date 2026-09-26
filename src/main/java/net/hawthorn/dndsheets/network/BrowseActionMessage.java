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
 * <p>Client → server: "show me a list that only the server knows about." It started out as just the
 * character roster and widened to the compendium, which has exactly the same shape — the client stores
 * neither the sheets nor the content registries, so in both cases the client asks, the server formats, and
 * the client paints. Renamed from {@code RosterActionMessage} when it widened: a name that no longer
 * describes what the class does is debt, not a detail.</p>
 *
 * <p>A single message parameterized by {@link Action} instead of one class per query, following the same
 * pattern as {@link SheetAdjustMessage} (which already groups seven actions) and {@link ScreenActionMessage}.
 * The network id doesn't change on a rename: it's assigned by registration order, not by name.</p>
 *
 * <p>{@code LIST_PARTY} is the only one that requires operator permission: seeing everyone's sheet is DM
 * information. Listing your own characters and switching between them are actions on your own stuff, with
 * nothing to gate.</p>
 */
public class BrowseActionMessage {

	//At the end, never in the middle: writeEnum travels by ordinal (see invariant 2 in PROJECT_CONTEXT.md).
	//From GIVE_WEAPONS downward are the former *ListRequestMessage pairs (eight nearly identical classes:
	//requesting a list that only lives in the server's memory), merged here — invariant 3.
	//characterId, which was already free-form text, carries the target's uuid, the category, or the ContentType.
	public enum Action { LIST_MINE, LIST_PARTY, SWITCH, LIST_CONTENT, CONTENT_DETAIL, JOURNAL_DETAIL, DELETE, CREATE, SKILL_TOGGLE, LIST_SUBCLASSES, SUBCLASS_CHOOSE, LIST_FEATS, FEAT_CHOOSE,
		GIVE_WEAPONS, GIVE_SPELLS, GRANT_TRAITS, LIST_PRESETS, LIST_PRESETS_MULTICLASS, SPAWN_MONSTERS,
		MANAGE_OPTIONS, CONTENT_ENTRIES, CHARACTER_OPTIONS, LIST_ENCOUNTERS,
		SPELL_PREPARE, SPELL_UNPREPARE, DESIGN_ENCOUNTER,
		RULES_LIST, RULES_SET, CONFIG_LIST, CONFIG_SET, GIVE_MAGIC, LIST_IDS }

	final Action action;
	//Used by SWITCH and DELETE (an id), CREATE (the new character's name), and SKILL_TOGGLE (the skill's
	//index); the rest send it empty.
	//The field is free-form text, so CREATE fits here without registering another message — invariant 3.
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
					//Checked here and not just when painting the button: a modified client can send the
					//message regardless, and the permission has to hold on the server side to mean anything.
					if (DndsheetsMod.canActAsDm(sender)) sendParty(sender);
				}
				//The compendium is read-only and reveals nothing a player can't already see in their
				//Grimoire or in a monster's sheet while fighting it: not gated by operator.
				case LIST_CONTENT -> CompendiumQuery.sendList(sender, message.characterId);
				case CONTENT_DETAIL -> CompendiumQuery.sendDetail(sender, message.characterId);
				case JOURNAL_DETAIL -> sendJournalEntry(sender, message.characterId);
				case CREATE -> {
					String name = message.characterId.trim();
					//Validated on the server even though the screen already does: a client can send whatever
					//it wants, and a nameless character can't even be selected afterward by name.
					if (name.isEmpty()) {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.needs_name").withStyle(ChatFormatting.RED));
						return;
					}
					String created = SheetLoader.createCharacter(sender.getStringUUID(), name);
					//Created but NOT equipped: equipping it is a separate, deliberate action (see
					//SheetLoader.createCharacter). Said out loud, because otherwise it looks like nothing happened.
					sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.created", name).withStyle(ChatFormatting.GREEN));
					//Without this, a freshly created character is a blank sheet with no hint that there are
					//four things to choose, or where they are.
					sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.setup_hint").withStyle(ChatFormatting.GRAY));
					sendOwnCharacters(sender); //Reopens the list, now with the new one already in it.
					DndsheetsMod.LOGGER.info("dndsheets: character {} created for {}", created, sender.getName().getString());
				}
				case DELETE -> {
					//The permission only opens the door for the DM's NPCs; SheetLoader itself still refuses
					//to delete another player's character, no matter what permission the requester has.
					String error = SheetLoader.deleteCharacter(sender, message.characterId, DndsheetsMod.canActAsDm(sender));
					if (error == null) {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.deleted", message.characterId).withStyle(ChatFormatting.GREEN));
						sendOwnCharacters(sender);
					} else {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.delete_failed").withStyle(ChatFormatting.RED));
					}
				}
				//Choosing what you're proficient in is an action on your own character, like switching
				//between characters: not gated by operator. What a player CANNOT do is write the raw
				//expression — "skills" is an operator-only key in SheetServerMessage for exactly that reason
				//— so here the client sends an index and the server writes the rule. A modified client can
				//only request proficiency in one of its own skills, which is what the screen already offers.
				case SKILL_TOGGLE -> toggleSkill(sender, message.characterId);
				//The subclass belongs to your character, like the rest of this screen: no operator needed.
				//What decides which ones you can choose is set by the server (your preset and your level),
				//not whatever list the client has.
				case LIST_SUBCLASSES -> sendSubclasses(sender);
				case SUBCLASS_CHOOSE -> chooseSubclass(sender, message.characterId);
				//Feats are always listed; what decides whether one can be picked is having a pending
				//improvement, and LevelUpManager checks that when it's chosen.
				case LIST_FEATS -> sendFeats(sender);
				case FEAT_CHOOSE -> {
					if (!net.hawthorn.dndsheets.LevelUpManager.applyFeat(sender, message.characterId)) {
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.levelup.feat_unavailable").withStyle(ChatFormatting.RED));
					}
				}
				//The four "give/grant to another player" actions from the DM Panel: same server-side lock
				//as LIST_PARTY (a modified client can send whatever it wants), the list from the matching
				//registry, and the target's uuid travels back in context for the screen that opens.
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
				//Preparing and unpreparing are actions on YOUR OWN list, like switching characters or
				//toggling a proficiency: no operator needed. The limit is set by the server (see
				//CharacterRules.preparedLimitFor), not the screen — a modified client can't skip it.
				case SPELL_PREPARE -> setPrepared(sender, message.characterId, true);
				case SPELL_UNPREPARE -> setPrepared(sender, message.characterId, false);
				//The "Multiclass" button on the sheet is about yourself, never about another player.
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
				//No special permission needed: any player chooses their own race/background/class. It's the
				//fallback path when the species addon (which delegates to Origins) isn't installed.
				case CHARACTER_OPTIONS -> sendCharacterOptions(sender, message.characterId);
				case LIST_ENCOUNTERS -> {
					if (DndsheetsMod.canActAsDm(sender)) sendEncounters(sender);
				}
				//The designer builds a NEW encounter, so what it needs from the server isn't the list of
				//encounters but the bestiary with its XP cost and the party's thresholds: with that the
				//client recalculates difficulty on every click without another round trip per row (see
				//EncounterDesignerScreen).
				//The Rules menu (Auto/Manual per automation, plus feet per block and casting time). DM-gated
				//on the server, and RULES_SET answers with the fresh list so the screen repaints itself.
				case RULES_LIST -> {
					if (DndsheetsMod.canActAsDm(sender)) sendRules(sender);
				}
				case RULES_SET -> {
					if (DndsheetsMod.canActAsDm(sender) && applyRule(message.characterId)) sendRules(sender);
				}
				//Toml lists (hit dice, weapon damage, enchantment bonus) edited in-game. "TABLE" to list;
				//"TABLEoldnew" to set (old empty = add, new empty = delete).
				case CONFIG_LIST -> {
					if (DndsheetsMod.canActAsDm(sender)) sendConfig(sender, message.characterId);
				}
				case CONFIG_SET -> {
					if (DndsheetsMod.canActAsDm(sender)) {
						String table = applyConfig(message.characterId);
						if (table != null) sendConfig(sender, table);
					}
				}
				//Ids of a registry, for the "pick instead of typing" list (ChoiceScreen). Content names aren't secret
				//(the compendium shows them to everyone), so no operator check.
				case LIST_IDS -> sendIds(sender, message.characterId);
				case GIVE_MAGIC -> {
					if (DndsheetsMod.canActAsDm(sender)) sendMagicItems(sender, message.characterId);
				}
				case DESIGN_ENCOUNTER -> {
					if (DndsheetsMod.canActAsDm(sender)) sendEncounterDesign(sender);
				}
				case SWITCH -> {
					if (SheetLoader.switchCharacter(sender, message.characterId)) {
						JsonObject sheet = SheetLoader.getCharacterSheet(message.characterId);
						String name = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : message.characterId;
						sender.sendSystemMessage(Component.translatable("chat.dndsheets.character.now_playing", name).withStyle(ChatFormatting.GREEN));
						sendOwnCharacters(sender); //Reopens the list with the marker already moved, without another round trip.
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

	//The validation carried over intact from PresetListRequestMessage: targeting someone else requires DM
	//permission and the player to actually exist; a malformed uuid from a broken client is discarded
	//instead of crashing the server thread with an uncaught exception.
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
	 * <p>The live list of a {@code CharacterOptionsRegistry} category as a JSON array in a single label
	 * (same trick as DETAIL). Public: also re-sent as an echo by {@code OptionsSaveMessage} and by the
	 * content creator's saves, which used to duplicate this send independently, each on its own.</p>
	 */
	public static void sendOptions(ServerPlayer player, BrowseListMessage.Kind kind, String category) {
		if (!net.hawthorn.dndsheets.CharacterOptionsRegistry.isValidCategory(category)) return;
		com.google.gson.JsonArray array = new com.google.gson.JsonArray();
		for (String value : net.hawthorn.dndsheets.CharacterOptionsRegistry.get(category)) array.add(value);
		BrowseListMessage.send(player, kind, List.of(), List.of(Component.literal(array.toString())), category);
	}

	/** Public: also re-sent as an echo by the content creator's saves/deletes. */
	public static void sendContentEntries(ServerPlayer dm, String typeName) {
		net.hawthorn.dndsheets.ContentType type;
		try {
			type = net.hawthorn.dndsheets.ContentType.valueOf(typeName);
		} catch (IllegalArgumentException e) {
			return;
		}
		//Two arrays: what the DM created (editable and deletable) and what comes from the pack (shown so it
		//can be used as a starting point — saving a copy with the same id lets it take precedence, see
		//ContentPackFile).
		String mine = net.hawthorn.dndsheets.ContentPackFile.readArrayText(type.dmCreatedFile());
		String fromPacks = net.hawthorn.dndsheets.ContentPackFile.readOtherArraysText(type.dir, type.dmCreatedFile());
		//A component travels as at most 262144 characters of JSON: the magic-item pack alone is ~130 KB, so with
		//more packs on top the packet would fail to encode. Over the cap the pack part is left out (yours stays).
		if (fromPacks.length() > 200_000) fromPacks = "[]";
		BrowseListMessage.send(dm, BrowseListMessage.Kind.CONTENT_ENTRY, List.of(),
			List.of(Component.literal(mine), Component.literal(fromPacks)), type.name());
	}

	//Each row travels with its composition description ("goblin x4, wolf x2") so the DM chooses knowing
	//what they're summoning; the client's click fires the usual /dndencounters spawn.
	private static void sendEncounters(ServerPlayer dm) {
		List<String> ids = new ArrayList<>(net.hawthorn.dndsheets.EncounterRegistry.ids());
		java.util.Collections.sort(ids);
		List<Component> labels = new ArrayList<>(ids.size());
		for (String id : ids) {
			net.hawthorn.dndsheets.EncounterRegistry.Encounter encounter = net.hawthorn.dndsheets.EncounterRegistry.get(id);
			//The encounter's name comes from the pack, so it goes as a Component; the composition
			//("goblin x4, wolf x2") is built by describe() as plain text — see ContentNames.plain.
			if (encounter == null) {
				labels.add(Component.literal(id));
				continue;
			}
			net.minecraft.network.chat.MutableComponent label = ContentNames.of(encounter.name())
				.append(" · " + net.hawthorn.dndsheets.EncounterRegistry.describe(encounter));
			//And how tough it turns out for whoever is currently connected: that's half the question when
			//choosing from a list of prepared encounters, and until now only the composition was shown.
			int rating = net.hawthorn.dndsheets.EncounterBudget.rate(encounter, dm.server);
			if (rating >= 0) label.append(" · ").append(difficultyName(rating));
			labels.add(label);
		}
		BrowseListMessage.send(dm, BrowseListMessage.Kind.ENCOUNTER, ids, labels, "");
	}

	/** The verdict word ("Medium", "Deadly") — see {@code EncounterBudget.RATINGS}. */
	public static Component difficultyName(int rating) {
		return Component.translatable("gui.dndsheets.encounter.difficulty." + net.hawthorn.dndsheets.EncounterBudget.RATINGS[rating]);
	}

	//The bestiary with its estimated XP cost, plus the connected party's thresholds, in context as JSON
	//(same trick as sendOptions/sendContentEntries: a payload that isn't a list of rows travels as text
	//instead of inventing a new message — invariant 3).
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
			//Ones not yet available at this level ARE removed, unlike ones already taken: a level-19 Epic
			//Boon in a level-4 list isn't information, it's an option the server is going to reject.
			if (!net.hawthorn.dndsheets.FeatRegistry.availableAt(feat, level)) continue;
			ids.add(id);
			//Ones already taken are sent marked instead of removed: a list that shrinks without explanation
			//reads as missing content, and this is exactly the opposite.
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
	 * <p>Marks or unmarks a spell as prepared on the requester's sheet. Preparing above the limit is
	 * rejected with a warning; unpreparing is never rejected — going down is always valid, and being able
	 * to go down is required to be able to change your list.</p>
	 */
	private static void setPrepared(ServerPlayer sender, String spellId, boolean prepared) {
		JsonObject sheet = SheetLoader.getServerSheet(sender.getStringUUID());
		if (sheet == null) return;

		int limit = net.hawthorn.dndsheets.SpellRegistry.preparedLimitFor(sheet);
		//Limit 0 = not a spellcasting class, so there's no list to manage and the rule doesn't fire.
		if (limit <= 0) return;
		if (prepared && net.hawthorn.dndsheets.SpellRegistry.preparedCount(sheet) >= limit) {
			sender.sendSystemMessage(Component.translatable("chat.dndsheets.spell.prepared_full", limit)
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!net.hawthorn.dndsheets.SpellRegistry.setPrepared(sheet, spellId, prepared)) return;
		//Invariant 4: the prepared list is sheet state and gets lost on restart if not saved. saveAndSync
		//rather than saveServer because the screen repaints itself from the full sheet.
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
		SheetLoader.validateSheet(sheet); //An old sheet might not yet have all 18 skills.

		boolean proficient = !net.hawthorn.dndsheets.RollIndex.isSkillProficient(sheet, index);
		if (!net.hawthorn.dndsheets.RollIndex.setSkillProficiency(sheet, index, proficient)) return;

		//Invariant 4: whatever changes a sheet has to reach saveServer. Autosave is a safety net, not the
		//write path.
		SheetLoader.saveServer(sheet, player.getStringUUID());
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	/**
	 * <p>The journal with what THAT player can read. The filter is applied on the server and not on the
	 * client: sending entries that later get hidden while painting them would leave the DM's secrets in
	 * the memory of whoever shouldn't see them, which is not the same as hiding them.</p>
	 */
	public static void sendJournal(ServerPlayer player) {
		List<String> ids = new ArrayList<>();
		List<Component> labels = new ArrayList<>();
		boolean isDm = DndsheetsMod.canActAsDm(player);
		for (net.hawthorn.dndsheets.JournalManager.Entry entry : net.hawthorn.dndsheets.JournalManager.readableBy(player)) {
			ids.add(entry.id());
			//The visibility label is only shown to the DM: a player gains nothing from knowing that what
			//they just received is "2 players," and it does tell them someone else is in on it.
			labels.add(isDm
				? Component.translatable("gui.dndsheets.journal.row", entry.title(), entry.visibilityLabel())
				: Component.literal(entry.title()));
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.JOURNAL, ids, labels));
	}

	//Visibility is checked again when requesting the specific entry: the list the client has may have gone
	//stale, and a modified client can request any id. Filtering only at listing time isn't filtering.
	private static void sendJournalEntry(ServerPlayer player, String id) {
		net.hawthorn.dndsheets.JournalManager.Entry entry = net.hawthorn.dndsheets.JournalManager.get(id);
		if (entry == null || !entry.canRead(player)) return;
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.DETAIL, List.of(id),
				List.of(Component.literal(entry.title() + "\n" + entry.body()))));
	}

	/** Public: also used by {@code /dndchar} with no arguments, which already runs server-side. */
	public static void sendOwnCharacters(ServerPlayer player) {
		String activeId = SheetLoader.activeCharacterOf(player.getStringUUID());
		List<String> ids = new ArrayList<>();
		List<Component> labels = new ArrayList<>();

		List<String> owned = SheetLoader.charactersOf(player.getStringUUID());
		for (String characterId : owned) {
			JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
			//With the id appended only if another one shares the same name (see
			//CharacterRules.suggestionLabelFor): clicking a row sends the exact id, so there was no
			//ambiguity to resolve here — but two identical rows force a blind choice between them.
			String label = SheetLoader.suggestionLabelFor(owned, characterId);
			String characterClass = sheet != null && sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : "";
			ids.add(characterId);
			labels.add(Component.literal((characterId.equals(activeId) ? "▶ " : "   ") + label + (characterClass.isBlank() ? "" : " · " + characterClass)));
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new BrowseListMessage(BrowseListMessage.Kind.MINE, ids, labels));
	}

	/**
	 * <p>Party view: every connected player with the character they're currently playing, and every NPC
	 * with a body in the world, with their real HP and AC and their active conditions. HP and AC come from
	 * the {@link Combatant}, not the sheet, because the sheet only reflects them — and this is checked in
	 * the middle of combat, when what matters is the real number.</p>
	 *
	 * <p>NPCs are included here because they play by the full rules of a PC (see
	 * {@code Combatant.NpcCombatant}) and didn't show up in any list: to check on the one accompanying the
	 * party you had to go find it and look. It iterates <b>loaded entities</b> and not the sheets
	 * ({@code SheetLoader.npcIds}) on purpose — a sheet with no body isn't in the game, and an NPC with two
	 * bodies is two distinct things to track. The traversal is paid for when opening a menu, never in a
	 * combat loop, which is the same criterion by which {@code npcIds} iterates every sheet.</p>
	 */
	private static void sendParty(ServerPlayer dm) {
		List<String> ids = new ArrayList<>();
		List<Component> labels = new ArrayList<>();

		for (ServerPlayer player : dm.server.getPlayerList().getPlayers()) {
			Combatant combatant = Combatant.of(player);
			if (combatant == null) continue; //No sheet loaded yet: there's nothing to show for them.
			ids.add(player.getStringUUID());
			labels.add(partyRow(combatant));
		}

		for (ServerLevel level : dm.server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				String characterId = Combatant.characterIdOf(entity);
				if (characterId == null) continue;
				//Sheet deleted while the body is still in the world: Combatant.of falls back to monster or
				//null. Without a sheet it's no longer an NPC, so it's not part of the party either.
				if (!(Combatant.of(entity) instanceof Combatant.NpcCombatant combatant)) continue;
				//Id deliberately empty: an NPC's row isn't clickable (Sheet Adjust resolves by connected
				//player, and an NPC isn't one) — see PartyScreen.
				ids.add("");
				labels.add(partyRow(combatant).copy()
					.append(Component.translatable("gui.dndsheets.party.npc_tag").withStyle(ChatFormatting.DARK_GRAY)));
			}
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm),
			new BrowseListMessage(BrowseListMessage.Kind.PARTY, ids, labels));
	}

	/** One party row: name, HP, AC, and the conditions currently in effect. */
	private static Component partyRow(Combatant combatant) {
		StringBuilder label = new StringBuilder();

		//Conditions are what a DM needs to see at a glance, and otherwise are not visible anywhere without
		//opening each character's sheet separately.
		if (!combatant.conditions().isEmpty()) {
			label.append(" · ");
			boolean first = true;
			for (Condition condition : combatant.conditions()) {
				if (!first) label.append(", ");
				label.append(condition.displayLabel());
				first = false;
			}
		}
		return Component.literal(combatant.name())
			.append(Component.translatable("gui.dndsheets.party.row_stats", combatant.currentHp(), combatant.maxHp(), combatant.armorClass()))
			.append(label.toString());
	}

	//"KEY=value" pairs: one per Config.Rule (1 = automatic), plus FEET and CAST. Same list feeds the screen.
	private static void sendRules(ServerPlayer to) {
		List<String> ids = new ArrayList<>();
		for (net.hawthorn.dndsheets.Config.Rule rule : net.hawthorn.dndsheets.Config.Rule.values()) {
			ids.add(rule.name() + "=" + (net.hawthorn.dndsheets.Config.auto(rule) ? 1 : 0));
		}
		ids.add("FEET=" + net.hawthorn.dndsheets.Config.feetPerBlock());
		ids.add("CAST=" + net.hawthorn.dndsheets.Config.castTicksPerLevel());
		BrowseListMessage.send(to, BrowseListMessage.Kind.RULES, ids, List.of(), "");
	}

	private static boolean applyRule(String pair) {
		String[] kv = pair.split("=", 2);
		if (kv.length != 2) return false;
		try {
			int value = Integer.parseInt(kv[1].trim());
			switch (kv[0]) {
				case "FEET" -> {
					net.hawthorn.dndsheets.Config.setFeetPerBlock(value);
					net.hawthorn.dndsheets.TurnManager.refreshHud();
				}
				case "CAST" -> net.hawthorn.dndsheets.Config.setCastTicksPerLevel(value);
				default -> net.hawthorn.dndsheets.Config.setAuto(net.hawthorn.dndsheets.Config.Rule.valueOf(kv[0]), value != 0);
			}
			return true;
		} catch (IllegalArgumentException e) { //NumberFormatException included: a modified client can send anything.
			return false;
		}
	}

	private static void sendConfig(ServerPlayer to, String tableName) {
		net.hawthorn.dndsheets.Config.Table table;
		try {
			table = net.hawthorn.dndsheets.Config.Table.valueOf(tableName);
		} catch (IllegalArgumentException e) {
			return;
		}
		BrowseListMessage.send(to, BrowseListMessage.Kind.CONFIG, net.hawthorn.dndsheets.Config.entries(table), List.of(), table.name());
	}

	/** @return the table changed, or null if the request was refused (unknown table, invalid entry, entry not there). */
	private static String applyConfig(String payload) {
		String[] parts = payload.split("", -1);
		if (parts.length != 3) return null;
		net.hawthorn.dndsheets.Config.Table table;
		try {
			table = net.hawthorn.dndsheets.Config.Table.valueOf(parts[0]);
		} catch (IllegalArgumentException e) {
			return null;
		}
		String oldEntry = parts[1].trim(), newEntry = parts[2].trim();
		if (!newEntry.isEmpty() && !net.hawthorn.dndsheets.Config.isValid(table, newEntry)) return null;
		List<String> entries = net.hawthorn.dndsheets.Config.entries(table);
		int at = oldEntry.isEmpty() ? -1 : entries.indexOf(oldEntry);
		if (!oldEntry.isEmpty() && at < 0) return null;
		if (newEntry.isEmpty()) {
			if (at < 0) return null;
			entries.remove(at);
		} else if (at >= 0) {
			entries.set(at, newEntry);
		} else {
			entries.add(newEntry);
		}
		net.hawthorn.dndsheets.Config.setEntries(table, entries);
		return table.name();
	}

	//Magic items to hand out: ids + display names, with the target's uuid in context; the client turns each
	//row into the usual /dnditems give command (permission checked there too).
	private static void sendMagicItems(ServerPlayer dm, String targetUuid) {
		List<String> ids = new ArrayList<>(net.hawthorn.dndsheets.MagicItemRegistry.ids());
		java.util.Collections.sort(ids);
		List<Component> labels = new ArrayList<>();
		for (String id : ids) {
			net.hawthorn.dndsheets.MagicItemRegistry.MagicItem item = net.hawthorn.dndsheets.MagicItemRegistry.get(id);
			labels.add(item == null ? Component.literal(id) : ContentNames.of(item.name()));
		}
		BrowseListMessage.send(dm, BrowseListMessage.Kind.GIVE_MAGIC, ids, labels, targetUuid);
	}

	private static void sendIds(ServerPlayer to, String source) {
		List<String> ids;
		java.util.function.Function<String, String> nameOf = id -> id;
		switch (source) {
			case "SPELL" -> {
				ids = new ArrayList<>(net.hawthorn.dndsheets.SpellRegistry.ids());
				nameOf = id -> net.hawthorn.dndsheets.SpellRegistry.get(id).name();
			}
			case "TRAIT" -> {
				ids = new ArrayList<>(net.hawthorn.dndsheets.TraitRegistry.ids());
				nameOf = id -> net.hawthorn.dndsheets.TraitRegistry.get(id).name();
			}
			case "MONSTER" -> {
				ids = new ArrayList<>(net.hawthorn.dndsheets.MonsterRegistry.ids());
				nameOf = id -> net.hawthorn.dndsheets.MonsterRegistry.get(id).name();
			}
			case "MAGIC_ITEM" -> {
				ids = new ArrayList<>(net.hawthorn.dndsheets.MagicItemRegistry.ids());
				nameOf = id -> net.hawthorn.dndsheets.MagicItemRegistry.get(id).name();
			}
			case "WEAPON" -> ids = new ArrayList<>(net.hawthorn.dndsheets.Config.loadedWeaponIds());
			case "CLASS" -> ids = new ArrayList<>(net.hawthorn.dndsheets.CharacterOptionsRegistry.get(net.hawthorn.dndsheets.CharacterOptionsRegistry.CLASS));
			default -> {
				return;
			}
		}
		java.util.Collections.sort(ids);
		List<Component> labels = new ArrayList<>();
		for (String id : ids) {
			String name;
			try {
				name = nameOf.apply(id);
			} catch (RuntimeException e) { //An id that vanished between listing and naming: show the id.
				name = id;
			}
			//"Fireball (dndsheets:fireball)": the name to recognise it, the id because that's what gets written.
			labels.add(ContentNames.of(name).append(name.equals(id) ? "" : " (" + id + ")"));
		}
		BrowseListMessage.send(to, BrowseListMessage.Kind.IDS, ids, labels, source);
	}
}
