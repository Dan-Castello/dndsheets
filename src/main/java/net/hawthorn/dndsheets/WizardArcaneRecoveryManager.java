package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Recuperación Arcana del mago: una vez por descanso largo, el siguiente descanso CORTO le devuelve
 * {@code ceil(nivel / 2)} espacios de conjuro (sin superar el máximo) — automático, no hace falta ítem ni
 * comando, se engancha directo en {@link RestManager#applyRest}.</p>
 *
 * <p>A diferencia de Furia/Segundo Aliento (cualquiera que tenga el ítem los puede usar), esto solo debe
 * aplicar a magos de verdad: se comprueba igual que {@link Config#hitDieFor} comprueba la clase — por
 * subcadena contra el campo "Clase y Nivel" de la hoja, insensible a mayúsculas e idioma — en vez de
 * exigir un ítem o un rasgo concedido aparte, ya que esto no es algo que el jugador "activa", es algo que
 * pasa solo al descansar.</p>
 */
public class WizardArcaneRecoveryManager {
	private static final Set<UUID> usedSinceLongRest = ConcurrentHashMap.newKeySet();
	/** La Recuperación Arcana no devuelve espacios de nivel 6 o superior. */
	private static final int MAX_RECOVERED_LEVEL = 5;

	//Público: RestManager lo llama en cada descanso CORTO, con la MISMA hoja que ya está a punto de
	//guardar/enviar, para que el ajuste de espacios de conjuro viaje en el mismo SheetClientMessage.
	public static void onShortRest(ServerPlayer player, JsonObject sheet) {
		if (!isWizard(sheet)) return;
		if (!usedSinceLongRest.add(player.getUUID())) return; //Ya usada desde el último descanso largo.

		//La regla de 5e es un presupuesto de NIVELES SUMADOS (la mitad del nivel de mago, ninguno por
		//encima del 5º), no un número de espacios sueltos. Antes se contaban espacios porque con la bolsa
		//única no había "de qué nivel" que recuperar; con la tabla por niveles ya se puede aplicar tal cual.
		int budget = (int) Math.ceil(SheetLoader.characterLevelOf(sheet, player) / 2.0);
		int recovered = SpellSlots.restoreBudget(sheet, budget, MAX_RECOVERED_LEVEL);
		if (recovered <= 0) return;

		player.sendSystemMessage(Component.literal("Recuperación Arcana: recuperas " + recovered + " espacio(s) de conjuro.").withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	//Público: RestManager lo llama en cada descanso LARGO.
	public static void resetOnLongRest(ServerPlayer player) {
		usedSinceLongRest.remove(player.getUUID());
	}

	private static boolean isWizard(JsonObject sheet) {
		if (sheet == null || !sheet.has("characterClass")) return false;
		String characterClass = sheet.get("characterClass").getAsString().toLowerCase(Locale.ROOT);
		return characterClass.contains("mago") || characterClass.contains("wizard");
	}
}
