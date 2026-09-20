package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p><b>Help</b>, the fourth turn action: you distract an enemy or set up an ally's move, and their
 * next attack has advantage.</p>
 *
 * <p>It lives apart from {@link TurnActionManager} —where Dodge, Dash, and Disengage are— because it's
 * the only one of the four that needs a <em>target</em> to point at, so its natural place is a
 * right-click item on another player rather than a menu entry. It's exactly the same pattern as
 * Bardic Inspiration, which has the same problem and already solved it this way.</p>
 *
 * <p>The advantage is recorded in {@code nextAttackAdvantage}, the single-use flag the sheet already
 * had, which {@code CombatManager.consumeAdvantage} spends on the next attack roll, whether it comes
 * from a weapon, a spell, or the sheet's own button. No new mechanism was needed: it just needed to
 * reuse the one {@code /dndsheet advantage} has always used.</p>
 */
public class HelpActionManager {

	static void tryUse(PlayerInteractEvent.EntityInteract event) {
		if (!(event.getEntity() instanceof ServerPlayer helper) || !(event.getTarget() instanceof ServerPlayer ally)) return;
		InteractionEvents.consume(event);

		if (helper == ally) {
			helper.sendSystemMessage(Component.translatable("chat.dndsheets.action.help_self").withStyle(ChatFormatting.GRAY));
			return;
		}
		//Same as the other three: outside combat there's no turn to spend, and accepting the click without
		//saying so would leave the player thinking they helped.
		if (!TurnManager.isActive()) {
			helper.sendSystemMessage(Component.translatable("chat.dndsheets.action.needs_combat").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!TurnManager.tryAct(helper)) {
			TurnManager.notifyCantAct(helper);
			return;
		}

		JsonObject allySheet = SheetLoader.getServerSheet(ally.getStringUUID());
		if (allySheet == null) return;
		allySheet.addProperty("nextAttackAdvantage", "advantage");
		SheetLoader.saveServer(allySheet, ally.getStringUUID());

		//The ally needs to SEE that it landed: it's a flag on their sheet, and without this patch they'd
		//only find out by reopening it. Same short patch the rest of the mod uses after touching a loose field.
		JsonObject patch = new JsonObject();
		patch.addProperty("nextAttackAdvantage", "advantage");
		DndsheetsMod.sendSheetFieldUpdate(ally, patch);

		CombatFx.activate(ally);
		String helperName = SheetLoader.characterNameOf(SheetLoader.getServerSheet(helper.getStringUUID()), helper);
		String allyName = SheetLoader.characterNameOf(allySheet, ally);
		ChatFeedback.broadcast(helper, Component.translatable("chat.dndsheets.action.help", helperName, allyName).withStyle(ChatFeedback.RESOURCE));
	}

	public static ItemStack buildHelpStack() {
		return AbilityItem.build(ItemLook.HELP, "helpAction", Component.translatable("chat.dndsheets.action.help_item_name"),
			Component.translatable("chat.dndsheets.action.help_item_lore").withStyle(ChatFormatting.GRAY));
	}
}
