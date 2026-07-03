#!/usr/bin/env python3
"""Extract JAXB binding vocabulary from project XJB files."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

JAXB_NS = "http://java.sun.com/xml/ns/jaxb"
NS = {"jaxb": JAXB_NS}

NODE_PATTERNS = {
    "element": re.compile(r"xs:element\[@name='([^']+)'\]"),
    "element_ref": re.compile(r"xs:element\[@ref='[^:]*:([^']+)'\]"),
    "complexType": re.compile(r"xs:complexType\[@name='([^']+)'\]"),
    "simpleType": re.compile(r"xs:simpleType\[@name='([^']+)'\]"),
    "group": re.compile(r"xs:group\[@name='([^']+)'\]"),
}


def local_tag(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def walk_bindings(element: ET.Element, mappings: dict, source: str) -> None:
    node = element.get("node", "")
    for kind, pattern in NODE_PATTERNS.items():
        match = pattern.search(node)
        if not match:
            continue
        xsd_name = match.group(1)
        for child in element:
            tag = local_tag(child.tag)
            if tag == "property":
                java_name = child.get("name")
                if java_name:
                    mappings["elements"].append((xsd_name, java_name, source))
            elif tag == "class":
                java_name = child.get("name")
                if java_name:
                    key = {
                        "element": "elements_as_class",
                        "complexType": "complexTypes",
                        "simpleType": "simpleTypes",
                        "group": "groups",
                        "element_ref": "elements",
                    }[kind]
                    mappings[key].append((xsd_name, java_name, source))
            elif tag == "typesafeEnumClass":
                java_name = child.get("name")
                if java_name and kind == "simpleType":
                    mappings["enumClasses"].append((xsd_name, java_name, source))
                    for member in child:
                        if local_tag(member.tag) == "typesafeEnumMember":
                            value = member.get("value")
                            member_name = member.get("name")
                            if value and member_name:
                                mappings["enumMembers"].append(
                                    (xsd_name, value, member_name, source)
                                )
            elif tag == "bindings":
                walk_bindings(child, mappings, source)
        break

    for child in element:
        if local_tag(child.tag) == "bindings":
            walk_bindings(child, mappings, source)


def resolve_majority(entries: list[tuple]) -> tuple[dict[str, str], dict[str, list[dict]]]:
    """Pick most common mapping; report conflicts."""
    by_key: dict[str, Counter] = defaultdict(Counter)
    sources_by_pair: dict[tuple[str, str], set[str]] = defaultdict(set)

    for xsd_name, java_name, source in entries:
        by_key[xsd_name][java_name] += 1
        sources_by_pair[(xsd_name, java_name)].add(source)

    resolved: dict[str, str] = {}
    conflicts: dict[str, list[dict]] = {}

    for xsd_name, counter in sorted(by_key.items()):
        if len(counter) == 1:
            resolved[xsd_name] = counter.most_common(1)[0][0]
            continue
        winner, winner_count = counter.most_common(1)[0]
        resolved[xsd_name] = winner
        alts = []
        for java_name, count in counter.most_common():
            if java_name == winner:
                continue
            alts.append(
                {
                    "javaName": java_name,
                    "count": count,
                    "sources": sorted(sources_by_pair[(xsd_name, java_name)]),
                }
            )
        if alts:
            conflicts[xsd_name] = {
                "chosen": winner,
                "chosenCount": winner_count,
                "alternatives": alts,
            }

    return resolved, conflicts


def resolve_enum_members(entries: list[tuple]) -> dict[str, dict[str, str]]:
    by_type: dict[str, dict[str, str]] = defaultdict(dict)
    for type_name, value, member_name, _source in entries:
        by_type[type_name][value] = member_name
    return {k: dict(sorted(v.items(), key=lambda item: item[0])) for k, v in sorted(by_type.items())}


def yaml_quote(value: str) -> str:
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value):
        return value
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def dump_mapping(name: str, mapping: dict[str, str], indent: int = 0) -> list[str]:
    pad = " " * indent
    lines = [f"{pad}{name}:"]
    for key, value in mapping.items():
        lines.append(f"{pad}  {yaml_quote(key)}: {yaml_quote(value)}")
    return lines


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    xjb_dir = root / "src/main/resources/xjb"
    out_file = root / "src/main/resources/bindings/vocabulary.yml"

    mappings = {
        "elements": [],
        "elements_as_class": [],
        "complexTypes": [],
        "simpleTypes": [],
        "groups": [],
        "enumClasses": [],
        "enumMembers": [],
    }
    source_files: list[str] = []

    for xjb_path in sorted(xjb_dir.rglob("*.xjb")):
        rel = xjb_path.relative_to(root).as_posix()
        source_files.append(rel)
        tree = ET.parse(xjb_path)
        for bindings in tree.getroot().iter(f"{{{JAXB_NS}}}bindings"):
            walk_bindings(bindings, mappings, rel)

    elements, element_conflicts = resolve_majority(mappings["elements"])
    elements_as_class, _ = resolve_majority(mappings["elements_as_class"])
    complex_types, complex_conflicts = resolve_majority(mappings["complexTypes"])
    simple_types, _ = resolve_majority(mappings["simpleTypes"])
    groups, _ = resolve_majority(mappings["groups"])
    enum_classes, _ = resolve_majority(mappings["enumClasses"])
    enum_members = resolve_enum_members(mappings["enumMembers"])

    lines: list[str] = [
        "# Auto-generated JAXB binding vocabulary from project XJB files.",
        "# Rebuild: python scripts/build_xjb_vocabulary.py",
        "",
        "meta:",
        f"  sourceFiles: {len(source_files)}",
        f"  generatedFrom:",
    ]
    for source in source_files:
        lines.append(f"    - {source}")

    lines.append("")
    lines.extend(dump_mapping("elements", elements))
    lines.append("")
    lines.extend(dump_mapping("elementsAsClass", elements_as_class))
    lines.append("")
    lines.extend(dump_mapping("complexTypes", complex_types))
    lines.append("")
    lines.extend(dump_mapping("simpleTypes", simple_types))
    lines.append("")
    lines.extend(dump_mapping("groups", groups))
    lines.append("")
    lines.extend(dump_mapping("enumClasses", enum_classes))

    lines.append("")
    lines.append("enumMembers:")
    for type_name, members in enum_members.items():
        lines.append(f"  {yaml_quote(type_name)}:")
        for value, member_name in members.items():
            lines.append(f"    {yaml_quote(value)}: {yaml_quote(member_name)}")

    all_conflicts = {}
    for section, conflicts in [
        ("elements", element_conflicts),
        ("complexTypes", complex_conflicts),
    ]:
        if conflicts:
            all_conflicts[section] = conflicts

    lines.append("")
    lines.append("conflicts:")
    if not all_conflicts:
        lines.append("  {}")
    else:
        for section, conflicts in all_conflicts.items():
            lines.append(f"  {section}:")
            for xsd_name, info in sorted(conflicts.items()):
                lines.append(f"    {yaml_quote(xsd_name)}:")
                lines.append(f"      chosen: {yaml_quote(info['chosen'])}")
                lines.append(f"      chosenCount: {info['chosenCount']}")
                lines.append("      alternatives:")
                for alt in info["alternatives"]:
                    lines.append(f"        - javaName: {yaml_quote(alt['javaName'])}")
                    lines.append(f"          count: {alt['count']}")
                    lines.append("          sources:")
                    for source in alt["sources"]:
                        lines.append(f"            - {source}")

    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"Wrote {out_file}")
    print(f"  elements: {len(elements)}")
    print(f"  elementsAsClass: {len(elements_as_class)}")
    print(f"  complexTypes: {len(complex_types)}")
    print(f"  enumClasses: {len(enum_classes)}")
    print(f"  enum member types: {len(enum_members)}")
    print(f"  element conflicts: {len(element_conflicts)}")
    print(f"  complexType conflicts: {len(complex_conflicts)}")


if __name__ == "__main__":
    main()
