#!/usr/bin/env python3
"""Собирает гифки для README из кадров, записанных DemoRecorder.

    ./gradlew runIde -PliveDemo=/path/to/frames -PliveProject=/path/to/demo-project
    python3 scripts/make-gifs.py /path/to/frames docs/images

Кадры лежат по сценам: <frames>/<сцена>/<номер>-<длительность, мс>.png.
Нужен ffmpeg.
"""
import pathlib
import subprocess
import sys
import tempfile

WIDTH = 1100
# имя гифки -> сцены, из которых она склеивается
GIFS = {
    "demo": ["1-comment", "2-send"],
    "comment": ["1-comment"],
    "send": ["2-send"],
    "discussion": ["3-discussion"],
    "hide": ["4-hide"],
    "resolve": ["5-resolve"],
}


def frames(scene_dir):
    """Кадры сцены; одинаковые подряд склеиваются в один с суммарной длительностью."""
    result = []
    for path in sorted(scene_dir.glob("*.png")):
        millis = int(path.stem.split("-")[1])
        data = path.read_bytes()
        if result and result[-1][2] == data:
            result[-1][1] += millis
        else:
            result.append([path, millis, data])
    return [(path, millis) for path, millis, _ in result]


def build(name, scenes, source, target):
    sequence = [frame for scene in scenes for frame in frames(source / scene)]
    if not sequence:
        print(f"skip {name}: no frames")
        return
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as listing:
        for path, millis in sequence:
            listing.write(f"file '{path.resolve()}'\nduration {millis / 1000}\n")
        # concat-демультиплексор игнорирует длительность последнего кадра, если его не повторить
        listing.write(f"file '{sequence[-1][0].resolve()}'\n")
    output = target / f"{name}.gif"
    filters = (
        f"scale={WIDTH}:-1:flags=lanczos,split[a][b];"
        "[a]palettegen=stats_mode=diff:max_colors=128[p];[b][p]paletteuse=dither=none"
    )
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", listing.name,
         "-vf", filters, "-fps_mode", "vfr", str(output)],
        check=True,
    )
    print(f"{output}  {output.stat().st_size // 1024} KB  {len(sequence)} frames")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    source, target = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    target.mkdir(parents=True, exist_ok=True)
    for name, scenes in GIFS.items():
        build(name, scenes, source, target)


if __name__ == "__main__":
    main()
