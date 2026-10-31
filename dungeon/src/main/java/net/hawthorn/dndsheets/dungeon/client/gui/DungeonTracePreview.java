package net.hawthorn.dndsheets.dungeon.client.gui;

import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * <p>Litematica-style placement for a drawn room: "Preview in world" in {@link DungeonTraceScreen} closes the
 * editor but keeps the drawing here, anchored to a fixed block, so the DM can walk around it with the
 * world in full view. {@link DungeonTracePreviewRenderer} draws it; this class holds the draft, the keys
 * that move it and the on-screen hint.</p>
 *
 * <p>The keys only do something while a preview is active and no screen is open, so arrows and PageUp/Down
 * stay free the rest of the time.</p>
 */
@Mod.EventBusSubscriber(modid = net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DungeonTracePreview {
	private static final String CATEGORY = "key.categories.dndsheets_dungeon";
	static final KeyMapping EDIT = new KeyMapping("key.dndsheets.preview_edit", GLFW.GLFW_KEY_ENTER, CATEGORY);
	static final KeyMapping FORWARD = new KeyMapping("key.dndsheets.preview_forward", GLFW.GLFW_KEY_UP, CATEGORY);
	static final KeyMapping BACK = new KeyMapping("key.dndsheets.preview_back", GLFW.GLFW_KEY_DOWN, CATEGORY);
	static final KeyMapping LEFT = new KeyMapping("key.dndsheets.preview_left", GLFW.GLFW_KEY_LEFT, CATEGORY);
	static final KeyMapping RIGHT = new KeyMapping("key.dndsheets.preview_right", GLFW.GLFW_KEY_RIGHT, CATEGORY);
	static final KeyMapping UP = new KeyMapping("key.dndsheets.preview_up", GLFW.GLFW_KEY_PAGE_UP, CATEGORY);
	static final KeyMapping DOWN = new KeyMapping("key.dndsheets.preview_down", GLFW.GLFW_KEY_PAGE_DOWN, CATEGORY);

	/** The editor being previewed with the editor closed; null = no preview outside the editor. */
	static DungeonTraceScreen draft;
	/** Blocks of the piece that would replace something already in the world (set by the renderer each frame). */
	static int conflicts;

	private DungeonTracePreview() {}

	static void start(DungeonTraceScreen screen) {
		draft = screen;
		Minecraft.getInstance().setScreen(null);
	}

	static void stop() {
		draft = null;
		conflicts = 0;
	}

	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (event.phase != TickEvent.Phase.END || draft == null || minecraft.screen != null || minecraft.player == null) return;
		//Forward/back/left/right follow where the DM is looking, as in Litematica: "up arrow" moves it away.
		Direction facing = minecraft.player.getDirection();
		while (FORWARD.consumeClick()) draft.moveAnchor(facing.getStepX(), 0, facing.getStepZ());
		while (BACK.consumeClick()) draft.moveAnchor(-facing.getStepX(), 0, -facing.getStepZ());
		while (RIGHT.consumeClick()) draft.moveAnchor(facing.getClockWise().getStepX(), 0, facing.getClockWise().getStepZ());
		while (LEFT.consumeClick()) draft.moveAnchor(facing.getCounterClockWise().getStepX(), 0, facing.getCounterClockWise().getStepZ());
		while (UP.consumeClick()) draft.moveAnchor(0, 1, 0);
		while (DOWN.consumeClick()) draft.moveAnchor(0, -1, 0);
		if (EDIT.consumeClick()) minecraft.setScreen(draft);
	}

	@SubscribeEvent
	public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
		stop();
	}

	@Mod.EventBusSubscriber(modid = net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
	public static final class Registration {
		private Registration() {}

		@SubscribeEvent
		public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
			for (KeyMapping key : new KeyMapping[]{EDIT, FORWARD, BACK, LEFT, RIGHT, UP, DOWN}) event.register(key);
		}

		//Top-centre hint while previewing: what is shown, which keys move it, and how much it would overwrite.
		@SubscribeEvent
		public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
			event.registerAboveAll("dungeon_preview", (gui, guiGraphics, partialTick, width, height) -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (draft == null || minecraft.screen != null || minecraft.options.hideGui) return;
				int y = 4;
				guiGraphics.drawCenteredString(minecraft.font, Component.translatable("hud.dndsheets.dungeon_preview.title", draft.pieceId()), width / 2, y, GuiStyle.TITLE_COLOR);
				y += 11;
				guiGraphics.drawCenteredString(minecraft.font, Component.translatable("hud.dndsheets.dungeon_preview.keys",
					FORWARD.getTranslatedKeyMessage(), BACK.getTranslatedKeyMessage(), LEFT.getTranslatedKeyMessage(), RIGHT.getTranslatedKeyMessage(),
					UP.getTranslatedKeyMessage(), DOWN.getTranslatedKeyMessage(), EDIT.getTranslatedKeyMessage()), width / 2, y, GuiStyle.SUBTITLE_COLOR);
				if (conflicts > 0) {
					y += 11;
					guiGraphics.drawCenteredString(minecraft.font, Component.translatable("hud.dndsheets.dungeon_preview.conflicts", conflicts), width / 2, y, GuiStyle.WARN_COLOR);
				}
			});
		}
	}
}
