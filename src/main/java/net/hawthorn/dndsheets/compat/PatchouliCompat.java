package net.hawthorn.dndsheets.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * <p><b>Optional</b> integration with Patchouli, the standard mod for in-game manuals.</p>
 *
 * <p><b>What changes.</b> The Guide used to exist as a vanilla written book: 26 pages in a row, no
 * index, no search, and no way to jump back to a specific one. Fine for reading through once, not for
 * looking something up mid-session, which is when it's actually needed. With Patchouli installed, the
 * same Guide opens as a manual with categories, an index, search, and bookmarks, and also becomes an
 * item the player can keep in their inventory.</p>
 *
 * <p><b>The text isn't duplicated.</b> The Patchouli book's entries point to the <em>same</em> language
 * keys the written book uses ({@code gui.dndsheets.guide.page.*}, with {@code i18n: true} in
 * {@code book.json}). Writing the guide twice would have guaranteed the two versions would drift apart
 * on the very first fix; this way, a corrected page is corrected in both at once. The self-test checks
 * that each page appears in exactly one entry.</p>
 *
 * <p><b>Why there are two classes</b>, same as in {@link CuriosCompat}: this one doesn't import a single
 * Patchouli type, so loading it on an install without Patchouli doesn't blow up with
 * {@code NoClassDefFoundError}. Everything that touches its API lives in {@link PatchouliBook}, which is
 * only loaded after checking {@link #isLoaded()}.</p>
 */
public final class PatchouliCompat {

	/** The book defined by {@code data/dndsheets/patchouli_books/guide/book.json}. */
	public static final String BOOK_ID = "dndsheets:guide";

	private PatchouliCompat() {}

	private static final boolean LOADED = ModList.get().isLoaded("patchouli");

	public static boolean isLoaded() {
		return LOADED;
	}

	/**
	 * <p>Opens the Guide on the client. Returns {@code false} if Patchouli isn't present, in which case
	 * the caller opens the regular written book instead — the Guide is never unavailable just because a
	 * mod is missing.</p>
	 */
	public static boolean openOnClient() {
		if (!LOADED) return false;
		return PatchouliBook.openOnClient();
	}

	/** The manual item, or empty without Patchouli. So the player can keep it. */
	public static ItemStack bookStack() {
		if (!LOADED) return ItemStack.EMPTY;
		return PatchouliBook.bookStack();
	}
}
