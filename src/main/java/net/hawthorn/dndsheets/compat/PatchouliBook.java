package net.hawthorn.dndsheets.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import vazkii.patchouli.api.PatchouliAPI;

/**
 * <p>Lo único que toca la API de Patchouli. No se carga si Patchouli no está instalado: ver
 * {@link PatchouliCompat}, que es quien decide.</p>
 */
final class PatchouliBook {

	private static final ResourceLocation BOOK = new ResourceLocation(PatchouliCompat.BOOK_ID);

	private PatchouliBook() {}

	static boolean openOnClient() {
		//Se traga cualquier fallo a propósito: si una versión de Patchouli cambia esto o el libro no
		//llegó a cargar, la Guía tiene que seguir abriéndose. Devolver false manda al libro escrito.
		try {
			PatchouliAPI.get().openBookGUI(BOOK);
			return true;
		} catch (RuntimeException | LinkageError e) {
			net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn("dndsheets: Patchouli está instalado pero no pude abrir la Guía ({}). Abro el libro de siempre.", e.toString());
			return false;
		}
	}

	static ItemStack bookStack() {
		try {
			return PatchouliAPI.get().getBookStack(BOOK);
		} catch (RuntimeException | LinkageError e) {
			return ItemStack.EMPTY;
		}
	}
}
