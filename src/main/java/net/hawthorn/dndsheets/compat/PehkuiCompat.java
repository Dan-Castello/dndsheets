package net.hawthorn.dndsheets.compat;

import net.hawthorn.dndsheets.CreatureSize;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

/**
 * <p><b>Optional</b> Pehkui integration. It solves a problem the D&amp;D table has and Minecraft
 * doesn't: in 5e size is a declared property of every creature —Tiny to Gargantuan— and in 1.20.1
 * there is no vanilla attribute that scales an entity (Mojang's {@code generic.scale} arrives in
 * 1.21). With Pehkui installed, a Gargantuan dragon looks four times a human; without it, everything stays
 * exactly as before, which is how the whole bestiary looked until now.</p>
 *
 * <p><b>Why it is two classes and not one.</b> Same reason as {@link CuriosCompat}, and that is why it is
 * written the same way: this one imports not a single Pehkui type, because naming one would make the JVM try to
 * resolve it when loading the class and blow up with {@code NoClassDefFoundError} on any install without
 * Pehkui. Everything that touches its API lives in {@link PehkuiScale}, which is only loaded after checking
 * {@link #isLoaded()}.</p>
 *
 * <p><b>This is not a replacement for {@code MonsterSkins}</b>, it is the other half. The appearance packs
 * resolve <em>which model</em> a monster has by picking another mod's entity; this resolves
 * <em>at what scale</em> whichever one it is gets drawn. An ogre still needs an ogre model; what it no longer
 * needs is for that model to come out of the box measuring what an ogre measures.</p>
 */
public final class PehkuiCompat {

	private PehkuiCompat() {}

	//Resolved only once: ModList doesn't change after startup, and this is queried every time a
	//monster is summoned.
	private static final boolean LOADED = ModList.get().isLoaded("pehkui");

	public static boolean isLoaded() {
		return LOADED;
	}

	/**
	 * <p>Draws the entity at the size its 5e category calls for. It does nothing —and that is the correct
	 * behavior, not a shortcoming— if Pehkui isn't installed or if the size changes nothing
	 * ({@link CreatureSize#UNKNOWN}, Medium and Small are 1.0). A monster from a pack written before
	 * this field existed falls right into that case: it looks like it always did.</p>
	 */
	public static void applySize(Entity entity, CreatureSize size) {
		if (!LOADED || entity == null) return;
		float scale = size.spaceScale();
		if (scale == 1.0f) return;
		PehkuiScale.setScale(entity, scale);
	}
}
