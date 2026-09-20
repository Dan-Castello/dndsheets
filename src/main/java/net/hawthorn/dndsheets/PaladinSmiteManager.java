package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Divine Smite: right-click sets a one-use flag (same pattern as the sorcerer's Twinned Spell); the
 * paladin's NEXT weapon hit that connects spends a spell slot and adds radiant damage — {@link
 * CombatManager} rolls it separately and adds the amount, same as Sneak Attack/Hunter's Mark, so as not
 * to mix two groups of dice into the same expression.</p>
 *
 * <p>The die <b>scales with the slot actually spent</b>: 2d8 with a level-1 slot and +1d8 per level above
 * that, capped at 5d8. It used to be fixed at 2d8 because slots were a flat counter with no levels; since
 * {@link SpellSlots#spend} reports which level was actually spent, the rule can now be written as-is.
 * There's no level to choose: it still takes the lowest one remaining, so the smite only grows once the
 * paladin has no cheap slots left — which is exactly when spending an expensive one matters at the
 * table.</p>
 *
 * <p>And it adds <b>another d8 against undead and fiends</b>, which is what makes the paladin a
 * dead-hunter rather than a fighter with extra dice. This part stayed unwritten while a monster had no
 * creature type: there was nothing to check against, and guessing from the name would have gotten the
 * skeleton right and everything else wrong. See {@link CreatureType}.</p>
 */
public class PaladinSmiteManager {
	/**
	 * <p>Smite dice: 2d8 base, +1d8 per slot level above 1st capped at 5d8, plus +1d8 more if the target
	 * is undead or a fiend.</p>
	 *
	 * <p>The extra die is added <b>after</b> the cap on purpose: in 5e the 5d8 limit applies to the
	 * per-slot-level increase, and the die against undead is separate — a 6d8 smite with a 4th-level slot
	 * on a skeleton is the correct number, not an overflow.</p>
	 */
	static String diceForSlot(int slotLevel, CreatureType targetType) {
		int dice = Math.min(5, 1 + Math.max(1, slotLevel));
		if (targetType.isSmiteFavoredTarget()) dice++;
		return dice + "d8";
	}

	//Triggered from AbilityItemDispatcher instead of subscribing to RightClickItem on its own.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("smitePending", true);
		SheetLoader.saveServer(sheet, player.getStringUUID());
		//An armed flag you can't see is a flag you forget: the paladin had no way to tell if the smite
		//armed three turns ago was still ready or already spent.
		JsonObject patch = new JsonObject();
		patch.addProperty("smitePending", true);
		DndsheetsMod.sendSheetFieldUpdate(player, patch);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.smite_armed").withStyle(ChatFeedback.RESOURCE));
	}

	//Public: CombatManager consumes this right after confirming a hit (not before: missing the attack
	//shouldn't spend the slot). Returns null if there was no pending flag OR there were no slots left to spend.
	//The target is passed in because the die depends on WHAT is being smitten, not just on what pays for it.
	public static String consumeIfPending(JsonObject sheet, Entity target) {
		if (sheet == null || !sheet.has("smitePending") || !sheet.get("smitePending").getAsBoolean()) return null;
		sheet.remove("smitePending");

		//Any slot of level 1 or higher works; the lowest one is spent. And the die is based on the level
		//spend() reports having spent, not the level requested: if level-1 slots were exhausted, the smite
		//went out with a higher one and hits harder.
		if (!SpellSlots.hasSlotFor(sheet, 1)) return null;
		return diceForSlot(SpellSlots.spend(sheet, 1), MonsterRegistry.typeOf(target));
	}

	public static ItemStack buildDivineSmiteStack() {
		return AbilityItem.build(ItemLook.SMITE, "divineSmite", Component.translatable("chat.dndsheets.smite.item_name"),
			Component.translatable("chat.dndsheets.smite.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
