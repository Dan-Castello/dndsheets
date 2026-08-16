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
 *
 * <p>Si el hechizo dejó un efecto de estado corriendo (ver SpellRegistry.Spell#appliesEffect,
 * SpellCastManager), perder la concentración lo revierte de verdad (TurnManager.removeEffect) — antes
 * esto solo tiraba el dado y mandaba un mensaje, sin deshacer nada.</p>
 */
public class ConcentrationManager {
	//targetEntityId/effectName quedan en -1/null hasta que el hechizo de verdad aplica un efecto (ver
	//attachEffect) — muchos hechizos de concentración no dejan nada que revertir (curación, daño puro), y
	//eso sigue siendo válido: solo se llama a TurnManager.removeEffect si hay algo que quitar.
	private record Concentrating(String spellName, int targetEntityId, String effectName) {}

	private static final Map<UUID, Concentrating> concentratingOn = new HashMap<>();

	public static void startConcentrating(ServerPlayer caster, String spellName) {
		stopConcentrating(caster); //Un hechizo de concentración nuevo reemplaza cualquiera anterior — corta el efecto viejo antes de anotar el nuevo, en vez de dejarlo huérfano para siempre.
		concentratingOn.put(caster.getUUID(), new Concentrating(spellName, -1, null));
		notifyClient(caster, spellName);
	}

	/**
	 * <p>Le dice al cliente en qué se está concentrando, para que se vea en el HUD.</p>
	 *
	 * <p>La concentración vivía SOLO en el mapa de esta clase, así que el jugador no tenía forma de saber si
	 * seguía concentrado: perderla por un golpe es de las cosas que más se consultan en una mesa, y aquí
	 * pasaba en silencio salvo por una línea de chat que se va con el scroll. El campo va en la hoja para
	 * que viaje por la tubería que ya existe, no porque la hoja necesite recordarlo — al reiniciar el
	 * servidor no queda ninguna concentración viva de todos modos.</p>
	 */
	private static void notifyClient(ServerPlayer caster, String spellName) {
		JsonObject sheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (sheet == null) return;
		JsonObject patch = new JsonObject();
		if (spellName == null) {
			sheet.remove("concentratingOn");
			patch.add("concentratingOn", com.google.gson.JsonNull.INSTANCE); //Null en un parche = borrar la clave.
		} else {
			sheet.addProperty("concentratingOn", spellName);
			patch.addProperty("concentratingOn", spellName);
		}
		DndsheetsMod.sendSheetFieldUpdate(caster, patch);
	}

	//Llamado justo después de que el hechizo de concentración recién lanzado de verdad aplicó un efecto de
	//estado a un objetivo (ver SpellCastManager) — le suma el objetivo/efecto al registro que
	//startConcentrating ya creó, para que onDamageTaken/stopConcentrating sepan qué revertir si se pierde
	//la concentración más tarde. No-op si el lanzador no está concentrándose en nada.
	//ponytail: un solo objetivo por concentración — un hechizo de área (Guardianes Espirituales) que
	//afecta a varios solo recuerda el último; revertir en todos requeriría una lista, no hecho porque
	//ningún hechizo de ejemplo actual lo necesita.
	public static void attachEffect(ServerPlayer caster, int targetEntityId, String effectName) {
		Concentrating current = concentratingOn.get(caster.getUUID());
		if (current == null) return;
		concentratingOn.put(caster.getUUID(), new Concentrating(current.spellName(), targetEntityId, effectName));
	}

	public static void stopConcentrating(ServerPlayer caster) {
		//Los muros son de concentración: perderla los apaga. Sin esto, fallar la salvación de Constitución
		//dejaba el muro ardiendo igual — el mismo fallo que ya se corrigió una vez para los efectos de estado.
		ZoneManager.removeFor(caster.getUUID());
		//Los buffs de arma tambien son de concentracion (Favor Divino, Castigo Marcador).
		WeaponBuffManager.clear(SheetLoader.getServerSheet(caster.getStringUUID()));
		//Las invocaciones tambien: Arma Espiritual y Esfera Flamigera son de concentracion.
		if (caster.level() instanceof net.minecraft.server.level.ServerLevel summonLevel) {
			SummonManager.removeFor(summonLevel, caster.getUUID());
		}
		Concentrating previous = concentratingOn.remove(caster.getUUID());
		if (previous != null) notifyClient(caster, null);
		if (previous != null && previous.effectName() != null) {
			//El nivel sale del propio lanzador: se necesita para resolver la entidad objetivo y poder
			//levantarle la condición, no solo parar su temporizador de daño (ver TurnManager.removeEffect).
			net.minecraft.server.level.ServerLevel level = caster.level() instanceof net.minecraft.server.level.ServerLevel serverLevel ? serverLevel : null;
			TurnManager.removeEffect(level, previous.targetEntityId(), previous.effectName());
		}
	}

	//Llamado desde cada punto del mod donde un jugador recibe daño real (ver SpellCastManager.applyDamage,
	//CombatManager.onLivingHurt, MonsterActionManager.resolveAttack/resolveSpell).
	public static void onDamageTaken(ServerPlayer player, int damage) {
		Concentrating current = concentratingOn.get(player.getUUID());
		if (current == null || damage <= 0) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int dc = Math.max(10, damage / 2);
		DiceManager.RollOutcome saveRoll = sheet != null ? DiceManager.roll(sheet, "1d20 + $con") : DiceManager.roll(new JsonObject(), "1d20");
		boolean kept = saveRoll.result() != null && saveRoll.result().getValue() >= dc;

		String name = SheetLoader.characterNameOf(sheet, player);
		if (kept) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.concentration.kept", name, current.spellName(), dc, saveRoll.formatted()).withStyle(ChatFormatting.GRAY));
		} else {
			stopConcentrating(player); //Ahora sí revierte el efecto activo (ver arriba), no solo borra el registro.
			ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.concentration.lost", name, current.spellName(), dc, saveRoll.formatted()).withStyle(ChatFormatting.RED));
		}
	}
}
