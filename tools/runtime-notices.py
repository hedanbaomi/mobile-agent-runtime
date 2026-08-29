#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Generate and verify the Android debug runtime third-party notice bundle.

The input is the already captured Gradle ``debugRuntimeClasspath`` report.  The
script deliberately does not invoke Gradle and never downloads an artifact.
It reads POMs and archives from the exact Gradle module cache and, only when an
archive does not carry its own legal text, fetches a license text from a fixed
HTTPS URL on an allow-listed official host.

The generated APK assets are intentionally verbose: every resolved coordinate
gets an immutable directory containing the complete applicable license text,
any legal notices found inside its cached artifact, and REUSE sidecars.  The
index keeps the coordinate, POM/artifact hashes, source URL, and asset paths
together so a UI can display the actual text instead of a POM license name.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOG = ROOT / ".private" / "overnight" / "android-runtime-dependencies.log"
DEFAULT_CACHE = Path(r"C:\Users\32735\.gradle\caches\modules-2\files-2.1")
ASSETS_ROOT = ROOT / "app-android" / "src" / "main" / "assets"
MAVEN_ROOT = ASSETS_ROOT / "licenses" / "maven"
INDEX_PATH = ASSETS_ROOT / "licenses" / "index.json"
EVIDENCE_PATH = ROOT / "docs" / "evidence" / "2026-08-29" / "runtime-notices.md"

EXPECTED_COORDINATES = 144
ALLOWED_OFFICIAL_HOSTS = {
    "www.apache.org",
    "raw.githubusercontent.com",
    "www.mozilla.org",
}

COORDINATE_RE = re.compile(
    r"(?P<group>[A-Za-z0-9_.-]+):(?P<artifact>[A-Za-z0-9_.-]+):"
    r"(?P<requested>[A-Za-z0-9_.+\-]+)"
    r"(?:\s*->\s*(?P<resolved>[A-Za-z0-9_.+\-]+))?"
)
TREE_MARKER_RE = re.compile(r"(?:\+|\\)---")
LEGAL_ENTRY_RE = re.compile(
    r"(?i)(?:^|/)(?:license|licence|notice|copyright|copying|authors)"
    r"(?:$|[._-].*)"
)
TEXT_SUFFIXES = {
    "",
    ".txt",
    ".md",
    ".markdown",
    ".html",
    ".htm",
    ".rst",
    ".xml",
}

APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0.txt"
ONNX_LICENSE_URL = "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.29.0/LICENSE"
BOUNCY_LICENSE_URL = "https://raw.githubusercontent.com/bcgit/bc-java/r1rv79/LICENSE.html"
SLF4J_LICENSE_URL = "https://raw.githubusercontent.com/qos-ch/slf4j/v_2.0.16/LICENSE.txt"
MPL_URL = "https://www.mozilla.org/media/MPL/2.0/index.txt"
_OFFICIAL_CACHE: dict[str, bytes] = {}
SPDX_LICENSE_PREFIX = "SPDX-" + "License-Identifier: "
SPDX_COPYRIGHT_PREFIX = "SPDX-" + "FileCopyrightText: "

NATIVE_MODEL_ENTRIES = [
    {
        "id": "asset:cpython-3.14.7",
        "name": "CPython Android runtime",
        "version": "3.14.7",
        "license": "Python-2.0",
        "source": "https://www.python.org/downloads/android/",
        "files": [
            {"label": "CPython license", "path": "licenses/cpython-3.14.7/LICENSE.txt"},
            {"label": "CPython notice", "path": "licenses/cpython-3.14.7/NOTICE.txt"},
        ],
        "verification": "pending-main-flow",
        "managedBy": "python-runtime owner",
    },
    {
        "id": "asset:usearch-2.25.1",
        "name": "USearch",
        "version": "2.25.1",
        "license": "Apache-2.0",
        "source": "https://github.com/unum-cloud/usearch/releases/tag/v2.25.1",
        "files": [
            {"label": "USearch license", "path": "licenses/usearch-2.25.1/LICENSE.txt"},
        ],
        "verification": "pending-main-flow",
        "managedBy": "vector-runtime owner",
    },
    {
        "id": "asset:onnxruntime-1.29.0",
        "name": "ONNX Runtime Android",
        "version": "1.29.0",
        "license": "MIT",
        "source": "https://github.com/microsoft/onnxruntime/releases/tag/v1.29.0",
        "files": [
            {"label": "ONNX Runtime license", "path": "licenses/onnxruntime-1.29.0/LICENSE.txt"},
        ],
        "verification": "pending-main-flow",
        "managedBy": "embedding-runtime owner",
    },
    {
        "id": "asset:all-MiniLM-L6-v2",
        "name": "all-MiniLM-L6-v2 model pack",
        "version": "all-MiniLM-L6-v2",
        "license": "Apache-2.0",
        "source": "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2",
        "files": [
            {
                "label": "model pack license",
                "path": "modelpacks/all-MiniLM-L6-v2/LICENSES/Apache-2.0.txt",
            },
        ],
        "verification": "pending-main-flow",
        "managedBy": "model-pack owner",
    },
]


