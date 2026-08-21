package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Esqueleto repetido en RestChoiceScreen/RestVoteScreen/DeathSaveScreen: caja de tamaño fijo centrada en
//la pantalla, con botones colocados en coordenadas relativas a esa caja.
public abstract class ModalDialogScreen extends Screen {
	private final int dialogWidth;
	private final int dialogHeight;

	protected ModalDialogScreen(Component title, int dialogWidth, int dialogHeight) {
		super(title);
		this.dialogWidth = dialogWidth;
		this.dialogHeight = dialogHeight;
	}

	protected int dialogLeft() {
		return (this.width - dialogWidth) / 2;
	}

	protected int dialogTop() {
		return (this.height - dialogHeight) / 2;
	}

	//Primera línea de render() en cada subclase, en vez de un renderBackground() suelto: dibuja el fondo
	//borroso vanilla MÁS un panel con borde detrás de la caja del diálogo (ver GuiStyle), para que no sea
	//solo texto y botones flotando encima del mundo. Antes solo DeathSaveScreen dibujaba un panel propio.
	protected final void renderPanel(GuiGraphics guiGraphics) {
		this.renderBackground(guiGraphics);
		GuiStyle.panel(guiGraphics, dialogLeft(), dialogTop(), dialogLeft() + dialogWidth, dialogTop() + dialogHeight);
	}

	//x/y relativos a la esquina superior izquierda del diálogo, igual que las coordenadas que ya usaba
	//cada pantalla a mano contra su propio "left"/"top".
	protected Button addModalButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
		//TomeButton: tercer y último sitio del mod que fabrica botones, para que los diálogos no se queden
		//con el gris de vanilla mientras listas y formularios llevan el pergamino.
		return this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(
			message, onPress, dialogLeft() + x, dialogTop() + y, width, height));
	}
}
