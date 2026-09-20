package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>5e Concentration: casting a concentration spell replaces any previous concentration; taking real
 * damage forces a Constitution save (DC = max(10, damage/2)) or it's lost. Only players concentrate —
 * the DM's monsters are resolved action by action, with no tracking of this.</p>
 *
 * <p>If the spell left a status effect running (see SpellRegistry.Spell#appliesEffect, SpellCastManager),
 * losing concentration actually reverts it (TurnManager.removeEffect) — previously this only rolled the
 * die and sent a message, without undoing anything.</p>
 */
public class ConcentrationManager {
	//targetEntityId/effectName stay at -1/null until the spell actually applies an effect (see
	//attachEffect) — many concentration spells leave nothing to revert (healing, pure damage), and that's
	//still valid: TurnManager.removeEffect is only called if there's something to remove.
	private record Concentrating(String spellName, int targetEntityId, String effectName) {}

	private static final Map<UUID, Concentrating> concentratingOn = new HashMap<>();

	/** Only removes the entry from the map; does NOT revert zones or summons like stopConcentrating does.
	 *  For disconnection, where the only thing to avoid is the UUID staying in RAM forever. */
	static void clearFor(ServerPlayer player) {
		concentratingOn.remove(player.getUUID());
	}

	public static void startConcentrating(ServerPlayer caster, String spellName) {
		stopConcentrating(caster); //A new concentration spell replaces any previous one — cuts the old effect before recording the new one, instead of leaving it orphaned forever.
		concentratingOn.put(caster.getUUID(), new Concentrating(spellName, -1, null));
		notifyClient(caster, spellName);
	}

	/**
	 * <p>Tells the client what it's concentrating on, so it shows in the HUD.</p>
	 *
	 * <p>Concentration used to live ONLY in this class's map, so the player had no way of knowing whether
	 * they were still concentrating: losing it to a hit is one of the things most often checked at the
	 * table, and here it happened silently except for a chat line that scrolls away. The field goes on the
	 * sheet so it travels through the pipeline that already exists, not because the sheet needs to
	 * remember it — on a server restart no concentration survives anyway.</p>
	 */
	private static void notifyClient(ServerPlayer caster, String spellName) {
		JsonObject sheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (sheet == null) return;
		JsonObject patch = new JsonObject();
		if (spellName == null) {
			sheet.remove("concentratingOn");
			patch.add("concentratingOn", com.google.gson.JsonNull.INSTANCE); //Null in a patch = delete the key.
		} else {
			sheet.addProperty("concentratingOn", spellName);
			patch.addProperty("concentratingOn", spellName);
		}
		//Persist as well as notify: what the caster is concentrating on is sheet state (invariant 4).
		SheetLoader.saveServer(sheet, caster.getStringUUID());
		DndsheetsMod.sendSheetFieldUpdate(caster, patch);
	}

	//Called right after the just-cast concentration spell actually applied a status effect to a target
	//(see SpellCastManager) — adds the target/effect to the record startConcentrating already created,
	//so onDamageTaken/stopConcentrating know what to revert if concentration is lost later. No-op if the
	//caster isn't concentrating on anything.
	//ponytail: a single target per concentration — an area spell (Spirit Guardians) that affects several
	//only remembers the last one; reverting on all of them would need a list, not done because no current
	//example spell needs it.
	public static void attachEffect(ServerPlayer caster, int targetEntityId, String effectName) {
		Concentrating current = concentratingOn.get(caster.getUUID());
		if (current == null) return;
		concentratingOn.put(caster.getUUID(), new Concentrating(current.spellName(), targetEntityId, effectName));
	}

	public static void stopConcentrating(ServerPlayer caster) {
		//Walls are concentration spells: losing it puts them out. Without this, failing the Constitution
		//save left the wall burning anyway — the same bug already fixed once for status effects.
		ZoneManager.removeFor(caster.getUUID());
		//Weapon buffs are concentration spells too (Divine Favor, Branding Smite).
		//WeaponBuffManager is a pure helper over the JsonObject: whoever calls it is responsible for persisting it.
		JsonObject buffed = SheetLoader.getServerSheet(caster.getStringUUID());
		WeaponBuffManager.clear(buffed);
		if (buffed != null) SheetLoader.saveServer(buffed, caster.getStringUUID());
		//Summons too: Spiritual Weapon and Flaming Sphere are concentration spells.
		if (caster.level() instanceof net.minecraft.server.level.ServerLevel summonLevel) {
			SummonManager.removeFor(summonLevel, caster.getUUID());
		}
		Concentrating previous = concentratingOn.remove(caster.getUUID());
		if (previous != null) notifyClient(caster, null);
		if (previous != null && previous.effectName() != null) {
			//The level comes from the caster itself: it's needed to resolve the target entity and lift
			//its condition, not just stop its damage timer (see TurnManager.removeEffect).
			net.minecraft.server.level.ServerLevel level = caster.level() instanceof net.minecraft.server.level.ServerLevel serverLevel ? serverLevel : null;
			TurnManager.removeEffect(level, previous.targetEntityId(), previous.effectName());
		}
	}

	//Called from every point in the mod where a player takes real damage (see
	//SpellCastManager.applyDamage, CombatManager.onLivingHurt, MonsterActionManager.resolveAttack/resolveSpell).
	public static void onDamageTaken(ServerPlayer player, int damage) {
		//A spell being cast mid-cast gets interrupted by the SAME rule and the same DC (see
		//CastingManager), so it hooks in here instead of in the four damage paths separately: this method
		//is the point all of them already go through, and a new one someone adds tomorrow will go through
		//it too without having to remember to.
		CastingManager.onDamageTaken(player, damage);

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
			stopConcentrating(player); //Now it actually reverts the active effect (see above), not just clearing the record.
			ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.concentration.lost", name, current.spellName(), dc, saveRoll.formatted()).withStyle(ChatFormatting.RED));
		}
	}
}
