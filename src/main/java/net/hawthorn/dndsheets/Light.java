package net.hawthorn.dndsheets;

/**
 * <p>The three 5e light levels —bright light, dim light, and darkness— read from the actual light level
 * of the block the creature is standing in. Darkness is "heavily obscured" in the rulebook: whoever is
 * inside it is <b>effectively blinded</b>, and that already means something in this mod because
 * {@link Condition#BLINDED} exists with all its consequences since Phase 0.</p>
 *
 * <p>It's the same move as {@link Cover}, on the other half of the environment. Foundry sells dynamic
 * lighting and Roll20 fog-of-war layers <em>to simulate</em> what's visible from where; here the torch is
 * already lit and the cave is already dark, with the numbers Minecraft itself computes tick by tick. All
 * that was missing was for the table to read them.</p>
 *
 * <p><b>Where the cutoffs fall.</b> Minecraft gives a light level from 0 to 15 already blended (blocks and
 * sky, time of day applied), so the thresholds are chosen against that number rather than a scale of
 * their own: 8 and up is bright light, 4 to 7 is dim light, and below that is darkness. That open-sky
 * night falls into dim light isn't coincidence or fine-tuning — it's exactly what the SRD says about
 * moonlight, and it falls out just from using the vanilla number instead of inventing one.</p>
 *
 * <p>Dim light gives disadvantage on Perception checks that rely on sight — see
 * {@link VisionManager#inDimLight} and its only caller, {@code RollAnnouncerProcedure}, which turns it
 * into actual disadvantage only for that skill, not for the other 17.</p>
 */
public enum Light {
	BRIGHT, DIM, DARK;

	/** From this Minecraft light level onward, it's bright light. */
	static final int BRIGHT_FROM = 8;
	/** From this level onward, it's dim light; below it, darkness. */
	static final int DIM_FROM = 4;

	public static Light fromLightLevel(int lightLevel) {
		if (lightLevel >= BRIGHT_FROM) return BRIGHT;
		if (lightLevel >= DIM_FROM) return DIM;
		return DARK;
	}

	/**
	 * <p>What someone with darkvision sees: darkness counts as dim light for them, and dim light stays
	 * dim light. It's literally the SRD's wording, which is why it's written as a transformation from one
	 * level to another instead of a loose {@code if} inside the blinding rule — that way the trait is
	 * applied in exactly one place and any rule written tomorrow about {@link Light} already respects it.</p>
	 *
	 * <p>The <em>range</em> (60 feet, 120 for deep gnomes) doesn't enter here because this rule looks at
	 * where you are, not how far you can see: standing in a dark cave, having a range of 60 or 120 gives
	 * the same result. The feet are still stored on the sheet, which is where a player reads them.</p>
	 */
	public Light withDarkvision(boolean hasDarkvision) {
		return hasDarkvision && this == DARK ? DIM : this;
	}

	/** In darkness you are "heavily obscured": blind for rules purposes. */
	public boolean blinds() {
		return this == DARK;
	}
}
