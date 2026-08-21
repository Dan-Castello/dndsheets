package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.network.BrowseListMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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

	private static final String KEY = "gui.dndsheets.compendium.";

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
		List<Component> labels = new ArrayList<>();
		String prefix = category.name().toLowerCase(java.util.Locale.ROOT) + "|";

		switch (category) {
			case SPELLS -> {
				for (String id : sorted(SpellRegistry.ids())) {
					SpellRegistry.Spell spell = SpellRegistry.get(id);
					if (spell == null) continue;
					ids.add(prefix + id);
					labels.add(Component.translatable("gui.dndsheets.compendium.spell_line",
						spell.name(), spell.level(), spell.dice()));
				}
			}
			case MONSTERS -> {
				for (String id : sorted(MonsterRegistry.ids())) {
					MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
					if (block == null) continue;
					ids.add(prefix + id);
					labels.add(Component.translatable("gui.dndsheets.compendium.monster_line",
						block.name(), block.ac(), block.maxHp()));
				}
			}
			case ITEMS -> {
				for (String id : sorted(MagicItemRegistry.ids())) {
					MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
					if (item == null) continue;
					ids.add(prefix + id);
					//Se marca cuál aplica el motor y cuál narra el DM: es la distinción que más importa al
					//consultarlos, y sin ella un DM no sabe qué esperar al entregarlo.
					labels.add(Component.translatable(item.hasMechanics()
						? "gui.dndsheets.compendium.item_line"
						: "gui.dndsheets.compendium.item_line_narrative", item.name(), item.rarity()));
				}
			}
			case WEAPONS -> {
				for (String id : sorted(Config.loadedWeaponIds())) {
					Config.WeaponDefault weapon = Config.weaponDefaultFor(id);
					if (weapon == null) continue;
					ids.add(prefix + id);
					labels.add(Component.translatable("gui.dndsheets.compendium.weapon_line",
						id, weapon.dice(), weapon.damageType()));
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

		MutableComponent detail = switch (category) {
			case SPELLS -> describeSpell(id);
			case MONSTERS -> describeMonster(id);
			case ITEMS -> describeItem(id);
			case WEAPONS -> describeWeapon(id);
		};
		if (detail == null) return;

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> viewer),
			new BrowseListMessage(BrowseListMessage.Kind.DETAIL, List.of(id), List.of(detail)));
	}

	//Las fichas se arman como Component y no como String: asi los rotulos ("Nivel", "CA", "Salvacion")
	//los resuelve el CLIENTE en su idioma. El valor de cada uno es dato del registro y viaja tal cual.
	private static MutableComponent describeSpell(String id) {
		SpellRegistry.Spell spell = SpellRegistry.get(id);
		if (spell == null) return null;
		MutableComponent text = Component.literal(spell.name());
		text.append("\n").append(Component.translatable(KEY + "spell_head", spell.level(),
			Component.translatable(KEY + modeKey(spell))));
		text.append("\n").append(Component.translatable(KEY + "spell_cast", spell.castingAbility()));
		if ("save".equals(spell.mode())) {
			text.append("\n").append(Component.translatable(KEY + "spell_save", spell.saveAbility(),
				Component.translatable(KEY + (spell.halfOnSave() ? "save_half" : "save_none"))));
		}
		if (!"0".equals(spell.dice())) {
			text.append("\n").append(Component.translatable(KEY + "dice", spell.dice(), spell.damageType()));
		}
		if (spell.aoeRadius() > 0) {
			text.append("\n").append(Component.translatable(KEY + "area", spell.aoeShape(), spell.aoeRadius()));
		}
		if (spell.concentration()) text.append("\n").append(Component.translatable(KEY + "concentration"));
		if (spell.appliesEffect()) {
			text.append("\n").append(Component.translatable(KEY + "applies", spell.effectName(), spell.effectTurns()));
		}
		return text;
	}

	//Devuelve la CLAVE, no el texto: quien la pinta la envuelve en translatable, asi el modo tambien
	//se traduce en el cliente en vez de venir ya resuelto.
	private static String modeKey(SpellRegistry.Spell spell) {
		if (spell.isSummon()) return "mode_summon";
		if (spell.isZone()) return "mode_zone";
		return switch (spell.mode()) {
			case "save" -> "mode_save";
			case "heal" -> "mode_heal";
			case "buff" -> "mode_buff";
			case "temphp" -> "mode_temphp";
			default -> "mode_attack";
		};
	}

	private static MutableComponent describeMonster(String id) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
		if (block == null) return null;
		MutableComponent text = Component.literal(block.name());
		text.append("\n").append(Component.translatable(KEY + "monster_head",
			block.ac(), block.maxHp(), block.proficiencyBonus()));
		text.append("\n").append(Component.translatable(KEY + "monster_abilities",
			block.abilities().get("str"), block.abilities().get("dex"), block.abilities().get("con"),
			block.abilities().get("int"), block.abilities().get("wis"), block.abilities().get("cha")));
		for (MonsterRegistry.MonsterAttack attack : block.attacks()) {
			text.append("\n").append(Component.translatable(KEY + "monster_attack",
				attack.name(), attack.dice(), attack.damageType(), attack.toHitAbility()));
		}
		if (!block.damageAffinities().isEmpty()) {
			text.append("\n").append(Component.translatable(KEY + "affinities", block.damageAffinities().toString()));
		}
		if (!block.nonmagicalAffinities().isEmpty()) {
			text.append("\n").append(Component.translatable(KEY + "nonmagical", block.nonmagicalAffinities().toString()));
		}
		return text;
	}

	private static MutableComponent describeItem(String id) {
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
		if (item == null) return null;
		MutableComponent text = Component.literal(item.name());
		text.append("\n").append(Component.literal(item.rarity()));
		if (item.attunement()) text.append(Component.translatable(KEY + "attunement"));
		if (item.acBonus() != 0) text.append("\n").append(Component.translatable(KEY + "ac_bonus", item.acBonus()));
		if (item.saveBonus() != 0) text.append("\n").append(Component.translatable(KEY + "save_bonus", item.saveBonus()));
		if (!item.affinities().isEmpty()) {
			text.append("\n").append(Component.translatable(KEY + "affinities", item.affinities().toString()));
		}
		if (item.grantsSpellId() != null) {
			text.append("\n").append(Component.translatable(KEY + "casts", item.grantsSpellId()));
		}
		if (item.isConsumable()) {
			text.append("\n").append(Component.translatable(KEY + "consumable"));
			if (item.healDice() != null) text.append(Component.translatable(KEY + "heals", item.healDice()));
			if (item.temporaryHpDice() != null) text.append(Component.translatable(KEY + "temp_hp", item.temporaryHpDice()));
			if (item.grantsCondition() != null) text.append(Component.translatable(KEY + "grants", item.grantsCondition()));
			if (!item.temporaryAffinities().isEmpty()) {
				text.append(Component.translatable(KEY + "temp_affinities", item.temporaryAffinities().toString()));
			}
		}
		if (!item.description().isBlank()) text.append("\n\n").append(Component.literal(item.description()));
		return text;
	}

	private static MutableComponent describeWeapon(String id) {
		Config.WeaponDefault weapon = Config.weaponDefaultFor(id);
		if (weapon == null) return null;
		MutableComponent text = Component.literal(id);
		text.append("\n").append(Component.translatable(KEY + "weapon_head",
			weapon.dice(), weapon.damageType(), weapon.ability()));
		if (weapon.isVersatile()) {
			text.append("\n").append(Component.translatable(KEY + "versatile", weapon.versatileDice()));
		}
		if ("two".equals(weapon.hands())) text.append("\n").append(Component.translatable(KEY + "two_handed"));
		return text;
	}

	private static List<String> sorted(java.util.Set<String> ids) {
		List<String> list = new ArrayList<>(ids);
		java.util.Collections.sort(list);
		return list;
	}
}
