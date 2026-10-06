"""Publish successful combination bytes as a reusable, named font library family."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
from fontTools.ttLib import TTFont
from font_structure import validate_font

ROLES = {100: "Thin", 200: "ExtraLight", 300: "Light", 400: "Regular", 500: "Medium",
         600: "SemiBold", 700: "Bold", 800: "ExtraBold", 900: "Black"}


def check_cancelled():
    task, marker = os.environ.get("LUOSHU_MIX_PARENT_TASK"), os.environ.get("LUOSHU_MIX_CANCEL_FILE")
    if task and marker and Path(marker).is_file():
        if f"task={task}" in Path(marker).read_text(encoding="utf-8").splitlines():
            raise ValueError("组合任务已取消")


def persist_task(task_file, result_file):
    check_cancelled()
    task_file = Path(task_file)
    lines = task_file.read_text(encoding="utf-8").splitlines()
    old = dict(line.split("=", 1) for line in lines if "=" in line)
    expected = os.environ.get("LUOSHU_MIX_PARENT_TASK")
    if expected and old.get("task") != expected:
        raise ValueError("组合任务已被替换")
    data = json.loads(Path(result_file).read_text(encoding="utf-8"))["data"]
    fields = {"result": "prepared", "generatedFontId": data["id"],
              "generatedFontName": data["name"], "previewSource": data["previewSource"]}
    temp = task_file.with_name(task_file.name + f".prepared.{os.getpid()}")
    try:
        temp.write_text("\n".join([line for line in lines if line.split("=", 1)[0] not in fields]
                                  + [f"{k}={v}" for k, v in fields.items()]) + "\n", encoding="utf-8")
        check_cancelled()
        os.replace(temp, task_file)
    finally:
        temp.unlink(missing_ok=True)
    return fields


def publish(source, library, name, request):
    check_cancelled()
    name = validate_name(name)

    family = "ZiyuMix" + hashlib.sha256(request.encode()).hexdigest()[:16]
    library = Path(library)
    library.mkdir(parents=True, exist_ok=True)
    manifest = library / f"{family}.conf"
    return _publish(Path(source), library, name, request, family, manifest)


def validate_name(name):
    name = name.strip()
    if not name or len(name) > 60 or any(c in name for c in "\r\n\t/\\\0|"):
        raise ValueError("组合名称需为 1–60 个字符，不能包含换行、路径分隔符或竖线")
    return name


def _publish(source, library, name, request, family, manifest):
    if manifest.is_file():
        saved = dict(line.split("=", 1) for line in manifest.read_text(encoding="utf-8").splitlines() if "=" in line)
        expected = [library / filename for filename in json.loads(saved.get("files", "[]"))]
        if expected and all(p.is_file() for p in expected):
            return {"id": family, "name": saved.get("name", name), "reused": True,
                    "previewSource": str(expected[0]), "result": "prepared"}
        raise ValueError("之前保存的组合文件不完整，请先删除该条目再重新生成")
    candidates = ([source] if source.is_file() else sorted(
        p for p in source.iterdir() if p.is_file() and p.suffix.lower() in (".ttf", ".otf", ".font")))
    chosen = {}
    for path in candidates:
        with TTFont(path, lazy=True) as font:
            cmap = validate_font(font, path.stat().st_size, decode_all=False)
            if not all(ord(c) in cmap for c in "中A1"):
                raise ValueError(f"组合产物缺少中文、英文或数字：{path.name}")
            weight = int(font["OS/2"].usWeightClass)
            chosen.setdefault(weight, path)
    if not chosen:
        raise ValueError("没有可保存的完整字体组合")
    stage = Path(tempfile.mkdtemp(prefix=".ziyu-mix-", dir=library))
    published = []
    try:
        names = []
        for weight, path in chosen.items():
            role = ROLES.get(weight, "Regular") if len(chosen) > 1 else "Regular"
            filename = f"{family}-{role}.ttf"
            shutil.copyfile(path, stage / filename)
            names.append(filename)
        (stage / manifest.name).write_text(
            f"name={name}\nsupports_cjk=true\ncombination=true\nrequest={request}\n"
            f"files={json.dumps(names)}\n", encoding="utf-8")
        # Publish the manifest last; failures remove only files created by this call.
        for filename in names:
            check_cancelled()
            target = library / filename
            if target.exists():
                raise ValueError("组合目标文件已存在，未覆盖")
            os.replace(stage / filename, target)
            published.append(target)
        check_cancelled()
        os.replace(stage / manifest.name, manifest)
        return {"id": family, "name": name, "reused": False,
                "previewSource": str(library / names[0]), "result": "prepared"}
    except Exception:
        for target in published:
            target.unlink(missing_ok=True)
        raise
    finally:
        shutil.rmtree(stage)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--validate-name", action="store_true")
    parser.add_argument("--persist-task")
    parser.add_argument("--result-file")
    for argument in ("source", "library", "name", "request"):
        parser.add_argument("--" + argument)
    args = parser.parse_args()
    try:
        result = (persist_task(args.persist_task, args.result_file) if args.persist_task else
                  {"name": validate_name(args.name)} if args.validate_name else
                  publish(args.source, args.library, args.name, args.request))
        print(json.dumps({"status": "ok", "data": result}, ensure_ascii=False, separators=(",", ":")))
    except Exception as error:
        print(json.dumps({"status": "error", "message": str(error)}, ensure_ascii=False, separators=(",", ":")))
        raise SystemExit(1)