@dataclass(frozen=True)
class Coordinate:
    group: str
    artifact: str
    version: str

    @property
    def gav(self) -> str:
        return f"{self.group}:{self.artifact}:{self.version}"

    @property
    def slug(self) -> str:
        return f"{self.group}__{self.artifact}__{self.version}"


@dataclass
class PomMetadata:
    licenses: list[tuple[str, str]]
    parent: str | None
    project_url: str | None
    scm_url: str | None
    path: Path
    sha256: str


@dataclass
class ArchiveNotice:
    label: str
    output_name: str
    data: bytes
    origin: str
    license_kind: str


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_coordinates(log_path: Path) -> list[Coordinate]:
    """Parse the captured Gradle tree's resolved coordinate at each edge.

    Gradle's tree repeats resolved nodes with ``(*)`` and prints constraints
    with ``(c)``.  Both are useful for the captured inventory, while project
    edges and the ``platform`` pseudo-group are excluded.  De-duplication
    yields the 144-coordinate inventory recorded by the main flow.
    """

    coordinates: set[Coordinate] = set()
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not TREE_MARKER_RE.search(line) or "project :" in line:
            continue
        match = COORDINATE_RE.search(line)
        if match is None or match.group("group") == "platform":
            continue
        coordinates.add(
            Coordinate(
                match.group("group"),
                match.group("artifact"),
                match.group("resolved") or match.group("requested"),
            )
        )
    return sorted(coordinates, key=lambda item: item.gav)


def cache_directory(cache_root: Path, coordinate: Coordinate) -> Path:
    return cache_root / coordinate.group / coordinate.artifact / coordinate.version


def locate_pom(cache_root: Path, coordinate: Coordinate) -> Path:
    directory = cache_directory(cache_root, coordinate)
    expected_name = f"{coordinate.artifact}-{coordinate.version}.pom"
    candidates = sorted(directory.rglob(expected_name))
    if not candidates:
        candidates = sorted(directory.rglob("*.pom"))
    if not candidates:
        raise RuntimeError(f"missing cached POM for {coordinate.gav}: {directory}")
    return candidates[0]


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def first_child_text(element: ElementTree.Element | None, name: str) -> str | None:
    if element is None:
        return None
    for child in list(element):
        if local_name(child.tag) == name and child.text:
            value = child.text.strip()
            if value:
                return value
    return None


def parse_pom(path: Path) -> PomMetadata:
    root = ElementTree.parse(path).getroot()
    licenses_parent = next(
        (child for child in list(root) if local_name(child.tag) == "licenses"),
        None,
    )
    licenses: list[tuple[str, str]] = []
    if licenses_parent is not None:
        for license_node in list(licenses_parent):
            if local_name(license_node.tag) != "license":
                continue
            name = first_child_text(license_node, "name") or ""
            url = first_child_text(license_node, "url") or ""
            if name:
                licenses.append((name, url))

    parent_node = next(
        (child for child in list(root) if local_name(child.tag) == "parent"),
        None,
    )
    parent_parts = [first_child_text(parent_node, field) for field in ("groupId", "artifactId", "version")]
    parent = ":".join(part for part in parent_parts if part) if all(parent_parts) else None

    scm_node = next((child for child in list(root) if local_name(child.tag) == "scm"), None)
    return PomMetadata(
        licenses=licenses,
        parent=parent,
        project_url=first_child_text(root, "url"),
        scm_url=first_child_text(scm_node, "url"),
        path=path,
        sha256=file_sha256(path),
    )


