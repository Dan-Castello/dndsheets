
package net.hawthorn.dndsheets.init;

import net.hawthorn.dndsheets.client.gui.DmPanelScreen;
import net.hawthorn.dndsheets.client.gui.GrimoireScreen;
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

				DndsheetsMod.PACKET_HANDLER.sendToServer(new CharacterSheetOpenMessage());
				CharacterSheetOpenMessage.pressAction(Minecraft.getInstance().player);
			}
			isDownOld = isDown;
		}
	};

	//La tecla abre el panel siempre: el cliente no tiene forma barata de saber si el modo solo
	//(Config.soloMode()) está encendido —a diferencia del nivel de permiso, que sí le sincroniza el
	//servidor—, así que ya no filtramos acá quién puede verlo. El gate real vive del lado servidor, en
	//cada mensaje que el panel manda (DndsheetsMod.canActAsDm / NetworkUtil.handleOnServerAsDm) — un
	//jugador sin permiso y sin modo solo puede abrir el panel, pero cada acción que intente se rechaza
	//igual que antes.
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

	//Directo al Grimorio, sin pasar por la ficha (H) primero: no depende de nada que solo viva ahí (ver
	//GrimoireScreen), así que abrirlo suelto es tan válido como abrirlo desde el botón "Grimorio".
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

	//Lanzado rápido: repite lo último lanzado desde el Grimorio sin abrirlo. No es una barra de favoritos
	//—eso sería una lista propia en la hoja, con su red y su pantalla— sino la comodidad que de verdad se
	//pide en combate: el mismo truco, otra vez, sin menú. Para atar un hechizo CONCRETO a algo permanente
	//ya está el báculo reconfigurable (ver GrimoireScreen "Vincular al báculo").
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

	@SubscribeEvent
	public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(CHARACTER);
		event.register(DM_PANEL);
		event.register(GRIMOIRE);
		event.register(QUICK_CAST);
	}

	@Mod.EventBusSubscriber({Dist.CLIENT})
	public static class KeyEventListener {
		@SubscribeEvent
		public static void onClientTick(TickEvent.ClientTickEvent event) {
			if (Minecraft.getInstance().screen == null) {
				CHARACTER.consumeClick();
				DM_PANEL.consumeClick();
				GRIMOIRE.consumeClick();
				QUICK_CAST.consumeClick();
			}
		}
	}
}
