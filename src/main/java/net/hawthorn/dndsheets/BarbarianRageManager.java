package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Barbarian Rage: resistance to physical damage (slashing/piercing/bludgeoning/physical) and a bonus
 * to melee weapon damage with Strength that scales with level ({@link #damageBonusFor}), for
 * {@value #DURATION_ROUNDS} rounds
 * (1 real-time minute in 5e). Activated with right-click on the Rage Totem
 * ({@code {dndsheets:{rage:true}}}, given via {@code /dndsheet rageitem}), the same pattern as the
 * turn items ({@link TurnItemManager}).</p>
 *
 * <p><b>Duration in rounds, not just real ticks</b>: if turn mode is active when Rage is activated, the
 * duration is counted in full rounds ({@link TurnManager#onRoundsPass}) — a combat that's slow in real
 * time shouldn't "spend" Rage early, nor should a fast combat leave it running longer than it should.
 * Outside turn mode (free play, no initiative), it falls back to a real timer
 * ({@link DndsheetsMod#queueServerWork}). Decided ONCE, on activation; if turn mode is toggled on/off
 * mid-Rage, it doesn't switch counting mode (deliberate simplification).</p>
 *
 * <p><b>Another deliberate simplification</b>: in real 5e Rage has a limited number of uses per long rest
 * (2 at level 1-2, more at higher levels). Here there's no usage limit — activating again while Rage is
 * already up doesn't do anything strange, it just doesn't reset the counter.</p>
 */
public class BarbarianRageManager {
	private static final int DURATION_ROUNDS = 10; //1 minute in 5e = 10 rounds.
	private static final int DURATION_TICKS = 20 * 60; //1 real-time minute outside turn mode.
	/**
	 * <p>Rage damage bonus, by character level (+2/+3/+4, see {@link CharacterRules#rageDamageBonusFor}).</p>
	 *
	 * <p>It used to be a constant fixed at +2. A barbarian's progression <em>is</em> this number, so
	 * freezing it left a level 20 character hitting the same as a level 1 one except for the weapon.</p>
	 */
	public static int damageBonusFor(ServerPlayer player) {
		return CharacterRules.rageDamageBonusFor(
			SheetLoader.characterLevelOf(SheetLoader.getServerSheet(player.getStringUUID()), player));
	}

	private static final Set<UUID> raging = ConcurrentHashMap.newKeySet();

	/** Cuts off rage silently, no notification or return value: used by character switching. See SheetLoader. */
	public static void clearFor(ServerPlayer player) {
		raging.remove(player.getUUID());
	}

	public static boolean isRaging(ServerPlayer player) {
		return raging.contains(player.getUUID());
	}

	public static void activate(ServerPlayer player) {
		if (raging.contains(player.getUUID())) return; //Already raging: don't reset the counter or duplicate the message, and don't spend anything again.

		//Real 5e: "you can enter a rage as a bonus action." This wasn't gated at all
		//—activating Rage cost neither an action nor a bonus action— the only real SRD bonus action
		//this engine wasn't charging for yet. This goes before touching the "raging" set: if it can't
		//spend the bonus action, it must not end up marked as already raging.
		if (!TurnManager.tryActBonus(player)) {
			TurnManager.notifyCantActBonus(player);
			return;
		}
		raging.add(player.getUUID());

		UUID uuid = player.getUUID();
		MinecraftServer server = player.getServer();
		Runnable expire = () -> {
			if (raging.remove(uuid) && server != null) {
				ServerPlayer stillHere = server.getPlayerList().getPlayer(uuid);
				if (stillHere != null) stillHere.sendSystemMessage(Component.translatable("chat.dndsheets.rage.end").withStyle(ChatFormatting.GRAY));
			}
		};

		TurnManager.scheduleExpiry(DURATION_ROUNDS, DURATION_TICKS, expire);

		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.rage.start", damageBonusFor(player)).withStyle(ChatFeedback.RESOURCE));
	}

	//--- Rage Totem: activated from AbilityItemDispatcher instead of subscribing to the 3 interaction
	//events separately. Same pattern as the turn items
	//(TurnItemManager). ---

	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) activate(player);
	}

	public static ItemStack buildRageItemStack() {
		return AbilityItem.build(ItemLook.RAGE, "rage", Component.translatable("chat.dndsheets.rage.item_name"),
			Component.translatable("chat.dndsheets.rage.item_lore", DURATION_ROUNDS).withStyle(ChatFormatting.GRAY));
	}
}
