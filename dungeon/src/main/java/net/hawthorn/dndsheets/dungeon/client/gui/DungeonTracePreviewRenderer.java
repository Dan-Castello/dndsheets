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
 * <p>Proyecta en el mundo real, mientras {@link DungeonTraceScreen} está abierta, lo mismo que
 * {@link GridToStructure} traduciría al confirmar — la misma idea que el modo de colocación de
 * Litematica: nada de un visor 3D dentro del diálogo, se reutiliza el renderer de bloques de vanilla
 * sobre el mundo que ya está dibujado.</p>
 *
 * <p><b>Tinte fantasma:</b> el modelo del bloque no se manda por {@code renderSingleBlock} (elige él
 * solo el {@code RenderType} del bloque real, sólido para piedra) sino por
 * {@link ModelBlockRenderer#renderModel} contra el buffer de {@link RenderType#translucent()}, envuelto
 * en un {@link GhostVertexConsumer} que fuerza el alfa de cada vértice — así CUALQUIER bloque, sea
 * translúcido o no en el juego real, sale semitransparente en la proyección.</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DungeonTracePreviewRenderer {

	private DungeonTracePreviewRenderer() {}

	private static final int DOOR_COLOR = 0x4A90D9;
	private static final int START_COLOR = 0x2ECC71;
	//0-255: bastante transparente para leerse como "todavía no está" sin dejar de reconocerse la forma.
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

		//Cancela la traslación de cámara que ya trae event.getPoseStack() para volver a coordenadas de
		//mundo absolutas, y desde ahí se traslada a cada bloque como si nada — el mismo truco que usa
		//cualquier renderer que se engancha a este evento en vez de al pipeline de chunks.
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
			case CONNECTOR, START -> DungeonPiecePlacer.DEFAULT_OBJECT; //inalcanzable: las dos ramas de arriba las capturan antes
		};
	}

	/**
	 * <p>Envoltorio de {@link VertexConsumer} que fuerza el alfa de cada vértice, dejando el color que
	 * calculó el modelo tal cual. Solo hace falta sobrescribir {@code color(int,int,int,int)}: todos los
	 * demás métodos de conveniencia de la interfaz (el {@code vertex(...)} de 14 argumentos que de verdad
	 * usa {@code ModelBlockRenderer}, {@code color(float,float,float,float)}, etc.) son {@code default} y
	 * terminan llamando a este mismo método sobre {@code this} — interceptarlo acá alcanza para toda
	 * ruta de dibujado, no hace falta duplicar esa cadena.</p>
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
