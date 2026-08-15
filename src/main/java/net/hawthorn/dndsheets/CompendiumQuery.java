package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.network.BrowseListMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Consulta de contenido para el compendio en juego. Con 779 entradas importadas del SRD, la única
 * forma de mirar un hechizo o un bloque de estadísticas era recordar su id y escribir un comando — que
 * es tanto como no tenerlas.</p>
 *
 * <p>El servidor <b>formatea</b> y el cliente pinta: los registros viven aquí, y mandar un bloque de
 * estadísticas entero por la red para que el cliente lo componga sería mover el problema sin resolverlo.
 * Es el mismo criterio que ya usaba la vista de grupo.</p>
 *
 * <p>La lista manda una línea de resumen por entrada y la ficha completa se pide aparte al pulsarla. Con
 * 362 objetos, mandar las descripciones enteras de golpe serían decenas de kilobytes en un solo paquete;
 * dos viajes cortos es lo correcto, no una optimización prematura al revés.</p>
 */
public class CompendiumQuery {

	/** Categorías del compendio. El valor viaja como texto en el mensaje, así que se compara en minúsculas. */
	public enum Category {
		SPELLS("hechizos"), MONSTERS("monstruos"), ITEMS("objetos"), WEAPONS("armas");

		public final String label;
		Category(String label) { this.label = label; }

		static Category of(String raw) {
			for (Category category : values()) {
				if (category.name().equalsIgnoreCase(raw) || category.label.equalsIgnoreCase(raw)) return category;
			}
			return null;
		}
	}

