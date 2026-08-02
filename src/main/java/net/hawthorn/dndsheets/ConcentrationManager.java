package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Concentración de 5e: lanzar un hechizo de concentración reemplaza cualquier concentración previa;
 * recibir daño real obliga a una salvación de Constitución (CD = máx(10, daño/2)) o se pierde. Solo
 * jugadores concentran — los monstruos del DM se resuelven acción por acción, sin este seguimiento.</p>
 */
public class ConcentrationManager {
	private static final Map<UUID, String> concentratingOn = new HashMap<>();

	public static void startConcentrating(ServerPlayer caster, String spellName) {
		concentratingOn.put(caster.getUUID(), spellName);
	}

	public static void stopConcentrating(ServerPlayer caster) {
		concentratingOn.remove(caster.getUUID());
	}

	//Llamado desde cada punto del mod donde un jugador recibe daño real (ver SpellCastManager.applyDamage,
	//CombatManager.onLivingHurt, MonsterActionManager.resolveAttack/resolveSpell).
	public static void onDamageTaken(ServerPlayer player, int damage) {
		String spellName = concentratingOn.get(player.getUUID());
		if (spellName == null || damage <= 0) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int dc = Math.max(10, damage / 2);
		DiceManager.RollOutcome saveRoll = sheet != null ? DiceManager.roll(sheet, "1d20 + $con") : DiceManager.roll(new JsonObject(), "1d20");
		boolean kept = saveRoll.result() != null && saveRoll.result().getValue() >= dc;

		String name = SheetLoader.characterNameOf(sheet, player);
		if (kept) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.concentration.kept", name, spellName, dc, saveRoll.formatted()).withStyle(ChatFormatting.GRAY));
		} else {
			concentratingOn.remove(player.getUUID());
			ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.concentration.lost", name, spellName, dc, saveRoll.formatted()).withStyle(ChatFormatting.RED));
		}
	}
}
