package net.hawthorn.dndsheets.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import vazkii.patchouli.api.PatchouliAPI;

/**
 * <p>The only thing that touches the Patchouli API. Not loaded if Patchouli isn't installed: see
 * {@link PatchouliCompat}, which is the one that decides.</p>
 */
final class PatchouliBook {

	private static final ResourceLocation BOOK = new ResourceLocation(PatchouliCompat.BOOK_ID);

	private PatchouliBook() {}

	static boolean openOnClient() {
		//Swallows any failure on purpose: if a Patchouli version changes this or the book failed to
		//load, the Guide still has to open. Returning false falls back to the written book.
		try {
			PatchouliAPI.get().openBookGUI(BOOK);
			return true;
		} catch (RuntimeException | LinkageError e) {
			net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn("dndsheets: Patchouli is installed but the Guide could not be opened ({}). Opening the regular book.", e.toString());
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
