package net.hawthorn.dndsheets.compat;

import net.minecraft.world.entity.Entity;
import virtuoel.pehkui.api.ScaleTypes;

/**
 * <p>The only class in the mod that names Pehkui types. It is loaded only when {@link PehkuiCompat#isLoaded()}
 * has already said yes — same pair and same reason as {@link CuriosSlots} with Curios: Java loads classes
 * lazily, and that laziness is what turns the check into real isolation.</p>
 *
 * <p>{@code ScaleTypes.BASE} is used and not the per-axis types (width/height): a Large monster is large in all
 * three dimensions, and BASE is also the type Pehkui's other calculations hang off —collision
 * box, reach, eye height—, so the creature doesn't just look big, it takes up room.</p>
 */
final class PehkuiScale {

	private PehkuiScale() {}

	static void setScale(Entity entity, float scale) {
		//setScale and not setTargetScale: this is called when the creature is summoned, and an animated transition from
		//size 1 would make every monster appear to be growing. The target value is set too so
		//Pehkui doesn't send it back to 1 on the next interpolation tick.
		ScaleTypes.BASE.getScaleData(entity).setScale(scale);
		ScaleTypes.BASE.getScaleData(entity).setTargetScale(scale);
	}
}
