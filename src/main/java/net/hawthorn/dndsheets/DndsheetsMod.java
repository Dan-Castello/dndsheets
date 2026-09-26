
package net.hawthorn.dndsheets;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.PacketDistributor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;

import net.hawthorn.dndsheets.init.DndsheetsModSounds;
import net.hawthorn.dndsheets.init.DndsheetsModMenus;
import net.hawthorn.dndsheets.init.DndsheetsModCreativeTab;
import net.hawthorn.dndsheets.network.*;

import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;

@Mod("dndsheets")
public class DndsheetsMod {
	public static final Logger LOGGER = LogManager.getLogger(DndsheetsMod.class);

	/**
	 * <p>The indenting JSON writer the whole mod uses. It lives here for the same reason as LOGGER and
	 * PACKET_HANDLER: it's shared infrastructure, not owned by any one subsystem.</p>
	 *
	 * <p>Building a Gson isn't free — it assembles the whole TypeAdapterFactory list — and it was being
	 * done <b>on every write</b> in six places. The worst was SheetLoader.saveAll(), which runs every 5
	 * minutes and repeated the construction ONCE PER SHEET: with six players and several NPCs, a handful
	 * of new Gsons in the same tick. It's immutable and thread-safe, so a single instance is enough.</p>
	 */
	public static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final String MODID = "dndsheets";

