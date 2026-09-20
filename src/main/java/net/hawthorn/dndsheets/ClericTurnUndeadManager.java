package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;


/**
 * <p>Cleric Turn Undead (Channel Divinity): every undead within {@value #RADIUS} blocks rolls a Wisdom
 * save against the cleric's spell DC, and whoever fails is <b>frightened</b> for {@value #DURATION_ROUNDS}
 * rounds. Once per rest, short or long.</p>
 *
 * <p>The cleric was the <b>only</b> class with a preset and no resource of its own: barbarian, bard,
 * druid, fighter, sorcerer, ranger, wizard, monk, and paladin each had theirs, and whoever picked cleric
 * just got a plain spellcaster. This is their button.</p>
 *
 * <p>Couldn't be written until monsters had a creature type ({@link CreatureType}): a "turn undead" that
 * can't tell an undead apart is a shove at everyone.</p>
 *
 * <p><b>What's missing</b>: Destroy Undead, the level-5 upgrade that instantly destroys low-CR undead. A
 * stat block in this mod has no CR, and substituting it with HP would make a legendary undead with few
 * HP disappear while a weak one with lots of HP survives: the threshold would be measuring the wrong
 * thing. It needs a new field, not an approximation.</p>
 */
public class ClericTurnUndeadManager {
	/** 30 feet in 5e, at one block per 5 feet. */
	private static final int RADIUS = 6;
	/** 1 minute in 5e = 10 rounds, same as Rage. */
	private static final int DURATION_ROUNDS = 10;
	private static final String CONDITION = "frightened";

	//Same "used/not used" as Second Wind, and for the same reason: Channel Divinity has no duration to
	//track, it's just spent and recovered on rest. On the sheet, so it belongs to the character and
	//survives a restart — see RestResource.

	public static void use(ServerPlayer cleric) {
		if (!RestResource.spend(cleric, RestResource.CHANNEL_DIVINITY)) {
			cleric.sendSystemMessage(Component.translatable("chat.dndsheets.resource.spent_channel").withStyle(ChatFormatting.GRAY));
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(cleric.getStringUUID());
		if (sheet == null) return;

		//Same DC as any of their spells (8 + proficiency + Wisdom): Turn Undead is a cleric ability, not
		//a separate effect with its own numbers.
		int proficiency = CharacterRules.proficiencyBonusFor(SheetLoader.characterLevelOf(sheet, cleric));
		int saveDc = 8 + proficiency + CombatManager.abilityModifier(sheet, "wisdom");
		String clericName = SheetLoader.characterNameOf(sheet, cleric);

		CombatFx.activate(cleric);
		ChatFeedback.broadcast(cleric, Component.translatable("chat.dndsheets.cleric.turn_undead", clericName, saveDc).withStyle(ChatFeedback.RESOURCE));

		int turned = 0;
		AABB box = new AABB(cleric.position(), cleric.position()).inflate(RADIUS);
		for (Entity target : cleric.level().getEntities(cleric, box, entity -> entity.isAlive()
				&& MonsterRegistry.typeOf(entity) == CreatureType.UNDEAD)) {
			if (turnOne(cleric, target, saveDc)) turned++;
		}

		if (turned == 0) {
			//Notified even when nothing actually turned: without this, spending the resource with no
			//undead nearby looks exactly like a use that went wrong, and the cleric can't tell which it was.
			cleric.sendSystemMessage(Component.translatable("chat.dndsheets.resource.turn_undead_none").withStyle(ChatFormatting.GRAY));
		}
	}

	/** @return true if it was actually turned. */
	private static boolean turnOne(ServerPlayer cleric, Entity target, int saveDc) {
		Combatant combatant = Combatant.of(target);
		//Without a stat block there's no Wisdom to roll. Can't happen today (typeOf only returns undead
		//for a mod monster), but leaving it unchecked would turn a future change into a
		//NullPointerException inside a loop.
		if (combatant == null) return false;

		Combatant.SaveRoll save = combatant.rollSave("wis");
		String targetName = MonsterRegistry.statBlockOf(target) != null
			? MonsterRegistry.statBlockOf(target).name() : target.getName().getString();

		if (save.succeeds(saveDc)) {
			ChatFeedback.broadcast(cleric, Component.literal(targetName + " resiste: " + save.formatted() + " vs CD " + saveDc + ".").withStyle(ChatFormatting.GRAY));
			return false;
		}

		//The cleric goes in as the source because "frightened" in 5e depends on WHO is frightening you:
		//you can't approach them, and you have disadvantage while you can see them (see
		//TurnManager.applyEffect and Condition).
		TurnManager.applyEffect(target, CONDITION, "0", DURATION_ROUNDS, cleric);
		CombatFx.spellImpact(target, false, "radiant");
		ChatFeedback.broadcast(cleric, Component.translatable("chat.dndsheets.cleric.turned", targetName, save.formatted(), saveDc).withStyle(ChatFormatting.GOLD));
		return true;
	}

	//Public: RestManager calls it for both rest types — in 5e Channel Divinity recovers on a short rest,
	//same as Second Wind.
	public static void resetOnRest(ServerPlayer player) {
		RestResource.restore(player, RestResource.CHANNEL_DIVINITY);
	}

	//Triggered from AbilityItemDispatcher instead of subscribing to interaction events on its own.
	//Same pattern as the rest of the ability items.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) use(player);
	}

	public static ItemStack buildTurnUndeadStack() {
		return AbilityItem.build(ItemLook.TURN_UNDEAD, "turnUndead", Component.translatable("chat.dndsheets.turn_undead.item_name"),
			Component.translatable("chat.dndsheets.turn_undead.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
