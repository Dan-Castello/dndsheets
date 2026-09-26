package net.hawthorn.dndsheets.init;

import net.hawthorn.dndsheets.BarbarianRageManager;
import net.hawthorn.dndsheets.BardInspirationManager;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.CounterspellManager;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DruidWildShapeManager;
import net.hawthorn.dndsheets.FighterSecondWindManager;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.PaladinSmiteManager;
import net.hawthorn.dndsheets.RangerHunterMarkManager;
import net.hawthorn.dndsheets.RestManager;
import net.hawthorn.dndsheets.ShieldManager;
import net.hawthorn.dndsheets.SorcererMetamagicManager;
import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.TurnItemManager;
import net.hawthorn.dndsheets.command.MonsterCommand;
import net.hawthorn.dndsheets.command.NotesCommand;
import net.hawthorn.dndsheets.command.SpellCommand;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * <p>Creative-inventory tab with all of the mod's tools: the DM Wand, loaded custom weapons (see
 * {@link Config#customWeaponIds}), one staff per loaded spell (see {@link SpellRegistry#ids}), and one
 * summon card per loaded monster (see {@link MonsterRegistry#ids}) that works like a vanilla spawn egg.
 * Recomputed every time the tab is opened, so a fresh {@code /dndweapons load} or {@code /dndmonsters
 * load} shows up without restarting.</p>
 *
 * <p>Every item is added through {@link #safeAccept}: Forge requires every entry to have count 1, and
 * above all, its internal creative-tab deduplication (meant for the "the enchanted book shows up twice"
 * case) compares by known vanilla components (like attribute modifiers on swords/axes), NOT by our own
 * NBT tag. Two different custom weapons that reuse the SAME base item (e.g. two weapons built on
 * "minecraft:iron_sword") can collide there and crash the whole game when opening the creative
 * inventory — a single malformed content JSON shouldn't be able to do that, so any failure adding ONE
 * entry is logged and skipped, without bringing down the rest.</p>
 */
public class DndsheetsModCreativeTab {
	public static final DeferredRegister<CreativeModeTab> REGISTRY = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DndsheetsMod.MODID);

	public static final RegistryObject<CreativeModeTab> DND_TAB = REGISTRY.register("dnd_tab", () -> CreativeModeTab.builder()
		.title(Component.translatable("itemGroup.dndsheets.dnd_tab"))
		.icon(() -> new ItemStack(DndsheetsModItems.TOKEN.get()))
		.displayItems((params, output) -> {
			//The Patchouli manual, if installed: it can be kept in the inventory, which is half the point
			//compared to a screen that only opens via a button.
			ItemStack guide = net.hawthorn.dndsheets.compat.PatchouliCompat.bookStack();
			if (!guide.isEmpty()) safeAccept(output, guide);
			safeAccept(output, MonsterCommand.buildDmToolStack());
			safeAccept(output, MonsterCommand.buildMoveToolStack());
			safeAccept(output, net.hawthorn.dndsheets.RoleplayManager.buildRoleplayStack());
			safeAccept(output, NotesCommand.buildNotebookStack());
			safeAccept(output, RestManager.buildRestKitStack());
			safeAccept(output, TurnItemManager.buildNextTurnStack());
			safeAccept(output, TurnItemManager.buildUndoTurnStack());
			safeAccept(output, BarbarianRageManager.buildRageItemStack());
			safeAccept(output, FighterSecondWindManager.buildSecondWindStack());
			safeAccept(output, BardInspirationManager.buildInspirationStack());
			safeAccept(output, DruidWildShapeManager.buildWildShapeStack());
			safeAccept(output, SorcererMetamagicManager.buildTwinnedSpellStack());
			safeAccept(output, PaladinSmiteManager.buildDivineSmiteStack());
			safeAccept(output, RangerHunterMarkManager.buildHunterMarkStack());
			safeAccept(output, ShieldManager.buildShieldStack());
			safeAccept(output, CounterspellManager.buildCounterspellStack());
			for (String weaponId : Config.customWeaponIds()) {
				safeAccept(output, Config.buildWeaponStack(weaponId, 1));
			}
			for (String spellId : SpellRegistry.ids()) {
				SpellRegistry.Spell spell = SpellRegistry.get(spellId);
				if (spell != null) safeAccept(output, SpellCommand.buildStaffStack(spellId, spell, null));
			}
			for (String monsterId : MonsterRegistry.ids()) {
				safeAccept(output, MonsterRegistry.buildSpawnCard(monsterId));
			}
		})
		.build());

	private static void safeAccept(CreativeModeTab.Output output, ItemStack stack) {
		try {
			output.accept(stack);
		} catch (RuntimeException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: could not show {} in the creative tab ({}). It probably shares its base item with another loaded entry.", stack.getHoverName().getString(), e.getMessage());
		}
	}
}