	public DndsheetsMod() {
		MinecraftForge.EVENT_BUS.register(this);
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		net.hawthorn.dndsheets.init.DndsheetsModItems.REGISTRY.register(bus);
		DndsheetsModSounds.REGISTRY.register(bus);

		DndsheetsModMenus.REGISTRY.register(bus);
		DndsheetsModCreativeTab.REGISTRY.register(bus);
		bus.addListener(DndsheetsMod::registerNetworkMessages);

		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "dndsheets-common.toml");
	}

	//Bumped to "2": registerNetworkMessages() merged 6 messages into SheetAdjustMessage (see audit item
	//F14). messageID is assigned by REGISTRATION ORDER, not a fixed per-class constant — merging/removing/
	//adding one entry renumbers EVERYTHING registered after it in the list, not just what was touched.
	//Without bumping this, a client and a server on different mod versions would still pass the handshake
	//(same string as before) and end up misaligned on the message id for anything after the change point,
	//instead of Forge cleanly rejecting them on connect for an incompatible protocol version.
	//Bumped to "3": SheetSummaryMessage gains a field on the wire (the target's active conditions),
	//SheetAdjustMessage.Field gains the CONDITION constant, and BrowseActionMessage/BrowseListMessage get
	//registered. The first one is the truly dangerous part: one more field on an already-existing message
	//doesn't change any id, so without bumping this the handshake would pass and the old client would read
	//that message shifted by one field, silently and with plausible-looking data, instead of failing
	//cleanly on connect.
	//Bumped to "4": TurnActionMessage gets registered and ScreenActionMessage.Action gains
	//TURN_ACTION_OPEN. The second part is the truly dangerous one: an old client would read that ordinal
	//as an action it doesn't know instead of failing cleanly on connect.
	//Bumped to "5": AbilityImprovementMessage gets registered and ScreenActionMessage.Action gains
	//ABILITY_IMPROVEMENT_OPEN. The second part is the dangerous one: an old client would read that
	//ordinal as an action it doesn't know instead of failing cleanly on connect.
	//Bumped to "6": BrowseActionMessage.Action gains DELETE and CREATE. Adding to the END of an enum
	//doesn't renumber anything, but a new client sending that ordinal to an old server blows up on reading
	//it — which is exactly what the handshake is supposed to prevent. DELETE slipped in without bumping
	//the version: that's why NETWORK_SHAPE now exists, to fail the build when the network's shape changes
	//and this line doesn't.
	//Bumped to "8": BrowseListMessage sends its labels as a Component instead of plain text, so the
	//compendium is translated by the CLIENT in its own language instead of the server fixing its own.
	//This changes the wire format without changing either the message count or their order — so NEITHER
	//NETWORK_SHAPE NOR NETWORK_ORDER sees it, which is why NETWORK_WIRE now also exists (see below).
	//Bumped to "16": 7 new messages get registered at the END (DungeonTraceCaptureMessage,
	//MonsterSpawnListRequestMessage, MonsterSpawnListMessage, SpellGiveListRequestMessage,
	//SpellGiveListMessage, WeaponGiveListRequestMessage, WeaponGiveListMessage), and MonsterBindMessage
	//and WildShapeMessage gain new fields on the wire (bestiary resolved server-side).
	//Bumped to "17": the dungeon toolkit moves to its own addon/mod (dndsheets_dungeon) with its own
	//channel — 10 messages LEAVE this channel (DungeonGenerateMessage, DungeonJigsawConfigureMessage,
	//DungeonJigsawConfigureOpenMessage, DungeonPieceAddOpenMessage, DungeonPieceCaptureMessage,
	//DungeonPieceListMessage, DungeonPieceListRequestMessage, DungeonPieceRemoveMessage,
	//DungeonPieceUpdateMessage, DungeonTraceCaptureMessage). An old client that still sends them to this
	//channel finds no receiver: correct, because that addon no longer listens here, it listens on its own
	//"dndsheets_dungeon" channel.
	//Bumped to "18": new message StaffBindMessage (spell staves reconfigurable from the Grimoire).
	//Bumped to "19": TurnStateMessage gains the full initiative board (roster with per-combatant
	//conditions/state) for the professional turn HUD.
	//Bumped to "20": RosterRow gains bonusActionUsed (bonus action tracked separately from the action, see TurnManager).
	//Bumped to "21": "Multiclass" button on the sheet (audit item F25). MulticlassMessage gets registered
	//at the end, and PresetListRequestMessage/PresetListMessage gain a "multiclass" field on the wire
	//(same round trip, now with one more flag) so PresetScreen knows which mode to open in.
	//Bumped to "22": three things in the same batch. (1) RosterRow gains currentHp/maxHp at the end of the
	//payload (health bar on the turn board and on the floating name). (2) BrowseListMessage gains the
	//context field at the end. (3) The deliberate renumbering: the 8 List/ListRequest pairs (16 classes,
	//including the CharacterOptions* pair that nobody was sending anymore) get merged as new
	//actions/kinds of BrowseActionMessage/BrowseListMessage and their registrations are deleted — every
	//id after them changes, which is exactly what this handshake turns into a clean connection failure.
	//Bumped to "24": the coordinate chain left over from MCreator gets cleaned up, and two messages get
	//slimmer. (1) CharacterSheetOpenMessage loses type/pressedms: all three callers were sending (0, 0)
	//and the handler only had a branch for type == 0. (2) SheetRollButtonMessage loses x/y/z: they only
	//served to place the dice sound, and came from the player's position AT THE MOMENT THE SHEET WAS
	//OPENED (it sounded where you were before, not where you roll); now they're read from the player who
	//sends the packet, which the server already has.
	//Removing fields breaks things just like adding them does — an old client writes ints the new server
	//no longer reads and the buffer desyncs partway through — so the version bumps.
	//(3) In the same jump, invariant 3 gets applied to two groups that didn't follow it yet: the four rest
	//vote messages (RestPropose/RestVoteOpen/RestVoteResponse/RestVoteClose) merge into RestMessage with a
	//Kind, and the two death-save ones (DeathSaveRoll/DeathSaveGiveUp) into DeathSaveMessage. Six fewer
	//registrations and two new ones: EVERY id after them gets renumbered, which is exactly what this
	//handshake turns into a clean connection failure.
	//The three numbers move: NETWORK_SHAPE (114 → 116, six fewer messages but six more enum constants),
	//NETWORK_ORDER and NETWORK_WIRE.
	//Bumped to "25": the encounter designer. Two enum constants at the END of their lists
	//(BrowseActionMessage.DESIGN_ENCOUNTER and BrowseListMessage.ENCOUNTER_DESIGN, invariant 2), no new
	//messages: the bestiary with its XP cost and the party's thresholds travel in the context field
	//BrowseListMessage already had. An old client neither sends nor understands those two ordinals, so it
	//bumps anyway — NETWORK_SHAPE (116 → 118) and NETWORK_ORDER move; the wire itself doesn't change.
	//Still on "25", same unreleased batch: TurnStateMessage loses currentName. It was a writeUtf with the
	//name of the combatant on turn, sent to EVERY client on EVERY turn change, that landed on
	//TurnHudState.currentName and nobody read it — the HUD gets its names from the roster rows. Removed
	//from all three layers (message, client state, and TurnManager.broadcastTurnState). Moves
	//NETWORK_WIRE; neither the count nor the order changes.
	//Bumped to "26": fixes from the first round of human testing. ScreenActionMessage.Action gains
	//NEW_CHARACTER_OPEN at the END (invariant 2) so that "/dndchar new" with no name opens the wizard
	//instead of replying "unknown or incomplete command" — which is exactly what it told someone who was
	//writing exactly what they wanted to do. An old client doesn't know that ordinal, so it bumps anyway.
	//Moves NETWORK_SHAPE (118 -> 119) and NETWORK_ORDER; the wire itself doesn't change.
	//Bumped to "27": the Rules menu (Auto/Manual per automation). BrowseActionMessage.Action gains
	//RULES_LIST/RULES_SET and BrowseListMessage.Kind gains RULES, all at the END (invariant 2), and
	//TurnStateMessage gets feetPerBlock at the end of its payload (the HUD converts movement with it).
	//Bumped to "28": in-game editor for the toml tables (BrowseActionMessage CONFIG_LIST/CONFIG_SET, BrowseListMessage
	//CONFIG), handing out magic items (GIVE_MAGIC) and ContentType.MAGIC_ITEM, all appended at the END (invariant 2).
	private static final String PROTOCOL_VERSION = "28";

	/**
	 * <p>How many pieces cross the wire: registered messages plus the enum constants that travel by
	 * ordinal. <b>The game doesn't use it</b>: it exists so {@code JsonContentSelfTest} can compare it
	 * against the real shape and fail the build when someone adds one and doesn't touch
	 * {@link #PROTOCOL_VERSION}.</p>
	 *
	 * <p>Invariants 1 and 2 from PROJECT_CONTEXT.md are the two that have cost the most debugging
	 * sessions, and both fail silently: nothing breaks at compile time, and the client and server still
	 * shake hands only to end up misaligned later. A number that has to be touched by hand doesn't prevent
	 * the mistake, but it turns it into a decision instead of an oversight.</p>
	 */
	public static final int NETWORK_SHAPE = 131;

	/**
	 * <p>The exact order in which the pieces cross the wire, summarized as a hash. {@link #NETWORK_SHAPE}
	 * counts how many there are, and that's why it doesn't see the failure invariant 1 names first:
	 * <b>reordering</b> two already-registered entries doesn't change the count. Deleting lowers the
	 * number, inserting in the middle raises it, but swapping two leaves {@link #NETWORK_SHAPE} unchanged
	 * — and the ids get silently renumbered.</p>
	 *
	 * <p>The game doesn't use this one either: {@code JsonContentSelfTest.checkNetworkShape} rebuilds the
	 * hash from the source and fails the build when it doesn't match. If you move something on purpose,
	 * bump {@link #PROTOCOL_VERSION} and paste in the number the failure gives you.</p>
	 */
	public static final int NETWORK_ORDER = -592047910;

	/**
	 * <p>What gets written and read on the wire, summarized as a hash: the sequence of
	 * {@code writeX}/{@code readX} calls of every class in {@code network/}.</p>
	 *
	 * <p>It's the third angle on the same problem, and the one that was missing. {@link #NETWORK_SHAPE}
	 * counts HOW MANY pieces cross; {@link #NETWORK_ORDER} watches IN WHAT ORDER; neither of them sees a
	 * field change TYPE. Changing {@code BrowseListMessage.labels} from {@code writeUtf} to
	 * {@code writeComponent} left both numbers untouched and still broke compatibility: an old client
	 * would read a text where the new server writes a Component, and it desyncs partway through the packet.</p>
	 */
	public static final int NETWORK_WIRE = -1606748028;
	public static final SimpleChannel PACKET_HANDLER = NetworkRegistry.newSimpleChannel(new ResourceLocation(MODID, MODID), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
	private static int messageID = 0;

	public static <T> void addNetworkMessage(Class<T> messageType, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<NetworkEvent.Context>> messageConsumer) {
		PACKET_HANDLER.registerMessage(messageID, messageType, encoder, decoder, messageConsumer);
		messageID++;
	}

	//Centralized registration for the 60+ classes in network/: each one used to self-register with its
	//own @Mod.EventBusSubscriber + registerMessage(FMLCommonSetupEvent) method — ~5 lines of identical
	//boilerplate repeated per class. SimpleChannel requires that each class still have its own
	//buffer/constructor/handler (that can't be made generic), but the POINT where they're registered is
	//now a single one.
	private static void registerNetworkMessages(FMLCommonSetupEvent event) {
		addNetworkMessage(AddCustomAttackMessage.class, AddCustomAttackMessage::buffer, AddCustomAttackMessage::new, AddCustomAttackMessage::handler);
		addNetworkMessage(AdvancedRollEditorOpenMessage.class, AdvancedRollEditorOpenMessage::buffer, AdvancedRollEditorOpenMessage::new, AdvancedRollEditorOpenMessage::handler);
		addNetworkMessage(CharacterSheetOpenMessage.class, CharacterSheetOpenMessage::buffer, CharacterSheetOpenMessage::new, CharacterSheetOpenMessage::handler);
		addNetworkMessage(ClearCustomAttacksMessage.class, ClearCustomAttacksMessage::buffer, ClearCustomAttacksMessage::new, ClearCustomAttacksMessage::handler);
		addNetworkMessage(ContentEntryRemoveMessage.class, ContentEntryRemoveMessage::buffer, ContentEntryRemoveMessage::new, ContentEntryRemoveMessage::handler);
		addNetworkMessage(ContentEntrySaveMessage.class, ContentEntrySaveMessage::buffer, ContentEntrySaveMessage::new, ContentEntrySaveMessage::handler);
		addNetworkMessage(DeathSaveMessage.class, DeathSaveMessage::buffer, DeathSaveMessage::new, DeathSaveMessage::handler);
		addNetworkMessage(MonsterActionChooseMessage.class, MonsterActionChooseMessage::buffer, MonsterActionChooseMessage::new, MonsterActionChooseMessage::handler);
		addNetworkMessage(GiveItemMessage.class, GiveItemMessage::buffer, GiveItemMessage::new, GiveItemMessage::handler);
		addNetworkMessage(MonsterActionOpenMessage.class, MonsterActionOpenMessage::buffer, MonsterActionOpenMessage::new, MonsterActionOpenMessage::handler);
		addNetworkMessage(MonsterSaveTemplateMessage.class, MonsterSaveTemplateMessage::buffer, MonsterSaveTemplateMessage::new, MonsterSaveTemplateMessage::handler);
		addNetworkMessage(MonsterSpawnMessage.class, MonsterSpawnMessage::buffer, MonsterSpawnMessage::new, MonsterSpawnMessage::handler);
		addNetworkMessage(OptionsSaveMessage.class, OptionsSaveMessage::buffer, OptionsSaveMessage::new, OptionsSaveMessage::handler);
		addNetworkMessage(PassivePerceptionRequestMessage.class, PassivePerceptionRequestMessage::buffer, PassivePerceptionRequestMessage::new, PassivePerceptionRequestMessage::handler);
		addNetworkMessage(PresetApplyMessage.class, PresetApplyMessage::buffer, PresetApplyMessage::new, PresetApplyMessage::handler);
		addNetworkMessage(PresetApplyToMessage.class, PresetApplyToMessage::buffer, PresetApplyToMessage::new, PresetApplyToMessage::handler);
		addNetworkMessage(RemoveCustomAttackMessage.class, RemoveCustomAttackMessage::buffer, RemoveCustomAttackMessage::new, RemoveCustomAttackMessage::handler);
		addNetworkMessage(RestMessage.class, RestMessage::buffer, RestMessage::new, RestMessage::handler);
		addNetworkMessage(RollEditorOpenMessage.class, RollEditorOpenMessage::buffer, RollEditorOpenMessage::new, RollEditorOpenMessage::handler);
		addNetworkMessage(ScreenActionMessage.class, ScreenActionMessage::buffer, ScreenActionMessage::new, ScreenActionMessage::handler);
		addNetworkMessage(SheetAdjustMessage.class, SheetAdjustMessage::buffer, SheetAdjustMessage::new, SheetAdjustMessage::handler);
		addNetworkMessage(SheetClientMessage.class, SheetClientMessage::buffer, SheetClientMessage::new, SheetClientMessage::handler);
		addNetworkMessage(SheetFieldUpdateMessage.class, SheetFieldUpdateMessage::buffer, SheetFieldUpdateMessage::new, SheetFieldUpdateMessage::handler);
		addNetworkMessage(SheetRollButtonMessage.class, SheetRollButtonMessage::buffer, SheetRollButtonMessage::new, SheetRollButtonMessage::handler);
		addNetworkMessage(SheetServerMessage.class, SheetServerMessage::buffer, SheetServerMessage::new, SheetServerMessage::handler);
		addNetworkMessage(SheetSummaryMessage.class, SheetSummaryMessage::buffer, SheetSummaryMessage::new, SheetSummaryMessage::handler);
		addNetworkMessage(SheetSummaryRequestMessage.class, SheetSummaryRequestMessage::buffer, SheetSummaryRequestMessage::new, SheetSummaryRequestMessage::handler);
		addNetworkMessage(SpawnGenericMessage.class, SpawnGenericMessage::buffer, SpawnGenericMessage::new, SpawnGenericMessage::handler);
		addNetworkMessage(SpellCastMessage.class, SpellCastMessage::buffer, SpellCastMessage::new, SpellCastMessage::handler);
		addNetworkMessage(net.hawthorn.dndsheets.network.TurnActionMessage.class, net.hawthorn.dndsheets.network.TurnActionMessage::buffer,
			net.hawthorn.dndsheets.network.TurnActionMessage::new, net.hawthorn.dndsheets.network.TurnActionMessage::handler);
		addNetworkMessage(net.hawthorn.dndsheets.network.AbilityImprovementMessage.class, net.hawthorn.dndsheets.network.AbilityImprovementMessage::buffer,
			net.hawthorn.dndsheets.network.AbilityImprovementMessage::new, net.hawthorn.dndsheets.network.AbilityImprovementMessage::handler);
		addNetworkMessage(SpellGiveMessage.class, SpellGiveMessage::buffer, SpellGiveMessage::new, SpellGiveMessage::handler);
		addNetworkMessage(TraitGrantMessage.class, TraitGrantMessage::buffer, TraitGrantMessage::new, TraitGrantMessage::handler);
		addNetworkMessage(TurnControlMessage.class, TurnControlMessage::buffer, TurnControlMessage::new, TurnControlMessage::handler);
		addNetworkMessage(TurnEffectApplyMessage.class, TurnEffectApplyMessage::buffer, TurnEffectApplyMessage::new, TurnEffectApplyMessage::handler);
		addNetworkMessage(TurnStateMessage.class, TurnStateMessage::buffer, TurnStateMessage::new, TurnStateMessage::handler);
		addNetworkMessage(TutorialOpenMessage.class, TutorialOpenMessage::buffer, TutorialOpenMessage::new, TutorialOpenMessage::handler);
		addNetworkMessage(WeaponGiveMessage.class, WeaponGiveMessage::buffer, WeaponGiveMessage::new, WeaponGiveMessage::handler);

		//FROM HERE ON, BY ORDER OF INTRODUCTION, NOT ALPHABETICAL. Each message's network id is its
		//registration order, so slotting a new one into its alphabetical spot (Roster... would go between
		//Rest... and Sheet...) would silently renumber everything after it. Always add to the end of this list.
		//(The ids already got renumbered ONCE, deliberately and with PROTOCOL_VERSION bumped, when the 8
		//List/ListRequest pairs were merged into BrowseAction/BrowseList and their 16 classes were deleted.)
		addNetworkMessage(BrowseActionMessage.class, BrowseActionMessage::buffer, BrowseActionMessage::new, BrowseActionMessage::handler);
		addNetworkMessage(BrowseListMessage.class, BrowseListMessage::buffer, BrowseListMessage::new, BrowseListMessage::handler);
		addNetworkMessage(MonsterBindMessage.class, MonsterBindMessage::buffer, MonsterBindMessage::new, MonsterBindMessage::handler);
		addNetworkMessage(WildShapeMessage.class, WildShapeMessage::buffer, WildShapeMessage::new, WildShapeMessage::handler);
		addNetworkMessage(StaffBindMessage.class, StaffBindMessage::buffer, StaffBindMessage::new, StaffBindMessage::handler);
		addNetworkMessage(MulticlassMessage.class, MulticlassMessage::buffer, MulticlassMessage::new, MulticlassMessage::handler);
	}

	/**
	 * <p>Single point where the mod asks "can this source act as DM?" — a real operator, OR solo mode
	 * turned on ({@link Config#soloMode()}). Replaces every loose {@code hasPermission(2)}/
	 * {@code hasPermissions(2)} in the mod: in solo mode there's no new hierarchy to invent (no "party
	 * leader," no per-action permissions) — every connected player ends up equally trusted with each
	 * other, the same level of trust already required to share the same world. The one real exception is
	 * {@code SheetServerMessage} (checks/saves/skills on one's own sheet), which isn't an "are you DM"
	 * gate but a data-integrity one for values that are already computed on their own, and it doesn't go
	 * through here.</p>
	 */
	public static boolean canActAsDm(CommandSourceStack source) {
		return source.hasPermission(2) || Config.soloMode();
	}

	/** Same question as {@link #canActAsDm(CommandSourceStack)}, for call sites that only have the entity at hand. */
	public static boolean canActAsDm(Entity entity) {
		return entity.hasPermissions(2) || Config.soloMode();
	}

	//Pattern repeated across client(DM)->server messages that act on ANOTHER player (SheetAdjustMessage,
	//TraitGrantMessage, PresetApplyToMessage): check that whoever sent the message is an operator (or
	//that solo mode is on, see canActAsDm) and that the target player is still connected, before
	//delegating. Call this inside context.enqueueWork(...).
	public static void withDmTarget(NetworkEvent.Context context, String targetUuid, Consumer<ServerPlayer> action) {
		ServerPlayer dm = context.getSender();
		if (dm == null || !canActAsDm(dm)) return;
		UUID uuid;
		try {
			uuid = UUID.fromString(targetUuid);
		} catch (IllegalArgumentException e) {
			//An operator with a broken/modified client can send a malformed UUID; the message is
			//discarded instead of crashing the server thread with an uncaught exception.
			return;
		}
		ServerPlayer target = dm.getServer().getPlayerList().getPlayer(uuid);
		if (target != null) action.accept(target);
	}

	//Sends a patch of a few sheet fields (see network.SheetFieldUpdateMessage) instead of the full JSON
	//sheet — for narrow changes like consuming advantage/inspiration or spending a spell slot, which used
	//to resend the whole sheet on every hit/spell.
	public static void sendSheetFieldUpdate(ServerPlayer player, JsonObject patch) {
		PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetFieldUpdateMessage(patch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	//Mutable on purpose (ticksLeft is decremented in place every tick): a record would force
	//rebuilding/replacing the queue entry every tick just to subtract 1.
	private static final class PendingWork {
		final Runnable action;
		int ticksLeft;

		PendingWork(Runnable action, int ticksLeft) {
			this.action = action;
			this.ticksLeft = ticksLeft;
		}
	}

	private static final Collection<PendingWork> workQueue = new ConcurrentLinkedQueue<>();

	public static void queueServerWork(int tick, Runnable action) {
		workQueue.add(new PendingWork(action, tick));
	}

	@SubscribeEvent
	public void tick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || workQueue.isEmpty()) return;

		Iterator<PendingWork> it = workQueue.iterator();
		while (it.hasNext()) {
			PendingWork work = it.next();
			if (--work.ticksLeft <= 0) {
				work.action.run();
				it.remove();
			}
		}
	}
}
