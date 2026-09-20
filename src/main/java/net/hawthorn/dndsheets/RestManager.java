package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.RestMessage;
import net.hawthorn.dndsheets.network.ScreenActionMessage;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <p>Resting is a vote, not a unilateral button: using the Rest Kit
 * ({@code {dndsheets:{restKit:true}}}) asks the user to choose short/long, and that proposal is
 * sent to every connected player. Only if EVERYONE accepts does the rest apply to everyone;
 * if anyone rejects, it's cancelled. Only one vote can be pending at a time.</p>
 */
@Mod.EventBusSubscriber
public class RestManager {
	public enum RestType {
		SHORT("short"), LONG("long");
		public final String label;
		RestType(String label) { this.label = label; }
	}

	private static RestType pendingType = null;
	private static String pendingProposerName = null;
	private static final Set<java.util.UUID> pendingVoters = new HashSet<>();
	private static final Set<java.util.UUID> accepted = new HashSet<>();
	//Where the Rest Kit was used: the vote (and the rest itself) is scoped to whoever is actually
	//nearby at that moment — previously ANY connected player was asked, and had the rest applied to
	//them, regardless of whether they were one block away or on another continent of the map.
	private static Vec3 pendingOrigin = null;

	//Incremented on every new proposal; the timeout and the disconnect listener capture it when scheduled
	//and only act if it's still the SAME proposal by the time they run — without this, cancelling/resolving
	//a proposal and having another one start right away could let the old timeout cancel the new one.
	private static int proposalToken = 0;
	private static final int VOTE_TIMEOUT_TICKS = 3600; //3 real minutes, same queueServerWork pattern already used by BarbarianRageManager.

	//Triggered from AbilityItemDispatcher (right-click with item/block/entity, works the same in all
	//three cases because a block item like the clock, when looking at a wall, fires RightClickBlock
	//instead of RightClickItem) rather than subscribing to the 3 interaction events separately.
	static void tryOpenRestChoice(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		//A short/long rest resets HP/spell slots — in the middle of turn-based combat that's an
		//unwanted reset (free healing mid-fight), not a legitimate table decision. It's blocked here,
		//before even opening the short/long selector, instead of only in propose(), so the player
		//isn't made to pick a rest type that's going to be rejected anyway.
		if (TurnManager.isActive()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.rest.blocked_in_combat").withStyle(ChatFormatting.RED));
			return;
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new ScreenActionMessage(ScreenActionMessage.Action.REST_CHOICE_OPEN));
	}

	//A clock instead of a block item (like the campfire): a block item fires RightClickBlock instead
	//of RightClickItem as soon as you're looking at something placeable, which made detecting the click reliably harder.
	public static ItemStack buildRestKitStack() {
		return AbilityItem.build(ItemLook.REST_KIT, "restKit", Component.translatable("chat.dndsheets.rest.item_name"),
			Component.translatable("chat.dndsheets.rest.lore_use").withStyle(ChatFormatting.GRAY),
			Component.translatable("chat.dndsheets.rest.lore_requires_all").withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * <p>Called on receiving a {@code RestProposeMessage}: the kit's user chose short or long.
	 * The proposal is sent to every connected player and the proposer is counted as a yes.</p>
	 */
	public static void propose(ServerPlayer proposer, RestType type) {
		MinecraftServer server = proposer.getServer();
		if (server == null) return;

		//A real guard, not just cosmetic: tryOpenRestChoice already cuts this off before short/long can
		//be chosen, but a client that skipped that step (or turn mode starting right after the selector
		//opens) shouldn't be able to sneak the proposal through anyway.
		if (TurnManager.isActive()) {
			proposer.sendSystemMessage(Component.translatable("chat.dndsheets.rest.blocked_in_combat").withStyle(ChatFormatting.RED));
			return;
		}

		if (pendingType != null) {
			proposer.sendSystemMessage(Component.translatable("chat.dndsheets.rest.vote_in_progress").withStyle(ChatFormatting.RED));
			return;
		}

		JsonObject proposerSheet = SheetLoader.getServerSheet(proposer.getStringUUID());
		String proposerName = SheetLoader.characterNameOf(proposerSheet, proposer);

		pendingType = type;
		pendingProposerName = proposerName;
		pendingOrigin = proposer.position();
		pendingVoters.clear();
		accepted.clear();
		int token = ++proposalToken;

		List<ServerPlayer> nearby = nearbyPlayers(server, pendingOrigin);
		for (ServerPlayer player : nearby) pendingVoters.add(player.getUUID());

		for (ServerPlayer player : nearby) {
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), RestMessage.voteOpen(proposerName, type.label));
		}
		notifyVoters(server, Component.translatable("chat.dndsheets.rest.proposed", proposerName, Component.translatable("gui.dndsheets.rest_type." + type.label)).withStyle(ChatFormatting.AQUA));

		//Without this, a player who never responds (without disconnecting, just ignoring the prompt) would
		//block rests for the entire server forever — not only the disconnect case, see onPlayerLogout.
		DndsheetsMod.queueServerWork(VOTE_TIMEOUT_TICKS, () -> {
			if (pendingType == null || token != proposalToken) return;
			notifyVoters(server, Component.translatable("chat.dndsheets.rest.timed_out").withStyle(ChatFormatting.RED));
			clear(server);
		});

		registerVote(proposer, true); //The proposer already votes yes, by proposing it.
	}

