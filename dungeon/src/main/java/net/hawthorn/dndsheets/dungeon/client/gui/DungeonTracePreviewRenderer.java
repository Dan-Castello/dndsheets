package net.hawthorn.dndsheets.dungeon.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.hawthorn.dndsheets.dungeon.DungeonPiecePlacer;
import net.hawthorn.dndsheets.dungeon.GridToStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * <p>Projects into the real world, while {@link DungeonTraceScreen} is open, the same thing
 * {@link GridToStructure} would translate on confirm — the same idea as Litematica's placement mode:
 * no 3D viewport inside the dialog, it reuses vanilla's own block renderer over the world that's
 * already drawn.</p>
 *
 * <p><b>Ghost tint:</b> the block's model isn't sent through {@code renderSingleBlock} (it picks the
 * real block's {@code RenderType} on its own, solid for stone) but through
 * {@link ModelBlockRenderer#renderModel} against the {@link RenderType#translucent()} buffer, wrapped
 * in a {@link GhostVertexConsumer} that forces every vertex's alpha — so ANY block, translucent or not
 * in the real game, comes out semi-transparent in the projection.</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DungeonTracePreviewRenderer {

	private DungeonTracePreviewRenderer() {}

	private static final int DOOR_COLOR = 0x4A90D9;
	private static final int START_COLOR = 0x2ECC71;
	//0-255: transparent enough to read as "not there yet" while the shape is still recognizable.
	private static final int GHOST_ALPHA = 120;

	@SubscribeEvent
	public static void onRenderLevel(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
		if (!(Minecraft.getInstance().screen instanceof DungeonTraceScreen trace)) return;
		Player player = Minecraft.getInstance().player;
		if (player == null) return;

		BlockPos origin = BlockPos.containing(player.position()).offset(trace.originDx(), 0, trace.originDz());
		List<GridToStructure.PlacedBlock> blocks = GridToStructure.render(
			trace.gridSnapshot(), trace.pieceHeight(), trace.optionsSnapshot());
		if (blocks.isEmpty()) return;

		PoseStack poseStack = event.getPoseStack();
		Vec3 cam = event.getCamera().getPosition();
		MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
		BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();

		//Cancels the camera translation already baked into event.getPoseStack() to get back to absolute
		//world coordinates, and from there translates to each block normally — the same trick used by
		//any renderer that hooks into this event instead of the chunk pipeline.
		poseStack.pushPose();
		poseStack.translate(-cam.x, -cam.y, -cam.z);

		for (GridToStructure.PlacedBlock block : blocks) {
			BlockPos at = origin.offset(block.x(), block.y(), block.z());
			poseStack.pushPose();
			poseStack.translate(at.getX(), at.getY(), at.getZ());

			if (block.kind() == GridToStructure.Kind.CONNECTOR || block.kind() == GridToStructure.Kind.START) {
				int color = block.kind() == GridToStructure.Kind.START ? START_COLOR : DOOR_COLOR;
				LevelRenderer.renderLineBox(poseStack, bufferSource.getBuffer(RenderType.lines()), 0, 0, 0, 1, 1, 1,
					((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f, 1f);
			} else {
				BlockState state = stateFor(block, trace);
				BakedModel model = blockRenderer.getBlockModel(state);
				VertexConsumer ghost = new GhostVertexConsumer(bufferSource.getBuffer(RenderType.translucent()), GHOST_ALPHA);
				blockRenderer.getModelRenderer().renderModel(poseStack.last(), ghost, state, model,
					1f, 1f, 1f, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
			}

			poseStack.popPose();
		}

		poseStack.popPose();
		bufferSource.endBatch();
	}

	private static BlockState stateFor(GridToStructure.PlacedBlock block, DungeonTraceScreen trace) {
		return switch (block.kind()) {
			case FLOOR -> DungeonPiecePlacer.resolveOrDefault(trace.floorBlockId(), DungeonPiecePlacer.DEFAULT_FLOOR);
			case WALL -> DungeonPiecePlacer.resolveOrDefault(trace.wallBlockId(), DungeonPiecePlacer.DEFAULT_WALL);
			case OBJECT -> DungeonPiecePlacer.objectStateFor(block.blockId(), block.facing());
			case CONNECTOR, START -> DungeonPiecePlacer.DEFAULT_OBJECT; //unreachable: the two branches above already catch these first
		};
	}

	/**
	 * <p>A {@link VertexConsumer} wrapper that forces every vertex's alpha, leaving the color the model
	 * computed untouched. Only {@code color(int,int,int,int)} needs to be overridden: all the
	 * interface's other convenience methods (the 14-argument {@code vertex(...)} that
	 * {@code ModelBlockRenderer} actually uses, {@code color(float,float,float,float)}, etc.) are
	 * {@code default} and end up calling this same method on {@code this} — intercepting it here is
	 * enough for every drawing path, no need to duplicate that chain.</p>
	 */
	private static final class GhostVertexConsumer implements VertexConsumer {
		private final VertexConsumer delegate;
		private final int alpha;

		GhostVertexConsumer(VertexConsumer delegate, int alpha) {
			this.delegate = delegate;
			this.alpha = alpha;
		}

		@Override
		public VertexConsumer vertex(double x, double y, double z) {
			delegate.vertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alphaIn) {
			delegate.color(red, green, blue, alpha);
			return this;
		}

		@Override
		public VertexConsumer uv(float u, float v) {
			delegate.uv(u, v);
			return this;
		}

		@Override
		public VertexConsumer overlayCoords(int u, int v) {
			delegate.overlayCoords(u, v);
			return this;
		}

		@Override
		public VertexConsumer uv2(int u, int v) {
			delegate.uv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			delegate.normal(x, y, z);
			return this;
		}

		@Override
		public void endVertex() {
			delegate.endVertex();
		}

		@Override
		public void defaultColor(int red, int green, int blue, int alphaIn) {
			delegate.defaultColor(red, green, blue, alpha);
		}

		@Override
		public void unsetDefaultColor() {
			delegate.unsetDefaultColor();
		}
	}
}
