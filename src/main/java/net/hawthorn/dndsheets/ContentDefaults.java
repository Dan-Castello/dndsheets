package net.hawthorn.dndsheets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * <p>Keeps the mod's built-in content pack up to date inside the world folder.</p>
 *
 * <p>This used to be a <b>one-time</b> seeding, and only if the folder was empty ({@code seedDefaultsIfEmpty}).
 * The effect was that the world's copy froze at the version from the day the save was created: new
 * spells, resistances added to a monster, or slot-level scaling <b>never</b> reached a world that
 * already existed. It was discovered by the symptom: bumping a spell's level did nothing in an ongoing
 * game, because the server kept loading a pack from before that rule.</p>
 *
 * <p>Now the mod's pack has its own name ({@link #FILE}) and gets rewritten on every startup. Whatever
 * the DM writes in any other file in the folder is loaded <b>afterward</b> (see {@code DndPaths.autoLoadAll})
 * and overrides ours by id — that ordering is what makes rewriting it safe.</p>
 *
 * <p>Lives outside {@link DndPaths} for a specific reason: {@code DndPaths} resolves its paths against
 * {@code SheetLoader.GAME_DIR}, which only exists inside the game, so its static initialization blows up
 * outside it. Kept separate, this gets properly tested in the self-test with a temp folder, which is the
 * least the logic deciding which content file wins deserves.</p>
 */
public final class ContentDefaults {

	/**
	 * <p>Reserved name of the mod's pack inside each content folder. <b>Rewritten on every startup</b>,
	 * so it's not a place to write anything by hand: any other {@code .json} in the folder belongs to the
	 * DM and is never touched.</p>
	 */
	public static final String FILE = "mod_defaults.json";

	private ContentDefaults() {
	}

	/**
	 * <p>Brings the mod's pack up to date in {@code dir}, first setting aside the copy the previous
	 * version seeded, if it's still there.</p>
	 *
	 * <p>Deliberately logs nothing: touching {@code DndsheetsMod.LOGGER} initializes the entire mod
	 * class, and with it Forge's network channel, which doesn't exist outside the game. The caller is
	 * responsible for the log message (see {@code DndPaths.refreshDefaultsLogging}).</p>
	 *
	 * @return the old pack that was set aside, or {@code null} if there wasn't one.
	 */
	public static Path refresh(Path dir, String resourceFileName) throws IOException {
		Path retired = retireLegacySeed(dir, resourceFileName);

		try (InputStream in = ContentDefaults.class.getResourceAsStream("/dndsheets/defaults/" + resourceFileName)) {
			if (in != null) Files.copy(in, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
		}
		return retired;
	}

	/**
	 * <p>Sets aside the copy seeded by the old version ({@code spells.json} and friends). Without this it
	 * would keep loading <b>after</b> the new pack and override it entirely by id: exactly the stale
	 * content we came here to fix, now winning on purpose.</p>
	 *
	 * <p>It gets renamed instead of deleted, and only the first time (while the pack with its own name
	 * doesn't exist yet). A DM who had hand-written a file with that name loses nothing: it stays there,
	 * with a {@code .old} extension so it stops auto-loading, and the log message says where it is and
	 * what to do. Past that first time, a file with that name belongs to the DM and is never touched again.</p>
	 */
	private static Path retireLegacySeed(Path dir, String resourceFileName) throws IOException {
		Path legacy = dir.resolve(resourceFileName);
		if (Files.exists(dir.resolve(FILE)) || !Files.exists(legacy)) return null;

		Path retired = dir.resolve(resourceFileName + ".old");
		Files.move(legacy, retired, StandardCopyOption.REPLACE_EXISTING);
		return retired;
	}
}
