#!/usr/bin/env python3
"""Shows what the GraalVM tracing agent found that Spring AOT does not already cover.

Usage:
    ./gradlew :backend:bootJar -Pkb.aot.profile=external
    cd run && KB_AOT=0 JAVA_OPTS="-Xmx256m -Dspring.aot.enabled=true \\
        -agentlib:native-image-agent=config-output-dir=/tmp/kb-agent,config-write-period-secs=10" \\
        ./run.sh external
    # ...exercise the app, then Ctrl+C
    python3 scripts/native-agent-diff.py /tmp/kb-agent

The agent records every reflective access it observes, and most of them Spring AOT
has registered already — a raw dump is thousands of entries of which a handful
matter.  This subtracts what backend/build/generated/aotResources holds, leaving
the part worth reading.

What comes out is a lead, not a patch.  Copying agent output into the sources
would commit thousands of unexplained entries that nobody can later tell from the
ones that are load-bearing; the entries that belong in the image are written by
hand in config/NativeHints.java, with the reason they are needed.  An entry here
whose caller you cannot identify is usually one the agent saw on a path the image
never takes.

Reads both metadata shapes: the single reachability-metadata.json of newer agents
and the older reflect-config.json / jni-config.json set.
"""

import json
import pathlib
import sys

AOT_OUTPUT = "backend/build/generated/aotResources"
METADATA_FILES = ("reachability-metadata.json", "reflect-config.json", "jni-config.json")


def types(root):
    """Maps type name -> set of method names named explicitly for that type."""
    found = {}
    for path in pathlib.Path(root).rglob("*.json"):
        if path.name not in METADATA_FILES:
            continue
        data = json.loads(path.read_text())
        entries = data.get("reflection", []) if isinstance(data, dict) else data
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            name = entry.get("type") or entry.get("name")
            if isinstance(name, str):
                methods = {m.get("name") for m in entry.get("methods", []) if isinstance(m, dict)}
                found.setdefault(name, set()).update(methods)
    return found


def main():
    agent_dir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/kb-agent"
    known_dir = sys.argv[2] if len(sys.argv) > 2 else AOT_OUTPUT

    if not pathlib.Path(known_dir).is_dir():
        sys.exit(f"{known_dir} not found — run ./gradlew :backend:processAot first")

    agent, known = types(agent_dir), types(known_dir)
    if not agent:
        sys.exit(f"no agent metadata under {agent_dir}")

    fresh = sorted(t for t in agent if t not in known)
    grown = sorted(t for t in agent if t in known and agent[t] - known[t])

    print(f"agent: {len(agent)} types, already covered: {len(known)}")

    # JDK types and array descriptors are filtered out of the type list on purpose:
    # the agent reports a great many of them, and a missing one is a problem of the
    # image itself rather than of this application.  Methods on a known type are not
    # filtered — that is where gaps like a private any-setter show up.
    noise = ("java.", "javax.", "jdk.", "sun.", "com.sun.", "[")
    print(f"\n=== types Spring AOT does not register: {len(fresh)} ===")
    for name in fresh:
        if not name.startswith(noise):
            print(" ", name)

    print(f"\n=== registered types, methods the agent additionally invoked: {len(grown)} ===")
    for name in grown:
        print(" ", name, sorted(agent[name] - known[name])[:6])


if __name__ == "__main__":
    main()
