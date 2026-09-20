package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * <p>Warlock Pact Magic: unlike every other caster (who only recover spell slots on a LONG rest), the
 * warlock recovers them all on any rest, including a SHORT one — this is the mechanical difference that
 * truly sets a warlock apart from a wizard in 5e, and it hooks in exactly like the wizard's Arcane
 * Recovery ({@link WizardArcaneRecoveryManager}): a short rest in {@link RestManager#applyRest} calls
 * here instead of there, checking the class by substring against "Class and Level" (same pattern as
 * {@link Config#hitDieFor}).</p>
 *
 * <p>Simpler than Arcane Recovery in one sense: it recovers ALL slots, not half the level, and has no
 * once-per-long-rest limit — a true warlock can chain short rests and recharge every time, which is
 * exactly why this rule exists in 5e.</p>
 */
public class WarlockPactMagicManager {
	//Public: RestManager calls it on every SHORT rest, with the SAME sheet that's already about to be
	//saved/sent (same as WizardArcaneRecoveryManager.onShortRest).
	public static void onShortRest(ServerPlayer player, JsonObject sheet) {
		if (!isWarlock(sheet)) return;

		int max = sheet.get("spellSlotsMax").getAsInt();
		int current = sheet.get("spellSlotsCurrent").getAsInt();
		if (current >= max) return;

		SpellSlots.restoreAll(sheet);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.pact_magic").withStyle(ChatFormatting.DARK_PURPLE));
	}

	private static boolean isWarlock(JsonObject sheet) {
		if (sheet == null || !sheet.has("characterClass")) return false;
		String characterClass = sheet.get("characterClass").getAsString().toLowerCase(Locale.ROOT);
		return characterClass.contains("brujo") || characterClass.contains("warlock");
	}
}
