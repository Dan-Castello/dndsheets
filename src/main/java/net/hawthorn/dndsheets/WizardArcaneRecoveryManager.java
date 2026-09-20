package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * <p>Wizard's Arcane Recovery: once per long rest, the next SHORT rest restores
 * {@code ceil(level / 2)} spell slots (capped at the maximum) — automatic, no item or command needed,
 * hooked directly into {@link RestManager#applyRest}.</p>
 *
 * <p>Unlike Rage/Second Wind (anyone holding the item can use those), this should only apply to actual
 * wizards: it's checked the same way {@link Config#hitDieFor} checks class — by substring match against
 * the sheet's "Class and Level" field, case- and language-insensitive — instead of requiring an item or
 * a separately granted trait, since this isn't something the player "activates", it just happens on
 * resting.</p>
 */
public class WizardArcaneRecoveryManager {
	//On the sheet, not in a per-player set: it belongs to the character, and survives a server restart
	//(previously, restarting handed Arcane Recovery back to everyone without a long rest). See
	//RestResource.
	/** Arcane Recovery does not restore slots of level 6 or higher. */
	private static final int MAX_RECOVERED_LEVEL = 5;

	//Public: RestManager calls this on every SHORT rest, with the SAME sheet that's already about to be
	//saved/sent, so the spell-slot adjustment travels in the same SheetClientMessage.
	public static void onShortRest(ServerPlayer player, JsonObject sheet) {
		if (!isWizard(sheet)) return;
		if (!RestResource.spend(player, RestResource.ARCANE_RECOVERY)) return; //Already used since the last long rest.

		//The 5e rule is a budget of SUMMED LEVELS (half the wizard's level, none above 5th), not a count
		//of individual slots. Slots used to be counted because with a single shared pool there was no
		//"which level" to recover; with the per-level table it can now be applied as written.
		int budget = (int) Math.ceil(SheetLoader.characterLevelOf(sheet, player) / 2.0);
		int recovered = SpellSlots.restoreBudget(sheet, budget, MAX_RECOVERED_LEVEL);
		if (recovered <= 0) return;

		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.arcane_recovery", recovered).withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	//Public: RestManager calls this on every LONG rest.
	public static void resetOnLongRest(ServerPlayer player) {
		RestResource.restore(player, RestResource.ARCANE_RECOVERY);
	}

	private static boolean isWizard(JsonObject sheet) {
		if (sheet == null || !sheet.has("characterClass")) return false;
		String characterClass = sheet.get("characterClass").getAsString().toLowerCase(Locale.ROOT);
		return characterClass.contains("mago") || characterClass.contains("wizard");
	}
}
