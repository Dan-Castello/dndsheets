package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.command.MonsterCommand;
import net.hawthorn.dndsheets.command.NotesCommand;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p>The "fixed" items that until now could only be handed out by command (see
 * {@code command.SheetCommand}'s {@code give*Item}, {@code command.MonsterCommand.dmtool/movetool},
 * {@code command.NotesCommand.give}) — each one already had a public builder reused as-is, this just
 * gives them a common name so {@code client.gui.GiveItemListScreen}/{@code network.GiveItemMessage} can
 * treat them generically instead of one network message per item.</p>
 */
public enum GiveableItem {
	RESTKIT("gui.dndsheets.giveable.restkit", () -> List.of(RestManager.buildRestKitStack())),
	RAGE("gui.dndsheets.giveable.rage", () -> List.of(BarbarianRageManager.buildRageItemStack())),
	SECOND_WIND("gui.dndsheets.giveable.second_wind", () -> List.of(FighterSecondWindManager.buildSecondWindStack())),
	INSPIRATION("gui.dndsheets.giveable.inspiration", () -> List.of(BardInspirationManager.buildInspirationStack())),
	WILD_SHAPE("gui.dndsheets.giveable.wild_shape", () -> List.of(DruidWildShapeManager.buildWildShapeStack())),
	METAMAGIC("gui.dndsheets.giveable.metamagic", () -> List.of(SorcererMetamagicManager.buildTwinnedSpellStack())),
	SMITE("gui.dndsheets.giveable.smite", () -> List.of(PaladinSmiteManager.buildDivineSmiteStack())),
	TURN_UNDEAD("gui.dndsheets.giveable.turn_undead", () -> List.of(ClericTurnUndeadManager.buildTurnUndeadStack())),
	HUNTER_MARK("gui.dndsheets.giveable.hunter_mark", () -> List.of(RangerHunterMarkManager.buildHunterMarkStack())),
	SHIELD("gui.dndsheets.giveable.shield", () -> List.of(ShieldManager.buildShieldStack())),
	COUNTERSPELL("gui.dndsheets.giveable.counterspell", () -> List.of(CounterspellManager.buildCounterspellStack())),
	TURN_ACTIONS("gui.dndsheets.giveable.turn_actions", () -> List.of(TurnActionManager.buildTurnActionStack())),
	HELP_ACTION("gui.dndsheets.giveable.help_action", () -> List.of(HelpActionManager.buildHelpStack())),
	TURN_ITEMS("gui.dndsheets.giveable.turn_items", () -> List.of(TurnItemManager.buildNextTurnStack(), TurnItemManager.buildUndoTurnStack())),
	DM_WAND("gui.dndsheets.giveable.dm_wand", () -> List.of(MonsterCommand.buildDmToolStack())),
	MOVE_WAND("gui.dndsheets.giveable.move_wand", () -> List.of(MonsterCommand.buildMoveToolStack())),
	NOTEBOOK("gui.dndsheets.giveable.notebook", () -> List.of(NotesCommand.buildNotebookStack())),
	SHOVE("gui.dndsheets.giveable.shove", () -> List.of(ShoveManager.buildShoveStack()));

	private final String label;
	private final Supplier<List<ItemStack>> stacks;

	GiveableItem(String label, Supplier<List<ItemStack>> stacks) {
		this.label = label;
		this.stacks = stacks;
	}

	public String label() {
		return label;
	}

	public List<ItemStack> stacks() {
		return stacks.get();
	}
}
