package net.hawthorn.dndsheets.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.Icons;
import net.hawthorn.dndsheets.network.TurnStateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Condition glyphs floating above every combatant of the active encounter, so "who is stunned?" is read on
 * the table instead of asked in chat. The data is the roster the HUD already receives
 * ({@link TurnHudState}); outside combat, or for anything not in the turn order, nothing is drawn (invariant 9).</p>
 *
 * <p>Drawn from {@link RenderLivingEvent.Post} as a camera-facing quad per glyph, the same billboarding the
 * vanilla nametag uses: no mixin, nothing replaced. The row sits above the nametag ({@link NameTagHp}).</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = Dist.CLIENT)
public class ConditionBillboards {
	private static final int MAX_GLYPHS = 5;
	private static final float GLYPH = 0.30f;      //World size of each glyph, in blocks.
	private static final float GAP = 0.04f;
	private static final float ABOVE_HEAD = 0.95f; //Over the nametag (which sits at ~0.5).

	@SubscribeEvent
	public static void onRenderLiving(RenderLivingEvent.Post<?, ?> event) {
		if (!TurnHudState.active()) return;
		LivingEntity entity = event.getEntity();
		//Your own row is on the HUD; in first person the camera sits inside your head.
		if (entity == Minecraft.getInstance().player) return;
		TurnStateMessage.RosterRow row = TurnHudState.myRow(entity.getId());
		if (row == null || row.defeated() || row.conditions().isEmpty()) return;

		List<ResourceLocation> glyphs = new ArrayList<>();
		for (String label : row.conditions()) {
			ResourceLocation glyph = Icons.condition(label);
			if (glyph != null && !glyphs.contains(glyph) && glyphs.size() < MAX_GLYPHS) glyphs.add(glyph);
		}
		if (glyphs.isEmpty()) return;

		PoseStack poseStack = event.getPoseStack();
		poseStack.pushPose();
		poseStack.translate(0.0, entity.getBbHeight() + ABOVE_HEAD, 0.0);
		poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
		poseStack.scale(-1.0f, -1.0f, 1.0f); //Same flip the vanilla nametag does: local +y is down the screen.

		float step = GLYPH + GAP;
		float startX = -(glyphs.size() - 1) * step / 2.0f;
		for (int i = 0; i < glyphs.size(); i++) {
			quad(poseStack, event.getMultiBufferSource().getBuffer(RenderType.entityTranslucent(glyphs.get(i))), startX + i * step);
		}
		poseStack.popPose();
	}

	private static void quad(PoseStack poseStack, VertexConsumer buffer, float centerX) {
		Matrix4f pose = poseStack.last().pose();
		Matrix3f normal = poseStack.last().normal();
		float half = GLYPH / 2.0f;
		vertex(buffer, pose, normal, centerX - half, -half, 0.0f, 0.0f);
		vertex(buffer, pose, normal, centerX - half, half, 0.0f, 1.0f);
		vertex(buffer, pose, normal, centerX + half, half, 1.0f, 1.0f);
		vertex(buffer, pose, normal, centerX + half, -half, 1.0f, 0.0f);
	}

	private static void vertex(VertexConsumer buffer, Matrix4f pose, Matrix3f normal, float x, float y, float u, float v) {
		buffer.vertex(pose, x, y, 0.0f).color(255, 255, 255, 255).uv(u, v)
			.overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(normal, 0.0f, 0.0f, 1.0f).endVertex();
	}
}
