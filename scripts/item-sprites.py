"""Writes src/main/resources/item-sprites.txt: for every item, the atlas sprite a dialog can draw it with.

The server has no textures, so the table is read once from the client jar of the game version the plugin
is built for. Run it again when that version changes:  py -I scripts/item-sprites.py 26.3
"""
import io
import json
import pathlib
import sys
import tempfile
import urllib.request
import zipfile

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/item-sprites.txt"
# The texture of a model that best stands for the item in one flat square: a flat item's own layer, a
# block's face, and for a model drawn by code (a chest, a bed, a shulker box) what its particles are made of.
KEYS = ("layer0", "all", "front", "side", "texture", "cross", "plant", "wall", "wool", "top", "end", "north", "up", "particle")


def get(url):
    with urllib.request.urlopen(url) as response:
        return response.read()


def client_jar(version):
    manifest = json.loads(get(MANIFEST))
    meta_url = next(v["url"] for v in manifest["versions"] if v["id"] == version)
    return get(json.loads(get(meta_url))["downloads"]["client"]["url"])


def short(identifier):
    return identifier.removeprefix("minecraft:")


def main(version):
    # The jar is somebody else's download: it is only read as a zip, in a directory of its own.
    with tempfile.TemporaryDirectory() as scratch:
        path = pathlib.Path(scratch) / "client.jar"
        path.write_bytes(client_jar(version))
        jar = zipfile.ZipFile(io.BytesIO(path.read_bytes()))
    names = set(jar.namelist())

    def read(name):
        return json.loads(jar.read(name)) if name in names else None

    # What each atlas is stitched from: a sprite outside them would draw as the missing texture.
    def prefixes(atlas):
        sources = read(f"assets/minecraft/atlases/{atlas}.json")["sources"]
        return [s.get("prefix") or s["source"] + "/" for s in sources if s.get("type", "").endswith("directory")]

    atlases = {atlas: prefixes(atlas) for atlas in ("items", "blocks")}

    def atlas_of(sprite):
        return next((a for a, ps in atlases.items() if any(sprite.startswith(p) for p in ps)), None)

    def textures(model):
        merged = {}
        while model:
            data = read(f"assets/minecraft/models/{short(model)}.json")
            if data is None:
                break
            for key, value in data.get("textures", {}).items():
                merged.setdefault(key, value)
            model = data.get("parent")
        return merged

    def resolve(texs, value, depth=0):
        while isinstance(value, str) and value.startswith("#") and depth < 10:
            value, depth = texs.get(value[1:]), depth + 1
        if isinstance(value, dict):
            value = value.get("sprite")
        return short(value) if isinstance(value, str) and not value.startswith("#") else None

    def first_model(node):
        # A plain model, a special one's base, or the fallback of a choice before its first case.
        if isinstance(node, dict):
            if node.get("type", "").endswith("model") and isinstance(node.get("model"), str):
                return node["model"]
            if isinstance(node.get("base"), str):
                return node["base"]
            for key in ("base", "fallback", "on_false", "model"):
                found = first_model(node.get(key))
                if found:
                    return found
            for value in node.values():
                found = first_model(value)
                if found:
                    return found
        elif isinstance(node, list):
            for value in node:
                found = first_model(value)
                if found:
                    return found
        return None

    lines = []
    for name in sorted(n for n in names if n.startswith("assets/minecraft/items/") and n.endswith(".json")):
        item = name.rsplit("/", 1)[1].removesuffix(".json")
        model = first_model(read(name).get("model"))
        if model is None:
            continue
        texs = textures(model)
        # A tinted bottle or star is drawn as its liquid over the glass: the glass is what reads as the item.
        if (resolve(texs, texs.get("layer0")) or "").endswith("_overlay") and "layer1" in texs:
            texs["layer0"] = texs["layer1"]
        # Then any texture named after the item, which is how a model of its own (a beacon) names its face.
        for key in KEYS + tuple(sorted(texs)):
            sprite = resolve(texs, texs.get(key))
            # A flat item's layer is its own picture, whatever it is called. Any other texture is a face of
            # some block, and stands for the item only when named after it: the planks of a slab, a stair, a
            # button, a chest or a bed would pass the item off as planks. The button then names it instead.
            if key != "layer0" and sprite and item not in sprite:
                sprite = None
            atlas = sprite and atlas_of(sprite)
            if atlas:
                lines.append(f"{item} {atlas} {sprite}")
                break
    OUT.write_text(f"# item atlas sprite, from the {version} client; scripts/item-sprites.py\n" + "\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} items -> {OUT}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "26.3")
