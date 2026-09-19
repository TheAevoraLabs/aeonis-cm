#!/usr/bin/env python3
"""
Static mixin target validator.

Mixin resolves its targets by name at runtime, so a mixin that points at a
method which was renamed or relocated by a Minecraft update still compiles and
only fails when the game boots (or, worse, silently does nothing). The Gradle
`validateMixinReferences` task only checks that the declared mixin *classes*
exist, which is why a port can compile cleanly and still crash on startup.

This script closes that gap: it reads every mixin config in the repository,
resolves each injection against the real (deobfuscated) Minecraft jar using
`javap`, and reports

  * mixin target classes that do not exist,
  * `@Inject`/`@Redirect`/... `method` selectors that match nothing,
  * `@Inject` handlers whose parameter list does not match the target method
    (Mixin requires the target's parameters verbatim, followed by the callback),
  * `@Inject` handlers whose static-ness does not match the target,
  * `@Accessor`/`@Invoker`/`@Shadow` members that no longer exist.

Usage:
    python3 scripts/check-mixin-targets.py
    python3 scripts/check-mixin-targets.py --jar /path/to/minecraft-merged-deobf-26.3.jar
    python3 scripts/check-mixin-targets.py --module fabric   # only one module

Exit status: 0 = every target resolved, 1 = at least one problem found.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}
JAVA_LANG = {
    "String", "Object", "Integer", "Long", "Float", "Double", "Boolean", "Byte",
    "Character", "Short", "Number", "Class", "Enum", "Record", "Iterable",
    "Runnable", "Void", "Throwable", "Exception", "RuntimeException",
    "IllegalStateException", "IllegalArgumentException", "NullPointerException",
    "List", "Map", "Set", "Collection", "Optional", "Iterator", "Comparator",
}
# Annotations that can select a target member by name.
MEMBER_SELECTORS = (
    "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable",
    "ModifyConstant", "ModifyReturnValue", "ModifyExpressionValue",
    "WrapOperation", "WrapWithCondition", "Overwrite", "Accessor", "Invoker",
    "Shadow", "Mixin",
)
# Injections whose handler must mirror the target signature (+ callback).
DESCRIPTOR_CHECKED = ("Inject",)


@dataclass
class Problem:
    file: Path
    line: int
    message: str

    def __str__(self) -> str:
        location = f"{self.file.relative_to(REPO_ROOT)}:{self.line}" if self.line else str(
            self.file.relative_to(REPO_ROOT)
        )
        return f"{location}: {self.message}"


@dataclass
class JavaFile:
    path: Path
    text: str
    package: str
    imports: dict[str, str]
    type_vars: dict[str, str]


@dataclass
class Method:
    params: list[str]
    is_static: bool


@dataclass
class ClassInfo:
    name: str
    missing: bool = False
    extends: str | None = None
    implements: list[str] = field(default_factory=list)
    methods: dict[str, list[Method]] = field(default_factory=dict)
    fields: set[str] = field(default_factory=set)


def strip_generics(text: str) -> str:
    out, depth = [], 0
    for char in text:
        if char == "<":
            depth += 1
        elif char == ">":
            depth -= 1
        elif depth == 0:
            out.append(char)
    return "".join(out)


def descriptor_params(descriptor: str) -> list[str] | None:
    if not descriptor.startswith("("):
        return None
    body = descriptor[1:descriptor.rindex(")")]
    params, index = [], 0
    while index < len(body):
        start = index
        while body[index] == "[":
            index += 1
        if body[index] == "L":
            index = body.index(";", index) + 1
        else:
            index += 1
        params.append(body[start:index])
    return params


class JarIndex:
    """Lazily inspects classes in the Minecraft jar with `javap`."""

    def __init__(self, jar: Path):
        self.jar = jar
        self._classes: dict[str, ClassInfo] = {}

    def get(self, internal_name: str) -> ClassInfo:
        if internal_name in self._classes:
            return self._classes[internal_name]
        info = ClassInfo(name=internal_name)
        self._classes[internal_name] = info
        try:
            completed = subprocess.run(
                ["javap", "-p", "-s", "-classpath", str(self.jar), internal_name.replace("/", ".")],
                capture_output=True, text=True, timeout=60, check=False,
            )
        except (OSError, subprocess.SubprocessError):
            info.missing = True
            return info
        output = completed.stdout
        if not output or "Error:" in completed.stderr or "not found" in output:
            info.missing = True
            return info
        self._parse(info, output)
        return info

    def _parse(self, info: ClassInfo, output: str) -> None:
        pending_decl: str | None = None
        for raw_line in output.splitlines():
            line = raw_line.strip()
            if line.startswith("descriptor:"):
                if pending_decl:
                    self._record(info, pending_decl, line.split("descriptor:", 1)[1].strip())
                pending_decl = None
                continue
            if line.endswith(";") and not line.startswith("Compiled"):
                pending_decl = line
                continue
            if not re.search(r"\b(class|interface|enum|record)\s", line):
                continue
            tail = line[line.index(re.search(r"\b(class|interface|enum|record)\s", line).group(0)):]
            tail = tail[tail.index(" "):]
            extends = re.search(r"\bextends\s+([\w$.]+)", tail)
            implements = re.search(r"\bimplements\s+([\w$., ]+)", tail)
            if extends:
                ext = strip_generics(extends.group(1))
                if "Enum" not in ext:
                    info.extends = ext.replace(".", "/")
            if implements:
                for name in implements.group(1).split(","):
                    name = strip_generics(name).strip()
                    if name:
                        info.implements.append(name.replace(".", "/"))

    def _record(self, info: ClassInfo, declaration: str, descriptor: str) -> None:
        if "(" not in declaration:
            info.fields.add(declaration.rstrip(";").split()[-1])
            return
        match = re.match(r"([\w$.<>\[\],? ]+?)\s+([\w$.]+)\s*\(", declaration)
        if not match:
            return
        name = match.group(2)
        # javap prints constructors as the fully qualified class name.
        if "." in name or name == info.name.rsplit("/", 1)[-1]:
            name = "<init>"
        params = descriptor_params(descriptor)
        if params is None:
            return
        is_static = bool(re.match(r"\s*(public|protected|private)?\s*static\b", declaration))
        info.methods.setdefault(name, []).append(Method(params=params, is_static=is_static))

    def walk(self, internal_name: str):
        """Yield the class and every supertype javap can resolve."""
        seen: set[str] = set()
        stack = [internal_name]
        while stack:
            name = stack.pop()
            if name in seen:
                continue
            seen.add(name)
            info = self.get(name)
            if info.missing:
                continue
            if info.extends:
                stack.append(info.extends)
            stack.extend(info.implements)
            yield info

    def find_methods(self, internal_name: str, method_name: str) -> list[Method]:
        found: list[Method] = []
        for info in self.walk(internal_name):
            found.extend(info.methods.get(method_name, []))
        return found

    def has_field(self, internal_name: str, name: str) -> bool:
        return any(name in info.fields for info in self.walk(internal_name))


# --------------------------------------------------------------------------- #
# Source parsing
# --------------------------------------------------------------------------- #


def read_java(path: Path) -> JavaFile:
    text = path.read_text(errors="replace")
    package = re.search(r"^package\s+([\w.]+);", text, re.MULTILINE)
    imports: dict[str, str] = {}
    for match in re.finditer(r"^import\s+(?!static\s)([\w.]+);", text, re.MULTILINE):
        fqn = match.group(1)
        imports[fqn.rsplit(".", 1)[-1]] = fqn
    type_vars: dict[str, str] = {}
    class_decl = re.search(r"\bclass\s+\w+\s*<([^>]*)>", text)
    if class_decl:
        for part in class_decl.group(1).split(","):
            pieces = part.strip().split("extends")
            bits = pieces[0].strip().split()
            if not bits:
                continue
            bound = pieces[1].strip().split()[0] if len(pieces) > 1 and pieces[1].strip() else "Object"
            type_vars[bits[0]] = bound
    return JavaFile(
        path=path,
        text=text,
        package=package.group(1) if package else "",
        imports=imports,
        type_vars=type_vars,
    )


def balanced_span(text: str, start: int, opener: str = "(", closer: str = ")") -> tuple[str, int]:
    """Return the body of the bracket group starting at/after `start`, and its end."""
    open_index = text.find(opener, start)
    if open_index == -1:
        return "", start
    depth, index, in_string = 0, open_index, False
    while index < len(text):
        char = text[index]
        if char == '"' and text[index - 1] != "\\":
            in_string = not in_string
        elif not in_string:
            if char == opener:
                depth += 1
            elif char == closer:
                depth -= 1
                if depth == 0:
                    return text[open_index + 1:index], index
        index += 1
    return "", start


def top_level_args(body: str) -> list[tuple[str | None, str]]:
    """Split an annotation body into (key, value) pairs, ignoring nested groups."""
    args: list[tuple[str | None, str]] = []
    depth = 0
    in_string = False
    current = ""
    for index, char in enumerate(body):
        if char == '"' and body[index - 1] != "\\":
            in_string = not in_string
        if not in_string:
            if char in "([{":
                depth += 1
            elif char in ")]}":
                depth -= 1
            elif char == "," and depth == 0:
                args.append(current)
                current = ""
                continue
        current += char
    args.append(current)

    parsed: list[tuple[str | None, str]] = []
    for raw in args:
        raw = raw.strip()
        if not raw:
            continue
        if "=" in raw.split("(", 1)[0] and re.match(r"^\s*\w+\s*=", raw):
            key, value = raw.split("=", 1)
            parsed.append((key.strip(), value.strip()))
        else:
            parsed.append((None, raw))
    return parsed


def first_string(value: str) -> str | None:
    match = re.search(r'"((?:[^"\\]|\\.)*)"', value)
    return match.group(1) if match else None


def split_params(text: str) -> list[str]:
    parts, depth, current = [], 0, ""
    for char in text:
        if char in "<([":
            depth += 1
        elif char in ">)]":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += char
    parts.append(current)
    return [part.strip() for part in parts if part.strip()]


def member_after(text: str, position: int) -> tuple[str, str] | None:
    """Read the field or method declared immediately after an annotation."""
    index = position
    while True:
        while index < len(text) and text[index].isspace():
            index += 1
        if index < len(text) and text[index] == "@":
            # `@Final` has no arguments, so only consume a bracket group when
            # one directly follows the annotation's name.
            name_end = index + 1
            while name_end < len(text) and (text[name_end].isalnum() or text[name_end] in "_$."):
                name_end += 1
            cursor = name_end
            while cursor < len(text) and text[cursor].isspace():
                cursor += 1
            if cursor < len(text) and text[cursor] == "(":
                _, end = balanced_span(text, cursor)
                index = end + 1
            else:
                index = name_end
            continue
        break
    cursor = index
    while cursor < len(text) and text[cursor] not in ";({=":
        cursor += 1
    segment = text[index:cursor]
    identifiers = re.findall(r"[\w$]+", segment)
    if not identifiers:
        return None
    name = identifiers[-1]
    if cursor < len(text) and text[cursor] == "(":
        return "method", name
    return "field", name


def handler_after(text: str, position: int) -> tuple[str, list[str], bool] | None:
    """Find the method declaration that follows an annotation."""
    match = re.compile(
        r"\b((?:private|public|protected|static|final|abstract|synchronized|native|\s)+)"
        r"([\w<>,.\[\]?$\s]+?)\s+([\w$]+)\s*\(([^)]*)\)",
        re.DOTALL,
    ).search(text, position)
    if not match:
        return None
    params = []
    for raw in split_params(match.group(4)):
        raw = re.sub(r"@[\w.]+(\([^)]*\))?", "", raw).strip()
        raw = re.sub(r"\bfinal\b", "", raw).strip()
        if not raw:
            continue
        pieces = raw.rsplit(" ", 1)
        params.append(pieces[0].strip() if len(pieces) == 2 else raw)
    return match.group(3), params, "static" in match.group(1)


def descriptor_of(java_type: str, source: JavaFile) -> str:
    java_type = java_type.strip()
    if not java_type:
        return "V"
    if java_type.endswith("..."):
        return "[" + descriptor_of(java_type[:-3], source)
    if java_type.endswith("[]"):
        return "[" + descriptor_of(java_type[:-2], source)
    base = strip_generics(java_type).strip()
    if base in PRIMITIVES:
        return PRIMITIVES[base]
    if base in source.type_vars:
        return descriptor_of(source.type_vars[base], source)
    if "." in base and base.split(".")[0][:1].islower():
        return "L" + base.replace(".", "/") + ";"
    if base in source.imports:
        return "L" + source.imports[base].replace(".", "/") + ";"
    if base in JAVA_LANG:
        return "L" + base + ";"
    return "L" + (f"{source.package}.{base}" if source.package else base).replace(".", "/") + ";"


def resolve_class(name: str, source: JavaFile) -> str:
    name = strip_generics(name).strip()
    if "." in name:
        return name.replace(".", "/")
    if name in source.imports:
        return source.imports[name].replace(".", "/")
    return f"{source.package}.{name}".replace(".", "/")


@dataclass
class Injection:
    kind: str
    selectors: list[str]
    line: int
    offset: int


class MixinFile:
    def __init__(self, path: Path):
        self.source = read_java(path)
        self.path = path
        self.targets: list[str] = []
        self.injections: list[Injection] = []
        self._scan()

    def _scan(self) -> None:
        text = self.source.text
        for match in re.finditer(r"@([A-Z]\w+)", text):
            kind = match.group(1)
            if kind not in MEMBER_SELECTORS:
                continue
            # Only treat the next bracket group as arguments when it directly
            # follows the annotation name; `@Shadow public Foo bar;` has none.
            cursor = match.end()
            while cursor < len(text) and text[cursor].isspace():
                cursor += 1
            if cursor < len(text) and text[cursor] == "(":
                body, end = balanced_span(text, cursor)
            else:
                body, end = "", match.end()
            args = top_level_args(body) if body else []
            line = text.count("\n", 0, match.start()) + 1

            if kind == "Mixin":
                for _key, value in args:
                    for target in re.findall(r"([\w.$]+)\s*\.class", value):
                        self.targets.append(target)
                continue

            selectors: list[str] = []
            for key, value in args:
                if key == "method":
                    strings = re.findall(r'"((?:[^"\\]|\\.)*)"', value)
                    selectors.extend(strings)
                elif key == "target" and not selectors:
                    single = first_string(value)
                    if single:
                        selectors.append(single)
                elif key is None and not selectors and kind != "Group":
                    single = first_string(value)
                    if single:
                        selectors.append(single)
            if not selectors and kind in ("Accessor", "Invoker", "Shadow"):
                member = member_after(text, end)
                if member:
                    selectors = [member[1]]
            self.injections.append(
                Injection(kind=kind, selectors=selectors, line=line, offset=match.start())
            )


def load_mixin_sources(module: Path) -> list[Path]:
    sources: list[Path] = []
    for config in sorted(module.glob("src/**/*.mixins.json")):
        data = json.loads(config.read_text())
        package = data.get("package", "")
        members: list[str] = []
        for key in ("mixins", "client", "server"):
            members.extend(data.get(key, []))
        # config lives in <module>/src/<sourceSet>/resources, so the source root
        # is three levels up.
        src_root = config.parent.parent.parent
        for member in members:
            relative = Path(*package.split(".")) / f"{member}.java"
            for candidate in (
                src_root / "main" / "java" / relative,
                src_root / "client" / "java" / relative,
            ):
                if candidate.is_file():
                    sources.append(candidate)
                    break
    return sources


def loose_match(left: str, right: str) -> bool:
    if left == right:
        return True
    return left.rsplit("/", 1)[-1].rstrip(";") == right.rsplit("/", 1)[-1].rstrip(";")


def params_match_filter(params: list[str], filter_text: str) -> bool:
    if "(" not in filter_text or ")" not in filter_text:
        return True
    body = filter_text[filter_text.index("(") + 1:filter_text.rindex(")")]
    filter_params = descriptor_params("(" + body + ")V")
    if filter_params is None:
        return True
    if len(filter_params) != len(params):
        return False
    return all(loose_match(a, b) for a, b in zip(filter_params, params))


def check_file(path: Path, index: JarIndex) -> tuple[list[Problem], int, int]:
    problems: list[Problem] = []
    mixin = MixinFile(path)
    if not mixin.targets:
        problems.append(Problem(path, 0, "no @Mixin target declared"))
        return problems, 0, 0

    target_classes = [resolve_class(name, mixin.source) for name in mixin.targets]
    for internal in target_classes:
        if index.get(internal).missing:
            problems.append(Problem(path, 0, f"target class not found in Minecraft jar: {internal}"))

    resolved = skipped = 0
    for injection in mixin.injections:
        kind, where = injection.kind, f"@{injection.kind}"

        if kind in ("Accessor", "Invoker", "Shadow"):
            for selector in injection.selectors or [""]:
                member = selector.split("(")[0]
                for internal in target_classes:
                    if member and not (
                        index.find_methods(internal, member) or index.has_field(internal, member)
                    ):
                        problems.append(Problem(
                            path, injection.line,
                            f"{where} - member '{member}' not found on {internal.split('/')[-1]}",
                        ))
                    resolved += 1
            continue

        if not injection.selectors:
            skipped += 1
            continue

        # For @ModifyArg/@Redirect the first entry may be an owner class rather
        # than a method name; only plain "name" / "name(desc)" entries are checked.
        selector = next(
            (value for value in injection.selectors if "(" in value or value[:1].islower()),
            injection.selectors[0],
        )
        if "(" in selector:
            method_name = selector.split("(")[0]
            descriptor_filter = selector[selector.index("("):]
        else:
            method_name = selector
            descriptor_filter = None
        if not method_name:
            skipped += 1
            continue

        matches: list[Method] = []
        for internal in target_classes:
            for method in index.find_methods(internal, method_name):
                if descriptor_filter is None or params_match_filter(method.params, descriptor_filter):
                    matches.append(method)
        if not matches:
            names = ", ".join(internal.split("/")[-1] for internal in target_classes)
            problems.append(Problem(
                path, injection.line, f"{where} - no method '{method_name}' matching on {names}",
            ))
            continue
        resolved += 1

        if kind not in DESCRIPTOR_CHECKED:
            continue
        handler = handler_after(mixin.source.text, injection.offset)
        if not handler:
            continue
        name, params, is_static = handler
        target = matches[0]
        handler_params = [descriptor_of(param, mixin.source) for param in params]
        if len(handler_params) != len(target.params) + 1:
            problems.append(Problem(
                path, injection.line,
                f"{where} - handler {name}() takes {len(handler_params)} parameter(s) but "
                f"{method_name} takes {len(target.params)} plus the callback",
            ))
        else:
            for position, (got, want) in enumerate(zip(handler_params, target.params)):
                if not loose_match(got, want):
                    problems.append(Problem(
                        path, injection.line,
                        f"{where} - handler {name}() parameter {position + 1} is {got}, "
                        f"but {method_name} expects {want}",
                    ))
            if is_static != target.is_static:
                target_kind = "static" if target.is_static else "an instance method"
                problems.append(Problem(
                    path, injection.line,
                    f"{where} - target {method_name} is {target_kind} but handler {name}() "
                    f"is {'static' if is_static else 'not static'}",
                ))
    return problems, resolved, skipped


def find_minecraft_jar(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit).expanduser()
        if not path.is_file():
            raise SystemExit(f"jar not found: {path}")
        return path
    version = "26.3"
    properties = (REPO_ROOT / "gradle.properties").read_text()
    match = re.search(r"^minecraft_version=(.+)$", properties, re.MULTILINE)
    if match:
        version = match.group(1).strip()
    base = Path("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft").expanduser()
    for candidate in (
        base / f"minecraft-merged-deobf-run/{version}" / f"minecraft-merged-deobf-run-{version}.jar",
        base / f"minecraft-merged-deobf/{version}" / f"minecraft-merged-deobf-{version}.jar",
        base / f"minecraft-clientonly-deobf/{version}" / f"minecraft-clientonly-deobf-{version}.jar",
    ):
        if candidate.is_file():
            return candidate
    raise SystemExit(
        "could not find a deobfuscated Minecraft jar; run `./gradlew :fabric:build` first "
        "or pass --jar"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate mixin injection targets")
    parser.add_argument("--jar", default=None, help="deobfuscated Minecraft jar to check against")
    parser.add_argument("--module", default=None, help="only check one module (fabric, neoforge)")
    args = parser.parse_args()

    jar = find_minecraft_jar(args.jar)
    modules = [args.module] if args.module else ["common", "fabric", "neoforge"]
    files: list[Path] = []
    for module in modules:
        module_dir = REPO_ROOT / module
        if module_dir.is_dir():
            files.extend(load_mixin_sources(module_dir))

    if not files:
        print("no mixin sources found")
        return 0

    print(f"checking {len(files)} mixin classes against {jar.name}")
    index = JarIndex(jar)
    problems: list[Problem] = []
    resolved = skipped = 0
    for path in files:
        found, checks, skips = check_file(path, index)
        problems.extend(found)
        resolved += checks
        skipped += skips

    print(f"resolved {resolved} injection target(s); {skipped} skipped (dynamic selectors)")
    if problems:
        print()
        for problem in sorted(problems, key=lambda item: (str(item.file), item.line)):
            print(f"  {problem}")
        print(f"\n{len(problems)} problem(s) found")
        return 1
    print("all mixin targets resolved")
    return 0


if __name__ == "__main__":
    sys.exit(main())