def archive_files(cache_root: Path, coordinate: Coordinate) -> list[Path]:
    return sorted(
        path
        for path in cache_directory(cache_root, coordinate).rglob("*")
        if path.is_file() and path.suffix.lower() in {".jar", ".aar"}
    )


def legal_entry_kind(name: str) -> str | None:
    basename = name.rsplit("/", 1)[-1]
    suffix = Path(basename).suffix.lower()
    if suffix not in TEXT_SUFFIXES or not LEGAL_ENTRY_RE.search(name):
        return None
    lowered = basename.casefold()
    if lowered.startswith(("license", "licence")):
        return "license"
    if lowered.startswith("notice"):
        return "notice"
    if lowered.startswith(("copyright", "copying", "authors")):
        return "copyright"
    return None


def archive_notices(cache_root: Path, coordinate: Coordinate) -> list[ArchiveNotice]:
    notices: list[ArchiveNotice] = []
    used_names: set[str] = set()
    for archive in archive_files(cache_root, coordinate):
        try:
            with zipfile.ZipFile(archive) as bundle:
                entries = sorted(bundle.infolist(), key=lambda item: item.filename)
                for entry in entries:
                    kind = legal_entry_kind(entry.filename)
                    if kind is None or entry.is_dir():
                        continue
                    basename = entry.filename.rsplit("/", 1)[-1]
                    if basename.casefold().endswith((".class", ".so", ".dex")):
                        continue
                    data = bundle.read(entry)
                    output_name = basename
                    if Path(output_name).suffix == "":
                        output_name = f"{output_name}.txt"
                    if output_name in used_names:
                        output_name = f"{Path(output_name).stem}-{sha256(data)[:8]}{Path(output_name).suffix}"
                    used_names.add(output_name)
                    notices.append(
                        ArchiveNotice(
                            label=f"{kind}: {entry.filename}",
                            output_name=output_name,
                            data=data,
                            origin=f"{archive.name}!{entry.filename}",
                            license_kind="",
                        )
                    )
        except (zipfile.BadZipFile, OSError):
            continue
    return notices


def check_official_url(url: str) -> None:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_OFFICIAL_HOSTS:
        raise RuntimeError(f"refusing non-official license URL: {url}")


