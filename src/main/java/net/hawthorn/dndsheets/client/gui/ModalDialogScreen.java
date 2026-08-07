package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Esqueleto repetido en RestChoiceScreen/RestVoteScreen/DeathSaveScreen: caja de tamaño fijo centrada en
//la pantalla, con botones colocados en coordenadas relativas a esa caja. Ver AUDIT_TECHNICAL.md M-DUP-7.
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

	//x/y relativos a la esquina superior izquierda del diálogo, igual que las coordenadas que ya usaba
	//cada pantalla a mano contra su propio "left"/"top".
	protected Button addModalButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
		return this.addRenderableWidget(Button.builder(message, onPress).bounds(dialogLeft() + x, dialogTop() + y, width, height).build());
	}
}
