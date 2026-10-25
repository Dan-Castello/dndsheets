package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>What the character sheet screen shows for class resources (Rage, ki, Wild Shape...): which pools this character
 * has, how many uses they have and how many are left. A read-only view over the same sheet fields the managers write
 * ({@link RestResource}), public because {@code CharacterRules} and {@code RestResource} are package-private on purpose and the
 * screen lives in the client package. It computes nothing the rules don't already compute: it asks them.</p>
 */
public final class ResourceView {

	/** @param icon {@code category/name} under {@code textures/gui/icons}; @param nameKey translation key; @param max {@code -1} = unlimited. */
	public record Pool(String icon, String nameKey, int left, int max) {}

	private ResourceView() {
	}

	/** The pools that apply to this sheet's class(es), in a stable order. Empty for a class with none. */
	public static List<Pool> poolsFor(JsonObject sheet) {
		List<Pool> pools = new ArrayList<>();
		if (sheet == null) return pools;

		int barbarian = CharacterRules.classLevelOf(sheet, "barbarian", "bárbaro");
		if (barbarian > 0) {
			int max = CharacterRules.rageUsesFor(barbarian);
			boolean unlimited = max == Integer.MAX_VALUE;
			pools.add(new Pool("resource/rage", "gui.dndsheets.sheet.res_rage",
				unlimited ? -1 : RestResource.usesLeft(sheet, RestResource.RAGE_USES, max), unlimited ? -1 : max));
		}
		int monk = CharacterRules.classLevelOf(sheet, "monk", "monje");
		if (CharacterRules.kiPointsFor(monk) > 0) {
			int max = CharacterRules.kiPointsFor(monk);
			pools.add(new Pool("resource/ki", "gui.dndsheets.sheet.res_ki", RestResource.usesLeft(sheet, RestResource.KI_USES, max), max));
		}
		int sorcerer = CharacterRules.classLevelOf(sheet, "sorcerer", "hechicero");
		if (CharacterRules.sorceryPointsFor(sorcerer) > 0) {
			int max = CharacterRules.sorceryPointsFor(sorcerer);
			pools.add(new Pool("resource/sorcery", "gui.dndsheets.sheet.res_sorcery", RestResource.usesLeft(sheet, RestResource.SORCERY_POINTS, max), max));
		}
		if (CharacterRules.classLevelOf(sheet, "druid", "druida") > 0) {
			pools.add(new Pool("resource/wild_shape", "gui.dndsheets.sheet.res_wild_shape", RestResource.usesLeft(sheet, RestResource.WILD_SHAPE_USES, 2), 2));
		}
		if (CharacterRules.classLevelOf(sheet, "bard", "bardo") > 0) {
			int max = CharacterRules.bardicInspirationUsesFor(sheet);
			pools.add(new Pool("resource/inspiration", "gui.dndsheets.sheet.res_inspiration", RestResource.usesLeft(sheet, RestResource.INSPIRATION_USES, max), max));
		}
		int fighter = CharacterRules.classLevelOf(sheet, "fighter", "guerrero");
		if (fighter > 0) {
			pools.add(new Pool("resource/second_wind", "gui.dndsheets.sheet.res_second_wind", RestResource.isSpent(sheet, RestResource.SECOND_WIND) ? 0 : 1, 1));
			if (fighter >= 2) {
				pools.add(new Pool("action/surge", "gui.dndsheets.sheet.res_action_surge", RestResource.isSpent(sheet, RestResource.ACTION_SURGE) ? 0 : 1, 1));
			}
		}
		if (CharacterRules.classLevelOf(sheet, "cleric", "clérigo") > 0 || CharacterRules.classLevelOf(sheet, "cleric", "clerigo") > 0) {
			pools.add(new Pool("resource/turn_undead", "gui.dndsheets.sheet.res_channel_divinity", RestResource.isSpent(sheet, RestResource.CHANNEL_DIVINITY) ? 0 : 1, 1));
		}
		return pools;
	}
}
