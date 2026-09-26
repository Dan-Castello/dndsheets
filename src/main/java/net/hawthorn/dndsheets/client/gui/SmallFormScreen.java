package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Short form: fill in a few fields and hit <b>Confirm</b> once. Adds to
 * {@link FormPanelScreen} the only things that are its own — the Confirm/Cancel row, the optional
 * Delete row, and the {@link #onConfirm()} that fires the send.</p>
 *
 * <p>The frame, the rows, the fields, and {@code tick} live in the base class: they're shared with
 * {@link SheetAdjustScreen}, which is a control panel (ten independent actions, no Confirm) and so
 * can't inherit from here no matter how much it draws the same parchment.</p>
 *
 * <p>"Cancel" (and "Confirm", once the message is sent) go back to {@code parent} instead of closing
 * the whole menu — same navigation mechanism as ListPickerScreen, see that class.</p>
 */
public abstract class SmallFormScreen extends FormPanelScreen {

	protected SmallFormScreen(Component title, int titleRows, Screen parent) {
		super(title, titleRows, parent);
	}

	/** Called when "Confirm" is pressed, before closing the screen. */
	protected abstract void onConfirm();

	/** true adds its own "Delete" row below Confirm/Cancel — see {@link #onDelete()}. */
	protected boolean showDeleteButton() {
		return false;
	}

	/** Only called if {@link #showDeleteButton()} is true; closes the screen afterward, same as onConfirm. */
	protected void onDelete() {
	}

	protected Component deleteButtonLabel() {
		return Component.translatable("gui.dndsheets.common.delete");
	}

	@Override
	protected final void init() {
		layoutTop();
		buildForm();

		int y = nextRowY();
		//TomeButton, same as ListPickerScreen's rows: the two are the mod's only button factories,
		//so the look gets changed in two places and reaches every screen.
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.confirm"), button -> {
			onConfirm();
			this.onClose();
		}, centerX - formWidth() / 2, y, formWidth() / 2 - 2, FIELD_HEIGHT));

		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.cancel"), button -> this.onClose(),
			centerX + 2, y, formWidth() / 2 - 2, FIELD_HEIGHT));

		formBottom = y + FIELD_HEIGHT + 10;

		//Delete lives in the detail view of whatever is being edited instead of a separate row in the
		//outer list (ManageCustomAttacksScreen/DungeonPieceListScreen/ContentEntryListScreen used to have
		//an extra "Delete: X" row per item — it took up twice the height needed).
		if (showDeleteButton()) {
			int deleteY = nextRowY();
			this.addRenderableWidget(TomeButton.of(deleteButtonLabel(), button ->
				ConfirmScreen.ask(this.title, () -> {
					onDelete();
					this.onClose();
				}), centerX - formWidth() / 2, deleteY, formWidth(), FIELD_HEIGHT));
			formBottom = deleteY + FIELD_HEIGHT + 10;
		}
	}

	@Override
	public final void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		renderPanelChrome(guiGraphics);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		renderFields(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
