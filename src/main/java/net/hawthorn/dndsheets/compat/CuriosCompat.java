package net.hawthorn.dndsheets.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * <p><b>Optional</b> integration with the Curios API. Solves a problem the D&amp;D tabletop doesn't have
 * but Minecraft does: there's no ring, necklace, cloak, or belt slot, so a Ring of Protection had no
 * natural way of being "worn." With Curios installed, magic items are carried in their real slots;
 * without it, everything keeps working exactly as before via attunement.</p>
 *
 * <p><b>Why there are two classes and not one.</b> This one doesn't import a single Curios type: if it
 * did, the JVM would try to resolve them when loading it and blow up with {@code NoClassDefFoundError}
 * on any install without Curios — which is exactly what "soft dependency" has to avoid. Everything that
 * touches its API lives in {@link CuriosSlots}, which is only loaded after checking {@link #isLoaded()},
 * because Java loads classes lazily. The check isn't a courtesy: it's what makes the separation work.</p>
 *
 * <p>In the build, Curios is pulled in as {@code compileOnly} (plus {@code runtimeOnly} just to be able
 * to test it in the dev environment), and in {@code mods.toml} as a dependency with
 * {@code mandatory=false}. It's never packaged inside the published jar.</p>
 */
public final class CuriosCompat {

	private CuriosCompat() {}

	//Resolved once: ModList doesn't change after startup, and this is checked on every AC calculation of
	//every attack.
	private static final boolean LOADED = ModList.get().isLoaded("curios");

	public static boolean isLoaded() {
		return LOADED;
	}

	/**
	 * <p>What the player is carrying in Curios slots, or an empty list if Curios isn't installed.
	 * Returning empty instead of failing is what lets the caller treat both cases the same way.</p>
	 */
	public static List<ItemStack> equippedStacks(Player player) {
		if (!LOADED) return List.of();
		return CuriosSlots.equippedStacks(player);
	}
}
