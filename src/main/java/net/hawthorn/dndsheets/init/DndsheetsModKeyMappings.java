
package net.hawthorn.dndsheets.init;

import net.hawthorn.dndsheets.client.gui.DmPanelScreen;
import net.hawthorn.dndsheets.network.CharacterSheetOpenMessage;
import org.lwjgl.glfw.GLFW;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.api.distmarker.Dist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

import net.hawthorn.dndsheets.DndsheetsMod;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = {Dist.CLIENT})
public class DndsheetsModKeyMappings {
	public static final KeyMapping CHARACTER = new KeyMapping("key.dndsheets.character", GLFW.GLFW_KEY_H, "key.categories.dndsheets") {
		private boolean isDownOld = false;

		@Override
		public void setDown(boolean isDown) {
			super.setDown(isDown);
			if (isDownOld != isDown && isDown) {

				DndsheetsMod.PACKET_HANDLER.sendToServer(new CharacterSheetOpenMessage(0, 0));
				CharacterSheetOpenMessage.pressAction(Minecraft.getInstance().player, 0, 0);
			}
			isDownOld = isDown;
		}
	};

	//Solo abre el Panel de DM si el propio cliente ya sabe que es operador (nivel de permiso sincronizado
	//por el servidor) — un jugador normal que pulse la tecla no ve nada. El servidor vuelve a comprobarlo
	//en cada mensaje que el panel manda (defensa en profundidad, un cliente modificado no basta).
	public static final KeyMapping DM_PANEL = new KeyMapping("key.dndsheets.dmpanel", GLFW.GLFW_KEY_P, "key.categories.dndsheets") {
		private boolean isDownOld = false;

		@Override
		public void setDown(boolean isDown) {
			super.setDown(isDown);
			if (isDownOld != isDown && isDown && Minecraft.getInstance().player != null && Minecraft.getInstance().player.hasPermissions(2)) {
				DmPanelScreen.open();
			}
			isDownOld = isDown;
		}
	};

	@SubscribeEvent
	public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(CHARACTER);
		event.register(DM_PANEL);
	}

	@Mod.EventBusSubscriber({Dist.CLIENT})
	public static class KeyEventListener {
		@SubscribeEvent
		public static void onClientTick(TickEvent.ClientTickEvent event) {
			if (Minecraft.getInstance().screen == null) {
				CHARACTER.consumeClick();
				DM_PANEL.consumeClick();
			}
		}
	}
}
