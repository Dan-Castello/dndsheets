package net.hawthorn.dndsheets;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Slime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>Stat blocks for hostile mobs that have none (vanilla ones and other mods'), built from their Minecraft attributes so
 * they can fight under the same rules as the bestiary. {@link #fromStats} is pure arithmetic over numbers (testable without
 * a game); {@link #forEntity} only reads the numbers off the entity.</p>
 *
 * <p>The table is deliberately simple and fixed: a vanilla zombie (20 HP, 3 damage) comes out as roughly the SRD zombie
 * (AC 10, 1d6+1 slam). Mobs with special attacks (arrows, explosions, fireballs) get the same melee slam: that is a stated
 * simplification, and {@code adoptVanillaMobs} turns the whole thing off.</p>
 */
final class VanillaStatBlocks {
	private VanillaStatBlocks() {
	}

	/** Damage dice by Minecraft attack damage. */
	static String damageDice(double attackDamage) {
		if (attackDamage <= 2) return "1d4";
		if (attackDamage <= 4) return "1d6";
		if (attackDamage <= 7) return "1d8";
		if (attackDamage <= 10) return "2d6";
		if (attackDamage <= 15) return "2d8";
		return "4d8";
	}

	/** Strength modifier (to hit and to damage) by Minecraft attack damage. */
	static int strengthBonus(double attackDamage) {
		if (attackDamage < 3) return 0;
		if (attackDamage < 6) return 1;
		if (attackDamage < 10) return 2;
		if (attackDamage < 15) return 3;
		return 5;
	}

	/** Size from the collision box: the larger of the width and 80% of the height. */
	static CreatureSize sizeFor(double width, double height) {
		double m = Math.max(width, height * 0.8);
		if (m < 0.6) return CreatureSize.TINY;
		if (m < 1.2) return CreatureSize.SMALL;
		if (m < 1.9) return CreatureSize.MEDIUM;
		if (m < 3.2) return CreatureSize.LARGE;
		if (m < 5.0) return CreatureSize.HUGE;
		return CreatureSize.GARGANTUAN;
	}

	static int proficiencyFor(int maxHp) {
		return 2 + Math.min(4, Math.max(0, maxHp) / 50);
	}

	/** One block per (type, HP, AC, damage): a big slime and a tiny one cannot share their hit points. */
	static String idFor(String typeKey, int maxHp, int armorClass, double attackDamage) {
		String path = typeKey.replace(':', '_').replace('/', '_').toLowerCase(java.util.Locale.ROOT);
		return "dndsheets:auto_" + path + "_" + maxHp + "_" + armorClass + "_" + Math.round(attackDamage);
	}

	static MonsterRegistry.MonsterStatBlock fromStats(String typeKey, String nameKey, int maxHp, double armor, double attackDamage,
			double width, double height, CreatureType type, boolean flies, boolean fireImmune) {
		int hp = Math.max(1, maxHp);
		int armorClass = 10 + (int) Math.max(0, Math.round(armor));
		int bonus = strengthBonus(attackDamage);

		Map<String, Integer> abilities = new LinkedHashMap<>();
		abilities.put("str", 10 + 2 * bonus);
		abilities.put("dex", flies ? 14 : 10);
		abilities.put("con", 10 + 2 * Math.min(4, hp / 25));
		abilities.put("int", 6);
		abilities.put("wis", 10);
		abilities.put("cha", 6);

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>();
		attacks.add(new MonsterRegistry.MonsterAttack("content.dndsheets.attack.slam", "str", damageDice(attackDamage), "str", "bludgeoning", null, null, 0));

		Map<String, String> affinities = new HashMap<>();
		if (fireImmune) affinities.put("fire", "immune");

		return new MonsterRegistry.MonsterStatBlock(idFor(typeKey, hp, armorClass, attackDamage), nameKey, "minecraft:zombie",
			armorClass, hp, abilities, proficiencyFor(hp), attacks, new ArrayList<>(), affinities, new HashMap<>(), type,
			0, 0, 1, sizeFor(width, height), MonsterRegistry.Appearance.DEFAULT, true, false, flies);
	}

	static MonsterRegistry.MonsterStatBlock forEntity(LivingEntity entity) {
		String typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		double damage = entity.getAttribute(Attributes.ATTACK_DAMAGE) != null ? entity.getAttributeValue(Attributes.ATTACK_DAMAGE) : 2.0;
		CreatureType type;
		MobType mobType = entity.getMobType();
		if (entity instanceof Slime) type = CreatureType.OOZE;
		else if (mobType == MobType.UNDEAD) type = CreatureType.UNDEAD;
		else if (mobType == MobType.ARTHROPOD) type = CreatureType.BEAST;
		else if (mobType == MobType.ILLAGER) type = CreatureType.HUMANOID;
		else type = CreatureType.MONSTROSITY;
		return fromStats(typeKey, entity.getType().getDescriptionId(), (int) Math.ceil(entity.getMaxHealth()),
			entity.getAttributeValue(Attributes.ARMOR), damage, entity.getBbWidth(), entity.getBbHeight(), type,
			entity instanceof FlyingMob, entity.fireImmune());
	}
}
