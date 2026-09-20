package net.hawthorn.dndsheets;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <p>5e cover: ducking behind something raises AC and Dexterity saves. Half cover gives +2,
 * three-quarters +5, and total cover means you can't even be targeted.</p>
 *
 * <p>This is the rule the mod was in the best position in the world to have and didn't. Roll20 and Foundry
 * compute visibility with polygons and fog layers <em>to simulate</em> a 3D space; here the 3D space
 * IS the game. A stone wall at half height is already there, with real geometry, and until now it
 * meant nothing: you'd shoot someone crouched behind a block exactly the same as someone standing in
 * open ground.</p>
 *
 * <p><b>How it's measured.</b> Five rays from the attacker's eye to five points on the target's body, and
 * cover comes out of how many hit a block. The points sit inside the volume rather than at its
 * corners, so the ground under the target's feet doesn't count as cover. And the two side points are
 * taken <b>perpendicular to the line of fire</b>, not along the world axes: with the box's corners
 * aligned to the axes, firing diagonally measured the wrong width and a wall corner gave half
 * cover or none depending on which way the map happened to face.</p>
 */
public enum Cover {
	NONE(0),
	HALF(2),
	THREE_QUARTERS(5),
	/**
	 * <p>No line of sight: in 5e you can't even be chosen as a target, and that's what
	 * {@link #blocksTargeting()} decides.</p>
	 *
	 * <p>Its bonus is the three-quarters one and not infinite because there's a path where it still lands
	 * anyway: an arrow that already hit. If the projectile got through, cover wasn't total no matter what
	 * five rays say, so it's charged as the best partial cover instead of making impossible a hit the
	 * world just allowed.</p>
	 */
	TOTAL(5);

	/** How many body points are sampled. Odd on purpose: no possible tie at the midpoint. */
	private static final int SAMPLES = 5;
	/** How much of the body is spanned from the center. Less than half, to avoid grazing floor or ceiling. */
	private static final double BODY_INSET = 0.4;

	private final int bonus;

	Cover(int bonus) {
		this.bonus = bonus;
	}

	/** What it adds to the target's AC, and also to its Dexterity saves (in 5e it's the same number). */
	public int bonus() {
		return bonus;
	}

	public boolean blocksTargeting() {
		return this == TOTAL;
	}

	public String langKey() {
		return switch (this) {
			case NONE -> "";
			case HALF -> "chat.dndsheets.combat.cover_half";
			case THREE_QUARTERS -> "chat.dndsheets.combat.cover_three_quarters";
			case TOTAL -> "chat.dndsheets.combat.cover_total";
		};
	}

	/**
	 * <p>Degree of cover from how many body points end up blocked. Pure and separate from the world
	 * so it can be pinned down in the self-test: it's the rule's table, and a wrong table turns
	 * cover into a wall or vice versa.</p>
	 */
	static Cover fromBlocked(int blocked, int total) {
		if (total <= 0 || blocked <= 0) return NONE;
		if (blocked >= total) return TOTAL;
		//"Up to half blocked" is half cover; more than that, three-quarters. The SRD states it in
		//fractions of the body, so it's compared in fractions rather than a number of rays, and changing
		//SAMPLES doesn't rewrite the rule.
		return blocked * 2 <= total ? HALF : THREE_QUARTERS;
	}

	/** Cover the terrain gives the target against this attacker. */
	public static Cover between(Entity attacker, Entity target) {
		Level level = attacker.level();
		Vec3 from = attacker.getEyePosition(1.0f);
		AABB box = target.getBoundingBox();
		Vec3 center = box.getCenter();

		Vec3 toTarget = center.subtract(from);
		if (toTarget.lengthSqr() < 1.0E-6) return NONE; //On top of the target: there's no line to measure.
		Vec3 direction = toTarget.normalize();
		//Horizontal perpendicular to the line of fire. If firing straight up/down there are no sides to
		//measure and the cross product comes out null: then both side points fall on the center, which
		//is exactly correct (seen from above, nothing blocks the target's width).
		Vec3 side = direction.cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1.0E-6 ? Vec3.ZERO : side.normalize().scale((box.getXsize() + box.getZsize()) / 2 * BODY_INSET);
		double lift = box.getYsize() * BODY_INSET;

		Vec3[] points = {
			center,
			center.add(0, lift, 0),
			center.add(0, -lift, 0),
			center.add(side),
			center.subtract(side),
		};

		int blocked = 0;
		for (Vec3 point : points) {
			if (isBlocked(level, from, point, attacker)) blocked++;
		}
		return fromBlocked(blocked, SAMPLES);
	}

	/**
	 * <p>Is there a solid block between these two points? The one place in the mod that asks this: cover
	 * uses it, and so does {@code SpellCastManager} to decide who an area reaches, which used to carry its
	 * own copy of the same {@code clip}.</p>
	 */
	public static boolean isBlocked(Level level, Vec3 from, Vec3 to, Entity ignore) {
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ignore));
		return hit.getType() != HitResult.Type.MISS;
	}
}
