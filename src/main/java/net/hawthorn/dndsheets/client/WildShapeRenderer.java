package net.hawthorn.dndsheets.client;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Draws a transformed druid as the beast they're in, client-side and <b>without a single mixin</b>.
 * Forge fires {@link RenderPlayerEvent.Pre} for exactly this purpose: the player's drawing gets canceled
 * and something else is drawn in its place.</p>
 *
 * <p>Not needing a mixin is the reason this was written from scratch instead of porting an existing
 * transformation mod: the ones out there patch {@code PlayerRenderer} because their Minecraft version had
 * no such event, and {@code checkPortabilityCoupling} fails the build if a mixin sneaks in here — the
 * cost of a future port is exactly what that test protects.</p>
 *
 * <p><b>Why the offsets are zero.</b> {@code EntityRenderDispatcher.render} does {@code pushPose()} and
 * {@code translate(x, y, z)} <em>before</em> calling the renderer, and this event fires inside that
 * renderer — meaning with the pose stack already positioned on the player. Passing the position again
 * would draw the beast at twice its distance from the camera.</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WildShapeRenderer {

	private static final Map<UUID, String> shapes = new HashMap<>();
	//One fake entity per type, reused: building one per frame and per player means building sixty per
	//second and throwing them away, which is exactly how you get a mod that stutters.
	private static final Map<EntityType<?>, LivingEntity> dummies = new HashMap<>();
	//Last tickCount at which walkAnimation.update() was advanced for each beast type (see below): it
	//only needs to happen once per tick, not once per frame.
	private static final Map<EntityType<?>, Integer> lastAnimatedTick = new HashMap<>();

	private WildShapeRenderer() {
	}

	/**
	 * <p>An empty id means they've reverted to their own form. It's sent by {@code WildShapeWatcher}, and
	 * what it sends is the beast's BASE ENTITY (e.g. {@code minecraft:wolf}), not the monster id: the
	 * monster registry only lives on the server, and this client may be running as a separate process
	 * that never saw it load.</p>
	 */
	public static void setShape(UUID player, String baseEntityId) {
		if (baseEntityId == null || baseEntityId.isEmpty()) shapes.remove(player);
		else shapes.put(player, baseEntityId);
	}

	@SubscribeEvent
	public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
		Player player = event.getEntity();
		LivingEntity dummy = dummyFor(shapes.get(player.getUUID()));
		if (dummy == null) return;

		//The beast is drawn with the player's pose: without this it always faces north and never walks.
		dummy.setPos(player.getX(), player.getY(), player.getZ());
		dummy.yBodyRot = player.yBodyRot;
		dummy.yBodyRotO = player.yBodyRotO;
		dummy.yHeadRot = player.yHeadRot;
		dummy.yHeadRotO = player.yHeadRotO;
		dummy.setYRot(player.getYRot());
		dummy.yRotO = player.yRotO;
		dummy.setXRot(player.getXRot());
		dummy.xRotO = player.xRotO;
		dummy.tickCount = player.tickCount;

		//Real bug in the legs: update() does NOT take a position, it takes a TARGET VELOCITY it smooths
		//toward (and the second argument is the smoothing factor, not the render's partialTick) — this
		//used to be called with walkAnimation.position() (an accumulator that just grows over time, with
		//no relation to how fast you're actually walking) and a smoothing of 1.0 (no smoothing at all), so
		//the beast's "velocity" stayed pinned to that ever-growing number and the model flailed its legs
		//nonstop, whether you were walking or not. On top of that this ran once per FRAME instead of once
		//per TICK like real Minecraft does (update() accumulates position on every call), so the higher
		//the FPS, the faster the animation looked — hence the "flailing" even while standing still, with
		//just a hint of residual velocity. The last tick already animated per beast type is stored (the
		//dummy is reused across players of the same species) so the same tick never gets accumulated twice.
		EntityType<?> dummyType = dummy.getType();
		if (!Integer.valueOf(player.tickCount).equals(lastAnimatedTick.get(dummyType))) {
			dummy.walkAnimation.update(player.walkAnimation.speed(), 0.4f);
			lastAnimatedTick.put(dummyType, player.tickCount);
		}
		dummy.setShiftKeyDown(player.isShiftKeyDown());
		dummy.setInvisible(player.isInvisible());

		event.setCanceled(true);
		Minecraft.getInstance().getEntityRenderDispatcher().render(dummy, 0, 0, 0,
			player.getYRot(), event.getPartialTick(), event.getPoseStack(), event.getMultiBufferSource(),
			event.getPackedLight());
	}

	@Nullable
	private static LivingEntity dummyFor(String baseEntityId) {
		if (baseEntityId == null) return null;

		ResourceLocation loc = ResourceLocation.tryParse(baseEntityId);
		EntityType<?> type = loc == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(loc);
		if (type == null) return null;

		//computeIfAbsent won't do: create() can return null (an entity that can't be constructed on the
		//client), and caching that null would leave the slot occupied forever without ever retrying.
		LivingEntity cached = dummies.get(type);
		if (cached != null) return cached;

		Entity created = type.create(Minecraft.getInstance().level);
		if (!(created instanceof LivingEntity living)) return null;
		dummies.put(type, living);
		return living;
	}
}
