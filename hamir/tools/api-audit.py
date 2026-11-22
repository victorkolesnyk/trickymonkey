#!/usr/bin/env python3
"""Static audit of platform API use, standing in for Android Lint (unavailable offline).

Lists every android.*, java.*, javax.*, dalvik.* class member referenced by the compiled
app classes that is absent from the public API 23 stub jar. Each hit is either an API
added in level 24..34 (must be guarded by an SDK_INT check when above minSdk 26) or a
hidden API (must not be used). The list is meant for manual review.

Usage: api-audit.py <classes-dir>... [--stub /usr/lib/android-sdk/platforms/android-23/android.jar]
"""
import functools, os, re, subprocess, sys

STUB = "/usr/lib/android-sdk/platforms/android-23/android.jar"
PREFIXES = ("android/", "java/", "javax/", "dalvik/", "org/json/", "org/xml", "org/w3c")


def javap(args):
    return subprocess.run(["javap"] + args, capture_output=True, text=True,
                          env={**os.environ, "JAVA_TOOL_OPTIONS": "", "_JAVA_OPTIONS": ""}).stdout


@functools.lru_cache(maxsize=None)
def stub_class(name):
    """Returns (members: set of 'name:descriptor', supers: list) or None when the stub lacks the class."""
    dotted = name.replace("/", ".")
    out = javap(["-cp", STUB, "-p", "-s", dotted])
    lines = [l.strip() for l in out.splitlines()]
    head = next((l for l in lines if l.endswith("{")), None)
    if head is None:
        return None
    supers = []
    for key in (" extends ", " implements "):
        if key in head:
            part = head.split(key, 1)[1]
            part = part.split(" implements ")[0].rstrip("{ ")
            part = re.sub(r"<[^<>]*(<[^<>]*>)?[^<>]*>", "", part)
            supers += [x.strip().replace(".", "/") for x in part.split(",") if x.strip()]
    members, decl = set(), None
    for l in lines:
        if l.startswith("descriptor:") and decl:
            desc = l.split(":", 1)[1].strip()
            if "(" in decl:
                nm = decl.split("(")[0].split()[-1]
                nm = "<init>" if nm == dotted else nm
            else:
                nm = decl.rstrip(";").split()[-1]
            members.add(nm + ":" + desc)
            decl = None
        elif l.endswith(";") and not l.startswith("descriptor:"):
            decl = l
    return members, supers


def has_member(cls, member):
    info = stub_class(cls)
    if info is None:
        return False
    members, supers = info
    if member in members:
        return True
    if member.startswith("<init>"):
        return False
    parents = supers + ([] if cls == "java/lang/Object" else ["java/lang/Object"])
    return any(has_member(s, member) for s in parents)


def main():
    dirs = [a for a in sys.argv[1:] if not a.startswith("--")]
    files = []
    for d in dirs:
        for root, _, names in os.walk(d):
            files += [os.path.join(root, n) for n in names if n.endswith(".class")]
    refs = {}
    for f in files:
        owner = os.path.basename(f)[:-6]
        if owner == "R" or owner.startswith("R$"):
            continue
        for line in javap(["-v", "-p", f]).splitlines():
            m = re.search(r"= (Methodref|InterfaceMethodref|Fieldref|Class)\s+\S+\s+// +\"?([^\s\"]+)\"?", line)
            if not m:
                continue
            kind, ref = m.groups()
            if kind == "Class":
                cls, member = ref.lstrip("[L").rstrip(";"), None
            else:
                cls, member = ref.split(".", 1)
                member = member.replace('"', "")
            if cls.startswith("[") or not cls.startswith(PREFIXES):
                continue
            refs.setdefault((cls, member), set()).add(owner)
    missing_classes, missing_members = {}, {}
    for (cls, member), owners in sorted(refs.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
        if stub_class(cls) is None:
            missing_classes.setdefault(cls, set()).update(owners)
        elif member and not has_member(cls, member):
            missing_members[(cls, member)] = owners
    print(f"scanned {len(files)} class files, {len(refs)} platform references")
    print("\n== classes not in public API 23 (added later or hidden) ==")
    for cls, owners in sorted(missing_classes.items()):
        print(f"{cls}    <- {', '.join(sorted(owners))}")
    print("\n== members not in public API 23 (added later or hidden) ==")
    for (cls, member), owners in sorted(missing_members.items()):
        if cls in missing_classes:
            continue
        print(f"{cls}.{member}    <- {', '.join(sorted(owners))}")


if __name__ == "__main__":
    main()
