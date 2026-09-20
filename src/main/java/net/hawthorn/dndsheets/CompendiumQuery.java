package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.network.BrowseListMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Content lookup for the in-game compendium. With 779 entries imported from the SRD, the only way to
 * look up a spell or a stat block was to remember its id and type a command — which is as good as not
 * having them.</p>
 *
 * <p>The server <b>formats</b> and the client draws: the registries live here, and sending a whole stat
 * block over the network for the client to compose would move the problem without solving it. Same
 * criterion the party view already used.</p>
 *
 * <p>The list sends one summary line per entry and the full detail is requested separately when clicked.
 * With 362 items, sending every full description at once would be tens of kilobytes in a single packet;
 * two short trips is the right call here, not premature optimization run in reverse.</p>
 */
public class CompendiumQuery {

	private static final String KEY = "gui.dndsheets.compendium.";

	/** Compendium categories. The value travels as text in the message, so it's compared lowercase. */
	public enum Category {
		SPELLS("spells"), MONSTERS("monsters"), ITEMS("items"), WEAPONS("weapons"), TRAITS("traits");

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

		//Ids travel as "category|id": the client sends them back as-is when requesting the detail, without
		//having to guess which registry they came from. Guessing it from the screen title was fragile and
		//failed silently the moment someone changed that text.
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
						ContentNames.of(spell.name()), spell.level(), spell.dice()));
				}
			}
			case MONSTERS -> {
				for (String id : sorted(MonsterRegistry.ids())) {
					MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
					if (block == null) continue;
					ids.add(prefix + id);
					labels.add(Component.translatable("gui.dndsheets.compendium.monster_line",
						ContentNames.of(block.name()), block.ac(), block.maxHp()));
				}
			}
			case ITEMS -> {
				for (String id : sorted(MagicItemRegistry.ids())) {
					MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
					if (item == null) continue;
					ids.add(prefix + id);
					//Marks which one the engine applies and which one the DM narrates: that's the
					//distinction that matters most when looking them up, and without it a DM doesn't
					//know what to expect when handing it out.
					labels.add(Component.translatable(item.hasMechanics()
						? "gui.dndsheets.compendium.item_line"
						: "gui.dndsheets.compendium.item_line_narrative", ContentNames.of(item.name()), item.rarity()));
				}
			}
			//Traits were the only content the player couldn't look up anywhere: the sheet stores their
			//ids ("traits") but TraitRegistry lives only on the server, so without this a monk had no way
			//to know which die they hit with unarmed. The ones the viewer already has are marked, which
			//is half the question and comes free here: the sheet is right there.
			case TRAITS -> {
				java.util.Set<String> mine = grantedTraitIds(viewer);
				for (String id : sorted(TraitRegistry.ids())) {
					TraitRegistry.Trait trait = TraitRegistry.get(id);
					if (trait == null) continue;
					ids.add(prefix + id);
					labels.add(Component.translatable(mine.contains(id)
						? "gui.dndsheets.compendium.trait_line_mine"
						: "gui.dndsheets.compendium.trait_line", ContentNames.of(trait.name())));
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

	/** @param entryId {@code category|id}: the message only has one text field, so they travel together. */
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
			case TRAITS -> describeTrait(id);
		};
		if (detail == null) return;

		//The WHOLE entryId travels ("category|id"), not the bare id: the screen needs the category to
		//know what can be DONE with the entry (learn a spell, summon a monster...). The field already
		//existed and nobody read it, so this doesn't change a single byte of the packet's shape.
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> viewer),
			new BrowseListMessage(BrowseListMessage.Kind.DETAIL, List.of(entryId), List.of(detail)));
	}

	//Detail sheets are built as Component and not as String: that way labels ("Level", "AC", "Save")
	//get resolved by the CLIENT in its own language. Each value is registry data and travels as-is.
	private static MutableComponent describeSpell(String id) {
		SpellRegistry.Spell spell = SpellRegistry.get(id);
		if (spell == null) return null;
		MutableComponent text = ContentNames.of(spell.name());
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

	//Returns the KEY, not the text: whoever renders it wraps it in translatable, so the mode also gets
	//translated on the client instead of arriving already resolved.
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
		MutableComponent text = ContentNames.of(block.name());
		text.append("\n").append(Component.translatable(KEY + "monster_head",
			block.ac(), block.maxHp(), block.proficiencyBonus()));
		text.append("\n").append(Component.translatable(KEY + "monster_abilities",
			block.abilities().get("str"), block.abilities().get("dex"), block.abilities().get("con"),
			block.abilities().get("int"), block.abilities().get("wis"), block.abilities().get("cha")));
		for (MonsterRegistry.MonsterAttack attack : block.attacks()) {
			text.append("\n").append(Component.translatable(KEY + "monster_attack",
				ContentNames.of(attack.name()), attack.dice(), attack.damageType(), attack.toHitAbility()));
		}
		if (!block.damageAffinities().isEmpty()) {
			text.append("\n").append(Component.translatable(KEY + "affinities", block.damageAffinities().toString()));
		}
		if (!block.nonmagicalAffinities().isEmpty()) {
			text.append("\n").append(Component.translatable(KEY + "nonmagical", block.nonmagicalAffinities().toString()));
		}
		return text;
	}

	//A trait has no description text in the registry (see TraitRegistry.Trait): what it does is whatever
	//dice-per-level it declares, so the detail sheet IS that table. With no dice declared, only the name
	//is left, which is still more than was visible before.
	private static MutableComponent describeTrait(String id) {
		TraitRegistry.Trait trait = TraitRegistry.get(id);
		if (trait == null) return null;
		MutableComponent text = ContentNames.of(trait.name());
		for (TraitRegistry.LevelDice entry : trait.unarmedDiceByLevel()) {
			text.append("\n").append(Component.translatable(KEY + "trait_unarmed",
				entry.level(), entry.dice(), trait.unarmedAbility()));
		}
		for (TraitRegistry.LevelDice entry : trait.sneakAttackDiceByLevel()) {
			text.append("\n").append(Component.translatable(KEY + "trait_sneak", entry.level(), entry.dice()));
		}
		return text;
	}

	/** Trait ids from the viewer's sheet, to mark in the list which ones they already have. */
	private static java.util.Set<String> grantedTraitIds(ServerPlayer viewer) {
		com.google.gson.JsonObject sheet = SheetLoader.getServerSheet(viewer.getStringUUID());
		if (sheet == null || !sheet.has("traits")) return java.util.Set.of();
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (com.google.gson.JsonElement element : sheet.getAsJsonArray("traits")) ids.add(element.getAsString());
		return ids;
	}

	private static MutableComponent describeItem(String id) {
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
		if (item == null) return null;
		MutableComponent text = ContentNames.of(item.name());
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