	//Same radius that turn mode already uses for "who's participating" (TurnManager.DEFAULT_RADIUS) —
	//consistent with the rest of the mod for deciding who's concerned by a one-off action like this.
	private static List<ServerPlayer> nearbyPlayers(MinecraftServer server, Vec3 origin) {
		double radiusSq = TurnManager.DEFAULT_RADIUS * TurnManager.DEFAULT_RADIUS;
		return server.getPlayerList().getPlayers().stream()
			.filter(p -> p.position().distanceToSqr(origin) <= radiusSq)
			.toList();
	}

	//Sends a message only to whoever is actually part of THIS vote (pendingVoters), not the whole
	//server — reused by propose/registerVote/resolveRest for the 4 chat lines of a proposal's
	//lifecycle (proposed, rejected, expired, rest completed).
	private static void notifyVoters(MinecraftServer server, Component message) {
		for (UUID uuid : pendingVoters) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) player.sendSystemMessage(message);
		}
	}

	//Without this, a player disconnecting (crash, closing the game) before voting would leave pendingVoters
	//with a UUID that would never accept: accepted could never match it, and pendingType != null would
	//block any new proposal — nobody on the server could rest again until a restart.
	@SubscribeEvent
	public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		if (pendingType == null || !(event.getEntity() instanceof ServerPlayer player)) return;
		UUID uuid = player.getUUID();
		if (!pendingVoters.remove(uuid)) return; //Wasn't part of this vote.

		accepted.remove(uuid);
		if (pendingVoters.isEmpty()) { clear(player.getServer()); return; } //No one left to ask for the rest.
		if (accepted.containsAll(pendingVoters)) resolveRest(player.getServer());
	}

	//Symmetric to onPlayerLogout: without this, someone connecting while a vote is pending was
	//never asked, but resolveRest applied the rest to them anyway as soon as everyone else accepted —
	//they're added to the vote, like anyone else, and sent the same prompt the others already got.
	//Only if they're near where it was proposed: someone connecting far from that area shouldn't
	//get dragged into a vote for a group they aren't even playing with.
	@SubscribeEvent
	public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (pendingType == null || !(event.getEntity() instanceof ServerPlayer player)) return;
		if (pendingOrigin != null && player.position().distanceToSqr(pendingOrigin) > TurnManager.DEFAULT_RADIUS * TurnManager.DEFAULT_RADIUS) return;
		pendingVoters.add(player.getUUID());
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), RestMessage.voteOpen(pendingProposerName, pendingType.label));
	}

	/**
	 * <p>Called on receiving a {@code RestVoteResponseMessage}.</p>
	 */
	public static void registerVote(ServerPlayer player, boolean accept) {
		if (pendingType == null || !pendingVoters.contains(player.getUUID())) return;

		if (!accept) {
			MinecraftServer server = player.getServer();
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			String name = SheetLoader.characterNameOf(sheet, player);
			if (server != null) {
				notifyVoters(server, Component.translatable("chat.dndsheets.rest.rejected", name).withStyle(ChatFormatting.RED));
			}
			clear(server);
			return;
		}

		accepted.add(player.getUUID());
		if (accepted.containsAll(pendingVoters)) {
			resolveRest(player.getServer());
		}
	}

	private static void resolveRest(MinecraftServer server) {
		if (server == null) return;
		RestType type = pendingType;
		//Only to whoever actually voted (pendingVoters), not the whole server: this used to reset HP/
		//spell slots for ANY connected player as soon as the group that was actually resting finished
		//accepting, even if they were half the map away and had never even been asked.
		for (UUID uuid : pendingVoters) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) applyRest(player, type);
		}
		notifyVoters(server, Component.translatable("chat.dndsheets.rest.completed", Component.translatable("gui.dndsheets.rest_type." + type.label)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		clear(server);
	}

	private static void applyRest(ServerPlayer player, RestType type) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		SheetLoader.validateSheet(sheet);

		if (type == RestType.LONG) {
			player.setHealth(player.getMaxHealth());
			int max = sheet.get("spellSlotsMax").getAsInt();
			SpellSlots.restoreAll(sheet);
			WizardArcaneRecoveryManager.resetOnLongRest(player);
		} else {
			float missing = player.getMaxHealth() - player.getHealth();
			player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + missing / 2f));
			WizardArcaneRecoveryManager.onShortRest(player, sheet);
			WarlockPactMagicManager.onShortRest(player, sheet);
		}
		FighterSecondWindManager.resetOnRest(player); //5e recovers it with either kind of rest, not just the long one.
		ClericTurnUndeadManager.resetOnRest(player);  //Channel Divinity, same: recovers on a short rest.

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	//"server" can be null (e.g. a player disconnecting mid-shutdown); in that case there's simply
	//no one to notify. Before this, RestVoteScreen.close() existed but nothing ever called it: whoever
	//hadn't voted yet was left with a dead "Accept/Reject" screen for a vote that was already
	//resolved/expired/cancelled, with no indication it was no longer relevant.
	private static void clear(MinecraftServer server) {
		if (server != null) {
			for (UUID uuid : pendingVoters) {
				ServerPlayer voter = server.getPlayerList().getPlayer(uuid);
				if (voter != null) DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> voter), RestMessage.voteClose());
			}
		}
		pendingType = null;
		pendingProposerName = null;
		pendingOrigin = null;
		pendingVoters.clear();
		accepted.clear();
	}
}
