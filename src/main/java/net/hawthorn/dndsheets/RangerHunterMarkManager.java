package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Hunter's Mark: the ranger right-clicking a target (player or summoned monster) marks it
 * for {@value #DURATION_ROUNDS} rounds/10 real minutes (same rounds-or-ticks duration pattern
 * as Rage/Bardic Inspiration). While it lasts, every hit the ranger lands ON THAT SPECIFIC
 * TARGET adds {@value #DICE} extra damage — see {@link CombatManager}, which rolls it separately
 * and adds the amount, just like Sneak Attack, to avoid putting two dice groups in a single expression.</p>
 *
 * <p><b>Deliberate simplification</b>: in real 5e this is a concentration spell (level 1),
 * so hitting something else or taking certain damage can end it early. Here it isn't
 * hooked to {@link ConcentrationManager} — it lasts its fixed duration no matter what, simpler and
 * consistent with how this pass already simplified Wild Shape/Rage.</p>
 */
public class RangerHunterMarkManager {
	public static final String DICE = "1d6";
	private static final int DURATION_ROUNDS = 100; //5e's 10 minutes = 100 rounds.
	private static final int DURATION_TICKS = 20 * 60 * 10;

	private static final Map<UUID, Integer> markedEntityIdByRanger = new ConcurrentHashMap<>();

	/** Forgets who was marked: used by character switching. See SheetLoader. */
	public static void clearFor(ServerPlayer ranger) {
		markedEntityIdByRanger.remove(ranger.getUUID());
	}

	public static boolean isMarked(ServerPlayer ranger, Entity target) {
		Integer markedId = markedEntityIdByRanger.get(ranger.getUUID());
		return markedId != null && markedId == target.getId();
	}

	public static void mark(ServerPlayer ranger, Entity target) {
		markedEntityIdByRanger.put(ranger.getUUID(), target.getId());
		CombatFx.activate(ranger);
		ranger.sendSystemMessage(Component.translatable("chat.dndsheets.resource.hunters_mark", target.getName().getString()).withStyle(ChatFeedback.RESOURCE));

		UUID rangerUuid = ranger.getUUID();
		int targetId = target.getId();
		Runnable expire = () -> {
			Integer current = markedEntityIdByRanger.get(rangerUuid);
			if (current != null && current == targetId) markedEntityIdByRanger.remove(rangerUuid);
		};
		TurnManager.scheduleExpiry(DURATION_ROUNDS, DURATION_TICKS, expire);
	}

	//Triggered from AbilityItemDispatcher instead of subscribing to EntityInteract on its own.
	static void tryUse(PlayerInteractEvent.EntityInteract event) {
		if (!(event.getEntity() instanceof ServerPlayer ranger)) return;

		InteractionEvents.consume(event);
		mark(ranger, event.getTarget());
	}

	public static ItemStack buildHunterMarkStack() {
		return AbilityItem.build(ItemLook.HUNTERS_MARK, "hunterMark", Component.translatable("chat.dndsheets.hunter_mark.item_name"),
			Component.translatable("chat.dndsheets.hunter_mark.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
