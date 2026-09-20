package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Shield: right-click marks the spell as "ready" on the sheet (same pattern as Divine Smite/Twinned
 * Spell), but unlike those it does NOT get consumed just by firing once: in real 5e you decide to cast it
 * already knowing whether the incoming attack would hit, so here it's checked right where the attack roll
 * already gets compared against AC ({@link CombatManager#onLivingHurt},
 * {@link MonsterActionManager#resolveAttack}), and it only spends a spell slot + reaction when the +5 AC
 * actually turns a hit into a miss. If the attack was going to miss anyway, or would hit regardless even
 * with Shield, nothing is spent and the flag stays ready for the next attack of the round.</p>
 */
public class ShieldManager {

	//Shield is a level 1 spell in 5e.
	private static final int LEVEL = 1;
	private static final int AC_BONUS = 5;

	//Triggered from AbilityItemDispatcher instead of subscribing to RightClickItem on its own.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("shieldReady", true);
		SheetLoader.saveAndSync(player, sheet);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.shield_ready").withStyle(ChatFeedback.RESOURCE));
	}

	//Public: checked right where the attack roll already gets compared against AC, both in PvP and for a
	//monster attacking a player. Returns the AC to use in THAT comparison: +5 if Shield actually protected
	//(and already spent the slot + reaction), the normal AC if it didn't apply or wasn't needed.
	public static int effectiveAc(ServerPlayer victim, int attackRollValue, int normalAc) {
		if (attackRollValue < normalAc || attackRollValue >= normalAc + AC_BONUS) return normalAc; //Wouldn't change the outcome.

		JsonObject sheet = SheetLoader.getServerSheet(victim.getStringUUID());
		if (sheet == null || !sheet.has("shieldReady") || !sheet.get("shieldReady").getAsBoolean()) return normalAc;

		//Shield is a LEVEL 1 spell: any slot works, but the lowest one available is spent.
		if (!SpellSlots.hasSlotFor(sheet, LEVEL) || !TurnManager.tryReact(victim)) return normalAc;

		SpellSlots.spend(sheet, LEVEL);
		SheetLoader.saveAndSync(victim, sheet);
		victim.sendSystemMessage(Component.translatable("chat.dndsheets.resource.shield_hit", (normalAc + AC_BONUS)).withStyle(ChatFormatting.AQUA));
		return normalAc + AC_BONUS;
	}

	public static ItemStack buildShieldStack() {
		return AbilityItem.build(ItemLook.SHIELD, "shieldSpell", Component.translatable("chat.dndsheets.shield.item_name"),
			Component.translatable("chat.dndsheets.shield.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
