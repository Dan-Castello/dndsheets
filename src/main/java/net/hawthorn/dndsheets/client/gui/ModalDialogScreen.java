package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Skeleton repeated across RestChoiceScreen/RestVoteScreen/DeathSaveScreen: a fixed-size box centered on
//the screen, with buttons placed at coordinates relative to that box.
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

	//First line of render() in every subclass, instead of a bare renderBackground(): draws vanilla's
	//blurred background PLUS a bordered panel behind the dialog box (see GuiStyle), so it isn't just text
	//and buttons floating over the world. Previously only DeathSaveScreen drew its own panel.
	protected final void renderPanel(GuiGraphics guiGraphics) {
		this.renderBackground(guiGraphics);
		GuiStyle.panel(guiGraphics, dialogLeft(), dialogTop(), dialogLeft() + dialogWidth, dialogTop() + dialogHeight);
	}

	//x/y relative to the dialog's top-left corner, same as the coordinates each screen already used by
	//hand against its own "left"/"top".
	protected Button addModalButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
		//TomeButton: the third and last place in the mod that manufactures buttons, so dialogs don't
		//stay stuck with vanilla gray while lists and forms get the parchment look.
		return this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(
			message, onPress, dialogLeft() + x, dialogTop() + y, width, height));
	}
}
