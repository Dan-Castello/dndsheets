package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;

/**
 * <p>Objetos que se usan gastándolos: pociones y aceites. Es la capacidad que faltaba para que las ~40
 * pociones del SRD dejaran de ser narrativas — sus efectos vienen de <em>beberlas</em> y duran un rato,
 * así que modelarlos como bonificadores pasivos habría protegido a quien lleva la botella en el bolsillo
 * sin abrirla.</p>
 *
 * <p>No inventa mecanismos: cada efecto entra por un camino que ya existía. La curación por el mismo sitio
 * que un conjuro de curación, los PG temporales por {@link Combatant#grantTemporaryHp}, y una condición
 * por {@link TurnManager#applyEffect}, que ya sabe aplicarla y retirarla al expirar. Lo único nuevo son
 * las resistencias temporales, porque no había dónde guardar "resistente al fuego durante 10 asaltos".</p>
 */
public class ConsumableManager {

	//En la hoja, junto al resto del estado del personaje, con el formato "tipo:afinidad:asaltos". Una sola
	//cadena por entrada en vez de un objeto anidado: el resto de la hoja ya usa formatos planos así, y
	//esto se lee en cada cálculo de daño.
	private static final String KEY = "temporaryAffinities";

	static void tryUse(PlayerInteractEvent event, String magicItemId) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(magicItemId);
		if (item == null || !item.isConsumable()) return;

		event.setCanceled(true);
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		Combatant combatant = Combatant.of(player);
		if (sheet == null || combatant == null) return;

		StringBuilder happened = new StringBuilder();

		if (item.healDice() != null) {
			DiceManager.RollOutcome roll = DiceManager.roll(sheet, item.healDice());
			if (roll.result() != null) {
				//heal() de Minecraft y no tocar la hoja: los PG reales de un jugador SON su salud vanilla
				//(ver Combatant.PlayerCombatant), así que curar en la hoja no curaría nada.
				player.heal(roll.result().getValue());
				happened.append(roll.result().getValue()).append(" PG");
			}
		}

		if (item.temporaryHpDice() != null) {
			DiceManager.RollOutcome roll = DiceManager.roll(sheet, item.temporaryHpDice());
			if (roll.result() != null) {
				combatant.grantTemporaryHp(roll.result().getValue());
				if (happened.length() > 0) happened.append(", ");
				happened.append(roll.result().getValue()).append(" PG temporales");
			}
		}

		if (item.grantsCondition() != null) {
			//Por TurnManager.applyEffect y no escribiendo la condición a mano: ese camino ya la aplica Y la
			//retira al expirar el contador. Escribirla directa la dejaría puesta para siempre.
			TurnManager.applyEffect(player, item.grantsCondition(), "0", item.durationRounds(), null);
			if (happened.length() > 0) happened.append(", ");
			happened.append(item.grantsCondition());
		}

		if (!item.temporaryAffinities().isEmpty()) {
			grantTemporaryAffinities(sheet, item.temporaryAffinities(), item.durationRounds());
			if (happened.length() > 0) happened.append(", ");
			happened.append("resistencia ").append(String.join(", ", item.temporaryAffinities().keySet()));
		}

		SheetLoader.saveServer(sheet, player.getStringUUID());
		CombatFx.spellCast(player);
		ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.item.consumed",
			SheetLoader.characterNameOf(sheet, player), item.name(), happened.toString())
			.withStyle(ChatFormatting.GREEN));

		//Se gasta al final, ya con el efecto aplicado: si algo hubiera fallado antes, el jugador conserva
		//la poción en vez de perderla sin recibir nada.
		ItemStack stack = event.getItemStack();
		if (!player.getAbilities().instabuild) stack.shrink(1);
	}

	//--- Resistencias temporales ------------------------------------------------------------------------

	private static void grantTemporaryAffinities(JsonObject sheet, Map<String, String> affinities, int rounds) {
		JsonObject stored = sheet.has(KEY) ? sheet.getAsJsonObject(KEY) : new JsonObject();
		for (Map.Entry<String, String> entry : affinities.entrySet()) {
			stored.addProperty(entry.getKey(), entry.getValue() + ":" + rounds);
		}
		sheet.add(KEY, stored);
	}

	/**
	 * <p>Afinidad temporal activa para ese tipo de daño, o {@code null}. La lee
	 * {@code Combatant.SheetBacked.damageMultiplier}, que es el punto único por el que pasa toda pregunta
	 * de "¿cuánto daño recibe de verdad?".</p>
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
			return null; //Hoja editada a mano con un valor raro: se ignora en vez de tumbar el combate.
		}
	}

	/**
	 * <p>Descuenta un asalto a las resistencias temporales de esa hoja. Se llama al cerrar el asalto, junto
	 * al resto de duraciones — en asaltos completos y no por turno, o durarían tantas veces menos como
	 * combatientes haya en la iniciativa.</p>
	 *
	 * @return los tipos de daño cuya resistencia acaba de expirar, para poder avisar.
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
				continue; //Entrada corrupta: se deja caer en vez de arrastrarla para siempre.
			}
			if (left > 0) remaining.addProperty(type, parts[0] + ":" + left);
			else expired.add(type);
		}
		sheet.add(KEY, remaining);
		return expired;
	}
}
