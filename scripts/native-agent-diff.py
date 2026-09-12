#!/usr/bin/env python3
"""Shows what the GraalVM tracing agent found that the image's metadata does not cover.

Usage (the whole procedure is written up in docs/проект/нативный-образ-graalvm.md):
    KB_NATIVE=1 ./gradlew :backend:bootJar -Pkb.aot.profile=external
    cd run && KB_AOT=0 JAVA_OPTS="-Xmx256m -Dspring.aot.enabled=true \\
        -agentlib:native-image-agent=config-output-dir=/tmp/kb-agent,config-write-period-secs=10" \\
        ./run.sh external
    # ...exercise the app, then Ctrl+C
    python3 scripts/native-agent-diff.py /tmp/kb-agent

The agent records every reflective access it observes, and nearly all of them are
covered already — a raw dump is thousands of entries of which a handful matter.

An image draws metadata from three places, and all three have to be subtracted or
the output is mostly false positives.  Spring AOT's own output is the default; the
rest are passed as extra arguments, directories or jars alike (a jar is read for
its META-INF/native-image entries):

    python3 scripts/native-agent-diff.py /tmp/kb-agent \\
        backend/build/generated/aotResources \\
        ~/.gradle/caches/modules-2/files-2.1/com.openai/openai-java-core/*/*/*.jar

The third source, the GraalVM reachability-metadata repository the Gradle plugin
downloads, is not subtracted here — entries from libraries it covers can still
show up as noise.

What comes out is a lead, not a patch.  Copying agent output into the sources
would commit thousands of unexplained entries that nobody can later tell from the
ones that are load-bearing; the entries that belong in the image are written by
hand in config/NativeHints.java, with the reason they are needed.  An entry here
whose caller you cannot identify is usually one the agent saw on a path the image
never takes.

Reads both metadata shapes: the single reachability-metadata.json of newer agents
and the older reflect-config.json / jni-config.json set.
"""

import glob
import json
import pathlib
import sys
import zipfile

AOT_OUTPUT = "backend/build/generated/aotResources"
METADATA_FILES = ("reachability-metadata.json", "reflect-config.json", "jni-config.json")

# Blanket flags. A type carrying one of these needs no per-member entry, so a
# member the agent reports for it is already covered — miss this and every
# proxied bean and every @Tool holder reads as a gap.
ALL_METHODS = ("allDeclaredMethods", "allPublicMethods")
ALL_CONSTRUCTORS = ("allDeclaredConstructors", "allPublicConstructors")


class Coverage:
    """What a metadata set says about one type."""

    def __init__(self):
        self.methods = set()
        self.all_methods = False
        self.all_constructors = False

    def add(self, entry):
        self.methods.update(
            m.get("name") for m in entry.get("methods", []) if isinstance(m, dict)
        )
        self.all_methods |= any(entry.get(flag) for flag in ALL_METHODS)
        self.all_constructors |= any(entry.get(flag) for flag in ALL_CONSTRUCTORS)

    def missing(self, names):
        """Of `names`, the ones this coverage does not account for."""
        return {
            name
            for name in names
            if name not in self.methods
            and not (self.all_constructors if name == "<init>" else self.all_methods)
        }


def read(source):
    """Maps type name -> Coverage, from a directory tree or a jar."""
    found = {}

    def collect(text):
        data = json.loads(text)
        entries = data.get("reflection", []) if isinstance(data, dict) else data
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            name = entry.get("type") or entry.get("name")
            if isinstance(name, str):
                found.setdefault(name, Coverage()).add(entry)

    path = pathlib.Path(source)
    if path.suffix == ".jar":
        with zipfile.ZipFile(path) as jar:
            for name in jar.namelist():
                if name.startswith("META-INF/native-image/") and name.endswith(".json"):
                    if pathlib.PurePath(name).name in METADATA_FILES:
                        collect(jar.read(name).decode("utf-8"))
    else:
        for file in path.rglob("*.json"):
            if file.name in METADATA_FILES:
                collect(file.read_text())
    return found


def main():
    agent_dir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/kb-agent"
    known_sources = sys.argv[2:] or [AOT_OUTPUT]

    agent = read(agent_dir)
    if not agent:
        sys.exit(f"no agent metadata under {agent_dir}")

    known = {}
    for source in known_sources:
        # Shells that leave an unmatched glob alone would otherwise hand us a
        # literal pattern; a jar path with a version wildcard is the usual case.
        for expanded in sorted(glob.glob(source)) or [source]:
            if not pathlib.Path(expanded).exists():
                sys.exit(f"{expanded} not found")
            for name, coverage in read(expanded).items():
                known.setdefault(name, Coverage()).methods.update(coverage.methods)
                known[name].all_methods |= coverage.all_methods
                known[name].all_constructors |= coverage.all_constructors

    fresh = sorted(t for t in agent if t not in known)
    grown = {t: known[t].missing(agent[t].methods) for t in agent if t in known}
    grown = {t: ms for t, ms in grown.items() if ms}

    print(f"agent: {len(agent)} types, already covered: {len(known)}")
    print(f"known sources: {', '.join(str(s) for s in known_sources)}")

    # JDK types and array descriptors are filtered out of the type list on purpose:
    # the agent reports a great many of them, and a missing one is a problem of the
    # image itself rather than of this application.  Members of a known type are not
    # filtered — that is where gaps like a private any-setter show up.
    noise = ("java.", "javax.", "jdk.", "sun.", "com.sun.", "[")
    listed = [name for name in fresh if not name.startswith(noise)]
    print(f"\n=== types nothing registers: {len(fresh)} ({len(listed)} shown) ===")
    for name in listed:
        print(" ", name)

    print(f"\n=== registered types, members the agent additionally invoked: {len(grown)} ===")
    for name in sorted(grown):
        print(" ", name, sorted(grown[name])[:6])


if __name__ == "__main__":
    main()
