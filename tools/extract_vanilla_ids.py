# -*- coding: utf-8 -*-
"""Regenerates the list of vanilla ids used by the self-test.

    python tools/extract_vanilla_ids.py

It comes from the 1.20.1 client's en_us.json that ForgeGradle already has downloaded, so nothing has to be
downloaded and the game doesn't have to start. If the Minecraft version is ever bumped, run it again.

It exists because of a real failure: one of the 26 weapons had "minecraft:mace" as its base item, which is from 1.21.
In 1.20.1 it doesn't resolve, the weapon fell back to a stick, and the only thing seen was a warning in the
client log when opening the creative tab.
"""
import io
import json
import os
import zipfile

CLIENT = os.path.expanduser(
    '~/.gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar')
OUT = os.path.join('src', 'test', 'resources', 'vanilla_ids_1_20_1.txt')

if __name__ == '__main__':
    lang = json.loads(zipfile.ZipFile(CLIENT).read('assets/minecraft/lang/en_us.json').decode('utf-8'))
    ids = set()
    for prefix, kind in [('item.minecraft.', 'item'), ('block.minecraft.', 'item'),
                         ('entity.minecraft.', 'entity')]:
        for key in lang:
            if key.startswith(prefix) and key.count('.') == 2:
                ids.add('%s/%s' % (kind, key.split('.', 2)[2]))

    with io.open(OUT, 'w', encoding='utf-8') as f:
        f.write('# Item/block and entity ids that exist in Minecraft 1.20.1.\n'
                '# Taken from the 1.20.1 client en_us.json; regenerated with tools/extract_vanilla_ids.py.\n'
                '# It lets the self-test catch an id from another version (it happened with minecraft:mace, which is from 1.21).\n')
        f.write('\n'.join(sorted(ids)) + '\n')
    print('escritos %d ids en %s' % (len(ids), OUT))
