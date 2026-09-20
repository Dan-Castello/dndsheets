package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import com.mojang.math.Axis;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>The caster's hand while casting. A spell with a casting time (see {@code CastingManager}) no longer
 * resolves the instant it's requested, and with nothing to accompany it that second of waiting reads as
 * the mod having frozen: the hand rises, pulls back, and trembles a little while the particle charge
 * builds up in front of it.</p>
 *
 * <p><b>First person only, and that's a decision, not an oversight.</b> Setting the arm pose in third
 * person would mean writing to {@code HumanoidModel.rightArmPose}, and {@code PlayerRenderer} overwrites
 * it in {@code setModelProperties} AFTER {@code RenderPlayerEvent.Pre} — meaning there's nowhere to hook
 * in without a mixin, and this mod deliberately carries no mixins (see "Portability" in
 * PROJECT_CONTEXT.md, enforced by {@code checkPortabilityCoupling}). What other players see is the
 * particle charge from {@code CombatFx.spellCharge}, which is server-side and therefore visible to
 * everyone: the story reads the same without touching portability.</p>
 *
 * <p>State arrives through the field patch {@code CastingManager} already sends to the client sheet
 * ({@code castingSpell}/{@code castingTicks}/{@code castingUntil}) — the same pipeline as concentration
 * and pending advantage, with no network message of its own.</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class SpellCastAnimator {

	/**
	 * <p>How far into casting we are, from 0 to 1, or -1 if nothing is being cast. Computed from the end
	 * tick and the duration instead of receiving a patch every tick: the network already delivered both
	 * numbers once, at the start, and the client works out the rest on its own.</p>
	 */
	private static float progress(float partialTick) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null || !sheet.has("castingUntil") || !sheet.has("castingTicks")) return -1;

		net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return -1;

		int total = sheet.get("castingTicks").getAsInt();
		if (total <= 0) return -1;
		float left = sheet.get("castingUntil").getAsLong() - (level.getGameTime() + partialTick);
		//Nothing is drawn outside the interval: if the "I'm done" patch were ever lost, the hand would
		//return to its place on its own instead of staying raised forever.
		if (left <= 0 || left > total) return -1;
		return 1.0f - left / total;
	}

	@SubscribeEvent
	public static void onRenderHand(RenderHandEvent event) {
		float progress = progress(event.getPartialTick());
		if (progress < 0) return;

		//Main hand only: casting with both at once reads as a nervous tic, not as a gesture.
		if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

		//Pulls back and up as the charge progresses, with a cap: past a certain point the hand leaves the
		//frame and stops conveying anything.
		event.getPoseStack().translate(0.0, progress * 0.22, progress * 0.30);
		event.getPoseStack().mulPose(Axis.XP.rotationDegrees(-progress * 45.0f));

		//A small, fast tremor that GROWS with the charge: it's what separates "holding something" from
		//"holding something that's a strain". Without it, the still raised hand reads as a frozen frame.
		float time = (Minecraft.getInstance().level.getGameTime() + event.getPartialTick()) * 0.9f;
		float tremor = (float) Math.sin(time) * 0.012f * progress;
		event.getPoseStack().translate(tremor, tremor * 0.5f, 0.0);
	}
}
