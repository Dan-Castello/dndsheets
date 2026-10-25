
package net.hawthorn.dndsheets.init;

import net.hawthorn.dndsheets.client.gui.DmPanelScreen;
import net.hawthorn.dndsheets.client.gui.GrimoireScreen;
import org.lwjgl.glfw.GLFW;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.api.distmarker.Dist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.network.StaffBindMessage;
import net.minecraftforge.client.event.InputEvent;

@Mod.EventBusSubscriber(modid = net.hawthorn.dndsheets.DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = {Dist.CLIENT})
public class DndsheetsModKeyMappings {
	public static final KeyMapping CHARACTER = new KeyMapping("key.dndsheets.character", GLFW.GLFW_KEY_H, "key.categories.dndsheets") {
		private boolean isDownOld = false;

		@Override
		public void setDown(boolean isDown) {
			super.setDown(isDown);
			if (isDownOld != isDown && isDown) {

				net.hawthorn.dndsheets.client.gui.PlayerSheetScreen.toggle();
			}
			isDownOld = isDown;
		}
	};

	//The key always opens the panel: the client has no cheap way to know whether Solo mode
	//(Config.soloMode()) is on — unlike permission level, which the server does sync to it — so who's
	//allowed to see it is no longer filtered here. The real gate lives server-side, on every message the
	//panel sends (DndsheetsMod.canActAsDm / NetworkUtil.handleOnServerAsDm) — a player without permission
	//and without solo mode can open the panel, but every action they attempt is still rejected just like
	//before.
	public static final KeyMapping DM_PANEL = new KeyMapping("key.dndsheets.dmpanel", GLFW.GLFW_KEY_P, "key.categories.dndsheets") {
		private boolean isDownOld = false;

		@Override
		public void setDown(boolean isDown) {
			super.setDown(isDown);
			if (isDownOld != isDown && isDown && Minecraft.getInstance().player != null) {
				DmPanelScreen.open();
			}
			isDownOld = isDown;
		}
	};

	//Straight to the Spellbook, without going through the sheet (H) first: it doesn't depend on anything
	//that only lives there (see GrimoireScreen), so opening it standalone is just as valid as opening it
	//from the "Spellbook" button.
	public static final KeyMapping GRIMOIRE = new KeyMapping("key.dndsheets.grimoire", GLFW.GLFW_KEY_G, "key.categories.dndsheets") {
		private boolean isDownOld = false;

		@Override
		public void setDown(boolean isDown) {
			super.setDown(isDown);
			if (isDownOld != isDown && isDown) {
				GrimoireScreen.open(null);
			}
			isDownOld = isDown;
		}
	};

	//Quick cast: repeats the last thing cast from the Spellbook without opening it. Not a favorites bar —
	//that would be its own list on the sheet, with its own networking and its own screen — but the
	//convenience actually asked for in combat: the same cantrip, again, no menu. To bind a SPECIFIC spell
	//to something permanent there's already the reconfigurable staff (see GrimoireScreen "Bind to staff").
	public static final KeyMapping QUICK_CAST = new KeyMapping("key.dndsheets.quickcast", GLFW.GLFW_KEY_R, "key.categories.dndsheets") {
		private boolean isDownOld = false;

		@Override
		public void setDown(boolean isDown) {
			super.setDown(isDown);
			if (isDownOld != isDown && isDown) {
				GrimoireScreen.castLast();
			}
			isDownOld = isDown;
		}
	};

	//Cycle the active spell of the staff in hand (same as Shift+scroll, see KeyEventListener).
	public static final KeyMapping STAFF_NEXT = staffKey("key.dndsheets.staff_next", GLFW.GLFW_KEY_X, StaffBindMessage.Action.NEXT);
	public static final KeyMapping STAFF_PREV = staffKey("key.dndsheets.staff_prev", GLFW.GLFW_KEY_Z, StaffBindMessage.Action.PREV);

	private static KeyMapping staffKey(String name, int key, StaffBindMessage.Action action) {
		return new KeyMapping(name, key, "key.categories.dndsheets") {
			private boolean isDownOld = false;

			@Override
			public void setDown(boolean isDown) {
				super.setDown(isDown);
				if (isDownOld != isDown && isDown) sendStaff(action);
				isDownOld = isDown;
			}
		};
	}

	//The server re-validates everything; the client check only avoids sending packets for nothing.
	static boolean sendStaff(StaffBindMessage.Action action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.screen != null || !(SpellRegistry.isConfigurableStaff(mc.player.getMainHandItem()) || net.hawthorn.dndsheets.MonsterRegistry.isConfigurableCard(mc.player.getMainHandItem()))) return false;
		DndsheetsMod.PACKET_HANDLER.sendToServer(new StaffBindMessage("", action));
		return true;
	}

	@SubscribeEvent
	public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(CHARACTER);
		event.register(DM_PANEL);
		event.register(GRIMOIRE);
		event.register(QUICK_CAST);
		event.register(STAFF_NEXT);
		event.register(STAFF_PREV);
	}

	@Mod.EventBusSubscriber(modid = net.hawthorn.dndsheets.DndsheetsMod.MODID, value = {Dist.CLIENT})
	public static class KeyEventListener {
		@SubscribeEvent
		public static void onClientTick(TickEvent.ClientTickEvent event) {
			if (Minecraft.getInstance().screen == null) {
				CHARACTER.consumeClick();
				DM_PANEL.consumeClick();
				GRIMOIRE.consumeClick();
				QUICK_CAST.consumeClick();
				STAFF_NEXT.consumeClick();
				STAFF_PREV.consumeClick();
			}
		}

		//Shift+scroll with a reconfigurable staff in hand cycles its spells instead of the hotbar.
		@SubscribeEvent
		public static void onScroll(InputEvent.MouseScrollingEvent event) {
			if (!net.minecraft.client.gui.screens.Screen.hasShiftDown() || event.getScrollDelta() == 0) return;
			if (sendStaff(event.getScrollDelta() > 0 ? StaffBindMessage.Action.PREV : StaffBindMessage.Action.NEXT)) event.setCanceled(true);
		}
	}
}
