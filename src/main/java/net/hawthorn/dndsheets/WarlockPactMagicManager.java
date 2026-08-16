package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * <p>Magia de Pacto del brujo: a diferencia de todos los demás casters (que solo recuperan espacios de
 * conjuro con un descanso LARGO), el brujo los recupera enteros con cualquier descanso, incluido el
 * CORTO — es la diferencia mecánica que de verdad distingue a un brujo de un mago en 5e, y se engancha
 * exactamente igual que la Recuperación Arcana del mago ({@link WizardArcaneRecoveryManager}): un
 * descanso corto en {@link RestManager#applyRest} llama aquí en vez de a él, comprobando la clase por
 * subcadena contra "Clase y Nivel" (mismo patrón que {@link Config#hitDieFor}).</p>
 *
 * <p>Más simple que Recuperación Arcana en un sentido: recupera TODOS los espacios, no la mitad del
 * nivel, y no tiene límite de una vez por descanso largo — un brujo de verdad puede encadenar descansos
 * cortos y recargar cada vez, que es exactamente la razón por la que esta regla existe en 5e.</p>
 */
public class WarlockPactMagicManager {
	//Público: RestManager lo llama en cada descanso CORTO, con la MISMA hoja que ya está a punto de
	//guardar/enviar (igual que WizardArcaneRecoveryManager.onShortRest).
	public static void onShortRest(ServerPlayer player, JsonObject sheet) {
		if (!isWarlock(sheet)) return;

		int max = sheet.get("spellSlotsMax").getAsInt();
		int current = sheet.get("spellSlotsCurrent").getAsInt();
		if (current >= max) return;

		SpellSlots.restoreAll(sheet);
		player.sendSystemMessage(Component.literal("Magia de Pacto: recuperas todos tus espacios de conjuro.").withStyle(ChatFormatting.DARK_PURPLE));
	}

	private static boolean isWarlock(JsonObject sheet) {
		if (sheet == null || !sheet.has("characterClass")) return false;
		String characterClass = sheet.get("characterClass").getAsString().toLowerCase(Locale.ROOT);
		return characterClass.contains("brujo") || characterClass.contains("warlock");
	}
}
