package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Formulario corto: se rellenan unos campos y se pulsa <b>Confirmar</b> una vez. Añade a
 * {@link FormPanelScreen} lo único que es suyo — la fila Confirmar/Cancelar, la de Borrar opcional, y el
 * {@link #onConfirm()} que dispara el envío.</p>
 *
 * <p>El marco, las filas, los campos y el {@code tick} viven en la base: los comparte con
 * {@link SheetAdjustScreen}, que es un panel de control (diez acciones sueltas, ningún Confirmar) y por
 * eso no puede heredar de aquí por mucho que dibuje el mismo pergamino.</p>
 *
 * <p>"Cancelar" (y "Confirmar", una vez enviado el mensaje) vuelven a {@code parent} en vez de cerrar todo
 * el menú — mismo mecanismo de navegación que ListPickerScreen, ver esa clase.</p>
 */
public abstract class SmallFormScreen extends FormPanelScreen {

	protected SmallFormScreen(Component title, int titleRows, Screen parent) {
		super(title, titleRows, parent);
	}

	/** Se llama al pulsar "Confirmar", antes de cerrar la pantalla. */
	protected abstract void onConfirm();

	/** true agrega una fila "Borrar" propia bajo Confirmar/Cancelar — ver {@link #onDelete()}. */
	protected boolean showDeleteButton() {
		return false;
	}

	/** Solo se llama si {@link #showDeleteButton()} es true; cierra la pantalla después, igual que onConfirm. */
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
		//TomeButton, igual que las filas de ListPickerScreen: los dos son las únicas fábricas de botones
		//del mod, así que el aspecto se cambia en dos sitios y llega a todas las pantallas.
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.confirm"), button -> {
			onConfirm();
			this.onClose();
		}, centerX - formWidth() / 2, y, formWidth() / 2 - 2, FIELD_HEIGHT));

		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.cancel"), button -> this.onClose(),
			centerX + 2, y, formWidth() / 2 - 2, FIELD_HEIGHT));

		formBottom = y + FIELD_HEIGHT + 10;

		//Borrar vive en el detalle de lo que se está editando en vez de una fila aparte en la lista de
		//afuera (ManageCustomAttacksScreen/DungeonPieceListScreen/ContentEntryListScreen usaban antes una
		//fila "Borrar: X" extra por cada elemento — ocupaba el doble de alto que hacía falta).
		if (showDeleteButton()) {
			int deleteY = nextRowY();
			this.addRenderableWidget(TomeButton.of(deleteButtonLabel(), button -> {
				onDelete();
				this.onClose();
			}, centerX - formWidth() / 2, deleteY, formWidth(), FIELD_HEIGHT));
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
