package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;

/**
 * <p>Items that are used up by consuming them: potions and oils. This was the missing capability for the
 * ~40 SRD potions to stop being purely narrative — their effects come from <em>drinking</em> them and
 * last a while, so modeling them as passive bonuses would have protected whoever carries the bottle in
 * their pocket without opening it.</p>
 *
 * <p>It doesn't invent new mechanisms: each effect goes through a path that already existed. Healing
 * through the same route as a healing spell, temporary HP via {@link Combatant#grantTemporaryHp}, and a
 * condition via {@link TurnManager#applyEffect}, which already knows how to apply and remove it on
 * expiry. The only new thing is temporary resistances, because there was nowhere to store "resistant to
 * fire for 10 rounds".</p>
 */
public class ConsumableManager {

	//On the sheet, alongside the rest of the character's state, with the format "type:affinity:rounds". A
	//single string per entry instead of a nested object: the rest of the sheet already uses flat formats
	//like this, and this gets read on every damage calculation.
	private static final String KEY = "temporaryAffinities";

	static void tryUse(PlayerInteractEvent event, String magicItemId) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(magicItemId);
		if (item == null || !item.isConsumable()) return;

		event.setCanceled(true);
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		Combatant combatant = Combatant.of(player);
		if (sheet == null || combatant == null) return;

		net.minecraft.network.chat.MutableComponent happened = Component.empty();

		if (item.healDice() != null) {
			DiceManager.RollOutcome roll = DiceManager.roll(sheet, item.healDice());
			if (roll.result() != null) {
				//Minecraft's heal() and not touching the sheet: a player's real HP IS their vanilla health
				//(see Combatant.PlayerCombatant), so healing on the sheet wouldn't heal anything.
				player.heal(roll.result().getValue());
				addPart(happened, Component.translatable("chat.dndsheets.item.effect_heal", roll.result().getValue()));
			}
		}

		if (item.temporaryHpDice() != null) {
			DiceManager.RollOutcome roll = DiceManager.roll(sheet, item.temporaryHpDice());
			if (roll.result() != null) {
				combatant.grantTemporaryHp(roll.result().getValue());
				addPart(happened, Component.translatable("chat.dndsheets.item.effect_temp_hp", roll.result().getValue()));
			}
		}

		if (item.grantsCondition() != null) {
			//Via TurnManager.applyEffect and not writing the condition by hand: that path already applies
			//it AND removes it when the counter expires. Writing it directly would leave it on forever.
			TurnManager.applyEffect(player, item.grantsCondition(), "0", item.durationRounds(), null);
			addPart(happened, Component.literal(item.grantsCondition()));
		}

		if (!item.temporaryAffinities().isEmpty()) {
			grantTemporaryAffinities(sheet, item.temporaryAffinities(), item.durationRounds());
			addPart(happened, Component.translatable("chat.dndsheets.item.effect_resistance", String.join(", ", item.temporaryAffinities().keySet())));
		}

		SheetLoader.saveServer(sheet, player.getStringUUID());
		CombatFx.spellCast(player);
		ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.item.consumed",
			SheetLoader.characterNameOf(sheet, player), ContentNames.of(item.name()), happened)
			.withStyle(ChatFormatting.GREEN));

		//Consumed at the end, once the effect is already applied: if something had failed earlier, the
		//player keeps the potion instead of losing it without getting anything.
		ItemStack stack = event.getItemStack();
		if (!player.getAbilities().instabuild) stack.shrink(1);
	}

	private static void addPart(net.minecraft.network.chat.MutableComponent all, Component part) {
		if (!all.getSiblings().isEmpty()) all.append(", ");
		all.append(part);
	}

	//--- Temporary resistances ---------------------------------------------------------------------------

	private static void grantTemporaryAffinities(JsonObject sheet, Map<String, String> affinities, int rounds) {
		JsonObject stored = sheet.has(KEY) ? sheet.getAsJsonObject(KEY) : new JsonObject();
		for (Map.Entry<String, String> entry : affinities.entrySet()) {
			stored.addProperty(entry.getKey(), entry.getValue() + ":" + rounds);
		}
		sheet.add(KEY, stored);
	}

	/**
	 * <p>Active temporary affinity for that damage type, or {@code null}. Read by
	 * {@code Combatant.SheetBacked.damageMultiplier}, the single point through which every "how much
	 * damage does it actually take?" question passes.</p>
	 */
	public static String activeAffinity(JsonObject sheet, String damageType) {
		if (sheet == null || damageType == null || !sheet.has(KEY)) return null;
		JsonObject stored = sheet.getAsJsonObject(KEY);
		if (!stored.has(damageType)) return null;
		String[] parts = stored.get(damageType).getAsString().split(":");
		if (parts.length != 2) return null;
		try {
			return Integer.parseInt(parts[1]) > 0 ? parts[0] : null;
		} catch (NumberFormatException e) {
			return null; //Sheet hand-edited with a weird value: ignore it instead of crashing combat.
		}
	}

	/**
	 * <p>Deducts one round from that sheet's temporary resistances. Called when closing the round, along
	 * with the rest of the durations — in full rounds and not per turn, or they'd last as many times
	 * fewer as there are combatants in initiative.</p>
	 *
	 * @return the damage types whose resistance just expired, so they can be announced.
	 */
	public static java.util.List<String> tickRound(JsonObject sheet) {
		java.util.List<String> expired = new java.util.ArrayList<>();
		if (sheet == null || !sheet.has(KEY)) return expired;

		JsonObject stored = sheet.getAsJsonObject(KEY);
		JsonObject remaining = new JsonObject();
		for (String type : stored.keySet()) {
			String[] parts = stored.get(type).getAsString().split(":");
			if (parts.length != 2) continue;
			int left;
			try {
				left = Integer.parseInt(parts[1]) - 1;
			} catch (NumberFormatException e) {
				continue; //Corrupt entry: drop it instead of carrying it forever.
			}
			if (left > 0) remaining.addProperty(type, parts[0] + ":" + left);
			else expired.add(type);
		}
		sheet.add(KEY, remaining);
		return expired;
	}
}
