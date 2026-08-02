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
import net.hawthorn.dndsheets.command.SpellCommand;
import net.hawthorn.dndsheets.command.WeaponCommand;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * <p>Pestaña de inventario creativo con todas las herramientas del mod: la Vara de DM, armas
 * personalizadas cargadas (ver {@link Config#customWeaponIds}), un báculo por cada hechizo cargado (ver
 * {@link SpellRegistry#ids}), y una carta de invocación por cada monstruo cargado (ver
 * {@link MonsterRegistry#ids}) que funciona como un huevo de spawn vanilla. Se recalcula cada vez que se
 * abre la pestaña, así que un {@code /dndweapons load} o {@code /dndmonsters load} nuevo aparece sin reiniciar.</p>
 *
 * <p>Cada ítem se agrega con {@link #safeAccept}: Forge exige que toda entrada tenga count 1 y, sobre
 * todo, su deduplicación interna de la pestaña creativa (pensada para el caso de "el libro encantado
 * aparece dos veces") compara por componentes vanilla conocidos (como los modificadores de atributo de
 * espadas/hachas), NO por nuestra etiqueta NBT propia. Dos armas personalizadas distintas que reusen el
 * MISMO ítem base (p.ej. dos armas sobre "minecraft:iron_sword") pueden colisionar ahí y tirar el juego
 * entero al abrir el inventario creativo — un solo JSON de contenido mal armado no debería poder hacer
 * eso, así que cualquier fallo al agregar UNA entrada se registra y se salta, sin tumbar el resto.</p>
 */
public class DndsheetsModCreativeTab {
	public static final DeferredRegister<CreativeModeTab> REGISTRY = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DndsheetsMod.MODID);

	public static final RegistryObject<CreativeModeTab> DND_TAB = REGISTRY.register("dnd_tab", () -> CreativeModeTab.builder()
		.title(Component.translatable("itemGroup.dndsheets.dnd_tab"))
		.icon(() -> new ItemStack(Items.BLAZE_ROD))
		.displayItems((params, output) -> {
			safeAccept(output, MonsterCommand.buildDmToolStack());
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
				safeAccept(output, WeaponCommand.buildWeaponStack(weaponId, 1));
			}
			for (String spellId : SpellRegistry.ids()) {
				SpellRegistry.Spell spell = SpellRegistry.get(spellId);
				if (spell != null) safeAccept(output, SpellCommand.buildStaffStack(spellId, spell, "minecraft:blaze_rod"));
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
			DndsheetsMod.LOGGER.warn("dndsheets: no pude mostrar {} en la pestaña creativa ({}). Probablemente comparte ítem base con otra entrada cargada.", stack.getHoverName().getString(), e.getMessage());
		}
	}
}