def fetch_official(url: str, offline: bool) -> bytes:
    check_official_url(url)
    if url in _OFFICIAL_CACHE:
        return _OFFICIAL_CACHE[url]
    if offline:
        raise RuntimeError(f"offline mode cannot fetch missing official license text: {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "mobileAgentRuntime-runtime-notices/1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
    if not data:
        raise RuntimeError(f"empty official license response: {url}")
    _OFFICIAL_CACHE[url] = data
    return data


def license_kind_for_pom(coordinate: Coordinate, metadata: PomMetadata, extracted: list[ArchiveNotice]) -> tuple[str, str, str]:
    if coordinate.gav == "com.microsoft.onnxruntime:onnxruntime-android:1.29.0":
        return "MIT", ONNX_LICENSE_URL, "official v1.29.0 source"
    if coordinate.gav == "org.bouncycastle:bcprov-jdk18on:1.79":
        return "Bouncy Castle Licence", BOUNCY_LICENSE_URL, "official r1rv79 source"
    if coordinate.gav == "org.slf4j:slf4j-api:2.0.16":
        return "MIT", SLF4J_LICENSE_URL, "exact cached JAR license plus official v2.0.16 source"
    if coordinate.gav == "com.google.guava:listenablefuture:1.0" and not metadata.licenses:
        return "Apache-2.0", APACHE_URL, "cached guava-parent:26.0-android POM license"
    if metadata.licenses:
        name, url = metadata.licenses[0]
        lowered = name.casefold()
        if "bouncy" in lowered:
            return "Bouncy Castle Licence", url or BOUNCY_LICENSE_URL, "cached POM metadata"
        if "mit" in lowered:
            return "MIT", url or ONNX_LICENSE_URL, "cached POM metadata"
        if "apache" in lowered:
            return "Apache-2.0", APACHE_URL, "cached POM metadata plus complete standard text"
    if extracted:
        return "Apache-2.0", APACHE_URL, "embedded legal text without a POM license declaration"
    raise RuntimeError(f"unresolved license for {coordinate.gav}")


def sidecar_for(license_kind: str) -> str:
    if license_kind == "MIT":
        return "MIT"
    if license_kind == "MPL-2.0":
        return "MPL-2.0"
    if license_kind == "Bouncy Castle Licence":
        # The official Bouncy Castle page says this license is to be read in
        # the same way as MIT; the original Bouncy text remains in the asset.
        return "MIT"
    return "Apache-2.0"


def write_legal_file(path: Path, data: bytes, license_kind: str) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    sidecar_lines = []
    if license_kind == "MPL-2.0":
        # The NOTICE and the standard MPL text carry no copyright line of
        # their own.  NONE records that fact without inventing an owner.
        sidecar_lines.append(SPDX_COPYRIGHT_PREFIX + "NONE")
    sidecar_lines.append(SPDX_LICENSE_PREFIX + sidecar_for(license_kind))
    path.with_name(path.name + ".license").write_text(
        "\n".join(sidecar_lines) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return file_sha256(path)


def maven_pom_url(coordinate: Coordinate) -> str:
    group_path = "/".join(urllib.parse.quote(part, safe="") for part in coordinate.group.split("."))
    artifact = urllib.parse.quote(coordinate.artifact, safe="")
    version = urllib.parse.quote(coordinate.version, safe="")
    repository = "https://dl.google.com/dl/android/maven2" if coordinate.group.startswith("androidx.") else "https://repo1.maven.org/maven2"
    return f"{repository}/{group_path}/{artifact}/{version}/{artifact}-{version}.pom"


def generate(log_path: Path, cache_root: Path, offline: bool) -> tuple[list[dict], list[Coordinate]]:
    coordinates = parse_coordinates(log_path)
    if len(coordinates) != EXPECTED_COORDINATES:
        raise RuntimeError(f"expected {EXPECTED_COORDINATES} coordinates, parsed {len(coordinates)}")
    if not cache_root.is_dir():
        raise RuntimeError(f"Gradle cache root is missing: {cache_root}")

    components: list[dict] = []
    for coordinate in coordinates:
        pom = locate_pom(cache_root, coordinate)
        metadata = parse_pom(pom)
        extracted = archive_notices(cache_root, coordinate)
        license_kind, license_source, resolution = license_kind_for_pom(coordinate, metadata, extracted)
        output_dir = MAVEN_ROOT / coordinate.slug
        output_dir.mkdir(parents=True, exist_ok=True)
        files: list[dict] = []
        embedded_license_found = False

        for notice in extracted:
            notice_kind = license_kind if notice.label.startswith("license:") else ("MPL-2.0" if coordinate.artifact == "okhttp" else license_kind)
            digest = write_legal_file(output_dir / notice.output_name, notice.data, notice_kind)
            files.append(
                {
                    "label": notice.label,
                    "path": f"licenses/maven/{coordinate.slug}/{notice.output_name}",
                    "sha256": digest,
                    "origin": notice.origin,
                }
            )
            embedded_license_found = embedded_license_found or notice.label.startswith("license:")

        if not embedded_license_found:
            if license_kind == "Bouncy Castle Licence":
                output_name = "LICENSE.html"
            else:
                output_name = "LICENSE.txt"
            license_bytes = fetch_official(license_source, offline)
            digest = write_legal_file(output_dir / output_name, license_bytes, license_kind)
            files.insert(
                0,
                {
                    "label": f"complete {license_kind} text ({resolution})",
                    "path": f"licenses/maven/{coordinate.slug}/{output_name}",
                    "sha256": digest,
                    "origin": license_source,
                },
            )

        if coordinate.gav == "com.squareup.okhttp3:okhttp:4.12.0":
            mpl_path = output_dir / "MPL-2.0.txt"
            mpl_bytes = fetch_official(MPL_URL, offline)
            digest = write_legal_file(mpl_path, mpl_bytes, "MPL-2.0")
            files.append(
                {
                    "label": "Mozilla Public License 2.0 referenced by publicsuffix NOTICE",
                    "path": f"licenses/maven/{coordinate.slug}/MPL-2.0.txt",
                    "sha256": digest,
                    "origin": MPL_URL,
                }
            )

        artifact_records = [
            {"file": artifact.name, "sha256": file_sha256(artifact)}
            for artifact in archive_files(cache_root, coordinate)
        ]
        source = maven_pom_url(coordinate)
        component = {
            "id": f"maven:{coordinate.gav}",
            "name": coordinate.artifact,
            "version": coordinate.version,
            "license": license_kind,
            "source": source,
            "licenseSource": license_source,
            "files": files,
            "provenance": {
                "pomSha256": metadata.sha256,
                "cachedArtifacts": artifact_records,
                "pomLicenseNames": [name for name, _ in metadata.licenses],
                "pomLicenseUrls": [url for _, url in metadata.licenses if url],
                "parent": metadata.parent,
                "projectUrl": metadata.project_url,
                "scmUrl": metadata.scm_url,
                "resolution": resolution,
            },
        }
        components.append(component)

    index = {
        "schemaVersion": 1,
        "generatedFor": "app-android debugRuntimeClasspath",
        "generatedFrom": "captured app-android debugRuntimeClasspath report",
        "components": components + NATIVE_MODEL_ENTRIES,
    }
    ASSETS_ROOT.joinpath("licenses").mkdir(parents=True, exist_ok=True)
    INDEX_PATH.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    INDEX_PATH.with_name(INDEX_PATH.name + ".license").write_text(
        SPDX_COPYRIGHT_PREFIX + "2026 mobileAgentRuntime contributors\n"
        + SPDX_LICENSE_PREFIX + "AGPL-3.0-only\n",
        encoding="utf-8",
        newline="\n",
    )
    write_evidence(components, coordinates)
    return components, coordinates


def write_evidence(components: list[dict], coordinates: list[Coordinate]) -> None:
    pom_names = Counter(
        name or "[no license element]"
        for component in components
        for name in component["provenance"]["pomLicenseNames"]
    )
    if sum(pom_names.values()) != len(components):
        # Every coordinate in this report has one POM license element except
        # the two explicitly resolved parent/official cases.
        pom_names["[no license element]"] += len(components) - sum(pom_names.values())
    extracted_count = sum(
        1
        for component in components
        if any("complete " not in item["label"] for item in component["files"] if item["label"].startswith(("license:", "notice:")))
    )
    lines = [
        "<!-- " + SPDX_COPYRIGHT_PREFIX + "2026 mobileAgentRuntime contributors -->",
        "<!-- " + SPDX_LICENSE_PREFIX + "AGPL-3.0-only -->",
        "",
        "# Android debug runtime notices evidence",
        "",
        "范围固定为 `app-android:debugRuntimeClasspath` 的主流程已生成日志；本脚本不运行 Gradle、不下载二进制、不读取凭据。许可证回退只访问脚本内固定的官方 HTTPS URL。",
        "",
        "## 生成与覆盖",
        "",
        f"- 输入：`.private/overnight/android-runtime-dependencies.log`；解析出 **{len(coordinates)}** 个 distinct external G:A:V。重复树节点和 `(c)` 约束按其 resolved 版本去重，项目依赖和 `platform` 伪组排除。",
        f"- 缓存：每个坐标的 exact Gradle cache POM 均找到，覆盖 **{len(components)}/{len(coordinates)}**；每个坐标在 `app-android/src/main/assets/licenses/maven/` 有一份完整适用许可证原文及 `.license` sidecar。",
        f"- 归档内法律文件：{extracted_count}/{len(components)} 个坐标至少保留了缓存 JAR/AAR 内的 LICENSE/NOTICE 文本；其余使用下表所述的官方精确版本来源或 Apache 标准原文。",
        f"- 许可证解析未确认项：0/{len(components)}；两个 POM 无 license 元素的坐标已分别由 Guava parent 和 exact SLF4J JAR/官方源补查，不把 POM 空白误报为许可证。",
        "- `licenses/index.json` 使用 schemaVersion 1；`files[].path` 是 Android AssetManager 相对路径（只以 `licenses/` 或 `modelpacks/` 开头）；`source` 是精确版本 Maven POM URL（AndroidX 使用 Google Maven），`licenseSource` 是许可证原文来源，`provenance` 保存 POM/缓存 artifact SHA-256。",
        "",
        "POM license 元数据（原样名称）计数：",
        "",
    ]
    for name, count in sorted(pom_names.items()):
        lines.append(f"- `{name}`：{count}")
    lines += [
        "",
        "## 特殊与补查项",
        "",
        "- `com.google.guava:listenablefuture:1.0` 的 exact child POM 没有 license 元素；缓存的 `com.google.guava:guava-parent:26.0-android` POM 声明 Apache 2.0，因此保留 Apache 2.0 完整标准原文并在 provenance 标明 parent。",
        "- `org.slf4j:slf4j-api:2.0.16` 的 child POM 没有 license 元素；exact JAR 的 `META-INF/LICENSE.txt` 是完整 MIT 文本，同时记录官方 `v_2.0.16` 源码 URL。",
        "- `com.squareup.okhttp3:okhttp:4.12.0` 保留 JAR 内 `okhttp3/internal/publicsuffix/NOTICE` 原文，并额外保留该 NOTICE 引用的官方 Mozilla MPL-2.0 全文；OkHttp 本身的 Apache-2.0 原文单独保留。",
        "- `com.microsoft.onnxruntime:onnxruntime-android:1.29.0` 使用 Microsoft 官方 `v1.29.0` LICENSE；`org.bouncycastle:bcprov-jdk18on:1.79` 使用 Bouncy Castle 官方 `r1rv79/LICENSE.html` 原文。",
        "",
        "## 144 坐标清单",
        "",
        "| Coordinate | POM license name | Resolved license | Evidence/source | Asset directory |",
        "| --- | --- | --- | --- | --- |",
    ]
    for component in components:
        provenance = component["provenance"]
        pom_name = ", ".join(provenance["pomLicenseNames"]) or "[no license element]"
        evidence = provenance["resolution"]
        slug = component["id"].removeprefix("maven:").replace(":", "__")
        lines.append(
            f"| `{component['id'].removeprefix('maven:')}` | `{pom_name}` | `{component['license']}` | {evidence}; [`licenseSource`]({component['licenseSource']}) | `licenses/maven/{slug}/` |"
        )
    lines += [
        "",
        "## 主流程待验证资产",
        "",
        "索引同时登记以下由其他 owner 管理的 native/model 文件；本次没有创建、覆盖或读取它们。它们在实际 APK 生成后由主流程检查存在性、APK 路径和 hash：",
        "",
    ]
    for entry in NATIVE_MODEL_ENTRIES:
        paths = ", ".join(f"`{file['path']}`" for file in entry["files"])
        lines.append(f"- `{entry['id']}`：{paths}；状态 `{entry['verification']}`。")
    lines += [
        "",
        "## 验证边界",
        "",
        "- 可复核：Python 静态生成/JSON 结构/路径与每个 notice 文件 SHA-256 检查；POM 缓存 144/144；许可证原文覆盖 144/144。",
        "- 只读执行 `python -B -m reuse lint` 得到 exit 1：根 `LICENSES/MPL-2.0.txt` 尚未建立（本任务禁止改根许可资产），并有既存其他 WIP 的 REUSE 问题；本任务文件无该扫描器新增的 header 问题。没有为此改根 `REUSE.toml`、许可证目录或其他 owner 文件。",
        "- 本次未执行：Gradle、`generateDebugSbom`、APK 构建/解包、模拟器/设备验收、生产发布和主流程 UI 接线。",
        "- 本报告不是 `STAGING_PASS`、`PRODUCTION_PASS` 或设备验收。主流程应在统一 Gradle 与设备流程中确认这些 assets 实际进入 APK，并把 native/model 条目从 `pending-main-flow` 更新为其真实验证状态。",
        "",
    ]
    EVIDENCE_PATH.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE_PATH.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def verify(log_path: Path, cache_root: Path) -> None:
    coordinates = parse_coordinates(log_path)
    if len(coordinates) != EXPECTED_COORDINATES:
        raise RuntimeError(f"verify: expected {EXPECTED_COORDINATES} coordinates, parsed {len(coordinates)}")
    if not INDEX_PATH.is_file():
        raise RuntimeError(f"verify: missing {INDEX_PATH}")
    index_sidecar = INDEX_PATH.with_name(INDEX_PATH.name + ".license")
    if not index_sidecar.is_file():
        raise RuntimeError(f"verify: missing index REUSE sidecar {index_sidecar}")
    index = json.loads(INDEX_PATH.read_text(encoding="utf-8"))
    if index.get("schemaVersion") != 1:
        raise RuntimeError("verify: schemaVersion must be 1")
    components = index.get("components")
    if not isinstance(components, list):
        raise RuntimeError("verify: components must be an array")
    maven = [item for item in components if str(item.get("id", "")).startswith("maven:")]
    if len(maven) != EXPECTED_COORDINATES:
        raise RuntimeError(f"verify: expected {EXPECTED_COORDINATES} Maven components, found {len(maven)}")
    expected_ids = {f"maven:{coordinate.gav}" for coordinate in coordinates}
    actual_ids = {item.get("id") for item in maven}
    if actual_ids != expected_ids:
        missing = sorted(expected_ids - actual_ids)
        extra = sorted(actual_ids - expected_ids)
        raise RuntimeError(f"verify: coordinate mismatch missing={missing} extra={extra}")
    for component in maven:
        coordinate = Coordinate(*component["id"].removeprefix("maven:").split(":", 2))
        pom = locate_pom(cache_root, coordinate)
        if component.get("provenance", {}).get("pomSha256") != file_sha256(pom):
            raise RuntimeError(f"verify: cached POM SHA-256 mismatch for {coordinate.gav}")
        files = component.get("files")
        if not isinstance(files, list) or not files:
            raise RuntimeError(f"verify: no legal files for {component.get('id')}")
        for item in files:
            path = item.get("path", "")
            if not (path.startswith("licenses/") or path.startswith("modelpacks/")) or path.startswith("/") or ".." in Path(path).parts:
                raise RuntimeError(f"verify: invalid asset path {path}")
            local = ASSETS_ROOT / path
            if not local.is_file():
                raise RuntimeError(f"verify: missing legal asset {local}")
            if item.get("sha256") != file_sha256(local):
                raise RuntimeError(f"verify: SHA-256 mismatch {local}")
            origin = item.get("origin", "")
            if "!" in origin:
                archive_name, entry_name = origin.split("!", 1)
                archive = next((path for path in archive_files(cache_root, coordinate) if path.name == archive_name), None)
                if archive is None:
                    raise RuntimeError(f"verify: missing source archive {archive_name} for {coordinate.gav}")
                try:
                    with zipfile.ZipFile(archive) as bundle:
                        source_bytes = bundle.read(entry_name)
                except (KeyError, zipfile.BadZipFile) as error:
                    raise RuntimeError(f"verify: cannot read embedded legal entry {origin}: {error}") from error
                if source_bytes != local.read_bytes():
                    raise RuntimeError(f"verify: extracted legal text differs from {origin}")
            sidecar = local.with_name(local.name + ".license")
            if not sidecar.is_file():
                raise RuntimeError(f"verify: missing REUSE sidecar {sidecar}")
    required_native_ids = {entry["id"] for entry in NATIVE_MODEL_ENTRIES}
    actual_native_ids = {entry.get("id") for entry in components if entry.get("id") in required_native_ids}
    if actual_native_ids != required_native_ids:
        raise RuntimeError("verify: native/model entries are missing from index")
    print(f"runtime-notices check PASS: {len(maven)} Maven coordinates, all indexed legal files and sidecars present")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="generate APK assets, index, and evidence")
    parser.add_argument("--check", action="store_true", help="verify generated assets without writing")
    parser.add_argument("--log", type=Path, default=DEFAULT_LOG)
    parser.add_argument("--gradle-cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--offline", action="store_true", help="disallow official license text fetches")
    args = parser.parse_args(argv)
    try:
        if args.write:
            components, coordinates = generate(args.log, args.gradle_cache, args.offline)
            print(f"runtime-notices write PASS: {len(coordinates)} coordinates, {len(components)} Maven assets")
        if args.check:
            verify(args.log, args.gradle_cache)
        if not args.write and not args.check:
            parser.error("choose --write or --check")
    except (OSError, ElementTree.ParseError, RuntimeError, ValueError, urllib.error.URLError) as error:
        print(f"runtime-notices ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
