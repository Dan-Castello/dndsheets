package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;


/**
 * <p>Fighter's Second Wind: heals {@code 1d10 + level} once per rest (short or long — see
 * {@link RestManager#applyRest}, which calls {@link #resetOnRest} in both cases, exactly like real 5e).
 * No duration to track in rounds or ticks — unlike Rage, this is a simple "used/not used" flag a rest
 * resets, so it needs nothing from {@link TurnManager}.</p>
 */
public class FighterSecondWindManager {
	//The "already used" flag lives on the SHEET, not in a per-player set: it belongs to the character
	//(with two characters, spending it on one used to spend it on the other too) and it survives a
	//server restart, which used to give it back to everyone without them having rested. See RestResource.

	public static void use(ServerPlayer player) {
		if (!RestResource.spend(player, RestResource.SECOND_WIND)) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.spent_second_wind").withStyle(ChatFormatting.GRAY));
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int level = SheetLoader.characterLevelOf(sheet, player);
		DiceManager.RollOutcome heal = DiceManager.roll(sheet != null ? sheet : new JsonObject(), "1d10 + " + level);
		int amount = heal.result() != null ? heal.result().getValue() : level;

		player.heal(amount);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.second_wind", amount).withStyle(ChatFeedback.RESOURCE));
	}

	//Public: RestManager calls it for both rest types, short and long — 5e recovers this resource with
	//either one, unlike spell slots (long rest only).
	public static void resetOnRest(ServerPlayer player) {
		RestResource.restore(player, RestResource.SECOND_WIND);
	}

	//--- Second Wind item: activated from AbilityItemDispatcher instead of subscribing to the 3
	//interaction events separately. Same pattern as the Rage Totem
	//(BarbarianRageManager). ---

	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) use(player);
	}

	public static ItemStack buildSecondWindStack() {
		return AbilityItem.build(ItemLook.SECOND_WIND, "secondWind", Component.translatable("chat.dndsheets.second_wind.item_name"),
			Component.translatable("chat.dndsheets.second_wind.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