	public static void sendList(ServerPlayer viewer, String rawCategory) {
		Category category = Category.of(rawCategory);
		if (category == null) return;

		//Los ids viajan como "categoria|id": el cliente los devuelve tal cual al pedir la ficha, sin tener
		//que deducir de qué registro salieron. Deducirlo del título de la pantalla era frágil y fallaba en
		//silencio en cuanto alguien cambiara ese texto.
		List<String> ids = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		String prefix = category.name().toLowerCase(java.util.Locale.ROOT) + "|";

		switch (category) {
			case SPELLS -> {
				for (String id : sorted(SpellRegistry.ids())) {
					SpellRegistry.Spell spell = SpellRegistry.get(id);
					if (spell == null) continue;
					ids.add(prefix + id);
					labels.add(spell.name() + "  ·  nivel " + spell.level() + "  ·  " + spell.dice());
				}
			}
			case MONSTERS -> {
				for (String id : sorted(MonsterRegistry.ids())) {
					MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
					if (block == null) continue;
					ids.add(prefix + id);
					labels.add(block.name() + "  ·  CA " + block.ac() + "  ·  " + block.maxHp() + " PG");
				}
			}
			case ITEMS -> {
				for (String id : sorted(MagicItemRegistry.ids())) {
					MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
					if (item == null) continue;
					ids.add(prefix + id);
					//Se marca cuál aplica el motor y cuál narra el DM: es la distinción que más importa al
					//consultarlos, y sin ella un DM no sabe qué esperar al entregarlo.
					labels.add(item.name() + "  ·  " + item.rarity() + (item.hasMechanics() ? "" : "  ·  narrativo"));
				}
			}
			case WEAPONS -> {
				for (String id : sorted(Config.loadedWeaponIds())) {
					Config.WeaponDefault weapon = Config.weaponDefaultFor(id);
					if (weapon == null) continue;
					ids.add(prefix + id);
					labels.add(id + "  ·  " + weapon.dice() + "  ·  " + weapon.damageType());
				}
			}
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> viewer),
			new BrowseListMessage(BrowseListMessage.Kind.CONTENT, ids, labels));
	}

	/** @param entryId {@code categoria|id}: el mensaje solo tiene un campo de texto, así que viajan juntos. */
	public static void sendDetail(ServerPlayer viewer, String entryId) {
		String[] parts = entryId.split("\\|", 2);
		if (parts.length != 2) return;
		Category category = Category.of(parts[0]);
		if (category == null) return;
		String id = parts[1];

		String detail = switch (category) {
			case SPELLS -> describeSpell(id);
			case MONSTERS -> describeMonster(id);
			case ITEMS -> describeItem(id);
			case WEAPONS -> describeWeapon(id);
		};
		if (detail == null) return;

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> viewer),
			new BrowseListMessage(BrowseListMessage.Kind.DETAIL, List.of(id), List.of(detail)));
	}

	private static String describeSpell(String id) {
		SpellRegistry.Spell spell = SpellRegistry.get(id);
		if (spell == null) return null;
		StringBuilder text = new StringBuilder(spell.name());
		text.append("\nNivel ").append(spell.level()).append("  ·  ").append(modeLabel(spell));
		text.append("\nLanzamiento: ").append(spell.castingAbility());
		if ("save".equals(spell.mode())) {
			text.append("\nSalvación: ").append(spell.saveAbility());
			text.append(spell.halfOnSave() ? " (mitad si supera)" : " (sin daño si supera)");
		}
		if (!"0".equals(spell.dice())) text.append("\nDados: ").append(spell.dice()).append(" ").append(spell.damageType());
		if (spell.aoeRadius() > 0) text.append("\nÁrea: ").append(spell.aoeShape()).append(" de ").append(spell.aoeRadius()).append(" bloques");
		if (spell.concentration()) text.append("\nRequiere concentración");
		if (spell.appliesEffect()) text.append("\nAplica: ").append(spell.effectName()).append(" (").append(spell.effectTurns()).append(" asaltos)");
		return text.toString();
	}

	private static String modeLabel(SpellRegistry.Spell spell) {
		if (spell.isSummon()) return "invocación";
		if (spell.isZone()) return "zona persistente";
		return switch (spell.mode()) {
			case "save" -> "salvación";
			case "heal" -> "curación";
			case "buff" -> "potenciación";
			case "temphp" -> "PG temporales";
			default -> "ataque";
		};
	}

	private static String describeMonster(String id) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
		if (block == null) return null;
		StringBuilder text = new StringBuilder(block.name());
		text.append("\nCA ").append(block.ac()).append("  ·  ").append(block.maxHp()).append(" PG")
			.append("  ·  competencia +").append(block.proficiencyBonus());
		text.append("\nFUE ").append(block.abilities().get("str"))
			.append("  DES ").append(block.abilities().get("dex"))
			.append("  CON ").append(block.abilities().get("con"))
			.append("  INT ").append(block.abilities().get("int"))
			.append("  SAB ").append(block.abilities().get("wis"))
			.append("  CAR ").append(block.abilities().get("cha"));
		for (MonsterRegistry.MonsterAttack attack : block.attacks()) {
			text.append("\n").append(attack.name()).append(": ").append(attack.dice())
				.append(" ").append(attack.damageType()).append(" (").append(attack.toHitAbility()).append(")");
		}
		if (!block.damageAffinities().isEmpty()) text.append("\nAfinidades: ").append(block.damageAffinities());
		if (!block.nonmagicalAffinities().isEmpty()) text.append("\nFrente a lo no mágico: ").append(block.nonmagicalAffinities());
		return text.toString();
	}

	private static String describeItem(String id) {
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
		if (item == null) return null;
		StringBuilder text = new StringBuilder(item.name());
		text.append("\n").append(item.rarity());
		if (item.attunement()) text.append("  ·  requiere sintonización");
		if (item.acBonus() != 0) text.append("\nCA +").append(item.acBonus());
		if (item.saveBonus() != 0) text.append("\nSalvaciones +").append(item.saveBonus());
		if (!item.affinities().isEmpty()) text.append("\nAfinidades: ").append(item.affinities());
		if (item.grantsSpellId() != null) text.append("\nLanza: ").append(item.grantsSpellId());
		if (item.isConsumable()) {
			text.append("\nSe gasta al usarlo");
			if (item.healDice() != null) text.append("  ·  cura ").append(item.healDice());
			if (item.temporaryHpDice() != null) text.append("  ·  ").append(item.temporaryHpDice()).append(" PG temporales");
			if (item.grantsCondition() != null) text.append("  ·  aplica ").append(item.grantsCondition());
			if (!item.temporaryAffinities().isEmpty()) text.append("  ·  ").append(item.temporaryAffinities());
		}
		if (!item.description().isBlank()) text.append("\n\n").append(item.description());
		return text.toString();
	}

	private static String describeWeapon(String id) {
		Config.WeaponDefault weapon = Config.weaponDefaultFor(id);
		if (weapon == null) return null;
		StringBuilder text = new StringBuilder(id);
		text.append("\n").append(weapon.dice()).append(" ").append(weapon.damageType())
			.append("  ·  ").append(weapon.ability());
		if (weapon.isVersatile()) text.append("\nVersátil: ").append(weapon.versatileDice()).append(" a dos manos");
		if ("two".equals(weapon.hands())) text.append("\nA dos manos");
		return text.toString();
	}

	private static List<String> sorted(java.util.Set<String> ids) {
		List<String> list = new ArrayList<>(ids);
		java.util.Collections.sort(list);
		return list;
	}
}
