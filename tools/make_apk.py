import argparse
import os
import struct
import sys
import zipfile

RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_NAMESPACE = 0x0100
RES_XML_END_NAMESPACE = 0x0101
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
RES_XML_RESOURCE_MAP = 0x0180

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

ATTR_NAMES = [
    ("theme", 0x01010000),
    ("label", 0x01010001),
    ("name", 0x01010003),
    ("hasCode", 0x0101000c),
    ("exported", 0x01010010),
    ("description", 0x01010020),
    ("minSdkVersion", 0x0101020c),
    ("versionCode", 0x0101021b),
    ("versionName", 0x0101021c),
    ("targetSdkVersion", 0x01010270),
    ("allowBackup", 0x01010280),
]

ANDROID_NS = "http://schemas.android.com/apk/res/android"
NO_INDEX = 0xFFFFFFFF


class StringPool:
    def __init__(self):
        self.items = []
        self.index = {}

    def add(self, value):
        existing = self.index.get(value)
        if existing is not None:
            return existing
        position = len(self.items)
        self.items.append(value)
        self.index[value] = position
        return position

    def build(self):
        data = bytearray()
        offsets = []
        for value in self.items:
            offsets.append(len(data))
            data += struct.pack("<H", len(value))
            data += value.encode("utf-16-le")
            data += b"\x00\x00"
        while len(data) % 4:
            data += b"\x00"
        count = len(self.items)
        header_size = 28
        strings_start = header_size + 4 * count
        size = strings_start + len(data)
        chunk = struct.pack("<HHIIIIII", RES_STRING_POOL, header_size, size, count, 0, 0, strings_start, 0)
        chunk += b"".join(struct.pack("<I", offset) for offset in offsets)
        return chunk + bytes(data)


class AxmlWriter:
    def __init__(self):
        self.pool = StringPool()
        for name, _ in ATTR_NAMES:
            self.pool.add(name)
        self.android_ns = self.pool.add(ANDROID_NS)
        self.android_prefix = self.pool.add("android")
        self.attr_ids = {name: res_id for name, res_id in ATTR_NAMES}
        self.body = bytearray()

    def _node(self, kind, header_size, payload):
        data = struct.pack("<HHI", kind, header_size, 16 + len(payload))
        data += struct.pack("<II", 1, NO_INDEX)
        return data + payload

    def start_namespace(self):
        payload = struct.pack("<II", self.android_prefix, self.android_ns)
        self.body += self._node(RES_XML_START_NAMESPACE, 16, payload)

    def end_namespace(self):
        payload = struct.pack("<II", self.android_prefix, self.android_ns)
        self.body += self._node(RES_XML_END_NAMESPACE, 16, payload)

    def start_element(self, name, attributes):
        resolved = []
        for attribute in attributes:
            attr_name = attribute[0]
            kind = attribute[1]
            value = attribute[2]
            namespaced = attribute[3] if len(attribute) > 3 else True
            res_id = self.attr_ids.get(attr_name) if namespaced else None
            resolved.append((res_id if res_id is not None else -1, self.pool.add(attr_name),
                             self.android_ns if namespaced else NO_INDEX, kind, value))
        resolved.sort(key=lambda item: item[0])

        payload = bytearray()
        payload += struct.pack("<II", NO_INDEX, self.pool.add(name))
        payload += struct.pack("<HHHHHH", 20, 20, len(resolved), 0, 0, 0)
        for _, name_idx, ns_idx, kind, value in resolved:
            raw = value if kind == "string" else NO_INDEX
            payload += struct.pack("<III", ns_idx, name_idx, raw)
            if kind == "string":
                payload += struct.pack("<HBBI", 8, 0, TYPE_STRING, value)
            elif kind == "int":
                payload += struct.pack("<HBBI", 8, 0, TYPE_INT_DEC, value)
            elif kind == "bool":
                payload += struct.pack("<HBBI", 8, 0, TYPE_INT_BOOLEAN, 1 if value else 0)
            else:
                raise ValueError(kind)
        self.body += self._node(RES_XML_START_ELEMENT, 16, bytes(payload))

    def end_element(self, name):
        payload = struct.pack("<II", NO_INDEX, self.pool.add(name))
        self.body += self._node(RES_XML_END_ELEMENT, 16, payload)

    def build(self):
        resource_map = struct.pack("<HHI", RES_XML_RESOURCE_MAP, 8, 8 + 4 * len(ATTR_NAMES))
        for _, res_id in ATTR_NAMES:
            resource_map += struct.pack("<I", res_id)
        chunks = self.pool.build() + resource_map + bytes(self.body)
        return struct.pack("<HHI", RES_XML, 8, 8 + len(chunks)) + chunks


def build_manifest(package, version_code, version_name, min_sdk, target_sdk,
                   label, description):
    writer = AxmlWriter()
    writer.start_namespace()

    writer.start_element("manifest", [
        ("package", "string", writer.pool.add(package), False),
        ("versionCode", "int", version_code),
        ("versionName", "string", writer.pool.add(version_name)),
    ])
    writer.start_element("uses-sdk", [
        ("minSdkVersion", "int", min_sdk),
        ("targetSdkVersion", "int", target_sdk),
    ])
    writer.end_element("uses-sdk")
    writer.start_element("application", [
        ("label", "string", writer.pool.add(label)),
        ("description", "string", writer.pool.add(description)),
        ("hasCode", "bool", True),
        ("allowBackup", "bool", False),
    ])

    writer.end_element("application")
    writer.end_element("manifest")
    writer.end_namespace()
    return writer.build()


def write_apk(out, manifest_bytes, dex_path, staging):
    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(out, "w") as apk:
        info = zipfile.ZipInfo("AndroidManifest.xml", date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        apk.writestr(info, manifest_bytes)

        info = zipfile.ZipInfo("classes.dex", date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        with open(dex_path, "rb") as handle:
            apk.writestr(info, handle.read())

        for root, _, files in os.walk(staging):
            for name in sorted(files):
                full = os.path.join(root, name)
                arc = os.path.relpath(full, staging).replace(os.sep, "/")
                info = zipfile.ZipInfo(arc, date_time=(2020, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(full, "rb") as handle:
                    apk.writestr(info, handle.read())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--dex", required=True)
    parser.add_argument("--staging", required=True)
    parser.add_argument("--package", default="io.github.dunxuan.tgfastviewer")
    parser.add_argument("--version-code", type=int, default=8)
    parser.add_argument("--version-name", default="1.4.1")
    parser.add_argument("--min-sdk", type=int, default=23)
    parser.add_argument("--target-sdk", type=int, default=36)
    parser.add_argument("--label", default="TG快点滑")
    parser.add_argument("--description", default="Telegram 相册查看器加速：一次性加载锚点窗口，立即左右滑动。")
    args = parser.parse_args()

    manifest = build_manifest(args.package, args.version_code, args.version_name,
                              args.min_sdk, args.target_sdk, args.label,
                              args.description)
    write_apk(args.out, manifest, args.dex, args.staging)
    print("apk", args.out, os.path.getsize(args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())







