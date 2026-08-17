# -*- coding: utf-8 -*-
"""Regenera la lista de ids de vanilla que usa el self-test.

    python tools/extract_vanilla_ids.py

Sale del en_us.json del cliente de 1.20.1 que ForgeGradle ya tiene descargado, asi que no hay que
bajarse nada ni arrancar el juego. Si algun dia se sube de version de Minecraft, se vuelve a ejecutar.

Existe por un fallo real: una de las 26 armas tenia "minecraft:mace" como item base, que es de 1.21.
En 1.20.1 no resuelve, el arma caia a un palo, y lo unico que se veia era un aviso en el log del
cliente al abrir la pestana creativa.
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
        f.write('# Ids de objeto/bloque y de entidad que existen en Minecraft 1.20.1.\n'
                '# Sacado del en_us.json del cliente 1.20.1; se regenera con tools/extract_vanilla_ids.py.\n'
                '# Sirve para que el self-test cace un id de otra version (paso con minecraft:mace, que es de 1.21).\n')
        f.write('\n'.join(sorted(ids)) + '\n')
    print('escritos %d ids en %s' % (len(ids), OUT))
