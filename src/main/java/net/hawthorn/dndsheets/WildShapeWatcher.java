package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.network.WildShapeMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Who sees whom transformed. The shape lives in the druid's sheet, but <b>drawing it</b> is up to
 * everyone else's clients, and a player's sheet is never sent to anyone else — so the shape has to
 * travel separately.</p>
 *
 * <p>It's broadcast to EVERYONE, not just whoever's nearby: the list of connected players at a D&amp;D
 * table fits on one hand, and "nearby" would have to be recomputed every time someone walks, which is
 * far more expensive than sending a thirty-byte packet whenever someone transforms.</p>
 *
 * <p>And it's resent on every world join for the same reason as the death-save conditions
 * ({@code DeathSaveManager.resendState}): someone who just connected never saw the original packet, and
 * without this they'd see the bear as a normal player until it shifted back.</p>
 */
@Mod.EventBusSubscriber
public class WildShapeWatcher {

	private WildShapeWatcher() {
	}

	/** Tells everyone what this player has turned into. Empty id = they've reverted to their own form. */
	public static void broadcast(ServerPlayer player, String monsterId) {
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.ALL.noArg(),
			new WildShapeMessage(WildShapeMessage.Kind.SHAPE, player.getUUID(), baseEntityIdOf(monsterId)));
	}

	/**
	 * <p>The monster registry only really lives on the server: a client running as a separate process
	 * (any LAN guest other than whoever opened the world) always sees it empty. That's why the SHAPE packet
	 * sent to everyone else doesn't carry the monster id — that would send the renderer back to query that
	 * empty registry — but instead carries the base entity already resolved here, which is all
	 * {@code WildShapeRenderer} needs to pick the model.</p>
	 */
	private static String baseEntityIdOf(String monsterId) {
		if (monsterId == null || monsterId.isEmpty()) return "";
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		return block != null ? block.baseEntityId() : "";
	}

	/** Resolves the beast list ON THE SERVER, which is where it really lives: see the comment on WildShapeMessage. */
	public static void openPicker(ServerPlayer player) {
		List<String> ids = DruidWildShapeManager.beastIds();
		List<String> names = new ArrayList<>();
		List<Integer> hps = new ArrayList<>();
		List<Integer> acs = new ArrayList<>();
		for (String id : ids) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
			names.add(block.name());
			hps.add(block.maxHp());
			acs.add(block.ac());
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new WildShapeMessage(player.getUUID(), ids, names, hps, acs));
	}

	/**
	 * <p>When someone joins the world, they get caught up on who's currently transformed, and their own
	 * shape is re-announced to everyone else. Both directions are needed: the newcomer never saw the earlier
	 * packets, and the players already present never saw theirs.</p>
	 */
	@SubscribeEvent
	public static void onJoin(EntityJoinLevelEvent event) {
		if (event.getLevel().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer joined) || joined.getServer() == null) return;

		for (ServerPlayer other : joined.getServer().getPlayerList().getPlayers()) {
			String shape = DruidWildShapeManager.shapeOf(SheetLoader.getServerSheet(other.getStringUUID()));
			if (shape == null) continue;
			//To the newcomer, everyone else's shape; and if the newcomer arrived transformed, their own to everyone.
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> joined),
				new WildShapeMessage(WildShapeMessage.Kind.SHAPE, other.getUUID(), baseEntityIdOf(shape)));
			if (other == joined) broadcast(joined, shape);
		}
	}
}
