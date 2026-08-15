package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.command.MonsterCommand;
import net.hawthorn.dndsheets.command.NotesCommand;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p>Los ítems "fijos" que hasta ahora solo se podían entregar por comando (ver
 * {@code command.SheetCommand}'s {@code give*Item}, {@code command.MonsterCommand.dmtool/movetool},
 * {@code command.NotesCommand.give}) — cada uno ya tenía un builder público reusado tal cual, esto solo
 * les da un nombre común para que {@code client.gui.GiveItemListScreen}/{@code network.GiveItemMessage}
 * los traten de forma genérica en vez de un mensaje de red por ítem.</p>
 */
public enum GiveableItem {
	RESTKIT("Kit de Descanso", () -> List.of(RestManager.buildRestKitStack())),
	RAGE("Tótem de Furia (Bárbaro)", () -> List.of(BarbarianRageManager.buildRageItemStack())),
	SECOND_WIND("Segundo Aliento (Guerrero)", () -> List.of(FighterSecondWindManager.buildSecondWindStack())),
	INSPIRATION("Cuerno de Inspiración (Bardo)", () -> List.of(BardInspirationManager.buildInspirationStack())),
	WILD_SHAPE("Forma Salvaje (Druida)", () -> List.of(DruidWildShapeManager.buildWildShapeStack())),
	METAMAGIC("Metamagia: Hechizo Gemelo (Hechicero)", () -> List.of(SorcererMetamagicManager.buildTwinnedSpellStack())),
	SMITE("Castigo Divino (Paladín)", () -> List.of(PaladinSmiteManager.buildDivineSmiteStack())),
	HUNTER_MARK("Marca del Cazador (Explorador)", () -> List.of(RangerHunterMarkManager.buildHunterMarkStack())),
	SHIELD("Escudo (reacción)", () -> List.of(ShieldManager.buildShieldStack())),
	COUNTERSPELL("Contrahechizo (reacción)", () -> List.of(CounterspellManager.buildCounterspellStack())),
	TURN_ITEMS("Ítems de turno (siguiente/deshacer)", () -> List.of(TurnItemManager.buildNextTurnStack(), TurnItemManager.buildUndoTurnStack())),
	DM_WAND("Vara de DM", () -> List.of(MonsterCommand.buildDmToolStack())),
	MOVE_WAND("Vara de Movimiento", () -> List.of(MonsterCommand.buildMoveToolStack())),
	NOTEBOOK("Cuaderno del DM", () -> List.of(NotesCommand.buildNotebookStack()));

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
